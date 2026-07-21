package com.docsgpt.android.ui.chat

import com.docsgpt.android.streaming.SourceDoc

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    val conversationId: String? = null,
    val errorMessage: String? = null,
)

data class ChatMessage(
    val id: String,
    val question: String,
    val answer: String = "",
    val thought: String = "",
    val sources: List<SourceDoc> = emptyList(),
    val isComplete: Boolean = false,
)
