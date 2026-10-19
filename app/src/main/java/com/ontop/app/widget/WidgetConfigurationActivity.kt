package com.ontop.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ontop.app.data.CardEntity
import com.ontop.app.LocalCardImage
import com.ontop.app.cardBackground
import com.ontop.app.R
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
                var selectedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
                var mode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(WidgetMode.FIXED) }
                var initialized by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(retry) {
                    loading = true
                    error = null
                    try {
                        if (!initialized) {
                            val selection = WidgetSelectionStore(this@WidgetConfigurationActivity).selection(widgetId)
                            selectedId = selection.cardId
                            mode = selection.mode
                            initialized = true
                        }
                        val dao = withContext(Dispatchers.IO) { OnTopDatabase.getInstance(this@WidgetConfigurationActivity).cardDao() }
                        dao.observeCards().collect { cards = it; loading = false }
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { error = "Не удалось загрузить карточки"; loading = false }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                        Text("Карточка для виджета OnTop", style = MaterialTheme.typography.headlineSmall)
                        WidgetModeChoices(mode, !saving) { mode = it }
                        if (mode == WidgetMode.ROTATION) {
                            Text("Заметки меняются примерно каждые 30 минут. Более важные появляются чаще")
                            Button(enabled = !saving && !loading, onClick = {
                                saving = true
                                lifecycleScope.launch {
                                    try {
                                        WidgetRotation.configure(this@WidgetConfigurationActivity, widgetId, WidgetMode.ROTATION)
                                        setResult(RESULT_OK, result); finish()
                                    } catch (e: CancellationException) { throw e
                                    } catch (_: Exception) { error = "Не удалось настроить виджет. Повторите попытку."; saving = false }
                                }
                            }) { Text("Включить ротацию") }
                        }
                        if (loading) Text("Загрузка…")
                        if (mode == WidgetMode.FIXED && !loading && error == null && cards.isEmpty()) Text("Сначала создайте карточку в OnTop, затем добавьте виджет.")
                        error?.let { Text(it); TextButton(onClick = { retry++ }, enabled = !saving) { Text("Повторить") } }
                        LazyColumn(Modifier.weight(1f)) {
                            items(if (mode == WidgetMode.FIXED) cards else emptyList(), key = { it.id }) { card ->
                                WidgetCardChoice(card, selectedId == card.id, enabled = !saving, onClick = {
                                    if (saving) return@WidgetCardChoice
                                    saving = true
                                    lifecycleScope.launch {
                                        try {
                                            val exists = withContext(Dispatchers.IO) {
                                                OnTopDatabase.getInstance(this@WidgetConfigurationActivity).cardDao().findById(card.id) != null
                                            }
                                            check(exists)
                                            WidgetRotation.configure(this@WidgetConfigurationActivity, widgetId, WidgetMode.FIXED, card.id)
                                            selectedId = card.id
                                            setResult(RESULT_OK, result)
                                            finish()
                                        } catch (e: CancellationException) { throw e
                                        } catch (_: Exception) { error = "Не удалось настроить виджет. Повторите выбор."; saving = false }
                                    }
                                })
                            }
                        }
                        TextButton(enabled = !saving, onClick = { finish() }) { Text("Отмена") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WidgetModeChoices(mode: WidgetMode, enabled: Boolean, onMode: (WidgetMode) -> Unit) {
    listOf(WidgetMode.FIXED to "Одна заметка", WidgetMode.ROTATION to "Случайная ротация").forEach { (value, title) ->
        Row(Modifier.fillMaxWidth().selectable(mode == value, enabled = enabled, role = Role.RadioButton,
            onClick = { onMode(value) }), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = mode == value, onClick = null, enabled = enabled)
            Text(title)
        }
    }
}

/** Uses the same bounded, IO-dispatched local image loader as the board and editor. */
@Composable
internal fun WidgetCardChoice(card: CardEntity, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .background(if (selected) Color(0xFFDCE5D4) else MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) Color(0xFF304734) else MaterialTheme.colorScheme.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(10.dp).testTag("widget-card-${card.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(cardBackground(card.id))
            .testTag("widget-thumbnail-${card.id}")) {
            card.widgetCoverPath()?.let { path ->
                LocalCardImage(path, Modifier.fillMaxSize(), maxSide = 256, showError = false)
            }
        }
        Text(card.title, modifier = Modifier.weight(1f), maxLines = 5,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Color(0xFF304734) else MaterialTheme.colorScheme.onSurface)
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = "Выбрана",
            modifier = Modifier.size(20.dp), tint = Color(0xFF304734))
    }
}
