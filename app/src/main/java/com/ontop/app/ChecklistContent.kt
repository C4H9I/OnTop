package com.ontop.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import com.ontop.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ChecklistCounter(items: List<ChecklistItem>, showProgress: Boolean = false) {
    val actual = items.filter { it.text.isNotBlank() }
    val progress = Checklist.progress(items)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Выполнено ${actual.count { it.done }} из ${actual.size}" + if (showProgress) " — $progress%" else "",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("checklist-counter"))
        if (showProgress) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp),
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
internal fun ChecklistViewRow(item: ChecklistItem, enabled: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(item.done, { onToggle() }, enabled = enabled,
            modifier = Modifier.testTag("checklist-toggle-${item.id}"))
        Text(item.text, style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (item.done) TextDecoration.LineThrough else null,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.done) .65f else 1f),
            modifier = Modifier.weight(1f).padding(vertical = 8.dp))
    }
}

/** The drag handle alone owns pointer gestures. Root coordinates also track rows during edge scrolling. */
@Composable
internal fun ChecklistEditor(items: List<ChecklistItem>, onChange: (List<ChecklistItem>) -> Unit,
    enabled: Boolean, scroll: ScrollState, viewport: Rect) {
    var focusId by rememberSaveable { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    val rows = remember { mutableStateMapOf<String, Rect>() }
    val current by rememberUpdatedState(items)
    val change by rememberUpdatedState(onChange)
    val visibleViewport by rememberUpdatedState(viewport)
    val focusManager = LocalFocusManager.current
    fun move() {
        val id = dragging ?: return
        val from = current.indexOfFirst { it.id == id }
        val target = current.indices.minByOrNull { index ->
            rows[current[index].id]?.let { kotlin.math.abs(it.center.y - pointerY) } ?: Float.MAX_VALUE
        } ?: return
        if (from >= 0 && target != from) change(current.toMutableList().apply { add(target, removeAt(from)) })
    }
    LaunchedEffect(dragging) {
        while (dragging != null) {
            val edge = 100f
            val delta = when {
                pointerY < visibleViewport.top + edge -> -18f
                pointerY > visibleViewport.bottom - edge -> 18f
                else -> 0f
            }
            if (delta != 0f) { scroll.scrollBy(delta); move() }
            delay(16)
        }
    }
    fun addAt(index: Int) {
        val item = ChecklistItem()
        change(current.toMutableList().apply { add(index.coerceIn(0, size), item) })
        focusId = item.id
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChecklistCounter(items, showProgress = true)
        Spacer(Modifier.height(8.dp))
        items.forEach { item -> key(item.id) {
            val focus = remember { FocusRequester() }
            val bring = remember { BringIntoViewRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            val focusScope = rememberCoroutineScope()
            var menuOpen by remember { mutableStateOf(false) }
            var handleBounds by remember { mutableStateOf(Rect.Zero) }
            LaunchedEffect(focusId) {
                if (focusId == item.id) {
                    withFrameNanos { }
                    bring.bringIntoView()
                    focus.requestFocus(); keyboard?.show(); focusId = null
                }
            }
            DisposableEffect(item.id) { onDispose { rows.remove(item.id) } }
            Row(Modifier.fillMaxWidth().bringIntoViewRequester(bring).onGloballyPositioned {
                rows[item.id] = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
            },
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).testTag("checklist-drag-${item.id}")
                        .onGloballyPositioned { handleBounds = it.boundsInRoot() }
                        .pointerInput(item.id, enabled) {
                            if (enabled) detectDragGestures(
                                onDragStart = {
                                    pointerY = handleBounds.top + it.y; dragging = item.id
                                    focusManager.clearFocus(); keyboard?.hide()
                                },
                                onDragEnd = { dragging = null }, onDragCancel = { dragging = null },
                                onDrag = { event, amount -> event.consume(); pointerY += amount.y; move() })
                        }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_grip_vertical), "Переместить пункт", Modifier.size(20.dp))
                }
                Checkbox(item.done, { done -> change(current.map { if (it.id == item.id) it.copy(done = done) else it }) },
                    enabled = enabled, modifier = Modifier.size(48.dp))
                BasicTextField(item.text,
                    { text -> change(current.map { if (it.id == item.id) it.copy(text = text) else it }) },
                    enabled = enabled, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.done) .65f else 1f)),
                    decorationBox = { field -> Box(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        contentAlignment = Alignment.CenterStart) {
                        if (item.text.isEmpty()) Text("Пункт", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        field()
                    } },
                    modifier = Modifier.weight(1f).focusRequester(focus)
                        .onFocusChanged { state -> if (state.isFocused) focusScope.launch {
                            withFrameNanos { }; bring.bringIntoView()
                        } }.testTag("checklist-text-${item.id}"))
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = enabled, modifier = Modifier.size(48.dp)
                        .testTag("checklist-menu-${item.id}")) {
                        Icon(painterResource(R.drawable.ic_more), "Действия с пунктом")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                        properties = PopupProperties(focusable = false)) {
                        val index = items.indexOfFirst { it.id == item.id }
                        DropdownMenuItem(text = { Text("Добавить пункт выше") }, onClick = {
                            menuOpen = false; addAt(index)
                        })
                        DropdownMenuItem(text = { Text("Добавить пункт ниже") }, onClick = {
                            menuOpen = false; addAt(index + 1)
                        })
                        DropdownMenuItem(text = { Text("Удалить пункт") }, onClick = {
                            menuOpen = false; change(current.filterNot { it.id == item.id })
                        })
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .24f))
        } }
        TextButton(onClick = { addAt(items.size) }, enabled = enabled, modifier = Modifier.testTag("checklist-add-last")) {
            Text("+ Добавить пункт")
        }
    }
}
