package com.ontop.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardCommentEntity
import com.ontop.app.data.CardSize
import com.ontop.app.data.OnTopDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BoardState(
    val cards: List<CardEntity> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null
)

class BoardViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = OnTopDatabase.getInstance(application).cardDao()
    private val commentDao = OnTopDatabase.getInstance(application).cardCommentDao()

    fun observeComments(cardId: Long) = commentDao.observeComments(cardId)

    fun addComment(cardId: Long, text: String, onSuccess: () -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        write(onSuccess) {
            commentDao.insert(CardCommentEntity(cardId = cardId, text = trimmed,
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
            if (card == null) {
                dao.insertAtEnd(CardEntity(
                    title = title.trim(), description = description.trim(), size = size,
                    createdAt = now, updatedAt = now, sortOrder = 0
                ))
            } else {
                check(dao.update(card.copy(
                    title = title.trim(), description = description.trim(), size = size, updatedAt = now
                )) == 1)
            }
        }
    }

    fun delete(card: CardEntity, onSuccess: () -> Unit) = write(onSuccess) {
        check(dao.delete(card) == 1)
    }

    fun clearError() { mutableState.update { it.copy(error = null) } }

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
                    it.copy(saving = false, error = "Не удалось сохранить изменения. Попробуйте ещё раз.")
                }
            }
        }
    }
}
