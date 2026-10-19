package com.ontop.app.text

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ontop.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Preview and optional public oEmbed title only; no video download or API key. */
internal interface YouTubePreviewLoader {
    suspend fun thumbnail(id: String): Bitmap?
    suspend fun title(id: String): String?
}

private object NetworkYouTubePreviewLoader : YouTubePreviewLoader {
    private fun bytes(url: String, limit: Int): ByteArray? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == 200)
            connection.inputStream.use { it.readBytesLimited(limit) }
        } finally { connection.disconnect() }
    }.getOrNull()

    override suspend fun thumbnail(id: String): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = bytes("https://i.ytimg.com/vi/$id/hqdefault.jpg", 2_000_000) ?: return@withContext null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inSampleSize = 1 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1280) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    override suspend fun title(id: String): String? = withContext(Dispatchers.IO) {
        // Separate request: missing metadata never delays the cover or the Play button.
        val url = "https://www.youtube.com/oembed?url=${Uri.encode("https://www.youtube.com/watch?v=$id")}&format=json"
        val bytes = bytes(url, 32_768) ?: return@withContext null
        runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).optString("title").trim().take(300).takeIf { it.isNotEmpty() } }.getOrNull()
    }
}

internal val LocalYouTubePreviewLoader = staticCompositionLocalOf<YouTubePreviewLoader> { NetworkYouTubePreviewLoader }

@Composable
internal fun YouTubePreview(video: YouTubeLink, modifier: Modifier = Modifier,
    onPlay: () -> Unit, onRemove: (() -> Unit)? = null) {
    val loader = LocalYouTubePreviewLoader.current
    val context = LocalContext.current
    var retry by remember(video.id) { mutableIntStateOf(0) }
    var menu by remember(video.id) { mutableStateOf(false) }
    var failedToOpen by remember(video.id) { mutableStateOf(false) }
    var loading by remember(video.id, retry) { mutableStateOf(true) }
    val bitmap by produceState<Bitmap?>(null, video.id, retry, loader) {
        value = null
        loading = true
        value = loader.thumbnail(video.id)
        loading = false
    }
    val title by produceState<String?>(null, video.id, retry, loader) {
        value = null
        value = loader.title(video.id)
    }
    val open = {
        failedToOpen = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.watchUrl)))
        }.isFailure
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .testTag("youtube-preview-${video.id}")
            .semantics { contentDescription = "Воспроизвести видео" }
            .clickable(role = Role.Button, onClickLabel = "Воспроизвести видео", onClick = onPlay),
            contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            // A large enough touch target with a small, quiet play symbol; the whole preview also plays.
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = .58f)), Alignment.Center) {
                Canvas(Modifier.size(22.dp)) {
                    drawPath(Path().apply {
                        moveTo(size.width * .22f, 0f); lineTo(size.width, size.height / 2)
                        lineTo(size.width * .22f, size.height); close()
                    }, Color.White)
                }
            }
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(18.dp)
                .testTag("youtube-preview-loading"), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            else if (bitmap == null) Text("Обложка недоступна · проверьте интернет",
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .65f)).padding(8.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        title?.let { Text(it, Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = open, modifier = Modifier.semantics { contentDescription = "Открыть видео в YouTube" },
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(painterResource(R.drawable.ic_youtube), null, Modifier.size(24.dp), tint = Color.Unspecified)
                Spacer(Modifier.width(6.dp))
                Text("YouTube ↗")
            }
            Spacer(Modifier.weight(1f))
            if (onRemove != null) Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.ic_more), "Действия с видео")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Обновить обложку") }, onClick = { menu = false; retry++ })
                    DropdownMenuItem(text = { Text("Открыть в YouTube") }, onClick = { menu = false; open() })
                    DropdownMenuItem(text = { Text("Удалить видео") }, onClick = { menu = false; onRemove() })
                }
            }
        }
        if (failedToOpen) Text("Нет приложения для открытия ссылки. ${video.watchUrl}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
