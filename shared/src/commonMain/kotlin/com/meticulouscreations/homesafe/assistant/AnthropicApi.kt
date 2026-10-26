package com.meticulouscreations.homesafe.assistant

import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_error_busy
import homesafe.shared.generated.resources.assistant_error_generic
import homesafe.shared.generated.resources.assistant_error_key
import homesafe.shared.generated.resources.assistant_error_network
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The client for the assistant's calls to Anthropic. Its own instance, as the weather and finance
 * apps' are: Frigate's session cookies never ride along to a third party. A turn can stream for
 * minutes on the bigger models, so only a stalled socket times it out, not the length of the answer.
 */
@ContributesTo(AppScope::class)
interface AssistantProviders {
    @Named(ASSISTANT_CLIENT)
    @SingleIn(AppScope::class)
    @Provides
    fun provideAssistantHttpClient(): HttpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15 * 60_000L
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
    }
}

const val ASSISTANT_CLIENT = "assistant"

/**
 * Anthropic's Messages and Models APIs over plain HTTP: there is no Anthropic SDK for Kotlin
 * Multiplatform (the Java one doesn't reach iOS), so this speaks the wire format the API
 * documents. Requests and replies are kept as JSON rather than typed, because a turn's content
 * blocks have to go back to the API exactly as they came (thinking blocks carry signatures).
 */
@Inject
@SingleIn(AppScope::class)
class AnthropicApi(@Named(ASSISTANT_CLIENT) private val httpClient: HttpClient) {

    /** Every model the key can use, newest first as the API lists them, read across all its pages. */
    suspend fun listModels(apiKey: String): List<ListedModel> {
        val models = mutableListOf<ListedModel>()
        var after: String? = null
        repeat(MAX_MODEL_PAGES) {
            val response = withRetries {
                httpClient.get("$BASE_URL/v1/models") {
                    headers(apiKey)
                    parameter("limit", 100)
                    after?.let { parameter("after_id", it) }
                }
            }
            if (!response.status.isSuccess()) throw failure(response.status.value, response.bodyAsText())
            val page = json.parseToJsonElement(response.bodyAsText()).jsonObject
            page["data"]?.jsonArray.orEmpty().forEach { element ->
                val model = element.jsonObject
                val id = model.string("id") ?: return@forEach
                models += ListedModel(id = id, displayName = model.string("display_name"), createdAt = model.string("created_at"))
            }
            if (page["has_more"]?.jsonPrimitive?.booleanOrNull != true) return models
            after = page.string("last_id") ?: return models
        }
        return models
    }

    /**
     * Sends [body] (a Messages request, without `stream`) and reads the answer as it streams,
     * handing [onEvent] each piece of text as it arrives. Returns the whole message once it ends,
     * its content blocks rebuilt from the stream as the API would have sent them unstreamed.
     */
    suspend fun streamMessage(apiKey: String, body: JsonObject, betas: List<String>, onEvent: (StreamEvent) -> Unit): StreamedMessage {
        val streamed = JsonObject(body + ("stream" to JsonPrimitive(true)))
        val statement = httpClient.preparePost("$BASE_URL/v1/messages") {
            headers(apiKey)
            if (betas.isNotEmpty()) header("anthropic-beta", betas.joinToString(","))
            contentType(ContentType.Application.Json)
            setBody(streamed.toString())
        }
        var attempt = 0
        while (true) {
            val outcome = networkFailures {
                statement.execute { response ->
                    if (!response.status.isSuccess()) {
                        val status = response.status.value
                        val text = response.bodyAsText()
                        if (status in RETRIED_STATUSES && attempt < MAX_RETRIES) return@execute Retry(retryAfterMillis(response, attempt))
                        throw failure(status, text)
                    }
                    Done(readStream(response, onEvent))
                }
            }
            when (outcome) {
                is Retry -> {
                    attempt++
                    delay(outcome.afterMillis)
                }

                is Done -> return outcome.message
            }
        }
    }

    private suspend fun readStream(response: HttpResponse, onEvent: (StreamEvent) -> Unit): StreamedMessage {
        val channel = response.bodyAsChannel()
        val message = MessageAssembler()
        while (true) {
            val line = channel.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val event = runCatching { json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }.getOrNull() ?: continue
            if (event.string("type") == "error") {
                val error = event["error"]?.jsonObject
                throw failure(if (error?.string("type") == "overloaded_error") OVERLOADED else 500, event.toString())
            }
            message.accept(event, onEvent)
            if (message.finished) break
        }
        // A stream cut off before `message_stop` is half a turn: its blocks can't go back to the API.
        if (!message.finished) throw LocalizedException(UiText.of(Res.string.assistant_error_network), technical = "The stream ended before message_stop")
        return message.result()
    }

    private suspend fun withRetries(request: suspend () -> HttpResponse): HttpResponse {
        var attempt = 0
        while (true) {
            val response = networkFailures { request() }
            if (response.status.value !in RETRIED_STATUSES || attempt >= MAX_RETRIES) return response
            delay(retryAfterMillis(response, attempt))
            attempt++
        }
    }

