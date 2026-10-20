package com.ontop.app

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.ontop.app.ui.theme.OnTopTheme
import com.ontop.app.sharing.ShareInbox
import com.ontop.app.sharing.readIncomingShare
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.widget.Toast

internal fun Intent.isWidgetNoteOpen(): Boolean = getStringExtra(MainActivity.EXTRA_OPEN_SOURCE) == "widget" ||
    (data?.scheme == "ontop" && data?.host == "card" && data?.pathSegments?.let {
        it.size == 3 && it[1] == "widget" && it[0].toLongOrNull() == getLongExtra(MainActivity.EXTRA_CARD_ID, 0)
    } == true)

class MainActivity : ComponentActivity() {
    private lateinit var shareInbox: ShareInbox
    private val requestedCard = mutableStateOf<Long?>(null)
    private val requestFromWidget = mutableStateOf(false)
    companion object {
        const val EXTRA_CARD_ID = "com.ontop.app.CARD_ID"
        const val EXTRA_OPEN_SOURCE = "com.ontop.app.OPEN_SOURCE"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("pending_widget_card", requestedCard.value ?: 0L)
        outState.putBoolean("pending_widget_source", requestFromWidget.value)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestFromWidget.value = intent.isWidgetNoteOpen()
        if (intent.action == Intent.ACTION_SEND) {
            requestedCard.value = null
            receiveShare(intent)
        } else requestedCard.value = intent.getLongExtra(EXTRA_CARD_ID, 0).takeIf { it > 0 }
    }

    private fun receiveShare(intent: Intent) {
        try { readIncomingShare(intent)?.let(shareInbox::enqueue)
        } catch (error: Exception) {
            Toast.makeText(this, if (error is IllegalArgumentException) error.message else
                "Не удалось прочитать полученный материал", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shareInbox = ViewModelProvider(this)[ShareInbox::class.java]
        if (savedInstanceState == null) receiveShare(intent)
        com.ontop.app.reminders.ReminderRestoreJob.enqueue(this)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.ontop.app.widget.WidgetRotation.reconcile(this@MainActivity) }
        requestedCard.value = (savedInstanceState?.getLong("pending_widget_card")
            ?: if (intent.action == Intent.ACTION_SEND) 0L else intent.getLongExtra(EXTRA_CARD_ID, 0)).takeIf { it > 0 }
        val viewModel = ViewModelProvider(this)[BoardViewModel::class.java]
        requestFromWidget.value = savedInstanceState?.getBoolean("pending_widget_source")
            ?: intent.isWidgetNoteOpen()
        setContent {
            OnTopTheme {
                val incoming by shareInbox.queue.collectAsState()
                BoardScreen(viewModel, lifecycle, requestedCard.value,
                    requestFromWidget = requestFromWidget.value,
                    onCardRequestHandled = { requestedCard.value = null }, incomingShare = incoming.firstOrNull(),
                    onShareHandled = { incoming.firstOrNull()?.let { shareInbox.consumed(it.id) } })
            }
        }
    }
}
