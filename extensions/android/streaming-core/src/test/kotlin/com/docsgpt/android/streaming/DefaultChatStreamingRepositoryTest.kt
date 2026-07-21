package com.docsgpt.android.streaming

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultChatStreamingRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultChatStreamingRepository

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = DefaultChatStreamingRepository(config = DocsGptConfig(baseUrl = server.url("/").toString()))
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streams every event in order and completes on end`() = runTest {
        val body = buildString {
            append("""data: {"type": "answer", "answer": "Hi"}""").append("\n\n")
            append("""data: {"type": "answer", "answer": " there"}""").append("\n\n")
            append("""data: {"type": "id", "id": "conv-1"}""").append("\n\n")
            append("""data: {"type": "end"}""").append("\n\n")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )

        val events = withTimeout(5_000) {
            repository.streamAnswer(StreamAnswerRequest(question = "hi")).toList()
        }

        assertEquals(
            listOf(
                StreamEvent.Answer("Hi"),
                StreamEvent.Answer(" there"),
                StreamEvent.ConversationId("conv-1"),
                StreamEvent.End,
            ),
            events,
        )

        val recorded = server.takeRequest()
        assertEquals("/api/answer/stream", recorded.path)
        assertEquals("POST", recorded.method)
        assertTrue(recorded.body.readUtf8().contains("\"question\":\"hi\""))
    }

    @Test
    fun `sends a bearer token header when a token is supplied`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"type": "end"}""" + "\n\n"),
        )

        withTimeout(5_000) {
            repository.streamAnswer(StreamAnswerRequest(question = "hi"), token = "abc123").toList()
        }

        val recorded = server.takeRequest()
        assertEquals("Bearer abc123", recorded.getHeader("Authorization"))
    }

    @Test
    fun `a non-2xx response is surfaced as an Error event`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val events = withTimeout(5_000) {
            repository.streamAnswer(StreamAnswerRequest(question = "hi")).toList()
        }

        assertEquals(listOf(StreamEvent.Error("HTTP 401")), events)
    }

    @Test
    fun `a dropped connection is surfaced as an Error event instead of hanging`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val events = withTimeout(5_000) {
            repository.streamAnswer(StreamAnswerRequest(question = "hi")).toList()
        }

        assertTrue(events.single() is StreamEvent.Error)
    }
}
