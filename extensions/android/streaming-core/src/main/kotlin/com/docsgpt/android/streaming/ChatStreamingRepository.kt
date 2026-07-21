package com.docsgpt.android.streaming

import kotlinx.coroutines.flow.Flow

/**
 * Streams a DocsGPT chat answer, emitting one [StreamEvent] per server-sent event.
 *
 * The returned [Flow] is cold: the HTTP request is only made once collected, and cancelling
 * collection (e.g. leaving the screen, or the user tapping "stop") cancels the underlying
 * network call so the backend can stop generating and persist the partial answer, mirroring
 * `handleAbort()` in the web frontend.
 */
interface ChatStreamingRepository {
    fun streamAnswer(request: StreamAnswerRequest, token: String? = null): Flow<StreamEvent>
}
