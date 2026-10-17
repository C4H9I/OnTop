package com.ontop.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ontop.app.data.CardEntity
import com.ontop.app.data.OnTopDatabase
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        val manager = AppWidgetManager.getInstance(this)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            manager.getAppWidgetInfo(widgetId)?.provider != ComponentName(this, CardWidgetReceiver::class.java)) {
            finish()
            return
        }
        setContent {
            OnTopTheme {
                var cards by remember { mutableStateOf<List<CardEntity>>(emptyList()) }
                var loading by remember { mutableStateOf(true) }
                var saving by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var retry by remember { mutableIntStateOf(0) }
                LaunchedEffect(retry) {
                    loading = true
                    error = null
                    try {
                        val dao = withContext(Dispatchers.IO) { OnTopDatabase.getInstance(this@WidgetConfigurationActivity).cardDao() }
                        dao.observeCards().collect { cards = it; loading = false }
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { error = "Не удалось загрузить карточки"; loading = false }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                        Text("Карточка для виджета OnTop", style = MaterialTheme.typography.headlineSmall)
                        if (loading) Text("Загрузка…")
                        if (!loading && error == null && cards.isEmpty()) Text("Сначала создайте карточку в OnTop, затем добавьте виджет.")
                        error?.let { Text(it); TextButton(onClick = { retry++ }, enabled = !saving) { Text("Повторить") } }
                        LazyColumn(Modifier.weight(1f)) {
                            items(cards, key = { it.id }) { card ->
                                TextButton(enabled = !saving, onClick = {
                                    saving = true
                                    lifecycleScope.launch {
                                        try {
                                            val exists = withContext(Dispatchers.IO) {
                                                OnTopDatabase.getInstance(this@WidgetConfigurationActivity).cardDao().findById(card.id) != null
                                            }
                                            check(exists)
                                            WidgetSelectionStore(this@WidgetConfigurationActivity).set(widgetId, card.id)
                                            updateCardWidget(this@WidgetConfigurationActivity, widgetId)
                                            setResult(RESULT_OK, result)
                                            finish()
                                        } catch (e: CancellationException) { throw e
                                        } catch (_: Exception) { error = "Не удалось настроить виджет. Повторите выбор."; saving = false }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text(card.title) }
                            }
                        }
                        TextButton(enabled = !saving, onClick = { finish() }) { Text("Отмена") }
                    }
                }
            }
        }
    }
}
