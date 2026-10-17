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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.ontop.app.widget.notifyCardWidgets

data class BoardState(
    val cards: List<CardEntity> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val draftImagePath: String? = null
)

class BoardViewModel @JvmOverloads constructor(
    application: Application,
    database: OnTopDatabase = OnTopDatabase.getInstance(application)
) : AndroidViewModel(application) {
    private val dao = database.cardDao()
    private val commentDao = database.cardCommentDao()
    private val images = CardImageStore(application)
    private var imageEditorActive = false
    private var originalImagePath: String? = null

    fun beginImageEdit(card: CardEntity?) {
        if (imageEditorActive) return
        imageEditorActive = true
        originalImagePath = card?.imagePath
        mutableState.update { it.copy(draftImagePath = originalImagePath) }
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
        imageEditorActive = false
    }

    fun imagePickerFailed() {
        mutableState.update { it.copy(error = "Не удалось открыть выбор изображения. Попробуйте ещё раз.") }
    }

    private suspend fun removeImage(path: String?) {
        try { images.delete(path) } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            mutableState.update { it.copy(error = "Изменения сохранены, но старый файл изображения не удалось удалить.") }
        }
    }

    fun observeComments(cardId: Long) = commentDao.observeComments(cardId)

    fun addComment(cardId: Long, text: String, onSuccess: (Long) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        var insertedId = 0L
        write({ onSuccess(insertedId) }) {
            insertedId = commentDao.insert(CardCommentEntity(cardId = cardId, text = trimmed,
                createdAt = System.currentTimeMillis()))
        }
    }

    fun deleteComment(id: Long, onSuccess: () -> Unit) = write(onSuccess) {
        check(commentDao.deleteById(id) == 1)
    }
    private val mutableState = MutableStateFlow(BoardState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                dao.observeCards().collect { cards ->
                    mutableState.update { it.copy(cards = cards, loading = false) }
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
        if (title.isBlank()) return
        write(onSuccess) {
            val now = System.currentTimeMillis()
            val imagePath = mutableState.value.draftImagePath
            if (card == null) {
                dao.insertAtEnd(CardEntity(
                    title = title.trim(), description = description.trim(), size = size,
                    createdAt = now, updatedAt = now, sortOrder = 0, imagePath = imagePath
                ))
            } else {
                check(dao.update(card.copy(
                    title = title.trim(), description = description.trim(), size = size, updatedAt = now,
                    imagePath = imagePath
                )) == 1)
            }
            // The previous file remains usable until the new database reference has committed.
            if (card != null) refreshWidgets(card.id)
            if (card?.imagePath != imagePath) removeImage(card?.imagePath)
            imageEditorActive = false
        }
    }

    fun delete(card: CardEntity, onSuccess: () -> Unit) = write(onSuccess) {
        check(dao.delete(card) == 1)
        refreshWidgets(card.id)
        removeImage(card.imagePath)
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

    private fun write(onSuccess: () -> Unit, operation: suspend () -> Unit) {
        if (mutableState.value.saving) return
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                // Room suspend DAO methods dispatch database work off the main thread.
                operation()
                mutableState.update { it.copy(saving = false) }
                onSuccess()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(saving = false, error = "Не удалось сохранить изменения или изображение. Проверьте доступность файла и свободное место и попробуйте ещё раз.")
                }
            } catch (_: OutOfMemoryError) {
                mutableState.update { it.copy(saving = false, error = "Недостаточно памяти для изображения. Выберите другое изображение.") }
            }
        }
    }
}
