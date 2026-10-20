package com.ontop.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

data class ChecklistItem(val id: String = UUID.randomUUID().toString(), val text: String = "",
    val done: Boolean = false) : java.io.Serializable

object Checklist {
    fun clean(items: List<ChecklistItem>): List<ChecklistItem> = items.filter { it.text.isNotBlank() }
        .map { it.copy(text = it.text.trim()) }.also { list ->
            require(list.all { it.id.isNotBlank() && it.id.length <= 128 })
            require(list.map { it.id }.distinct().size == list.size)
        }
    fun progress(items: List<ChecklistItem>): Int {
        val actual = items.filter { it.text.isNotBlank() }
        return if (actual.isEmpty()) 0 else (100.0 * actual.count { it.done } / actual.size).roundToInt()
    }
    fun encode(items: List<ChecklistItem>): String = JSONArray().apply {
        clean(items).forEachIndexed { index, item -> put(JSONObject().put("id", item.id)
            .put("text", item.text).put("done", item.done).put("position", index)) }
    }.toString()
    fun decode(value: String?): List<ChecklistItem> {
        if (value == null) return emptyList()
        val array = JSONArray(value)
        val entries = (0 until array.length()).map { array.getJSONObject(it) }
        require(entries.map { it.getInt("position") }.sorted() == entries.indices.toList())
        val items = entries.sortedBy { it.getInt("position") }.map {
            require(it.get("id") is String && it.get("text") is String && it.get("done") is Boolean)
            ChecklistItem(it.getString("id"), it.getString("text"), it.getBoolean("done"))
        }
        return clean(items)
    }
    fun text(items: List<ChecklistItem>): String = clean(items).joinToString("\n") {
        (if (it.done) "[x] " else "[ ] ") + it.text
    }
}

fun CardEntity.checklistItems(): List<ChecklistItem> = if (noteType == NoteType.CHECKLIST) Checklist.decode(checklistJson) else emptyList()
fun CardEntity.normalizedChecklist(): CardEntity = if (noteType == NoteType.CHECKLIST) {
    val items = checklistItems()
    copy(checklistJson = Checklist.encode(items), progress = Checklist.progress(items))
} else copy(checklistJson = null)
