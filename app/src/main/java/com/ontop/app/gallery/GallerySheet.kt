package com.ontop.app.gallery

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ontop.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GallerySheet(ui: GalleryUiState, thumbnails: GalleryThumbnails,
    onDismiss: () -> Unit, onSelect: (GalleryItem) -> Unit, onCamera: () -> Unit,
    onFile: () -> Unit, onSystemPicker: () -> Unit, onRequestAccess: () -> Unit,
    onRetry: () -> Unit, onLoadMore: () -> Unit, title: String = "Галерея", onYouTube: (() -> Unit)? = null) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val height by animateDpAsState((LocalConfiguration.current.screenHeightDp * if (expanded) .94f else .60f).dp,
        label = "gallery-height")
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grid = rememberLazyGridState()
    val currentMore by rememberUpdatedState(onLoadMore)
    val currentUi by rememberUpdatedState(ui)
    LaunchedEffect(grid) {
        snapshotFlow {
            !currentUi.loading && currentUi.hasMore && currentUi.items.isNotEmpty() &&
                (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= currentUi.items.size - 10
        }.distinctUntilChanged().collect { if (it) currentMore() }
    }
    val expandOnScroll = remember { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y < -1f) expanded = true
            return Offset.Zero
        }
    } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, dragHandle = null,
        containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().height(height).testTag("gallery-sheet")) {
            // Material's default partial anchor is 50%. Resizing the fully shown sheet gives
            // OnTop a 60% initial height while retaining its native downward dismissal.
            Row(Modifier.fillMaxWidth().height(52.dp).testTag("gallery-handle")
                .pointerInput(expanded) {
                    var distance = 0f
                    detectVerticalDragGestures(onDragStart = { distance = 0f },
                        onVerticalDrag = { change, delta -> change.consume(); distance += delta },
                        onDragEnd = {
                            if (distance < -24.dp.toPx()) expanded = true
                            if (distance > 24.dp.toPx()) { if (expanded) expanded = false else onDismiss() }
                        })
                }, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp).weight(1f))
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("gallery-expand")) {
                    Text(if (expanded) "Свернуть" else "Развернуть")
                }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
            if (!ui.canRead || !ui.fullAccess) {
                Text(if (ui.canRead) "Доступна только часть медиатеки" else "Разрешите доступ к фотографиям и видео",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = onRequestAccess, modifier = Modifier.testTag("gallery-permission")) {
                        Text(if (ui.canRead) "Изменить доступ" else "Разрешить доступ")
                    }
                    TextButton(onClick = onSystemPicker, modifier = Modifier.testTag("gallery-system-picker")) { Text("Системный выбор") }
                }
            }
            if (onYouTube != null) TextButton(onClick = onYouTube) { Text("YouTube по ссылке") }
            ui.error?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = onRetry) { Text("Обновить галерею") }
            }
            LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid,
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(expandOnScroll).testTag("gallery-grid")) {
                item(key = "camera") {
                    Column(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onCamera).testTag("gallery-camera"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(36.dp))
                        Text(if (ui.filter == GalleryFilter.VIDEOS) "Снять видео" else "Камера", style = MaterialTheme.typography.labelMedium)
                    }
                }
                items(ui.items, key = { it.uri.toString() }, contentType = { "thumbnail" }) { item ->
                    GalleryThumbnail(item, thumbnails, onSelect)
                }
                if (ui.loading) item(span = { GridItemSpan(3) }, key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                if (!ui.loading && ui.items.isEmpty() && ui.canRead && ui.error == null)
                    item(span = { GridItemSpan(3) }, key = "empty") {
                        Text("Нет доступных фотографий и видео", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Tab(selected = true, onClick = { }, modifier = Modifier.weight(1f).testTag("gallery-tab"), text = { Text("Галерея") })
                Tab(selected = false, onClick = onFile, modifier = Modifier.weight(1f).testTag("gallery-file-tab"), text = { Text("Файл") })
            }
        }
    }
}

@Composable
private fun GalleryThumbnail(item: GalleryItem, thumbnails: GalleryThumbnails, onSelect: (GalleryItem) -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, item, thumbnails) {
        try { value = thumbnails.load(item) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null }
    }
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable { onSelect(item) }.testTag("gallery-item-${item.id}")
        .semantics { contentDescription = "${if (item.video) "Видео" else "Фото"}: ${item.name}" }) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (item.video) {
            val seconds = item.duration / 1000
            Text("▶ ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = .55f)).padding(horizontal = 4.dp, vertical = 2.dp))
        }
    }
}
