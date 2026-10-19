package com.ontop.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ontop.app.data.CardEntity
import com.ontop.app.text.MultimediaNoteView
import com.ontop.app.text.RichTextCodec
import kotlinx.coroutines.launch

internal enum class NoteSliderOrder(val label: String) {
    BOARD("Порядок доски"), CREATED("Дата создания · новые сначала"),
    UPDATED("Дата изменения · новые сначала"), RANDOM("Случайный порядок")
}

/** Activity ViewModel owns this session; no database fields or persisted preferences. */
internal class NoteSliderSession {
    var currentId: Long? = null
    var index = 0
    var order by mutableStateOf(NoteSliderOrder.BOARD)
    private var randomIds = emptyList<Long>()

    fun enter(cards: List<CardEntity>) { randomIds = cards.map { it.id }.shuffled() }

    fun ordered(cards: List<CardEntity>): List<CardEntity> = when (order) {
        NoteSliderOrder.BOARD -> cards.sortedWith(compareBy<CardEntity> { it.sortOrder }.thenBy { it.id })
        NoteSliderOrder.CREATED -> cards.sortedWith(compareByDescending<CardEntity> { it.createdAt }.thenBy { it.id })
        NoteSliderOrder.UPDATED -> cards.sortedWith(compareByDescending<CardEntity> { it.updatedAt }.thenBy { it.id })
        NoteSliderOrder.RANDOM -> {
            val ranks = randomIds.withIndex().associate { it.value to it.index }
            cards.sortedWith(compareBy<CardEntity> { ranks[it.id] ?: Int.MAX_VALUE }.thenBy { it.id })
        }
    }

    fun position(cards: List<CardEntity>): Int = cards.indexOfFirst { it.id == currentId }
        .takeIf { it >= 0 } ?: index.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
}

@Composable
internal fun NoteSliderScreen(cards: List<CardEntity>, session: NoteSliderSession,
    onClose: () -> Unit, onEdit: (CardEntity) -> Unit, onDetails: (CardEntity) -> Unit) {
    BackHandler(onBack = onClose)
    val ordered = session.ordered(cards)
    var menu by remember { mutableStateOf(false) }
    // Recreate only on membership/order changes, keeping the selected identity (or nearest neighbour).
    key(ordered.map { it.id }) {
        val pager = rememberPagerState(initialPage = session.position(ordered)) { ordered.size }
        val scope = rememberCoroutineScope()
        val current = ordered.getOrNull(pager.currentPage)
        LaunchedEffect(pager, ordered.map { it.id }) {
            snapshotFlow { pager.currentPage }.collect { index ->
                ordered.getOrNull(index)?.let { session.currentId = it.id; session.index = index }
            }
        }
        Scaffold(modifier = Modifier.fillMaxSize().testTag("note-slider"), topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onClose) { Text("Закрыть") }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("Порядок") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            NoteSliderOrder.entries.forEach { order ->
                                DropdownMenuItem(text = { Text((if (order == session.order) "✓ " else "") + order.label) },
                                    onClick = { session.order = order; menu = false })
                            }
                        }
                    }
                }
                if (current != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { onEdit(current) }) { Text("Редактировать") }
                    TextButton(onClick = { onDetails(current) }) { Text("Комментарии") }
                }
            }
        }, bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(enabled = pager.currentPage > 0, onClick = {
                    scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                }) { Text("Назад") }
                Text(if (ordered.isEmpty()) "0 из 0" else "${pager.currentPage + 1} из ${ordered.size}")
                TextButton(enabled = pager.currentPage < ordered.lastIndex, onClick = {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }) { Text("Далее") }
            }
        }) { padding ->
            if (ordered.isEmpty()) Column(Modifier.padding(padding).padding(24.dp)) {
                Text("Пока нет заметок", style = MaterialTheme.typography.headlineSmall)
                Text("Создайте карточку на доске, чтобы начать просмотр.")
            } else HorizontalPager(state = pager, key = { ordered[it].id },
                modifier = Modifier.padding(padding).fillMaxSize()) { page ->
                val card = ordered[page]
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                    .testTag("slide-${card.id}")) {
                    CardVisual(card, minHeight = 160.dp, titleStyle = MaterialTheme.typography.headlineSmall)
                    MultimediaNoteView(RichTextCodec.decode(card.description, card.formatting),
                        active = page == pager.currentPage && !pager.isScrollInProgress)
                }
            }
        }
    }
}
