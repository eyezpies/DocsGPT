package com.docsgpt.android.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamEventParserTest {

    private fun feed(parser: StreamEventParser, vararg lines: String): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        for (line in lines) {
            parser.onLine(line)?.let { events.add(it) }
        }
        return events
    }

    @Test
    fun `decodes a single answer chunk framed with a trailing blank line`() {
        val parser = StreamEventParser()
        val events = feed(
            parser,
            """data: {"type": "answer", "answer": "Hel"}""",
            "",
        )
        assertEquals(listOf(StreamEvent.Answer("Hel")), events)
    }

    @Test
    fun `decodes a full conversation matching the backend event order`() {
        val parser = StreamEventParser()
        val lines = listOf(
            """data: {"type": "answer", "answer": "The"}""",
            "",
            """data: {"type": "answer", "answer": " sky is blue"}""",
            "",
            """data: {"type": "source", "source": [{"title": "sky.txt", "text": "...", "source": "local"}]}""",
            "",
            """data: {"type": "thought", "thought": "checking docs"}""",
            "",
            """data: {"type": "id", "id": "conv-123"}""",
            "",
            """data: {"type": "end"}""",
            "",
        )
        val events = feed(parser, *lines.toTypedArray())

        assertEquals(
            listOf(
                StreamEvent.Answer("The"),
                StreamEvent.Answer(" sky is blue"),
                StreamEvent.Source(listOf(SourceDoc("sky.txt", "...", "local"))),
                StreamEvent.Thought("checking docs"),
                StreamEvent.ConversationId("conv-123"),
                StreamEvent.End,
            ),
            events,
        )
    }

    @Test
    fun `decodes an error event`() {
        val parser = StreamEventParser()
        val events = feed(
            parser,
            """data: {"type": "error", "error": "Unauthorized"}""",
            "",
        )
        assertEquals(listOf(StreamEvent.Error("Unauthorized")), events)
    }

    @Test
    fun `unrecognized type falls back to Unknown instead of throwing`() {
        val parser = StreamEventParser()
        val events = feed(
            parser,
            """data: {"type": "future_event", "payload": 1}""",
            "",
        )
        assertEquals(1, events.size)
        assertTrue(events.single() is StreamEvent.Unknown)
    }

    @Test
    fun `malformed json decodes as an Error instead of throwing`() {
        val parser = StreamEventParser()
        val events = feed(parser, "data: not json", "")
        assertEquals(1, events.size)
        assertTrue(events.single() is StreamEvent.Error)
    }

    @Test
    fun `multiline data fields are joined before decoding, per the SSE spec`() {
        val parser = StreamEventParser()
        val events = feed(
            parser,
            """data: {"type":""",
            """data: "answer", "answer": "joined"}""",
            "",
        )
        assertEquals(listOf(StreamEvent.Answer("joined")), events)
    }

    @Test
    fun `non-data lines and blank lines with no pending event are ignored`() {
        val parser = StreamEventParser()
        assertNull(parser.onLine(""))
        assertNull(parser.onLine(": this is a comment"))
        assertNull(parser.onLine("event: message"))
    }

    @Test
    fun `flush emits a buffered event that never received a terminating blank line`() {
        val parser = StreamEventParser()
        assertNull(parser.onLine("""data: {"type": "answer", "answer": "cut off"}"""))
        assertEquals(StreamEvent.Answer("cut off"), parser.flush())
        assertNull(parser.flush())
    }
}
