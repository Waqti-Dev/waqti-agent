package com.waqti.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.waqti.agent.ui.ChatScreen
import com.waqti.agent.ui.ChatViewModel

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                ChatScreen(viewModel = chatViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may grant "All files access" in system settings and come back.
        chatViewModel.refreshAccessFlag()
    }
}
