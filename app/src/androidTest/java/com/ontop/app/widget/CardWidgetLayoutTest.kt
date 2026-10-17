package com.ontop.app.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.math.roundToInt

@RunWith(Parameterized::class)
class CardWidgetLayoutTest(private val width: Int, private val height: Int, private val fontScale: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}x{1}dp font={2}")
        fun sizes() = listOf(arrayOf<Any>(110, 110, 1f), arrayOf<Any>(160, 160, 1f),
            arrayOf<Any>(300, 160, 1f), arrayOf<Any>(110, 110, 2f), arrayOf<Any>(300, 160, 2f))
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext.let { base ->
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { this.fontScale = this@CardWidgetLayoutTest.fontScale })
    }

    private suspend fun render(content: WidgetContent, check: (View, List<View>) -> Unit) {
        val widget = object : GlanceAppWidget() {
            override val sizeMode = SizeMode.Exact
            override suspend fun provideGlance(context: Context, id: GlanceId) {
                provideContent { CardWidget().WidgetBody(context, 42, content) }
            }
        }
        val remoteViews = widget.compose(context, size = DpSize(width.dp, height.dp))
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val root = remoteViews.apply(context, FrameLayout(context))
                val density = context.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((width * density).roundToInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).roundToInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val views = mutableListOf<View>()
                fun visit(view: View) {
                    views.add(view)
                    if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
                visit(root)
                check(root, views)
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    private fun bounds(root: View, child: View): Rect = Rect(0, 0, child.width, child.height).also {
        (root as ViewGroup).offsetDescendantRectToMyCoords(child, it)
    }

    @Test fun photoFillsWidgetGradientProtectsTextAndActionsAreSeparate() = runBlocking<Unit> {
        val image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val density = context.resources.displayMetrics.density
        val screenshot = Bitmap.createBitmap((width * density).roundToInt(), (height * density).roundToInt(),
            Bitmap.Config.ARGB_8888)
        val phrase = "Возвращай внимание к тому, что важно. ".repeat(12)
        try {
            render(WidgetContent(5, phrase, image, false)) { root, views ->
                val title = views.filterIsInstance<TextView>().single { it.text.toString() == phrase }
                val textBounds = bounds(root, title)
                assertTrue(textBounds.bottom <= root.height)
                assertTrue(textBounds.top >= root.height * 0.35)
                assertTrue(title.maxLines >= 1)
                assertTrue(title.layout.getEllipsisCount(title.layout.lineCount - 1) > 0)
                assertTrue(title.layout.getLineBottom(title.layout.lineCount - 1) <= title.height)
                val photo = views.filterIsInstance<ImageView>().single { it.drawable is BitmapDrawable }
                assertEquals(Rect(0, 0, root.width, root.height), bounds(root, photo))
                val icon = views.single { it.contentDescription?.toString() == "Сменить карточку" }
                val action = generateSequence(icon) { it.parent as? View }.first { it.hasOnClickListeners() }
                val actionBounds = bounds(root, action)
                assertTrue(actionBounds.right <= root.width && actionBounds.top >= 0)
                assertTrue(actionBounds.bottom <= textBounds.top)
                assertTrue(views.count { it.hasOnClickListeners() } >= 2)
                assertFalse(views.filterIsInstance<TextView>().any { "Сменить карточку" in it.text.toString() })
                root.draw(Canvas(screenshot))
                val upper = screenshot.getPixel((root.width * 0.05).toInt(), (root.height * 0.1).toInt())
                val lower = screenshot.getPixel((root.width * 0.05).toInt(), (root.height * 0.65).toInt())
                assertTrue(Color.red(upper) > Color.red(lower) + 60)
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
                    val file = File(output, "design-review/widget-${width}x${height}-${fontScale}x.png")
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            val shade = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            context.getDrawable(R.drawable.widget_photo_shade)!!.apply { setBounds(0, 0, 100, 100); draw(Canvas(shade)) }
            assertTrue(Color.alpha(shade.getPixel(50, 0)) < 30)
            assertTrue(Color.alpha(shade.getPixel(50, 40)) >= 200)
            shade.recycle()
        } finally { screenshot.recycle(); image.recycle() }
    }

    @Test fun missingPhotoAndDeletedCardKeepReadableContent() = runBlocking<Unit> {
        for (content in listOf(WidgetContent(2, "Тихое внимание", null, true),
            WidgetContent(null, "Карточка удалена", null, false))) {
            render(content) { root, views ->
                val title = views.filterIsInstance<TextView>().single { it.text.toString() == content.title }
                val textBounds = bounds(root, title)
                assertTrue(textBounds.left >= 0 && textBounds.right <= root.width && textBounds.bottom <= root.height)
                assertTrue(title.maxLines >= 1)
                assertEquals(if (content.cardId == null) Color.WHITE else Color.rgb(36, 53, 41), title.currentTextColor)
                assertNotNull(views.single { it.contentDescription?.toString() == "Сменить карточку" })
            }
        }
    }
}
