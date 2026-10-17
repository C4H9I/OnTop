package com.ontop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OnTopTheme {
                OnTopScreen()
            }
        }
    }
}

private data class ThoughtCard(val phrase: String, val wide: Boolean = false)

private val cardColors = listOf(
    Color(0xFFE4EBDD),
    Color(0xFFE6E1EF),
    Color(0xFFF3E6D3),
    Color(0xFFDDEBEC),
    Color(0xFFF0DFDF),
    Color(0xFFE6E8D9)
)

@Composable
private fun OnTopScreen() {
    val cards = remember {
        mutableStateListOf(
            ThoughtCard("Верни внимание к тому, что важно.", wide = true),
            ThoughtCard("Маленькие шаги тоже ведут вперёд."),
            ThoughtCard("Оставь место для тишины."),
            ThoughtCard("Не всё требует немедленного ответа.", wide = true),
            ThoughtCard("Замечай хорошее в обычном."),
            ThoughtCard("Будь к себе бережнее.")
        )
    }
    var showAddDialog by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.semantics { contentDescription = "Добавить карточку" }
            ) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            Text(
                text = "OnTop",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    count = cards.size,
                    span = { index -> GridItemSpan(if (cards[index].wide) 2 else 1) }
                ) { index ->
                    ThoughtTile(cards[index], cardColors[index % cardColors.size])
                }
            }
        }
    }

    if (showAddDialog) {
        AddCardDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { phrase ->
                cards.add(ThoughtCard(phrase))
                showAddDialog = false
                coroutineScope.launch { gridState.animateScrollToItem(cards.lastIndex) }
            }
        )
    }
}

@Composable
private fun ThoughtTile(card: ThoughtCard, background: Color) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = if (card.wide) 132.dp else 176.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = background,
            contentColor = Color(0xFF252A28)
        )
    ) {
        Text(
            text = card.phrase,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(20.dp)
        )
    }
}

@Composable
private fun AddCardDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var phrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая карточка") },
        text = {
            OutlinedTextField(
                value = phrase,
                onValueChange = { phrase = it },
                label = { Text("Короткая фраза") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(phrase.trim()) }, enabled = phrase.isNotBlank()) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun OnTopPreview() {
    OnTopTheme {
        OnTopScreen()
    }
}
