package com.ontop.app

import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.ontop.app.data.CardImageStore
import kotlinx.coroutines.CancellationException

/** The bounded bitmap lives in ImageView, never in Compose state or saved state. */
@Composable
internal fun LocalCardImage(path: String, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop, maxSide: Int = 800, showError: Boolean = true,
    adaptHeight: Boolean = false) {
    val context = LocalContext.current
    val view = remember(context) { ImageView(context).apply { contentDescription = "Изображение карточки" } }
    var failed by remember(path) { mutableStateOf(false) }
    var ratio by remember(path) { mutableFloatStateOf(4f / 3f) }
    LaunchedEffect(path, maxSide, view) {
        view.setImageDrawable(null)
        try {
            val bitmap = CardImageStore(context).load(path, maxSide)
            ratio = bitmap.width.toFloat() / bitmap.height
            view.setImageBitmap(bitmap)
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { failed = true
        } catch (_: OutOfMemoryError) { failed = true }
    }
    DisposableEffect(view) { onDispose { view.setImageDrawable(null) } }
    Box(modifier.then(if (adaptHeight) Modifier.aspectRatio(ratio) else Modifier)) {
        AndroidView(factory = { view }, modifier = Modifier.matchParentSize(), update = {
            it.scaleType = if (contentScale == ContentScale.Crop) ImageView.ScaleType.CENTER_CROP
                else ImageView.ScaleType.FIT_CENTER
        })
        if (failed && showError) Text("Изображение недоступно")
    }
}