    /** Runs [call], turning a failure to reach the API at all (no connection, a dropped socket) into one the person can read. */
    private suspend fun <T> networkFailures(call: suspend () -> T): T = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: LocalizedException) {
        throw e
    } catch (e: Exception) {
        throw LocalizedException(UiText.of(Res.string.assistant_error_network), technical = e.message, cause = e)
    }

    private fun HttpRequestBuilder.headers(apiKey: String) {
        header("x-api-key", apiKey)
        header("anthropic-version", API_VERSION)
    }

    private fun retryAfterMillis(response: HttpResponse, attempt: Int): Long =
        response.headers["retry-after"]?.toLongOrNull()?.times(1_000)?.coerceAtMost(MAX_RETRY_WAIT_MS)
            ?: (BASE_RETRY_WAIT_MS shl attempt)

    private sealed interface Outcome

    private class Retry(val afterMillis: Long) : Outcome

    private class Done(val message: StreamedMessage) : Outcome

    /** Everything a streamed turn came to, its [content] ready to go back to the API in the history. */
    class StreamedMessage(val model: String?, val content: List<JsonObject>, val stopReason: String?)

    /** What the stream says as it goes, for the screen to show before the turn is over. */
    sealed interface StreamEvent {
        /** More of an answer's text. */
        data class Text(val text: String) : StreamEvent

        /** A content block of [type] opening, `tool_use` with the tool's [name]. */
        data class BlockStarted(val type: String, val name: String?) : StreamEvent
    }

    /**
     * Rebuilds a message's content blocks from its stream: each block as `content_block_start`
     * opened it, with its deltas folded in (text, a tool call's JSON input, a thinking block's
     * thinking and signature). Any block type it doesn't know is kept as it opened, so it goes back
     * to the API unchanged.
     */
    private class MessageAssembler {
        private val blocks = mutableMapOf<Int, MutableMap<String, JsonElement>>()
        private val partialInputs = mutableMapOf<Int, StringBuilder>()
        private var model: String? = null
        private var stopReason: String? = null
        var finished = false
            private set

        fun accept(event: JsonObject, onEvent: (StreamEvent) -> Unit) {
            when (event.string("type")) {
                "message_start" -> model = event["message"]?.jsonObject?.string("model")

                "content_block_start" -> {
                    val index = event.index() ?: return
                    val block = event["content_block"]?.jsonObject ?: return
                    blocks[index] = block.toMutableMap()
                    onEvent(StreamEvent.BlockStarted(block.string("type").orEmpty(), block.string("name")))
                }

                "content_block_delta" -> {
                    val index = event.index() ?: return
                    val block = blocks[index] ?: return
                    val delta = event["delta"]?.jsonObject ?: return
                    when (delta.string("type")) {
                        "text_delta" -> delta.string("text")?.let { text ->
                            block.append("text", text)
                            onEvent(StreamEvent.Text(text))
                        }

                        "input_json_delta" -> delta.string("partial_json")?.let { partialInputs.getOrPut(index) { StringBuilder() }.append(it) }

                        "thinking_delta" -> delta.string("thinking")?.let { block.append("thinking", it) }

                        "signature_delta" -> delta.string("signature")?.let { block["signature"] = JsonPrimitive(it) }
                    }
                }

                "content_block_stop" -> {
                    val index = event.index() ?: return
                    val input = partialInputs.remove(index)?.toString() ?: return
                    // An empty input arrives as no deltas at all, and stays the `{}` the block opened with.
                    blocks[index]?.set("input", if (input.isBlank()) JsonObject(emptyMap()) else runCatching { json.parseToJsonElement(input) }.getOrElse { JsonObject(emptyMap()) })
                }

                "message_delta" -> event["delta"]?.jsonObject?.string("stop_reason")?.let { stopReason = it }

                "message_stop" -> finished = true
            }
        }

        fun result() = StreamedMessage(model, blocks.entries.sortedBy { it.key }.map { JsonObject(it.value) }, stopReason)

        private fun MutableMap<String, JsonElement>.append(key: String, text: String) {
            this[key] = JsonPrimitive(((this[key] as? JsonPrimitive)?.contentOrNull ?: "") + text)
        }

        private fun JsonObject.index(): Int? = (this["index"] as? JsonPrimitive)?.intOrNull
    }

    companion object {
        private const val BASE_URL = "https://api.anthropic.com"
        private const val API_VERSION = "2026-10-21"
        private const val MAX_RETRIES = 2
        private const val BASE_RETRY_WAIT_MS = 1_500L
        private const val MAX_RETRY_WAIT_MS = 20_000L
        private const val MAX_MODEL_PAGES = 5
        private const val OVERLOADED = 529
        private val RETRIED_STATUSES = setOf(408, 409, 429, 500, 502, 503, 504, OVERLOADED)
        private val json = Json { ignoreUnknownKeys = true }

        /** A failed call, with what the person sees for it and the API's own words kept in `technical`. */
        internal fun failure(status: Int, body: String): AnthropicException {
            val text = when (status) {
                401, 403 -> Res.string.assistant_error_key
                429, OVERLOADED -> Res.string.assistant_error_busy
                else -> Res.string.assistant_error_generic
            }
            return AnthropicException(status, UiText.of(text), technical = "HTTP $status: ${body.take(500)}")
        }
    }
}

/** A refused call to Anthropic; [status] is the HTTP status, for callers to tell a bad request from a busy server. */
class AnthropicException(val status: Int, text: UiText, technical: String) : LocalizedException(text, technical)

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
