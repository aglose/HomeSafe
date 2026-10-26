package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.assistant.AnthropicApi
import com.meticulouscreations.homesafe.assistant.AssistantAgent
import com.meticulouscreations.homesafe.assistant.AssistantModel
import com.meticulouscreations.homesafe.assistant.AssistantModelFamily
import com.meticulouscreations.homesafe.assistant.AssistantStore
import com.meticulouscreations.homesafe.assistant.latestModels
import com.meticulouscreations.homesafe.assistant.userMessage
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.ui.assistant.encodeScreenJpeg
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_cut_off
import homesafe.shared.generated.resources.assistant_error_generic
import homesafe.shared.generated.resources.assistant_refused
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.StringResource
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** One question and Claude's answer to it, as far as it has got. [note] says why it stopped short, when it did. */
@Immutable
data class AssistantTurn(val question: String, val answer: String = "", val note: UiText? = null)

@Immutable
data class AssistantUiState(
    val hasKey: Boolean = false,
    val family: AssistantModelFamily = AssistantModelFamily.Default,
    /** The newest model of each family, in the picker's order. */
    val models: List<AssistantModel> = AssistantModelFamily.entries.map(AssistantModel::fallback),
    val turns: List<AssistantTurn> = emptyList(),
    val working: Boolean = false,
    /** What Claude is reading while it works, or null while it thinks or writes. */
    val activity: StringResource? = null,
    /** Whether a question goes with a picture of the screen it was asked over. */
    val includeScreen: Boolean = true,
) {
    val model: AssistantModel get() = models.firstOrNull { it.family == family } ?: AssistantModel.fallback(family)
}

