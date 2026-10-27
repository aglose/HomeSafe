package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.assistant.AnthropicApi
import com.meticulouscreations.homesafe.assistant.AssistantAgent
import com.meticulouscreations.homesafe.assistant.AssistantTool
import com.meticulouscreations.homesafe.assistant.noInput
import com.meticulouscreations.homesafe.data.InMemoryApiKeyStore
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_tool_budget
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {

    private val locked = MutableStateFlow(false)

    private val budgetTool = object : AssistantTool {
        override val name = "budget"
        override val description = "The budget."
        override val inputSchema = noInput
        override val activity = Res.string.assistant_tool_budget
        override val lock: StateFlow<Boolean> get() = locked

        override suspend fun run(input: JsonObject): String = "Groceries: 412.18 of 600"
    }

    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_793_000_000)
    }

    /** The Messages requests sent, in order. */
    private val sent = mutableListOf<JsonObject>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aLockComingBackOnClearsWhatWasReadFromBehindIt() = runTest {
        val viewModel = viewModel(replies = listOf(toolCall("budget"), answer("You've spent 412.18 on groceries."), answer("Hello.")))

        viewModel.ask("How much on groceries?", screen = "Finance app, budget tab", picture = null)
        settle(viewModel)
        assertEquals("You've spent 412.18 on groceries.", viewModel.uiState.value.turns.single().answer)

        locked.value = true
        assertTrue(viewModel.uiState.value.turns.isEmpty())

        viewModel.ask("Hi", screen = "Home", picture = null)
        settle(viewModel)
        // The question that follows goes alone: nothing read from behind the lock goes back with it.
        val messages = sent.last()["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertFalse(messages.toString().contains("412.18"))
    }

    @Test
    fun aLockComingBackOnStopsAnAnswerStillBeingWritten() = runTest {
        val gate = CompletableDeferred<Unit>()
        val viewModel = viewModel(replies = listOf(toolCall("budget"), answer("You've spent 412.18 on groceries."), answer("Hello.")), holdReply = 1, gate = gate)

        viewModel.ask("How much on groceries?", screen = "Finance app, budget tab", picture = null)
        // The tool has run, and the answer that would use what it read is on its way.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (sent.size < 2) delay(10) } }

        locked.value = true
        gate.complete(Unit)
        val state = viewModel.uiState.value
        assertTrue(state.turns.isEmpty())
        assertFalse(state.working)

        viewModel.ask("Hi", screen = "Home", picture = null)
        settle(viewModel)
        val messages = sent.last()["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("Hello.", viewModel.uiState.value.turns.single().answer)
    }

    @Test
    fun aQuestionAskedOverALockedAwayScreenGoesWhenItsLockComesBackOn() = runTest {
        val screenLock = MutableStateFlow(false)
        val viewModel = viewModel(replies = listOf(answer("That's your checking account."), answer("Hello.")))

        // No tool reads anything; the picture of the unlocked screen is all that went.
        viewModel.ask("What's this?", screen = "Finance app, wallet tab", picture = null, screenLocks = listOf(screenLock))
        settle(viewModel)
        assertEquals(1, viewModel.uiState.value.turns.size)

        screenLock.value = true
        assertTrue(viewModel.uiState.value.turns.isEmpty())

        viewModel.ask("Hi", screen = "Home", picture = null)
        settle(viewModel)
        assertEquals(1, sent.last()["messages"]!!.jsonArray.size)
    }

    @Test
    fun aScreenAlreadyLockedHadNothingToHoldBack() = runTest {
        val screenLock = MutableStateFlow(true)
        val viewModel = viewModel(replies = listOf(answer("It's locked.")))

        viewModel.ask("What's this?", screen = "Finance app, locked", picture = null, screenLocks = listOf(screenLock))
        settle(viewModel)
        screenLock.value = false
        screenLock.value = true

        assertEquals("It's locked.", viewModel.uiState.value.turns.single().answer)
    }

    @Test
    fun catchesUpWithAKeyForgottenElsewhere() = runTest {
        val store = InMemoryApiKeyStore(key = "sk-test")
        val viewModel = viewModel(replies = emptyList(), store = store)
        assertTrue(viewModel.uiState.value.hasKey)

        // Another tab's assistant forgets it.
        store.saveApiKey(null)
        viewModel.catchUp()

        assertFalse(viewModel.uiState.value.hasKey)
    }

    private fun viewModel(
        replies: List<String>,
        store: InMemoryApiKeyStore = InMemoryApiKeyStore(key = "sk-test"),
        holdReply: Int = -1,
        gate: CompletableDeferred<Unit>? = null,
    ): AssistantViewModel {
        val queue = ArrayDeque(replies)
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/v1/models")) {
                return@MockEngine respond("""{"data":[],"has_more":false}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val index = sent.size
            sent += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            // Taken before any hold, so a request stopped while held still uses up its reply.
            val reply = queue.removeFirst()
            if (index == holdReply) gate?.await()
            respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val api = AnthropicApi(HttpClient(engine))
        return AssistantViewModel(AssistantAgent(api, setOf(budgetTool)), api, store, clock)
    }

    /** Waits, in real time (the HTTP engine runs on threads of its own), for the answer under way to finish. */
    private suspend fun settle(viewModel: AssistantViewModel) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { viewModel.uiState.first { !it.working } }
    }

    private fun toolCall(name: String) = stream(
        """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"$name","input":{}}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":10}}""",
    )

    private fun answer(text: String) = stream(
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":10}}""",
    )

    private fun stream(vararg events: String): String = buildString {
        val all = listOf("""{"type":"message_start","message":{"id":"msg","model":"claude-opus-5-5","content":[]}}""") + events + """{"type":"message_stop"}"""
        all.forEach { data ->
            val type = Json.parseToJsonElement(data).jsonObject["type"].toString().trim('"')
            append("event: ").append(type).append('\n').append("data: ").append(data).append("\n\n")
        }
    }
}
