package com.ontop.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun BoardHeader(onSettings: () -> Unit = {}, sortMode: BoardSort = BoardSort.MANUAL,
    onSort: (BoardSort) -> Unit = {}, sortEnabled: Boolean = true) {
    val context = LocalContext.current.applicationContext
    var info by remember { mutableStateOf<AppVersionInfo?>(null) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(context, retry) {
        error = false
        try { info = AppVersionInfo.load(context) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    BoardHeaderContent(info, menuOpen, { menuOpen = it }, { menuOpen = false; aboutOpen = true },
        { menuOpen = false; onSettings() }, sortMode, onSort, sortEnabled)
    if (aboutOpen) AboutDialog(info, error, { retry++ }, { aboutOpen = false })
}

@Composable
internal fun BoardHeaderContent(info: AppVersionInfo?, menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit, onAbout: () -> Unit, onSettings: () -> Unit = {},
    sortMode: BoardSort = BoardSort.MANUAL, onSort: (BoardSort) -> Unit = {}, sortEnabled: Boolean = true) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("OnTop", style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            if (info?.isDebug == true) Text("DEV · ${info.shortId}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("dev-build"))
        }
        Box {
            IconButton(onClick = { sortOpen = true }, enabled = sortEnabled,
                modifier = Modifier.testTag("board-sort")) {
                Icon(painterResource(R.drawable.ic_sort), contentDescription = "Сортировка доски: ${sortMode.label}")
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false },
                modifier = Modifier.widthIn(max = 320.dp), containerColor = MaterialTheme.colorScheme.surface) {
                BoardSort.entries.forEach { mode ->
                    DropdownMenuItem(text = { Text(mode.label) },
                        modifier = Modifier.testTag("board-sort-${mode.name}").semantics { selected = mode == sortMode },
                        trailingIcon = {
                            if (mode == sortMode) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                        }, onClick = { sortOpen = false; onSort(mode) })
                }
            }
        }
        Box {
            IconButton(onClick = { onMenuOpen(true) }) {
                Icon(painterResource(R.drawable.ic_more), contentDescription = "Меню приложения")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpen(false) },
                containerColor = MaterialTheme.colorScheme.surface) {
                DropdownMenuItem(text = { Text("Настройки") }, onClick = onSettings)
                DropdownMenuItem(text = { Text("О приложении") }, onClick = onAbout)
            }
        }
    }
}
