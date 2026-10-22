package com.ontop.app.gallery

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

internal data class GalleryUiState(
    val access: GalleryAccess = GalleryAccess(false, false, false),
    val items: List<GalleryItem> = emptyList(), val loading: Boolean = true,
    val hasMore: Boolean = true, val error: String? = null, val filter: GalleryFilter = GalleryFilter.ALL) {
    val canRead get() = when (filter) {
        GalleryFilter.ALL -> access.any
        GalleryFilter.IMAGES -> access.canQueryImages
        GalleryFilter.VIDEOS -> access.canQueryVideos
    }
    val fullAccess get() = when (filter) {
        GalleryFilter.ALL -> access.images && access.videos
        GalleryFilter.IMAGES -> access.images
        GalleryFilter.VIDEOS -> access.videos
    }
}

@Composable
internal fun GalleryPicker(onDismiss: () -> Unit, onSelect: (GalleryItem) -> Unit,
    onCamera: () -> Unit, onFile: () -> Unit, onSystemPicker: () -> Unit,
    destination: MediaDestination = MediaDestination.CONTENT, onYouTube: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember(context) { GalleryStore(context.applicationContext) }
    val thumbnails = remember(context) { GalleryThumbnails(context.applicationContext) }
    var ui by remember { mutableStateOf(GalleryUiState()) }
    var revision by remember { mutableIntStateOf(0) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val more = remember { Channel<Unit>(Channel.CONFLATED) }
    val filter = when (destination) {
        MediaDestination.COVER -> GalleryFilter.IMAGES
        MediaDestination.VIDEO -> GalleryFilter.VIDEOS
        else -> GalleryFilter.ALL
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }

    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { foreground = true; revision++ }
            if (event == Lifecycle.Event.ON_PAUSE) { foreground = false }
        }
        val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        lifecycle.addObserver(observer)
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        onDispose {
            lifecycle.removeObserver(observer)
            context.contentResolver.unregisterContentObserver(mediaObserver)
            thumbnails.clear()
        }
    }
    LaunchedEffect(foreground, revision, filter) {
        thumbnails.clear()
        ui = GalleryUiState(access = GalleryAccess.current(context), loading = foreground, filter = filter)
        if (!foreground) return@LaunchedEffect
        delay(120) // Coalesce MediaStore changes and discard stale results after access changes.
        while (more.tryReceive().isSuccess) { }
        more.trySend(Unit)
        var before: GalleryCursor? = null
        for (ignored in more) {
            if (!ui.canRead) { ui = ui.copy(loading = false, hasMore = false); continue }
            if (!ui.hasMore) continue
            ui = ui.copy(loading = true, error = null)
            try {
                val page = store.page(ui.access, before, filter)
                before = page.lastOrNull()?.let { GalleryCursor(it.date, it.id) }
                ui = ui.copy(items = (ui.items + page).distinctBy { it.uri },
                    loading = false, hasMore = page.size == GalleryStore.PAGE_SIZE)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: SecurityException) {
                thumbnails.clear()
                ui = GalleryUiState(access = GalleryAccess.current(context), loading = false, hasMore = false, filter = filter,
                    error = "Доступ к медиатеке изменился. Обновите галерею или используйте системный выбор.")
            } catch (_: Exception) {
                ui = ui.copy(loading = false, hasMore = false, error = "Не удалось загрузить галерею. Попробуйте ещё раз.")
            }
        }
    }
    GallerySheet(ui, thumbnails, onDismiss, onSelect, onCamera, onFile, onSystemPicker,
        onRequestAccess = { permissions.launch(GalleryAccess.permissions(Build.VERSION.SDK_INT, filter)) },
        onRetry = { revision++ }, onLoadMore = { if (!ui.loading && ui.hasMore) more.trySend(Unit) },
        title = when (destination) { MediaDestination.COVER -> "Обложка"; MediaDestination.VIDEO -> "Видео"; else -> "Галерея" },
        onYouTube = onYouTube)
}
