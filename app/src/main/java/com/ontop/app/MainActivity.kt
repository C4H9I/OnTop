package com.ontop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.ontop.app.ui.theme.OnTopTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModel = ViewModelProvider(this)[BoardViewModel::class.java]
        setContent {
            OnTopTheme {
                BoardScreen(viewModel, lifecycle)
            }
        }
    }
}
