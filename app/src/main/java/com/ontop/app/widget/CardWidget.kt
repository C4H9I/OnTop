package com.ontop.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.appwidget.state.updateAppWidgetState
import com.ontop.app.MainActivity
import com.ontop.app.R
import com.ontop.app.data.CardImageStore
import com.ontop.app.data.OnTopDatabase
import com.ontop.app.data.CardEntity
import com.ontop.app.text.RichTextCodec
import com.ontop.app.text.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

class CardWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetRotation.reconcile(context)
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val revision = currentState<Preferences>()[longPreferencesKey("revision")]
            val loaded by produceState<WidgetContent?>(null, revision) {
                value = load(context, widgetId)
            }
            val content = loaded
            if (content == null) Text("OnTop · Загрузка…") else WidgetBody(context, widgetId, content)
        }
    }

    internal suspend fun load(context: Context, widgetId: Int,
        database: OnTopDatabase? = null,
        selections: WidgetSelectionStore = WidgetSelectionStore(context)): WidgetContent {
        var cardId: Long? = null
        var title = "Выберите карточку"
        var image: android.graphics.Bitmap? = null
        var unavailableImage = false
        var mode = WidgetMode.FIXED
        try {
            val db = database ?: OnTopDatabase.getInstance(context)
            if (selections.selection(widgetId).mode == WidgetMode.ROTATION) {
                com.ontop.app.backup.BackupManager.dataAccess {
                    WidgetSelectionStore.mutex.withLock { WidgetRotation.select(selections, db, widgetId, false) }
                }
            }
            val selection = selections.selection(widgetId)
            mode = selection.mode
            cardId = selection.cardId
            if (mode == WidgetMode.ROTATION && cardId == null) title = if (selection.tagFilterEnabled)
                "Нет заметок в выбранных темах. Измените настройки" else "Пока нет заметок"
            if (cardId != null) {
                val card = withContext(Dispatchers.IO) {
                    (database ?: OnTopDatabase.getInstance(context)).cardDao().findById(cardId!!)
                }
                title = card?.title ?: "Карточка удалена"
                if (card == null) cardId = null
                card?.widgetCoverPath()?.let {
                    try {
                        // At most 256px per side: <=256 KiB, also bounded for multiple size variants.
                        image = CardImageStore(context).load(it, 256)
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { unavailableImage = true
                    } catch (_: OutOfMemoryError) { unavailableImage = true }
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            title = "Не удалось загрузить карточку"
        }
        return WidgetContent(cardId, title, image, unavailableImage, mode)
    }

    @Composable
    internal fun WidgetBody(context: Context, widgetId: Int, content: WidgetContent) {
        val (cardId, title, image) = content
        val open = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("ontop://card/${cardId ?: 0}/widget/$widgetId")
            putExtra(MainActivity.EXTRA_CARD_ID, cardId ?: 0L)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val configure = Intent(context, WidgetConfigurationActivity::class.java).apply {
            data = Uri.parse("ontop://widget/$widgetId/configure")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val size = LocalSize.current
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val fontSize = if (size.height.value < 130 || size.width.value < 150) 16f else 18f
        // Reserve the top toolbar and use a conservative line height, including Android font padding.
        val textTop = maxOf(44f, size.height.value * 0.4f)
        val lines = ((size.height.value - textTop - 12) / (fontSize * 1.4f * fontScale))
            .toInt().coerceIn(1, 30)
        val backgrounds = listOf(0xFF304734, 0xFF9BAA8B, 0xFFE7D4BD, 0xFFDDA18A, 0xFFF5F0E9)
        val background = Color(backgrounds[Math.floorMod(cardId ?: 0L, backgrounds.size.toLong()).toInt()])
        val textColor = if (image != null || background == Color(0xFF304734)) Color.White else Color(0xFF243529)
        Box(GlanceModifier.fillMaxSize().appWidgetBackground().background(background).cornerRadius(20.dp)) {
            // A drawable gradient scales in RemoteViews; no extra raster or Compose Brush is needed.
            if (image != null) {
                Image(ImageProvider(image), null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Image(ImageProvider(R.drawable.widget_photo_shade), null,
                    modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            Column(GlanceModifier.fillMaxSize().clickable(actionStartActivity(if (cardId != null) open else configure))
                .padding(start = 12.dp, end = 12.dp, top = textTop.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom) {
                if (image != null) {
                    // Native text shadow protects glyphs on bright details without dimming the photo.
                    // Keep the same size, font padding, line limit and parent click target as Glance Text.
                    AndroidRemoteViews(android.widget.RemoteViews(context.packageName, R.layout.widget_photo_title).apply {
                        setTextViewText(R.id.widget_photo_title, title)
                        setTextViewTextSize(R.id.widget_photo_title, android.util.TypedValue.COMPLEX_UNIT_SP, fontSize)
                        setInt(R.id.widget_photo_title, "setMaxLines", lines)
                    }, modifier = GlanceModifier.fillMaxWidth())
                } else {
                    Text(title, modifier = GlanceModifier.fillMaxWidth(),
                        style = TextStyle(color = ColorProvider(textColor), fontSize = fontSize.sp), maxLines = lines)
                }
            }
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                Box(GlanceModifier.size(44.dp).clickable(actionStartActivity(configure)).padding(6.dp)) {
                    Box(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_icon_background)),
                        contentAlignment = Alignment.Center) {
                        Image(ImageProvider(R.drawable.ic_widget_change), "Сменить карточку",
                            modifier = GlanceModifier.size(20.dp))
                    }
                }
            }
            if (content.mode == WidgetMode.ROTATION) Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                Box(GlanceModifier.size(44.dp).clickable(actionRunCallback<NextWidgetNote>()).padding(6.dp)) {
                    Box(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_icon_background)),
                        contentAlignment = Alignment.Center) {
                        Image(ImageProvider(R.drawable.ic_widget_next), "Следующая заметка", modifier = GlanceModifier.size(20.dp))
                    }
                }
            }
        }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        WidgetRotation.deleted(context, GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}

internal data class WidgetContent(val cardId: Long?, val title: String,
    val image: android.graphics.Bitmap?, val unavailableImage: Boolean, val mode: WidgetMode = WidgetMode.FIXED)

internal fun CardEntity.widgetCoverPath(): String? = imagePath ?: RichTextCodec.decode(description, formatting)
    .media.firstOrNull { it.kind == MediaKind.IMAGE }?.path

suspend fun updateCardWidget(context: Context, widgetId: Int) {
    val id = GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
    updateAppWidgetState(context, id) { it[longPreferencesKey("revision")] = System.nanoTime() }
    CardWidget().update(context, id)
}

class CardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CardWidget()
}

/** Glance schedules rendering in its worker; no polling or app-owned service. */
suspend fun notifyCardWidgets(context: Context, cardId: Long) {
    WidgetRotation.refreshAfterEdit(context, cardId)
}
