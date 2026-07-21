package com.docsgpt.android.streaming

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Default [ChatStreamingRepository], talking to `POST {baseUrl}/api/answer/stream`.
 *
 * The endpoint responds with `Content-Type: text/event-stream` but isn't a plain GET
 * EventSource - it's a streamed POST response - so this reads the raw response body line by
 * line rather than relying on an SSE client library, the same approach the web frontend takes
 * with `response.body.getReader()` in conversationHandlers.ts.
 */
class DefaultChatStreamingRepository(
    private val config: DocsGptConfig,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = StreamEventParser.defaultJson,
) : ChatStreamingRepository {

    override fun streamAnswer(request: StreamAnswerRequest, token: String?): Flow<StreamEvent> =
        callbackFlow {
            val requestBody = json.encodeToString(StreamAnswerRequest.serializer(), request)
                .toRequestBody(JSON_MEDIA_TYPE)

            val httpRequestBuilder = Request.Builder()
                .url("${config.baseUrl.trimEnd('/')}$STREAM_PATH")
                .post(requestBody)
            token?.let { httpRequestBuilder.header("Authorization", "Bearer $it") }

            val call = client.newCall(httpRequestBuilder.build())

            launch(Dispatchers.IO) {
                runCatching { call.execute() }
                    .onSuccess { response -> response.use { readEvents(it) { event -> trySend(event) } } }
                    .onFailure { error ->
                        if (!call.isCanceled()) {
                            trySend(StreamEvent.Error(error.message ?: "Stream request failed"))
                        }
                    }
                close()
            }

            awaitClose { call.cancel() }
        }

    private suspend fun readEvents(response: Response, send: (StreamEvent) -> Unit) {
        if (!response.isSuccessful) {
            send(StreamEvent.Error("HTTP ${response.code}"))
            return
        }
        val source = response.body?.source() ?: run {
            send(StreamEvent.Error("Empty response body"))
            return
        }
        val parser = StreamEventParser(json)
        while (!source.exhausted()) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            parser.onLine(line)?.let(send)
        }
        parser.flush()?.let(send)
    }

    companion object {
        private const val STREAM_PATH = "/api/answer/stream"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
