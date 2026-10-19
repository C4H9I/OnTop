package com.ontop.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local presentation setting; portable .ontop backups currently contain documents only. */
internal class BoardPreferences(context: Context, name: String = "board_preferences") {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    suspend fun loadSort(): BoardSort = withContext(Dispatchers.IO) {
        val value = preferences.getString("sort", null)
        BoardSort.entries.firstOrNull { it.name == value } ?: BoardSort.MANUAL
    }

    suspend fun saveSort(mode: BoardSort) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("sort", mode.name).commit())
    }
}
