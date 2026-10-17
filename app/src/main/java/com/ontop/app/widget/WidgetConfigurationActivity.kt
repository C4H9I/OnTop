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
                var selectedId by remember { mutableStateOf<Long?>(null) }
                LaunchedEffect(retry) {
                    loading = true
                    error = null
                    try {
                        selectedId = WidgetSelectionStore(this@WidgetConfigurationActivity).get(widgetId)
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
                                WidgetCardChoice(card, selectedId == card.id, enabled = !saving, onClick = {
                                    if (saving) return@WidgetCardChoice
                                    saving = true
                                    lifecycleScope.launch {
                                        try {
                                            val exists = withContext(Dispatchers.IO) {
                                                OnTopDatabase.getInstance(this@WidgetConfigurationActivity).cardDao().findById(card.id) != null
                                            }
                                            check(exists)
                                            WidgetSelectionStore(this@WidgetConfigurationActivity).set(widgetId, card.id)
                                            selectedId = card.id
                                            updateCardWidget(this@WidgetConfigurationActivity, widgetId)
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
            card.imagePath?.let { path ->
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
