package com.docsgpt.android.streaming

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Groups raw SSE lines into `data:` payloads and decodes them into [StreamEvent]s.
 *
 * The backend frames every event as `data: <json>\n\n` (see base.py#complete_stream), the
 * same framing the web frontend parses by splitting on blank lines. This class is fed lines
 * one at a time so it can be driven either from a real network stream or from a plain list of
 * strings in tests.
 */
class StreamEventParser(private val json: Json = defaultJson) {

    private val pendingDataLines = mutableListOf<String>()

    /** Feed one raw line (without the trailing newline). Returns a decoded event once a blank line completes it. */
    fun onLine(line: String): StreamEvent? {
        if (line.isBlank()) {
            if (pendingDataLines.isEmpty()) return null
            val payload = pendingDataLines.joinToString("\n")
            pendingDataLines.clear()
            return decode(payload)
        }
        if (line.startsWith("data:")) {
            pendingDataLines.add(line.removePrefix("data:").trim())
        }
        return null
    }

    /** Flush any buffered `data:` lines that never received a terminating blank line. */
    fun flush(): StreamEvent? {
        if (pendingDataLines.isEmpty()) return null
        val payload = pendingDataLines.joinToString("\n")
        pendingDataLines.clear()
        return decode(payload)
    }

    private fun decode(payload: String): StreamEvent {
        return try {
            val element = json.parseToJsonElement(payload).jsonObject
            when (element["type"]?.jsonPrimitive?.content) {
                "answer" -> StreamEvent.Answer(element["answer"]?.jsonPrimitive?.content.orEmpty())
                "source" -> StreamEvent.Source(
                    (element["source"] as? JsonArray ?: JsonArray(emptyList())).map {
                        json.decodeFromJsonElement(SourceDoc.serializer(), it)
                    },
                )
                "tool_calls" -> StreamEvent.ToolCalls(element["tool_calls"] ?: JsonArray(emptyList()))
                "thought" -> StreamEvent.Thought(element["thought"]?.jsonPrimitive?.content.orEmpty())
                "structured_answer" -> StreamEvent.StructuredAnswer(
                    answer = element["answer"]?.jsonPrimitive?.content.orEmpty(),
                    schema = element["schema"],
                )
                "id" -> StreamEvent.ConversationId(element["id"]?.jsonPrimitive?.content.orEmpty())
                "error" -> StreamEvent.Error(element["error"]?.jsonPrimitive?.content ?: "Unknown error")
                "end" -> StreamEvent.End
                else -> StreamEvent.Unknown(payload)
            }
        } catch (e: Exception) {
            StreamEvent.Error("Failed to parse stream event: ${e.message}")
        }
    }

    companion object {
        val defaultJson = Json { ignoreUnknownKeys = true }
    }
}
