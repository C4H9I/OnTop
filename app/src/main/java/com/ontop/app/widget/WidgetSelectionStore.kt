package com.ontop.app.widget

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Separate entries allow every launcher instance to keep its own selection. */
class WidgetSelectionStore(context: Context, name: String = "widget_selections") {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    suspend fun get(widgetId: Int): Long? = withContext(Dispatchers.IO) {
        preferences.getLong(widgetId.toString(), -1).takeIf { it > 0 }
    }

    suspend fun set(widgetId: Int, cardId: Long) = withContext(Dispatchers.IO) {
        require(widgetId > 0 && cardId > 0)
        check(preferences.edit().putLong(widgetId.toString(), cardId).commit())
    }

    suspend fun remove(widgetId: Int) = withContext(Dispatchers.IO) {
        check(preferences.edit().remove(widgetId.toString()).commit())
    }

    suspend fun idsFor(cardId: Long): IntArray = withContext(Dispatchers.IO) {
        preferences.all.filterValues { it == cardId }.keys.mapNotNull { it.toIntOrNull() }.toIntArray()
    }
}
