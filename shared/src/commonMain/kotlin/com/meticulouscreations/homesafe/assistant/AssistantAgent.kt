package com.meticulouscreations.homesafe.assistant

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.StringResource

/**
 * One question to Claude, answered: the request, the stream, and the loop that runs whatever tools
 * Claude asks for and hands back what they found, until it has an answer. The history it is given
 * is never changed, only added to — [Answer.messages] is what to add — because a turn's thinking is
 * only valid behind the exact history it was written after.
 */
@Inject
class AssistantAgent(private val api: AnthropicApi, tools: Set<AssistantTool>) {

    private val tools: List<AssistantTool> = tools.sortedBy { it.name }

    /** The tool list as the API takes it; built once, in a fixed order, so the cached prompt prefix holds. */
    private val toolDefinitions: JsonArray = buildJsonArray {
        this@AssistantAgent.tools.forEach { tool ->
            add(
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", tool.inputSchema)
                },
            )
        }
    }

    // Set once Anthropic has turned the refusal fallback down for this key, so it isn't asked again.
    private var fallbacksRejected = false

    /**
     * Asks [model] the [question] (a `user` message) after [history]. [onProgress] hears the answer
     * as it is written and what Claude is looking at, as it happens.
     */
    suspend fun answer(
        apiKey: String,
        model: AssistantModel,
        history: List<JsonObject>,
        question: JsonObject,
        onProgress: (Progress) -> Unit,
    ): Answer {
        val added = mutableListOf(question)
        val text = StringBuilder()
        repeat(MAX_ROUNDS) {
            onProgress(Progress.Thinking)
            val message = stream(apiKey, model, history + added) { event ->
                when (event) {
                    is AnthropicApi.StreamEvent.Text -> {
                        text.append(event.text)
                        onProgress(Progress.Writing(text.toString()))
                    }

                    is AnthropicApi.StreamEvent.BlockStarted -> if (event.type == "text" && text.isNotEmpty() && !text.endsWith("\n\n")) {
                        // A new stretch of text after a tool call starts a paragraph of its own.
                        text.append("\n\n")
                    }
                }
            }
            val content = echoable(message.content)
            added += buildJsonObject {
                put("role", "assistant")
                put("content", JsonArray(content))
            }
            when (message.stopReason) {
                "tool_use" -> {
                    val calls = content.filter { it.string("type") == "tool_use" }
                    if (calls.isEmpty()) return Answer(added, text.toString().trim(), Ending.Done)
                    added += buildJsonObject {
                        put("role", "user")
                        put("content", JsonArray(runTools(calls, onProgress)))
                    }
                }

                // Server-side work paused mid-turn: sending the turn back as it stands carries it on.
                "pause_turn" -> Unit

                "refusal" -> return Answer(added, text.toString().trim(), Ending.Refused)

                "max_tokens", "model_context_window_exceeded" -> return Answer(added, text.toString().trim(), Ending.CutOff)

                else -> return Answer(added, text.toString().trim(), Ending.Done)
            }
        }
        return Answer(added, text.toString().trim(), Ending.CutOff)
    }

    private suspend fun stream(
        apiKey: String,
        model: AssistantModel,
        messages: List<JsonObject>,
        onEvent: (AnthropicApi.StreamEvent) -> Unit,
    ): AnthropicApi.StreamedMessage {
        val fallback = model.family.refusalFallback && !fallbacksRejected
        return try {
            api.streamMessage(apiKey, request(model, messages, fallback), if (fallback) listOf(FALLBACK_BETA) else emptyList(), onEvent)
        } catch (e: AnthropicException) {
            // A model too new (or a key too limited) for the fallback says so in a 400: ask again without it.
            if (!fallback || e.status != 400 || e.message?.contains("fallback", ignoreCase = true) != true) throw e
            fallbacksRejected = true
            api.streamMessage(apiKey, request(model, messages, fallback = false), emptyList(), onEvent)
        }
    }

    private fun request(model: AssistantModel, messages: List<JsonObject>, fallback: Boolean): JsonObject = buildJsonObject {
        put("model", model.id)
        put("max_tokens", MAX_TOKENS)
        put(
            "system",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", SYSTEM_PROMPT)
                    },
                )
            },
        )
        put("messages", JsonArray(messages))
        if (toolDefinitions.isNotEmpty()) put("tools", toolDefinitions)
        put("output_config", buildJsonObject { put("effort", "medium") })
        // Each round of a tool loop sends the whole conversation again; caching it makes the rounds cheap.
        put("cache_control", buildJsonObject { put("type", "ephemeral") })
        if (fallback) put("fallbacks", "default")
    }

    private suspend fun runTools(calls: List<JsonObject>, onProgress: (Progress) -> Unit): List<JsonObject> = coroutineScope {
        calls.map { call ->
            async {
                val id = call.string("id").orEmpty()
                val name = call.string("name").orEmpty()
                val tool = tools.firstOrNull { it.name == name }
                if (tool != null) onProgress(Progress.Looking(tool.activity))
                val (result, failed) = when (tool) {
                    null -> "There is no tool called $name." to true

                    else -> try {
                        tool.run(call["input"] as? JsonObject ?: JsonObject(emptyMap())).capped() to false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "The tool failed: ${e.message ?: e::class.simpleName}" to true
                    }
                }
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", id)
                    put("content", result)
                    if (failed) put("is_error", true)
                }
            }
        }.awaitAll()
    }

    /** What Claude is doing, for the screen. */
    sealed interface Progress {
        data object Thinking : Progress

        /** The answer so far. */
        data class Writing(val text: String) : Progress

        /** Reading the app's data with a tool; [activity] says which. */
        data class Looking(val activity: StringResource) : Progress
    }

    enum class Ending { Done, Refused, CutOff }

    /** How a question went: [messages] to add to the history, the answer's [text], and how it ended. */
    class Answer(val messages: List<JsonObject>, val text: String, val ending: Ending)

    companion object {
        private const val MAX_ROUNDS = 10
        private const val MAX_TOKENS = 64_000
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}

