package com.ontop.app.analysis

import android.content.Context
import androidx.room.withTransaction
import com.ontop.app.backup.BackupManager
import com.ontop.app.data.OnTopDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId

internal data class AnalysisExportState(val busy: Boolean = false, val message: String = "", val error: String? = null)

internal class AnalysisExporter(private val context: Context, private val db: OnTopDatabase) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AnalysisExportState())
    val state = mutableState.asStateFlow()
    fun cancelled() { if (!state.value.busy) mutableState.value = AnalysisExportState(message = "Экспорт отменён") }
    fun pickerFailed() { if (!state.value.busy) mutableState.value = AnalysisExportState(error = "Не удалось открыть диалог сохранения JSON.") }

    suspend fun export(openOutput: () -> OutputStream) {
        if (!mutex.tryLock()) return
        mutableState.value = AnalysisExportState(busy = true, message = "Подготовка JSON…")
        try {
            withContext(Dispatchers.IO) {
                val temporary = File.createTempFile("ontop-analysis-", ".json", context.cacheDir)
                try {
                    val now = Instant.now()
                    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                    BackupManager.dataAccess {
                        db.withTransaction {
                            temporary.bufferedWriter(Charsets.UTF_8).use { AnalysisJson.write(db, it, now, version, ZoneId.systemDefault()) }
                        }
                    }
                    ensureActive()
                    mutableState.value = AnalysisExportState(busy = true, message = "Сохранение JSON…")
                    openOutput().use { output -> temporary.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    } }
                } finally { temporary.delete() }
            }
            mutableState.value = AnalysisExportState(message = "JSON сохранён. Файл может содержать личную информацию.")
        } catch (cancelled: CancellationException) {
            mutableState.value = AnalysisExportState(message = "Экспорт отменён")
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = AnalysisExportState(error = "Не удалось сохранить JSON. Файл может быть неполным. Проверьте место и доступ к папке, затем повторите экспорт.")
        } finally { mutex.unlock() }
    }
}
