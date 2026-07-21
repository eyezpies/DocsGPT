package com.docsgpt.android.streaming

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request body for POST /api/answer/stream, mirroring the payload built by
 * frontend/src/conversation/conversationHandlers.ts#handleFetchAnswerSteaming.
 */
@Serializable
data class StreamAnswerRequest(
    val question: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("prompt_id") val promptId: String? = "default",
    val chunks: Int = 2,
    val retriever: String? = null,
    @SerialName("api_key") val apiKey: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("active_docs") val activeDocs: String? = null,
    @SerialName("isNoneDoc") val isNoneDoc: Boolean? = null,
    val index: Int? = null,
    @SerialName("save_conversation") val saveConversation: Boolean = true,
    @SerialName("model_id") val modelId: String? = null,
    val attachments: List<String>? = null,
    /** JSON-encoded list of {prompt, response} pairs, only used for history-less new conversations. */
    val history: String? = null,
)

@Serializable
data class SourceDoc(
    val title: String? = null,
    val text: String? = null,
    val source: String? = null,
)

/**
 * One decoded server-sent event from the /api/answer/stream response, matching the
 * `{"type": ...}` payloads produced by application/api/answer/routes/base.py#complete_stream.
 */
sealed interface StreamEvent {
    data class Answer(val answer: String) : StreamEvent
    data class Source(val sources: List<SourceDoc>) : StreamEvent
    data class ToolCalls(val toolCalls: JsonElement) : StreamEvent
    data class Thought(val thought: String) : StreamEvent
    data class StructuredAnswer(val answer: String, val schema: JsonElement?) : StreamEvent
    data class ConversationId(val id: String) : StreamEvent
    data class Error(val message: String) : StreamEvent
    data object End : StreamEvent

    /** An event whose "type" isn't one recognized above; carries the raw JSON for forward-compat. */
    data class Unknown(val raw: String) : StreamEvent
}
