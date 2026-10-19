package com.ontop.app.backup

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import com.ontop.app.data.*
import com.ontop.app.reminders.ReminderManager
import com.ontop.app.text.*
import com.ontop.app.widget.*
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID
import kotlin.random.Random

internal data class BackupState(val busy: Boolean = false, val message: String = "",
    val error: String? = null, val ready: String? = null, val recoveryRequired: Boolean = false)

/** Files are installed under fresh names before one Room transaction. A durable journal bridges
 * that transaction and idempotent OS/widget reconciliation. No live database files are copied. */
internal class BackupManager internal constructor(
    private val context: Context, private val db: OnTopDatabase,
    private val reminders: ReminderManager = ReminderManager(context, db),
    private val root: File = File(context.noBackupFilesDir, "ontop-backup"),
    private val refreshWidgets: suspend () -> Unit = {
        WidgetSelectionStore(context).clear()
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, CardWidgetReceiver::class.java))
        ids.forEach { updateCardWidget(context, it) }
    },
    private val checkpoint: (String) -> Unit = {}
) {
    companion object {
        private val mutex = Mutex()
        private val instances = java.util.WeakHashMap<OnTopDatabase, BackupManager>()
        val active = MutableStateFlow(false)
        private var blocked = false
        @Synchronized fun get(context: Context, db: OnTopDatabase): BackupManager = instances.getOrPut(db) {
            BackupManager(context.applicationContext, db)
        }
        internal suspend fun <T> dataAccess(block: suspend () -> T): T = mutex.withLock {
            check(!blocked) { "Сначала завершите восстановление в настройках." }; block()
        }
    }

    private val mutableState = MutableStateFlow(BackupState())
    val state = mutableState.asStateFlow()
    private val generationFile = AtomicFile(File(root, "generation"))
    // A tiny local epoch also invalidates saved UI routes after a process restart.
    val revision = MutableStateFlow(runCatching {
        generationFile.openRead().use { it.bufferedReader().readLine().take(100) }
    }.getOrDefault("initial"))
    private val stages get() = File(root, "staging")
    private val journal get() = AtomicFile(File(root, "restore.json"))
    private var prepared: Pair<File, BackupData>? = null
    private fun hasJournal() = journal.baseFile.exists() || File(root, "restore.json.bak").exists()
    private fun progress(message: String) { mutableState.update { it.copy(message = message) } }
    fun pickerFailed() { mutableState.update { it.copy(error = "Не удалось открыть выбор файла. Проверьте, доступно ли приложение «Файлы» на устройстве.") } }
    fun rejectDocument() { mutableState.update { it.copy(error = "Выберите документ через системное окно выбора файлов.") } }

    private suspend fun operation(allowRecovery: Boolean = false, block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!allowRecovery && hasJournal()) {
                mutableState.value = BackupState(error = "Сначала завершите прерванное восстановление.", recoveryRequired = true)
                blocked = true
                return@withLock
            }
            active.value = true
            mutableState.update { it.copy(busy = true, error = null) }
            try { block()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                val pending = hasJournal()
                blocked = pending
                mutableState.update { it.copy(error = if (pending)
                    "Операция прервана. Нажмите «Завершить восстановление»: журнал позволит безопасно завершить или отменить замену."
                    else "Не удалось обработать резервную копию. Проверьте целостность и версию файла, доступность всех изображений и свободное место. Текущие заметки не заменены.",
                    ready = null, recoveryRequired = pending) }
            } catch (_: OutOfMemoryError) {
                blocked = hasJournal()
                mutableState.update { it.copy(error = "Недостаточно памяти для резервной копии.", recoveryRequired = blocked) }
            } finally {
                mutableState.update { it.copy(busy = false) }
                active.value = false
            }
        }
    }

    internal suspend fun snapshot(): BackupData = db.withTransaction {
        BackupData(db.cardDao().allCards(), db.cardCommentDao().all(), db.reminderDao().all())
    }

    private fun newStage(): File {
        // Only our abandoned staging areas are removed, never the application's images directory.
        check(stages.isDirectory || stages.mkdirs())
        stages.listFiles()?.filter { it != prepared?.first }?.forEach { it.deleteRecursively() }
        return File(stages, UUID.randomUUID().toString()).apply { check(mkdir()) }
    }

    suspend fun create(open: () -> OutputStream) = operation {
        prepared?.first?.deleteRecursively(); prepared = null
        progress("Подготовка резервной копии…")
        val stage = newStage()
        try {
            val data = reminders.withDataLock { snapshot() }
            val archive = File(stage, "backup.ontop")
            archive.outputStream().use { BackupFormat.write(data, { CardImageStore(context).resolve(it) }, it) }
            require(archive.length() <= BackupLimits().archive)
            progress("Сохранение файла…")
            open().use { output -> archive.inputStream().use { BackupFormat.copy(it, output, BackupLimits().archive) }; output.flush() }
            mutableState.value = BackupState(busy = true, message = "Резервная копия создана: ${data.cards.size} заметок.")
        } finally { stage.deleteRecursively() }
    }

    suspend fun prepare(open: () -> InputStream) = operation {
        prepared?.first?.deleteRecursively(); prepared = null
        progress("Чтение и проверка файла…")
        val stage = newStage()
        try {
            val archive = File(stage, "input.ontop")
            open().use { input -> archive.outputStream().use { BackupFormat.copy(input, it, BackupLimits().archive) } }
            val data = BackupFormat.read(archive, File(stage, "content"))
            prepared = stage to data
            mutableState.value = BackupState(busy = true,
                ready = "Заметок: ${data.cards.size}, комментариев: ${data.comments.size}, изображений: ${data.paths().size}, правил напоминаний: ${data.reminders.size}.")
        } finally { if (prepared?.first != stage) stage.deleteRecursively() }
    }

    suspend fun discardPrepared() = operation {
        prepared?.first?.deleteRecursively(); prepared = null
        mutableState.value = BackupState(busy = true)
    }

    /** Called only after the user has confirmed replacement of the checked dataset. */
    suspend fun restore() = operation {
        val (stage, original) = prepared ?: error("Выберите файл повторно.")
        mutableState.update { it.copy(ready = null) }
        progress("Подготовка изображений…")
        // Once journaled, cancellation of an Activity must not strand a half-completed foreground operation.
        withContext(NonCancellable) {
            reminders.withDataLock {
                val old = snapshot()
                val paths = original.paths().associateWith { "images/${UUID.randomUUID()}.jpg" }
                val usedIds = (old.reminders + original.reminders).map { it.id }.toMutableSet()
                fun newId(): Long { while (true) { val id = Random.nextLong(1, 1L shl 48); if (usedIds.add(id)) return id } }
                val incoming = original.copy(cards = original.cards.map { card ->
                    val doc = BackupFormat.document(card.description, card.formatting)
                    card.copy(imagePath = card.imagePath?.let(paths::getValue),
                        formatting = if (doc.imagePaths().isEmpty()) card.formatting else RichTextCodec.encode(doc.copy(
                            media = doc.media.map { block -> block.path?.let { block.copy(path = paths.getValue(it)) } ?: block })))
                }, reminders = original.reminders.map { it.copy(id = newId(), nextAt = null, snoozeAt = null) })
                writeJournal(JSONObject().put("fingerprint", BackupFormat.fingerprint(incoming))
                    .put("generation", UUID.randomUUID().toString())
                    .put("oldRules", JSONArray(old.reminders.map { it.id })).put("oldFiles", JSONArray(old.paths().toList()))
                    .put("newFiles", JSONArray(paths.values.toList())))
                blocked = true
                checkpoint("journal")
                paths.forEach { (source, destination) ->
                    val file = CardImageStore(context).resolve(destination)
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    FileOutputStream(file).use { output ->
                        File(File(stage, "content"), source).inputStream().use { BackupFormat.copy(it, output, BackupLimits().image) }
                        output.fd.sync()
                    }
                    checkpoint("image")
                }
                syncDirectory(File(context.filesDir, "images"))
                checkpoint("files")
                progress("Замена данных…")
                db.withTransaction {
                    db.cardDao().deleteAll() // FK cascades remove old comments and rules in the same transaction.
                    incoming.cards.forEach { db.cardDao().insert(it) }
                    incoming.comments.forEach { db.cardCommentDao().insert(it) }
                    incoming.reminders.forEach { db.reminderDao().insert(it) }
                    checkpoint("transaction")
                }
                checkpoint("committed")
            }
            finishJournal()
        }
    }

    /** Safe before any edits on launch and from the existing system reconciliation job. */
    suspend fun recover() = withContext(Dispatchers.IO) {
        if (!hasJournal()) {
            mutex.withLock {
                if (!hasJournal() && prepared == null) stages.listFiles()?.forEach { it.deleteRecursively() }
            }
            return@withContext
        }
        operation(allowRecovery = true) {
            if (!hasJournal()) return@operation
            progress("Завершение прерванного восстановления…")
            withContext(NonCancellable) { finishJournal() }
        }
    }

    private suspend fun finishJournal() {
        val record = journal.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        fun strings(name: String): List<String> = record.getJSONArray(name).let { a -> (0 until a.length()).map { a.getString(it) } }
        val current = reminders.withDataLock { snapshot() }
        val committed = BackupFormat.fingerprint(current) == record.getString("fingerprint")
        if (committed) {
            progress("Обновление напоминаний и виджетов…")
            val ids = record.getJSONArray("oldRules").let { a -> (0 until a.length()).map { a.getLong(it) } }
            reminders.finishReplacement(ids)
            refreshWidgets()
        }
        val references = current.paths()
        // Rollback removes only newly installed files; success removes only old, unreferenced files.
        strings(if (committed) "oldFiles" else "newFiles").filter { it !in references && BackupFormat.validPath(it) }.forEach { path ->
            val file = CardImageStore(context).resolve(path)
            check(!file.exists() || file.delete())
        }
        val finished = BackupState(busy = true, message = if (committed)
            "Данные восстановлены. Для виджетов выберите карточки заново. Напоминания используют текущее время и часовой пояс устройства." +
                if (current.reminders.any { it.enabled } && !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
                    " Чтобы получать напоминания, разрешите уведомления для OnTop в настройках Android." else ""
            else "Прерванная замена отменена. Прежние заметки сохранены.")
        checkpoint("reconciled")
        stages.listFiles()?.forEach { it.deleteRecursively() }
        prepared = null
        if (committed) {
            atomicWrite(generationFile, record.getString("generation").toByteArray(Charsets.UTF_8))
            checkpoint("generation")
        }
        journal.delete()
        blocked = false
        if (committed) revision.value = record.getString("generation")
        mutableState.value = finished
    }

    private fun writeJournal(record: JSONObject) {
        atomicWrite(journal, record.toString().toByteArray(Charsets.UTF_8))
    }

    private fun atomicWrite(file: AtomicFile, bytes: ByteArray) {
        check(root.isDirectory || root.mkdirs())
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output)
        } catch (error: Throwable) { file.failWrite(output); throw error }
        syncDirectory(root)
    }

    private fun syncDirectory(directory: File) {
        if (!directory.exists()) return
        val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
}
