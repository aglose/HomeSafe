package com.meticulouscreations.homesafe.assistant

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_tool_doors
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantAgentTest {

    private val doorsTool = object : AssistantTool {
        override val name = "doors"
        override val description = "The doors."
        override val inputSchema = noInput
        override val activity = Res.string.assistant_tool_doors
        var calls = 0

        override suspend fun run(input: JsonObject): String {
            calls++
            return "Front door: open since 18:02"
        }
    }

    @Test
    fun runsTheToolClaudeAsksForAndAnswersWithWhatItFound() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val replies = ArrayDeque(listOf(TOOL_CALL_STREAM, ANSWER_STREAM))
        val engine = MockEngine { request ->
            bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond(replies.removeFirst(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val agent = AssistantAgent(AnthropicApi(HttpClient(engine)), setOf(doorsTool))
        val progress = mutableListOf<AssistantAgent.Progress>()

        val answer = agent.answer(
            apiKey = "sk-test",
            model = AssistantModel.fallback(AssistantModelFamily.Opus),
            history = emptyList(),
            question = userMessage("Is the front door shut?", screenJpegBase64 = null),
            onProgress = { progress += it },
        )

        assertEquals(1, doorsTool.calls)
        assertEquals("Let me check.\n\nNo, the **front door** has been open since 6:02 PM.", answer.text)
        assertEquals(AssistantAgent.Ending.Done, answer.ending)
        assertTrue(AssistantAgent.Progress.Looking(Res.string.assistant_tool_doors) in progress)
        // The question, the turn that called the tool, the tool's result, and the answer.
        assertEquals(listOf("user", "assistant", "user", "assistant"), answer.messages.map { it["role"]!!.jsonPrimitive.content })
        // The tool call goes back as it came, its streamed input put together, and so does the thinking with its signature.
        val callTurn = answer.messages[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text", "tool_use"), callTurn.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("sig-1", callTurn[0]["signature"]!!.jsonPrimitive.content)
        assertEquals(buildJsonObject { put("which", "front") }, callTurn[2]["input"])
        val result = answer.messages[2]["content"]!!.jsonArray.single().jsonObject
        assertEquals("toolu_1", result["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("Front door: open since 18:02", result["content"]!!.jsonPrimitive.content)
        // The second request carried the whole exchange so far, and both asked for the refusal fallback.
        assertEquals(3, bodies[1]["messages"]!!.jsonArray.size)
        assertEquals(JsonPrimitive("default"), bodies[0]["fallbacks"])
        assertEquals(JsonPrimitive(true), bodies[0]["stream"])
    }

    @Test
    fun keepsOnlyTheTextFromBeforeAMidAnswerFallback() {
        val blocks = listOf(
            block("thinking"),
            block("text"),
            block("tool_use"),
            block("fallback"),
            block("thinking"),
            block("text"),
        )

        assertEquals(listOf("text", "fallback", "thinking", "text"), echoable(blocks).map { it["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun picksTheNewestModelOfEachFamily() {
        val latest = latestModels(
            listOf(
                ListedModel("claude-opus-5", "Claude Opus 5", "2026-03-01T00:00:00Z"),
                ListedModel("claude-opus-5-5", "Claude Opus 5.5", "2026-08-01T00:00:00Z"),
                ListedModel("claude-opus-4-8", "Claude Opus 4.8", "2026-01-10T00:00:00Z"),
                ListedModel("claude-sonnet-5-5", "Claude Sonnet 5.5", null),
                ListedModel("claude-mythos-5-1", "Claude Mythos 5.1", "2026-09-01T00:00:00Z"),
            ),
        )

        assertEquals("claude-opus-5-5", latest.getValue(AssistantModelFamily.Opus).id)
        assertEquals("claude-sonnet-5-5", latest.getValue(AssistantModelFamily.Sonnet).id)
        // Nothing listed for these, so they stay on what the app knew of.
        assertEquals(AssistantModelFamily.Haiku.fallbackId, latest.getValue(AssistantModelFamily.Haiku).id)
        assertEquals(AssistantModelFamily.Fable.fallbackId, latest.getValue(AssistantModelFamily.Fable).id)
    }

    private fun block(type: String) = buildJsonObject { put("type", type) }

    private companion object {
        val TOOL_CALL_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","content":[]}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-1"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Let me check."}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: content_block_start
            data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"doors","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"which\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"front\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":2}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":40}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()

        val ANSWER_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_2","model":"claude-opus-5-5","content":[]}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"No, the **front door** "}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"has been open since 6:02 PM."}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
    }
}
