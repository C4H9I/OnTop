package com.ontop.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardCommentEntity
import com.ontop.app.data.CardImageStore
import com.ontop.app.data.CardSize
import com.ontop.app.data.OnTopDatabase
import com.ontop.app.data.TagEntity
import com.ontop.app.data.CardTagCrossRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.ontop.app.widget.notifyCardWidgets
import androidx.room.withTransaction
import com.ontop.app.data.NoteInteractionRepository
import com.ontop.app.data.NoteInteractionType
import com.ontop.app.text.RichTextCodec
import com.ontop.app.text.imagePaths
import com.ontop.app.backup.BackupManager

data class BoardState(
    val cards: List<CardEntity> = emptyList(),
    val storedCards: List<CardEntity> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val draftImagePath: String? = null,
    val sortMode: BoardSort = BoardSort.MANUAL,
    val tags: List<TagEntity> = emptyList(),
    val cardTags: List<CardTagCrossRef> = emptyList(),
    val selectedTagId: Long? = null
)

class BoardViewModel internal constructor(
    application: Application,
    private val database: OnTopDatabase,
    internal val backup: BackupManager,
    private val boardPreferences: BoardPreferences = BoardPreferences(application)
) : AndroidViewModel(application) {
    internal val analysis = com.ontop.app.analysis.AnalysisExporter(application, database)
    internal fun exportAnalysis(uri: Uri) {
        if (uri.scheme != "content") { analysis.pickerFailed(); return }
        viewModelScope.launch {
            analysis.export { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: error("Не удалось открыть JSON для записи") }
        }
    }
    @JvmOverloads constructor(application: Application, database: OnTopDatabase = OnTopDatabase.getInstance(application)) :
        this(application, database, BackupManager.get(application, database))
    internal fun createBackup(uri: Uri) {
        if (uri.scheme != "content") { backup.rejectDocument(); return }
        viewModelScope.launch {
        backup.create { getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("Не удалось открыть файл для записи.") }
    } }
    internal fun prepareBackup(uri: Uri) {
        if (uri.scheme != "content") { backup.rejectDocument(); return }
        viewModelScope.launch {
        backup.prepare { getApplication<Application>().contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл.") }
    } }
    internal fun restoreBackup() { viewModelScope.launch { backup.restore() } }
    internal fun cancelBackupRestore() { viewModelScope.launch { backup.discardPrepared() } }
    internal fun recoverBackup() { viewModelScope.launch { backup.recover() } }
    internal val sliderSession = NoteSliderSession()
    private val dao = database.cardDao()
    internal val interactions = NoteInteractionRepository(database)

    internal fun recordOpening(id: Long, type: NoteInteractionType) {
        require(type.isOpening)
        if (BackupManager.active.value || backup.state.value.recoveryRequired) return
        val occurredAt = System.currentTimeMillis()
        val revision = backup.revision.value
        viewModelScope.launch {
            try {
                BackupManager.dataAccess {
                    if (!BackupManager.active.value && !backup.state.value.recoveryRequired && backup.revision.value == revision)
                        interactions.record(id, type, occurredAt)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { android.util.Log.w("OnTop", "Could not record local interaction", error) }
        }
    }
    private val tagDao = database.tagDao()
    private val commentDao = database.cardCommentDao()
    private val reminderDao = database.reminderDao()
    private val reminders = com.ontop.app.reminders.ReminderManager(application, database)
    private val images = CardImageStore(application)
    private var imageEditorActive = false
    private var originalImagePath: String? = null
    private val inlineDraftPaths = mutableSetOf<String>()
    private val mutableSharedImage = MutableStateFlow<Pair<String, String>?>(null)
    internal val sharedInlineImage = mutableSharedImage.asStateFlow()
    private var sharedImageRequest: String? = null
    internal fun prepareSharedImage(id: String, uri: Uri, restoredPath: String?) {
        if (sharedImageRequest == id) return
        sharedImageRequest = id
        if (restoredPath != null) {
            trackInlineDraft(setOf(restoredPath))
            mutableSharedImage.value = id to restoredPath
        } else importInlineImage(uri) { path -> mutableSharedImage.value = id to path }
    }
    internal fun trackInlineDraft(paths: Set<String>) { inlineDraftPaths.addAll(paths) }
    internal fun importInlineImage(uri: Uri, onImported: (String) -> Unit) = write({}) {
        val path = images.import(uri)
        inlineDraftPaths.add(path)
        onImported(path)
    }

    fun beginImageEdit(card: CardEntity?, restoredDraftPath: String? = null) {
        if (imageEditorActive) return
        imageEditorActive = true
        originalImagePath = card?.imagePath
        mutableState.update { it.copy(draftImagePath = restoredDraftPath ?: originalImagePath) }
    }

    fun importImage(uri: Uri) = write({}) {
        val newPath = images.import(uri)
        val previous = mutableState.value.draftImagePath
        mutableState.update { it.copy(draftImagePath = newPath) }
        if (previous != originalImagePath) removeImage(previous)
    }

    fun removeDraftImage() = write({}) {
        val previous = mutableState.value.draftImagePath
        mutableState.update { it.copy(draftImagePath = null) }
        if (previous != originalImagePath) removeImage(previous)
    }

    fun cancelImageEdit(onSuccess: () -> Unit) = write(onSuccess) {
        val draft = mutableState.value.draftImagePath
        if (draft != originalImagePath) removeImage(draft)
        inlineDraftPaths.toList().forEach { removeImage(it) }
        inlineDraftPaths.clear()
        imageEditorActive = false
        mutableState.update { it.copy(draftImagePath = null) }
    }

    fun imagePickerFailed() {
        mutableState.update { it.copy(error = "Не удалось открыть выбор изображения. Попробуйте ещё раз.") }
    }

    private suspend fun removeImage(path: String?) {
        try {
            if (path != null && dao.allCards().any { card -> card.imagePath == path ||
                    path in RichTextCodec.decode(card.description, card.formatting).imagePaths() }) return
            images.delete(path)
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            mutableState.update { it.copy(error = "Изменения сохранены, но старый файл изображения не удалось удалить.") }
        }
    }

    fun observeComments(cardId: Long) = commentDao.observeComments(cardId)
    internal suspend fun loadReminder(cardId: Long) = reminders.load(cardId)
    internal fun observeReminder(cardId: Long) = reminderDao.observeForCard(cardId)
    internal suspend fun saveReminder(cardId: Long, hour: Int, minute: Int, enabled: Boolean) {
        check(!BackupManager.active.value && !backup.state.value.recoveryRequired)
        BackupManager.dataAccess {
            val before = interactions.snapshot(cardId)
            reminders.save(cardId, hour, minute, enabled)
            interactions.recordEditIfChanged(before, cardId)
        }
    }
    internal suspend fun saveReminder(cardId: Long, schedule: com.ontop.app.reminders.ReminderSchedule,
        enabled: Boolean, pausedUntil: Long?) {
        check(!BackupManager.active.value && !backup.state.value.recoveryRequired)
        BackupManager.dataAccess {
            val before = interactions.snapshot(cardId)
            reminders.save(cardId, schedule, enabled, pausedUntil)
            interactions.recordEditIfChanged(before, cardId)
        }
    }

    fun addComment(cardId: Long, text: String, onSuccess: (Long) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        var insertedId = 0L
        write({ onSuccess(insertedId) }) {
            database.withTransaction {
                insertedId = commentDao.insert(CardCommentEntity(cardId = cardId, text = trimmed,
                    createdAt = System.currentTimeMillis()))
                interactions.record(cardId, NoteInteractionType.COMMENT_ADDED)
            }
        }
    }

    fun deleteComment(id: Long, onSuccess: () -> Unit) = write(onSuccess) {
        check(commentDao.deleteById(id) == 1)
    }
    private val mutableState = MutableStateFlow(BoardState())
    val state = mutableState.asStateFlow()
    private val sortMutex = Mutex()

    internal fun selectTag(id: Long?) {
        mutableState.update { it.copy(selectedTagId = id?.takeIf { key -> it.tags.any { tag -> tag.id == key } }) }
    }

    internal fun createTag(name: String, onSuccess: () -> Unit) =
        write(onSuccess, "Не удалось создать тему. Проверьте название и отсутствие дубликата.") { tagDao.create(name) }

    internal fun renameTag(id: Long, name: String, onSuccess: () -> Unit) =
        write(onSuccess, "Не удалось переименовать тему. Проверьте название и отсутствие дубликата.") { tagDao.rename(id, name) }

    internal fun deleteTag(id: Long, onSuccess: () -> Unit) =
        write(onSuccess, "Не удалось удалить тему. Повторите попытку.") {
            tagDao.delete(id)
            refreshWidgets(0L)
        }

    fun setBoardSort(mode: BoardSort) {
        if (mutableState.value.loading) return
        viewModelScope.launch {
            sortMutex.withLock {
                if (mutableState.value.sortMode == mode) return@withLock
                try {
                    boardPreferences.saveSort(mode)
                    mutableState.update { it.copy(sortMode = mode) }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) {
                    mutableState.update { it.copy(error = "Не удалось сохранить порядок доски. Повторите выбор.") }
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            backup.revision.drop(1).collect {
                val obsolete = inlineDraftPaths.toSet() + listOfNotNull(mutableState.value.draftImagePath)
                inlineDraftPaths.clear()
                imageEditorActive = false
                originalImagePath = null
                sliderSession.currentId = null; sliderSession.index = 0
                mutableState.update { state -> state.copy(draftImagePath = null, selectedTagId = null) }
                try { BackupManager.dataAccess { obsolete.forEach { path -> removeImage(path) } }
                } catch (error: CancellationException) { throw error
                } catch (_: Exception) {
                    mutableState.update { state -> state.copy(error = "Не удалось очистить временные изображения прежнего черновика.") }
                }
            }
        }
        viewModelScope.launch {
            try {
                backup.recover()
                val sortMode = boardPreferences.loadSort()
                mutableState.update { it.copy(sortMode = sortMode) }
                combine(dao.observeAllCards(), tagDao.observeTags()) { cards, tags -> cards to tags }.collect { (cards, topics) ->
                    val tags = topics.map { it.tag }
                    mutableState.update { it.copy(cards = cards.filter { card -> card.status == com.ontop.app.data.CardStatus.ACTIVE },
                        storedCards = cards, tags = tags, cardTags = topics.flatMap { topic -> topic.links },
                        selectedTagId = it.selectedTagId?.takeIf { id -> tags.any { tag -> tag.id == id } }, loading = false) }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = "Не удалось загрузить карточки. Перезапустите приложение.")
                }
            }
        }
    }

    fun save(card: CardEntity?, title: String, description: String, size: CardSize, onSuccess: () -> Unit) {
        saveNote(card, title, description, size, null, false, onSuccess)
    }

    internal fun saveNote(card: CardEntity?, title: String, description: String, size: CardSize,
        reminder: com.ontop.app.reminders.ReminderDraft?, reminderChanged: Boolean, onSuccess: () -> Unit,
        formatting: String? = if (description == card?.description) card?.formatting else null,
        importance: Int = card?.importance ?: 50, tagIds: List<Long>? = null, newTagNames: List<String> = emptyList(),
        progress: Int? = card?.progress) {
        if (title.isBlank()) return
        require(importance in 0..100)
        require(progress == null || progress in 0..100)
        write(onSuccess, "Не удалось сохранить заметку и напоминание. Черновик сохранён на экране — повторите сохранение.") {
            val now = System.currentTimeMillis()
            val imagePath = mutableState.value.draftImagePath
            val document = if (card == null) {
                CardEntity(
                    title = title.trim(), description = description, size = size,
                    createdAt = now, updatedAt = now, sortOrder = 0, imagePath = imagePath, formatting = formatting, importance = importance, progress = progress
                )
            } else {
                card.copy(
                    title = title.trim(), description = description, size = size, updatedAt = now,
                    imagePath = imagePath, formatting = formatting, importance = importance, progress = progress
                )
            }
            val before = card?.let { interactions.snapshot(it.id) }
            reminders.saveNote(document, card == null, reminder, reminderChanged, tagIds, newTagNames,
                onSaved = { id -> interactions.recordEditIfChanged(before, id) })
            val oldInline = card?.let { RichTextCodec.decode(it.description, it.formatting).imagePaths() }.orEmpty()
            (oldInline + inlineDraftPaths).forEach { removeImage(it) }
            inlineDraftPaths.clear()
            // The previous file remains usable until the new database reference has committed.
            refreshWidgets(card?.id ?: 0L)
            if (card?.imagePath != imagePath) removeImage(card?.imagePath)
            imageEditorActive = false
        }
    }

    internal fun savePercentages(id: Long, importance: Int, progress: Int?, onSuccess: () -> Unit) {
        require(importance in 0..100 && (progress == null || progress in 0..100))
        write(onSuccess, "Не удалось сохранить важность и готовность. Повторите сохранение.") {
            database.withTransaction {
                val before = interactions.snapshot(id)
                check(dao.updatePercentages(id, importance, progress, System.currentTimeMillis()) == 1)
                interactions.recordEditIfChanged(before, id)
            }
            refreshWidgets(id)
        }
    }

    internal fun changeStatus(card: CardEntity, status: com.ontop.app.data.CardStatus, onSuccess: () -> Unit) = write(onSuccess) {
        reminders.changeStatus(card.id, status)
        refreshWidgets(card.id)
    }

    fun delete(card: CardEntity, onSuccess: () -> Unit) = changeStatus(card, com.ontop.app.data.CardStatus.TRASHED) {
        cancelImageEdit(onSuccess)
    }

    internal fun permanentlyDelete(card: CardEntity, onSuccess: () -> Unit) = write(onSuccess) {
        val actual = dao.findById(card.id) ?: error("Заметка недоступна")
        check(actual.status == com.ontop.app.data.CardStatus.TRASHED)
        reminders.deleteCard(actual)
        refreshWidgets(actual.id)
        removeImage(actual.imagePath)
        RichTextCodec.decode(actual.description, actual.formatting).imagePaths().forEach { removeImage(it) }
        inlineDraftPaths.toList().forEach { removeImage(it) }
        inlineDraftPaths.clear()
        val draft = mutableState.value.draftImagePath
        if (imageEditorActive && draft != card.imagePath) removeImage(draft)
        imageEditorActive = false
    }

    fun clearError() { mutableState.update { it.copy(error = null) } }

    fun cardNotFound() { mutableState.update { it.copy(error = "Карточка удалена или недоступна.") } }

    private suspend fun refreshWidgets(cardId: Long) {
        try { notifyCardWidgets(getApplication(), cardId)
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(error = "Карточка сохранена, но виджет не удалось обновить. Откройте его настройки.") }
        }
    }

    private fun write(onSuccess: () -> Unit,
        failureMessage: String = "Не удалось сохранить изменения или изображение. Проверьте доступность файла и свободное место и попробуйте ещё раз.",
        operation: suspend () -> Unit) {
        if (mutableState.value.saving || BackupManager.active.value || backup.state.value.recoveryRequired) return
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                // Room suspend DAO methods dispatch database work off the main thread.
                BackupManager.dataAccess { operation() }
                mutableState.update { it.copy(saving = false) }
                onSuccess()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(saving = false, error = failureMessage)
                }
            } catch (_: OutOfMemoryError) {
                mutableState.update { it.copy(saving = false, error = "Недостаточно памяти для изображения. Выберите другое изображение.") }
            }
        }
    }
}