/**
 * The assistant's conversation, for as long as the app runs. Opening and closing the box keeps it;
 * [newConversation] starts over. Which model a family means is looked up from Anthropic once a day
 * (and whenever a key is saved), so a newly released model is picked up by itself.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class AssistantViewModel(
    private val agent: AssistantAgent,
    private val api: AnthropicApi,
    private val store: AssistantStore,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AssistantUiState(
            hasKey = store.apiKey() != null,
            family = store.string(PREF_FAMILY)?.let { name -> AssistantModelFamily.entries.firstOrNull { it.name == name } } ?: AssistantModelFamily.Default,
            models = cachedModels() ?: AssistantModelFamily.entries.map(AssistantModel::fallback),
            includeScreen = store.string(PREF_INCLUDE_SCREEN) != "false",
        ),
    )
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    /** Everything said so far, exactly as it went to the API: only ever added to (see [AssistantAgent]). */
    private val history = mutableListOf<JsonObject>()
    private var asking: Job? = null

    init {
        val checkedAt = store.string(PREF_MODELS_AT)?.toLongOrNull() ?: 0L
        if (clock.now().epochSeconds - checkedAt > MODELS_MAX_AGE_SECONDS) refreshModels()
    }

    /**
     * Asks [question] about the screen [screen] describes ([com.meticulouscreations.homesafe.ui.assistant.AssistantHost.describeScreen]),
     * with [picture] of it when the person lets Claude see the screen.
     */
    fun ask(question: String, screen: String, picture: ImageBitmap?) {
        val text = question.trim()
        val key = store.apiKey()
        if (text.isEmpty() || key == null || _uiState.value.working) return
        val model = _uiState.value.model
        _uiState.update { it.copy(turns = it.turns + AssistantTurn(text), working = true, activity = null) }
        asking = viewModelScope.launch {
            try {
                val jpeg = picture?.takeIf { _uiState.value.includeScreen }?.let { withContext(Dispatchers.Default) { encodeScreenJpeg(it) } }
                val message = userMessage(screenBlock(screen) + "\n\n" + text, jpeg?.let { Base64.encode(it) })
                val answer = agent.answer(key, model, history.toList(), message) { progress ->
                    when (progress) {
                        AssistantAgent.Progress.Thinking -> _uiState.update { it.copy(activity = null) }
                        is AssistantAgent.Progress.Looking -> _uiState.update { it.copy(activity = progress.activity) }
                        is AssistantAgent.Progress.Writing -> updateLastTurn { it.copy(answer = progress.text) }
                    }
                }
                when (answer.ending) {
                    // A declined exchange isn't kept: the conversation carries on as if it hadn't been asked.
                    AssistantAgent.Ending.Refused -> updateLastTurn { it.copy(answer = "", note = UiText.of(Res.string.assistant_refused)) }

                    AssistantAgent.Ending.CutOff -> {
                        history += answer.messages
                        updateLastTurn { it.copy(answer = answer.text, note = UiText.of(Res.string.assistant_cut_off)) }
                    }

                    AssistantAgent.Ending.Done -> {
                        history += answer.messages
                        updateLastTurn { it.copy(answer = answer.text) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateLastTurn { it.copy(note = (e as? LocalizedException)?.text ?: UiText.of(Res.string.assistant_error_generic)) }
            } finally {
                _uiState.update { it.copy(working = false, activity = null) }
            }
        }
    }

    /** Stops the answer being written; what has come so far stays on screen, and out of the conversation. */
    fun stop() {
        asking?.cancel()
    }

    fun newConversation() {
        stop()
        history.clear()
        _uiState.update { it.copy(turns = emptyList(), working = false, activity = null) }
    }

    fun selectFamily(family: AssistantModelFamily) {
        store.saveString(PREF_FAMILY, family.name)
        _uiState.update { it.copy(family = family) }
    }

    fun setIncludeScreen(include: Boolean) {
        store.saveString(PREF_INCLUDE_SCREEN, include.toString())
        _uiState.update { it.copy(includeScreen = include) }
    }

    fun saveKey(key: String) {
        val trimmed = key.trim().takeIf { it.isNotEmpty() } ?: return
        store.saveApiKey(trimmed)
        _uiState.update { it.copy(hasKey = true) }
        refreshModels()
    }

    fun forgetKey() {
        newConversation()
        store.saveApiKey(null)
        _uiState.update { it.copy(hasKey = false) }
    }

    private fun refreshModels() {
        val key = store.apiKey() ?: return
        viewModelScope.launch {
            // Not being able to look is no loss: the families keep the models they had.
            val listed = runCatching { api.listModels(key) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@launch
            val latest = latestModels(listed)
            val models = AssistantModelFamily.entries.map { latest.getValue(it) }
            store.saveString(PREF_MODELS, models.joinToString("\n") { "${it.family.name}\t${it.id}\t${it.displayName.orEmpty()}" })
            store.saveString(PREF_MODELS_AT, clock.now().epochSeconds.toString())
            _uiState.update { it.copy(models = models) }
        }
    }

    private fun cachedModels(): List<AssistantModel>? {
        val saved = store.string(PREF_MODELS)?.lines()?.mapNotNull { line ->
            val (family, id, name) = line.split('\t').takeIf { it.size == 3 } ?: return@mapNotNull null
            val known = AssistantModelFamily.entries.firstOrNull { it.name == family } ?: return@mapNotNull null
            AssistantModel(known, id, name.ifEmpty { null })
        }?.associateBy { it.family } ?: return null
        return AssistantModelFamily.entries.map { saved[it] ?: AssistantModel.fallback(it) }
    }

    private fun updateLastTurn(change: (AssistantTurn) -> AssistantTurn) = _uiState.update { state ->
        state.copy(turns = state.turns.dropLast(1) + listOfNotNull(state.turns.lastOrNull()?.let(change)))
    }

    /** Where the person is, for Claude: what's open, and their local time (Claude has no clock of its own). */
    private fun screenBlock(screen: String): String {
        val zone = TimeZone.currentSystemDefault()
        val now = clock.now().toLocalDateTime(zone)
        val time = "${now.date} ${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
        return "<screen>\nLocal time: $time, ${now.dayOfWeek.name.lowercase()} (${zone.id})\nOpen in HomeSafe:\n${screen.ifBlank { "(unknown)" }}\n</screen>"
    }

    private companion object {
        const val PREF_FAMILY = "family"
        const val PREF_INCLUDE_SCREEN = "include_screen"
        const val PREF_MODELS = "models"
        const val PREF_MODELS_AT = "models_checked_at"
        const val MODELS_MAX_AGE_SECONDS = 24 * 3_600L
    }
}
