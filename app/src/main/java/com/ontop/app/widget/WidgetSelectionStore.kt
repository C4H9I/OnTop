package com.ontop.app.widget

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WidgetMode { FIXED, ROTATION }
data class WidgetSelection(val mode: WidgetMode = WidgetMode.FIXED, val fixedId: Long? = null,
    val currentId: Long? = null, val changedAt: Long = 0,
    val tagIds: Set<Long> = emptySet(), val tagFilterEnabled: Boolean = tagIds.isNotEmpty()) {
    val cardId get() = if (mode == WidgetMode.FIXED) fixedId else currentId
}

/** Separate entries allow every launcher instance to keep its own selection. */
class WidgetSelectionStore(context: Context, name: String = "widget_selections") {
    companion object { internal val mutex = Mutex() }
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    suspend fun clear() = mutex.withLock { withContext(Dispatchers.IO) { check(preferences.edit().clear().commit()) } }

    suspend fun selection(widgetId: Int): WidgetSelection = withContext(Dispatchers.IO) {
        val key = widgetId.toString()
        val mode = if (preferences.getString("$key.mode", null) == "ROTATION") WidgetMode.ROTATION else WidgetMode.FIXED
        val tags = preferences.getStringSet("$key.tags", emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 } }.toSet()
        WidgetSelection(mode, preferences.getLong(key, -1).takeIf { it > 0 },
            preferences.getLong("$key.current", -1).takeIf { it > 0 }, preferences.getLong("$key.changed", 0),
            tags, preferences.getBoolean("$key.tagFilter", tags.isNotEmpty()))
    }

    internal suspend fun save(widgetId: Int, selection: WidgetSelection) = withContext(Dispatchers.IO) {
        require(widgetId > 0)
        val key = widgetId.toString()
        check(preferences.edit().putString("$key.mode", selection.mode.name)
            .putLong(key, selection.fixedId ?: -1).putLong("$key.current", selection.currentId ?: -1)
            .putLong("$key.changed", selection.changedAt)
            .putStringSet("$key.tags", selection.tagIds.map { it.toString() }.toSet())
            .putBoolean("$key.tagFilter", selection.tagFilterEnabled).commit())
    }

    suspend fun rotationIds(): Set<Int> = withContext(Dispatchers.IO) {
        preferences.all.filter { it.key.endsWith(".mode") && it.value == "ROTATION" }
            .keys.mapNotNull { it.removeSuffix(".mode").toIntOrNull() }.toSet()
    }

    suspend fun get(widgetId: Int): Long? = selection(widgetId).cardId

    suspend fun set(widgetId: Int, cardId: Long) = withContext(Dispatchers.IO) {
        require(widgetId > 0 && cardId > 0)
        save(widgetId, WidgetSelection(fixedId = cardId))
    }

    suspend fun remove(widgetId: Int) = withContext(Dispatchers.IO) {
        val key = widgetId.toString()
        check(preferences.edit().remove(key).remove("$key.mode").remove("$key.current").remove("$key.changed")
            .remove("$key.tags").remove("$key.tagFilter").commit())
    }

    suspend fun idsFor(cardId: Long): IntArray = withContext(Dispatchers.IO) {
        preferences.all.keys.mapNotNull { it.toIntOrNull() }.filter { get(it) == cardId }.toIntArray()
    }
}
