package com.ontop.app

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.ontop.app.ui.theme.OnTopTheme

class MainActivity : ComponentActivity() {
    private val requestedCard = mutableStateOf<Long?>(null)
    companion object { const val EXTRA_CARD_ID = "com.ontop.app.CARD_ID" }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("pending_widget_card", requestedCard.value ?: 0L)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedCard.value = intent.getLongExtra(EXTRA_CARD_ID, 0).takeIf { it > 0 }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedCard.value = (savedInstanceState?.getLong("pending_widget_card")
            ?: intent.getLongExtra(EXTRA_CARD_ID, 0)).takeIf { it > 0 }
        val viewModel = ViewModelProvider(this)[BoardViewModel::class.java]
        setContent {
            OnTopTheme {
                BoardScreen(viewModel, lifecycle, requestedCard.value) { requestedCard.value = null }
            }
        }
    }
}
