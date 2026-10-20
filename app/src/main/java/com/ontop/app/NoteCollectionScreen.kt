package com.ontop.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardStatus

@Composable
internal fun NoteCollectionScreen(status: CardStatus, state: BoardState, onBack: () -> Unit,
    onOpen: (CardEntity) -> Unit) {
    BackHandler(onBack = onBack)
    val cards = state.storedCards.filter { it.status == status }
    Surface(Modifier.fillMaxSize().testTag("collection-${status.name}")) {
        Column(Modifier.safeDrawingPadding()) {
            Row {
                TextButton(onClick = onBack) { Text("На доску") }
                Text(if (status == CardStatus.ARCHIVED) "Архив" else "Корзина",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge)
            }
            if (state.error != null) Text(state.error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            if (cards.isEmpty()) Text(if (status == CardStatus.ARCHIVED) "Архив пуст" else "Корзина пуста", Modifier.padding(24.dp))
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(cards, key = { it.id }) { card ->
                    Card(onClick = { onOpen(card) }, modifier = Modifier.fillMaxWidth().testTag("collection-card-${card.id}")) {
                        Text(card.title, Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
