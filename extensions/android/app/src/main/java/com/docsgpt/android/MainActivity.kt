package com.docsgpt.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.docsgpt.android.ui.chat.ChatScreen
import com.docsgpt.android.ui.chat.ChatViewModel
import com.docsgpt.android.ui.chat.ChatViewModelFactory
import com.docsgpt.android.ui.theme.DocsGptTheme

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<ChatViewModel> {
        val container = (application as DocsGptApplication).container
        ChatViewModelFactory(
            settingsRepository = container.settingsRepository,
            repositoryFactory = container::chatStreamingRepository,
            defaultApiHost = BuildConfig.DEFAULT_API_HOST,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DocsGptTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(viewModel = viewModel)
                }
            }
        }
    }
}