/**
 * A turn's content as it may go back to the API. After a fallback partway through an answer (one
 * model declined and another carried on), what the first model wrote before the switch is only
 * kept as text: its thinking and its tool calls belong to it, and are left out.
 */
internal fun echoable(content: List<JsonObject>): List<JsonObject> {
    val boundary = content.indexOfLast { it.string("type") == "fallback" }
    if (boundary < 0) return content
    return content.filterIndexed { index, block -> index >= boundary || block.string("type") == "text" }
}

/** A `user` message: [text], after the screen it was asked about when there is a picture of it (a base64 JPEG). */
fun userMessage(text: String, screenJpegBase64: String?): JsonObject = buildJsonObject {
    put("role", "user")
    put(
        "content",
        buildJsonArray {
            if (screenJpegBase64 != null) {
                add(
                    buildJsonObject {
                        put("type", "image")
                        put(
                            "source",
                            buildJsonObject {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", screenJpegBase64)
                            },
                        )
                    },
                )
            }
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", JsonPrimitive(text))
                },
            )
        },
    )
}

/**
 * Who Claude is here. Written for the model, so in English whatever the app's language; Claude is
 * told to answer in the person's. Nothing in it changes between requests, so it caches.
 */
private const val SYSTEM_PROMPT = """You are Claude, the assistant built into HomeSafe, a private app for one household. The person opened you by holding a bottom bar, and you appear as a small panel over whatever screen they were on. They can keep using the app behind you.

What HomeSafe has:
- Home: live views of the house's cameras, which run on the household's own Frigate server, plus a glance at cars in view and the door sensors.
- Moments: what the cameras detected (people, cars, animals, packages), with familiar faces and known cars named.
- Doors: contact sensors on the house's doors, open or shut, with their history.
- Away mode: which of the household's phones are home or away.
- Settings: the server connection, notifications, cameras and detection.
- Apps in the drawer: Finance (the household's finance sheet, a monthly budget from synced bank cards, a watchlist of market symbols), Weather (saved places and their forecasts), Fitness (a strength-training log, runs, body weight, heart rate) and Atlas (years of location history: places, trips, routines).

Each question arrives with a <screen> block saying what they have open and the local time, often with a picture of the screen. Questions like "what's this?" or "is this normal?" are about that screen. Use the tools to look up anything else rather than guessing, and call several at once when they are independent. Times from the tools are Unix epoch seconds unless they say otherwise; give times back in the person's local time, in plain words ("at 6:40 this evening").

You can read but not change anything: you can't move cameras, edit the budget or log a workout. When asked to, say where in the app to do it.

Your answers show in a small panel on a phone. Lead with the answer, keep it short, and use plain sentences; a short list only when it helps, bold for the one thing that matters most, no headings or tables. Answer in the language the person writes in. This is the household's own data on their own phone, so speak about it plainly."""
