package com.ontop.app.drawing

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import android.content.ContextWrapper
import com.ontop.app.backup.BackupManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ontop.app.ui.theme.Forest
import com.ontop.app.ui.theme.Ink
import com.ontop.app.ui.theme.Sand
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawingToolButton(
    icon: Int, label: String, selected: Boolean = false, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.size(48.dp)
            .background(if (selected) Sand.copy(alpha = .55f) else Color.Transparent, RoundedCornerShape(12.dp))
            .semantics { stateDescription = if (selected) "Выбран" else "Не выбран" }) {
        Icon(painterResource(icon), contentDescription = label, tint = if (selected) Forest else Ink)
    }
    }
}

internal class DrawingSession : ViewModel() {
    var session: String? = null
    var document by mutableStateOf(DrawingDocument()); private set
    var backgroundImage by mutableStateOf<android.graphics.Bitmap?>(null); private set
    private var original = DrawingDocument()
    private var redo by mutableStateOf(emptyList<DrawingDocument>())
    private var undo by mutableStateOf(emptyList<DrawingDocument>())
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var result by mutableStateOf<String?>(null)
    private var ink by mutableStateOf(false)
    private var sourceContext: Context? = null
    private var backgroundRevision = 0
    val changed get() = ready && document != original
    val canUndo get() = undo.isNotEmpty()
    val canClear get() = ink
    private fun updateInk() {
        val current = document
        viewModelScope.launch {
            val present = withContext(Dispatchers.Default) { DrawingRenderer.hasInk(current) }
            if (document == current) ink = present
        }
    }
    val canRedo get() = redo.isNotEmpty()
    fun open(context: Context, key: String, source: String?) {
        if (session == key) return
        backgroundRevision++
        sourceContext = context.applicationContext
        session = key; ready = false; error = null; result = null; redo = emptyList(); undo = emptyList(); ink = false
        viewModelScope.launch {
            try {
                val loaded = if (source == null) DrawingDocument() else DrawingStore.open(context, source)
                val background = DrawingStore.background(context, loaded, 1400)
                if (session == key) { val old = backgroundImage; document = loaded; backgroundImage = background
                    if (old !== background) old?.recycle(); original = loaded; ready = true; updateInk() }
                else background?.recycle()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (session == key) error = "Не удалось открыть рисунок. Исходное изображение сохранено." }
        }
    }
    fun add(operation: DrawingOperation) {
        if (document.operations.size >= 10_000 || document.operations.sumOf { it.points.size } + operation.points.size > DrawingCodec.MAX_POINTS) {
            error = "Достигнут предел сложности рисунка. Сохраните текущий рисунок."; return
        }
        commit(document.copy(operations = document.operations + operation))
    }
    private fun commit(next: DrawingDocument) {
        if (next == document) return
        undo = (undo + document).takeLast(200); document = next; redo = emptyList(); updateInk()
    }
    fun crop(context: Context, frame: CropFrame, onApplied: () -> Unit) {
        if (busy || !ready) return
        busy = true; error = null
        val before = document
        viewModelScope.launch {
            try {
                val next = withContext(Dispatchers.Default) {
                    DrawingCrop.apply(before, frame).also { DrawingCodec.decode(DrawingCodec.encode(it)) }
                }
                val preview = DrawingStore.background(context, next, 1400)
                if (document == before) {
                    backgroundRevision++
                    val old = backgroundImage
                    commit(next); backgroundImage = preview
                    if (old !== preview) old?.recycle()
                    onApplied()
                } else preview?.recycle()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = "Не удалось обрезать изображение. Попробуйте выбрать другую область." }
            catch (_: OutOfMemoryError) { error = "Недостаточно памяти для обрезки изображения." }
            finally { busy = false }
        }
    }
    private fun restoreBackground() {
        val context = sourceContext ?: return
        val revision = ++backgroundRevision
        val current = document
        val old = backgroundImage
        backgroundImage = null
        busy = true
        viewModelScope.launch {
            try {
                val preview = DrawingStore.background(context, current, 1400)
                if (revision == backgroundRevision) {
                    backgroundImage = preview
                    if (old !== preview) old?.recycle()
                } else preview?.recycle()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (revision == backgroundRevision) backgroundImage = old
                error = "Не удалось открыть обрезанное изображение." }
            finally { if (revision == backgroundRevision) busy = false }
        }
    }
    fun text(value: DrawingText) {
        if (document.texts.size >= 1000 && document.texts.none { it.id == value.id }) return
        val positioned = DrawingRenderer.position(value, document)
        commit(document.copy(texts = document.texts.filterNot { it.id == value.id } + positioned))
    }
    fun deleteText(id: String) = commit(document.copy(texts = document.texts.filterNot { it.id == id }))
    fun shape(value: DrawingShape) {
        if (!value.valid() || (document.shapes.size >= 1000 && document.shapes.none { it.id == value.id })) return
        commit(document.copy(shapes = document.shapes.filterNot { it.id == value.id } + value.normalized()))
    }
    fun deleteShape(id: String) = commit(document.copy(shapes = document.shapes.filterNot { it.id == id }))
    fun clear() = commit(document.copy(operations = emptyList(), texts = emptyList(), shapes = emptyList()))
    fun undo() { undo.lastOrNull()?.let { redo = redo + document; val changedCrop = document.sourceCrop != it.sourceCrop
        document = it; undo = undo.dropLast(1); updateInk(); if (changedCrop) restoreBackground() } }
    fun redo() { redo.lastOrNull()?.let { undo = undo + document; val changedCrop = document.sourceCrop != it.sourceCrop
        document = it; redo = redo.dropLast(1); updateInk(); if (changedCrop) restoreBackground() } }
    fun save(context: Context) {
        if (busy || !ready) return
        busy = true; error = null
        viewModelScope.launch {
            try { result = BackupManager.dataAccess { DrawingStore.save(context, document) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = "Не удалось сохранить рисунок. Повторите попытку."
            } catch (_: OutOfMemoryError) { error = "Недостаточно памяти для сохранения изображения."
            } finally { busy = false }
        }
    }
}

@Composable
internal fun DrawingEditor(key: String, source: String?, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    var ownerContext = context
    while (ownerContext !is ViewModelStoreOwner && ownerContext is ContextWrapper) ownerContext = ownerContext.baseContext
    val owner = ownerContext as ViewModelStoreOwner
    val session = remember(owner) { ViewModelProvider(owner)["drawing-editor", DrawingSession::class.java] }
    LaunchedEffect(key) { session.open(context, key, source) }
    LaunchedEffect(session.result) { session.result?.let { onSaved(it); session.result = null } }
    var eraser by remember { mutableStateOf(false) }
    var textTool by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var shapeTool by remember { mutableStateOf<ShapeType?>(null) }
    var shapeMenu by remember { mutableStateOf(false) }
    var selectedShapeId by remember { mutableStateOf<String?>(null) }
    var cropFrame by remember(key) { mutableStateOf<CropFrame?>(null) }
    var cropPreset by remember(key) { mutableStateOf("Свободно") }
    val selectedShape = session.document.shapes.firstOrNull { it.id == selectedShapeId }
    var editingText by remember { mutableStateOf<DrawingText?>(null) }
    val selected = session.document.texts.firstOrNull { it.id == selectedId }
    val viewport = remember(key, session.document.width, session.document.height) {
        DrawingViewportState(session.document.width, session.document.height)
    }
    var color by remember { mutableIntStateOf(android.graphics.Color.BLACK) }
    var width by remember { mutableFloatStateOf(4f) }
    LaunchedEffect(selectedShape?.id, selectedShape?.width, selectedShape?.color) {
        selectedShape?.let { width = it.width; color = it.color }
    }
    var discard by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    fun leave() { if (!session.busy) {
        if (cropFrame != null) cropFrame = null
        else if (session.changed) discard = true else onDismiss()
    } }
    Dialog(onDismissRequest = { leave() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { leave() }
        MaterialTheme(colorScheme = com.ontop.app.noteReadingColors()) {
            Surface(Modifier.fillMaxSize().testTag("drawing-editor"), color = Color.White) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val narrowHeader = maxWidth < 320.dp
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { leave() }, enabled = !session.busy,
                            modifier = Modifier.size(48.dp).testTag("drawing-back")) {
                            Icon(painterResource(com.ontop.app.R.drawable.ic_back), "Назад", tint = Forest)
                        }
                        Text("Рисунок", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (!narrowHeader) IconButton(onClick = viewport::fit, enabled = session.ready && !session.busy,
                            modifier = Modifier.size(48.dp).testTag("drawing-fit")) {
                            Icon(painterResource(com.ontop.app.R.drawable.ic_fit_screen), "Вписать в экран", tint = Forest)
                        }
                        IconButton(onClick = session::undo, enabled = session.ready && !session.busy && session.canUndo,
                            modifier = Modifier.size(48.dp).testTag("drawing-undo")) {
                            Icon(painterResource(com.ontop.app.R.drawable.ic_undo), "Отменить действие", tint = Forest)
                        }
                        IconButton(onClick = session::redo, enabled = session.ready && !session.busy && session.canRedo,
                            modifier = Modifier.size(48.dp).testTag("drawing-redo")) {
                            Icon(painterResource(com.ontop.app.R.drawable.ic_redo), "Повторить действие", tint = Forest)
                        }
                        IconButton(onClick = { session.save(context) }, enabled = session.ready && !session.busy && cropFrame == null,
                            modifier = Modifier.size(48.dp).testTag("drawing-save")) {
                            Icon(painterResource(com.ontop.app.R.drawable.ic_check), "Готово", tint = Forest)
                        }
                    }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (session.ready) DrawingCanvas(session, viewport, eraser, textTool, color, width, selectedId,
                            onSelect = { selectedId = it }, onEdit = { editingText = it }, modifier = Modifier.fillMaxSize(), shapeTool = shapeTool, selectedShapeId = selectedShapeId,
                            onSelectShape = { selectedShapeId = it; session.document.shapes.firstOrNull { s -> s.id == it }?.let { s -> width = s.width; color = s.color } })
                        else if (session.error == null) CircularProgressIndicator()
                        cropFrame?.let { frame ->
                            val ratio = when (cropPreset) {
                                "1:1" -> 1f; "4:3" -> 4f / 3f; "16:9" -> 16f / 9f
                                "Исходные" -> session.document.width.toFloat() / session.document.height
                                else -> null
                            }
                            DrawingCropOverlay(frame, viewport, ratio, onChange = { cropFrame = it })
                        }
                    }
                    session.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
                    if (session.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (cropFrame != null) {
                        Text("Соотношение сторон", Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Свободно", "1:1", "4:3", "16:9", "Исходные").forEach { preset ->
                                FilterChip(selected = cropPreset == preset, onClick = {
                                    cropPreset = preset
                                    val ratio = when (preset) {
                                        "1:1" -> 1f; "4:3" -> 4f / 3f; "16:9" -> 16f / 9f
                                        "Исходные" -> session.document.width.toFloat() / session.document.height
                                        else -> null
                                    }
                                    if (ratio != null) cropFrame = cropFrame?.aspect(ratio)
                                }, label = { Text(preset) }, enabled = !session.busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("crop-preset-$preset"))
                            }
                        }
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = { cropFrame = null }, enabled = !session.busy,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Отмена") }
                            Button(onClick = { cropFrame?.let { session.crop(context, it) { cropFrame = null } } },
                                enabled = !session.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("crop-apply")) {
                                Text("Применить")
                            }
                        }
                    } else {
                    Text("${(viewport.value.zoom * 100).toInt()}%",
                        Modifier.padding(horizontal = 12.dp).testTag("drawing-zoom"), style = MaterialTheme.typography.labelSmall)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val scrollTools = maxWidth < 344.dp
                    val moveFitToTools = maxWidth < 320.dp
                    Row((if (scrollTools) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth())
                        .padding(horizontal = 8.dp),
                        horizontalArrangement = if (scrollTools) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        DrawingToolButton(com.ontop.app.R.drawable.ic_draw, "Карандаш",
                            selected = !eraser && !textTool && shapeTool == null, enabled = !session.busy,
                            modifier = Modifier.testTag("drawing-pencil-tool")) {
                            eraser = false; textTool = false; selectedId = null; shapeTool = null; selectedShapeId = null
                        }
                        DrawingToolButton(com.ontop.app.R.drawable.ic_eraser, "Ластик",
                            selected = eraser && !textTool, enabled = !session.busy,
                            modifier = Modifier.testTag("drawing-eraser-tool")) {
                            eraser = true; textTool = false; selectedId = null; shapeTool = null; selectedShapeId = null
                        }
                        DrawingToolButton(com.ontop.app.R.drawable.ic_text_fields, "Текст",
                            selected = textTool, enabled = !session.busy,
                            modifier = Modifier.testTag("drawing-text-tool")) {
                            textTool = true; eraser = false; shapeTool = null; selectedShapeId = null
                        }
                        Box {
                            DrawingToolButton(com.ontop.app.R.drawable.ic_shapes, "Фигуры",
                                selected = shapeTool != null, enabled = !session.busy,
                                modifier = Modifier.testTag("drawing-shape-tool")) { shapeMenu = true }
                            DropdownMenu(shapeMenu, { shapeMenu = false }) {
                                ShapeType.entries.forEach { type -> DropdownMenuItem(text = { Text(type.label) }, onClick = {
                                    shapeTool = type; textTool = false; eraser = false; selectedId = null; selectedShapeId = null; shapeMenu = false
                                }) }
                            }
                        }
                        DrawingToolButton(com.ontop.app.R.drawable.ic_crop, "Обрезать",
                            enabled = session.ready && !session.busy,
                            modifier = Modifier.testTag("drawing-crop-tool")) {
                            cropPreset = "Свободно"; cropFrame = DrawingCrop.initial(session.document)
                            selectedId = null; selectedShapeId = null
                        }
                        DrawingToolButton(com.ontop.app.R.drawable.ic_delete, "Очистить",
                            enabled = session.ready && !session.busy && session.canClear,
                            modifier = Modifier.testTag("drawing-clear-tool")) { clear = true }
                        if (moveFitToTools) DrawingToolButton(com.ontop.app.R.drawable.ic_fit_screen,
                            "Вписать в экран", enabled = session.ready && !session.busy,
                            modifier = Modifier.testTag("drawing-fit"), onClick = viewport::fit)
                    }
                    }
                    if (!eraser) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("Чёрный" to 0xFF000000.toInt(), "Зелёный" to 0xFF234E35.toInt(), "Красный" to 0xFFD32F2F.toInt(),
                            "Синий" to 0xFF1565C0.toInt(), "Жёлтый" to 0xFFFFC107.toInt(), "Белый" to 0xFFFFFFFF.toInt()).forEach { (name, value) ->
                            OutlinedButton(onClick = { color = value; if (shapeTool != null) selectedShape?.let { session.shape(it.copy(color = value)) }; if (textTool) selected?.let { session.text(it.copy(color = value)) } else eraser = false }, enabled = !session.busy,
                                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp).testTag("drawing-color-$name").semantics { contentDescription = name },
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(value))) { if ((if (textTool) selected?.color ?: color else if (shapeTool != null) selectedShape?.color ?: color else color) == value) Text("✓", color = if (value == -1 || name == "Жёлтый") Color.Black else Color.White) }
                        }
                    }
                    if (textTool) {
                        // Selecting text must not resize/recenter the viewport underneath the first drag.
                        Box(Modifier.heightIn(min = 64.dp)) {
                            DrawingTextParameters(selected, session.busy, onEdit = { editingText = selected },
                                onDelete = { selected?.let { session.deleteText(it.id) }; selectedId = null }, onChange = session::text)
                        }
                    } else Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (shapeTool != null) TextButton(onClick = { selectedShape?.let { session.deleteShape(it.id) }; selectedShapeId = null }, enabled = selectedShape != null && !session.busy) { Text("Удалить") }
                        Text("Толщина ${width.toInt()}")
                        Slider(width, { width = it },
                            onValueChangeFinished = { if (shapeTool != null) selectedShape?.let { session.shape(it.copy(width = width)) } }, valueRange = 1f..24f, enabled = !session.busy, modifier = Modifier.weight(1f).testTag("drawing-width"))
                    }
                    }
                }
            }
            if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Отменить изменения рисунка?") },
                confirmButton = { TextButton(onClick = onDismiss) { Text("Не сохранять") } },
                dismissButton = { TextButton(onClick = { discard = false }) { Text("Продолжить рисование") } })
            editingText?.let { value ->
                DrawingTextDialog(value, onDismiss = { editingText = null }, onSave = {
                    session.text(it); selectedId = it.id; editingText = null
                })
            }
            if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text(if (session.document.backgroundImage == null) "Очистить холст?" else "Очистить нарисованный слой?") },
                confirmButton = { TextButton(onClick = { session.clear(); selectedId = null; clear = false }) { Text("Очистить") } },
                dismissButton = { TextButton(onClick = { clear = false }) { Text("Отмена") } })
        }
    }
}
