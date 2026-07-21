package com.docsgpt.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.docsgpt.android.data.SettingsRepository
import com.docsgpt.android.data.UserSettings
import com.docsgpt.android.streaming.ChatStreamingRepository
import com.docsgpt.android.streaming.StreamAnswerRequest
import com.docsgpt.android.streaming.StreamEvent
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives a single chat conversation against [ChatStreamingRepository]. Each call to
 * [sendMessage] streams one answer, appending [StreamEvent.Answer] chunks onto the message
 * as they arrive so the UI can render the response incrementally, same as the web client.
 */
class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val repositoryFactory: (baseUrl: String) -> ChatStreamingRepository,
    defaultApiHost: String,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.observe(defaultApiHost)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings(defaultApiHost, null))

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamingJob: Job? = null

    fun onInputChanged(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun sendMessage() {
        val question = _uiState.value.input.trim()
        if (question.isEmpty() || _uiState.value.isStreaming) return

        val messageId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage(id = messageId, question = question),
                input = "",
                isStreaming = true,
                errorMessage = null,
            )
        }

        val currentSettings = settings.value
        val repository = repositoryFactory(currentSettings.apiHost)
        val request = StreamAnswerRequest(
            question = question,
            conversationId = _uiState.value.conversationId,
        )

        streamingJob = viewModelScope.launch {
            repository.streamAnswer(request, token = currentSettings.token)
                .catch { error ->
                    _uiState.update {
                        it.copy(isStreaming = false, errorMessage = error.message ?: "Stream failed")
                    }
                }
                .collect { event -> handleEvent(messageId, event) }
        }
    }

    /** Cancels the in-flight request; the backend still persists the partial answer. */
    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        _uiState.update { it.copy(isStreaming = false) }
    }

    fun updateApiHost(apiHost: String) {
        viewModelScope.launch { settingsRepository.setApiHost(apiHost) }
    }

    fun updateToken(token: String) {
        viewModelScope.launch { settingsRepository.setToken(token) }
    }

    private fun handleEvent(messageId: String, event: StreamEvent) {
        when (event) {
            is StreamEvent.Answer -> updateMessage(messageId) { it.copy(answer = it.answer + event.answer) }
            is StreamEvent.StructuredAnswer -> updateMessage(messageId) { it.copy(answer = event.answer) }
            is StreamEvent.Source -> updateMessage(messageId) { it.copy(sources = event.sources) }
            is StreamEvent.Thought -> updateMessage(messageId) { it.copy(thought = it.thought + event.thought) }
            is StreamEvent.ToolCalls -> Unit
            is StreamEvent.ConversationId -> _uiState.update { it.copy(conversationId = event.id) }
            is StreamEvent.Error -> _uiState.update { it.copy(isStreaming = false, errorMessage = event.message) }
            StreamEvent.End -> {
                updateMessage(messageId) { it.copy(isComplete = true) }
                _uiState.update { it.copy(isStreaming = false) }
            }
            is StreamEvent.Unknown -> Unit
        }
    }

    private inline fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { message -> if (message.id == id) transform(message) else message })
        }
    }
}

class ChatViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val repositoryFactory: (baseUrl: String) -> ChatStreamingRepository,
    private val defaultApiHost: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            "ChatViewModelFactory can only create ChatViewModel, got $modelClass"
        }
        return ChatViewModel(settingsRepository, repositoryFactory, defaultApiHost) as T
    }
}
