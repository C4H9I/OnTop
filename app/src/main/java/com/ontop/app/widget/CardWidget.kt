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
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.appwidget.state.updateAppWidgetState
import com.ontop.app.MainActivity
import com.ontop.app.data.CardImageStore
import com.ontop.app.data.OnTopDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CardWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
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
        try {
            cardId = selections.get(widgetId)
            if (cardId != null) {
                val card = withContext(Dispatchers.IO) {
                    (database ?: OnTopDatabase.getInstance(context)).cardDao().findById(cardId!!)
                }
                title = card?.title ?: "Карточка удалена"
                if (card == null) cardId = null
                card?.imagePath?.let {
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
        return WidgetContent(cardId, title, image, unavailableImage)
    }

    @Composable
    private fun WidgetBody(context: Context, widgetId: Int, content: WidgetContent) {
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
        val showImage = image != null && size.height.value >= 140 * fontScale
        val imageHeight = if (showImage) minOf(64f, size.height.value * 0.25f) else 0f
        val lines = ((size.height.value - 24 - 52 * fontScale - imageHeight) / (24 * fontScale))
            .toInt().coerceIn(1, 30)
        Column(GlanceModifier.fillMaxSize().background(Color(0xFFE4EBDD)).padding(12.dp)
            .clickable(actionStartActivity(if (cardId != null) open else configure))) {
            Text("OnTop", style = TextStyle(color = ColorProvider(Color(0xFF425343)), fontSize = 12.sp))
            if (showImage) Image(ImageProvider(image!!), "Изображение карточки",
                modifier = GlanceModifier.fillMaxWidth().height(imageHeight.dp), contentScale = ContentScale.Crop)
            Text(title, modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                style = TextStyle(color = ColorProvider(Color(0xFF252A28)), fontSize = 18.sp), maxLines = lines)
            Text(if (cardId == null) "Выбрать другую" else "Сменить карточку", maxLines = 1,
                style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color(0xFF425343))),
                modifier = GlanceModifier.clickable(actionStartActivity(configure)))
        }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        WidgetSelectionStore(context).remove(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}

internal data class WidgetContent(val cardId: Long?, val title: String,
    val image: android.graphics.Bitmap?, val unavailableImage: Boolean)

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
    val ids = WidgetSelectionStore(context).idsFor(cardId)
    val active = AppWidgetManager.getInstance(context).getAppWidgetIds(
        android.content.ComponentName(context, CardWidgetReceiver::class.java)).toSet()
    var failure: Exception? = null
    for (id in ids.filter { it in active }) {
        try { updateCardWidget(context, id)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { failure = e }
    }
    failure?.let { throw it }
}
