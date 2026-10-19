package com.ontop.app

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** One saveable, local draft. Opening/closing topic settings never writes to Room. */
internal class EditorTopics(val original: List<Long>) {
    var ids by mutableStateOf(original)
    var names by mutableStateOf(emptyList<String>())
    var open by mutableStateOf(false)
    val changed get() = ids.toSet() != original.toSet() || names.isNotEmpty()

    companion object {
        val Saver = listSaver<EditorTopics, Any>(save = {
            listOf(it.original.toLongArray(), it.ids.toLongArray(), ArrayList(it.names), it.open)
        }, restore = { values ->
            EditorTopics((values[0] as LongArray).toList()).apply {
                ids = (values[1] as LongArray).toList()
                names = (values[2] as List<*>).filterIsInstance<String>()
                open = values[3] as Boolean
            }
        })
    }
}

@Composable
internal fun rememberEditorTopics(cardId: Long?, initialIds: List<Long>): EditorTopics =
    rememberSaveable(cardId, saver = EditorTopics.Saver) { EditorTopics(initialIds) }
