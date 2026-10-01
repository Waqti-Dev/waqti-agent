package com.waqti.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.waqti.agent.ui.ChatScreen
import com.waqti.agent.ui.ChatViewModel
import com.waqti.agent.ui.WaqtiTheme

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Waqti follows the system light/dark setting; the theme supplies both
            // schemes, so nothing here hardcodes a palette.
            WaqtiTheme {
                ChatScreen(viewModel = chatViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may grant "All files access" in system settings and come back,
        // and a model may have been imported while the app was in the background.
        chatViewModel.refreshAccessFlag()
        chatViewModel.refreshModelState()
    }
}
