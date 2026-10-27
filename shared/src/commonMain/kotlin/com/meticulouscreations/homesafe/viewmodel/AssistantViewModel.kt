package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.assistant.AnthropicApi
import com.meticulouscreations.homesafe.assistant.AssistantAgent
import com.meticulouscreations.homesafe.assistant.AssistantModel
import com.meticulouscreations.homesafe.assistant.AssistantModelFamily
import com.meticulouscreations.homesafe.assistant.latestModels
import com.meticulouscreations.homesafe.assistant.userMessage
import com.meticulouscreations.homesafe.data.ASSISTANT_KEYS
import com.meticulouscreations.homesafe.data.ApiKeyStore
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import com.meticulouscreations.homesafe.ui.assistant.encodeScreenJpeg
import com.meticulouscreations.homesafe.vault.HouseholdKeys
import com.meticulouscreations.homesafe.vault.note
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
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
import kotlinx.coroutines.flow.first
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
    /** Why the household's key can't be read or saved just now (signed out, not let in yet), or why saving it failed. */
    val keyNote: UiText? = null,
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
    @Named(ASSISTANT_KEYS) private val store: ApiKeyStore,
    private val keys: HouseholdKeys,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(stored(AssistantUiState()))
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    /** Everything said so far, exactly as it went to the API: only ever added to (see [AssistantAgent]). */
    private val history = mutableListOf<JsonObject>()
    private var asking: Job? = null

    // Counts questions and fresh starts. A stopped answer can still be winding down when the next
    // question goes; what it does then is checked against this, so it can't touch the newer turn.
    private var generation = 0

    /**
     * The locks this conversation has been behind, each watched until it comes back on: those of the
     * tools it read from ([com.meticulouscreations.homesafe.assistant.AssistantTool.lock]), and of the
     * screens it was asked over ([com.meticulouscreations.homesafe.ui.assistant.AssistantHost.showsBehind]).
     */
    private val watching = mutableMapOf<StateFlow<Boolean>, Job>()

    init {
        val checkedAt = store.string(PREF_MODELS_AT)?.toLongOrNull() ?: 0L
        if (clock.now().epochSeconds - checkedAt > MODELS_MAX_AGE_SECONDS) refreshModels()
        // The key lives in the household's vault: it comes when the vault opens on sign-in, and goes if someone forgets it.
        viewModelScope.launch {
            keys.state.collect { vault ->
                val hadKey = _uiState.value.hasKey
                _uiState.update { it.copy(hasKey = keys.current(HouseholdKeys.ANTHROPIC) != null, keyNote = vault.note()) }
                if (!hadKey && _uiState.value.hasKey) refreshModels()
            }
        }
    }

    /**
     * Picks up what was changed in the store since: on iOS each tab has an assistant of its own, so
     * the key may have been saved or forgotten, or the model changed, in another. Called as it opens.
     */
    fun catchUp() {
        keys.refresh()
        _uiState.update(::stored)
    }

    /**
     * Asks [question] about the screen [screen] describes ([com.meticulouscreations.homesafe.ui.assistant.AssistantHost.describeScreen]),
     * with [picture] of it when the person lets Claude see the screen. [screenLocks] are the locks
     * that screen is shown from behind.
     */
    fun ask(question: String, screen: String, picture: ImageBitmap?, screenLocks: List<StateFlow<Boolean>> = emptyList()) {
        val text = question.trim()
        val key = keys.current(HouseholdKeys.ANTHROPIC)
        if (key == null) _uiState.update { it.copy(hasKey = false) }
        if (text.isEmpty() || key == null || _uiState.value.working) return
        val model = _uiState.value.model
        val asked = ++generation
        fun current() = generation == asked
        _uiState.update { it.copy(turns = it.turns + AssistantTurn(text), working = true, activity = null) }
        screenLocks.forEach(::behind)
        asking = viewModelScope.launch {
            try {
                val jpeg = picture?.takeIf { _uiState.value.includeScreen }?.let { withContext(Dispatchers.Default) { encodeScreenJpeg(it) } }
                val message = userMessage(screenBlock(screen) + "\n\n" + text, jpeg?.let { Base64.encode(it) })
                val answer = agent.answer(key, model, history.toList(), message) { progress ->
                    if (!current()) return@answer
                    when (progress) {
                        AssistantAgent.Progress.Thinking -> _uiState.update { it.copy(activity = null) }

                        is AssistantAgent.Progress.Looking -> {
                            progress.lock?.let(::behind)
                            _uiState.update { it.copy(activity = progress.activity) }
                        }

                        is AssistantAgent.Progress.Writing -> updateLastTurn { it.copy(answer = progress.text) }
                    }
                }
                if (!current()) return@launch
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
                if (current()) updateLastTurn { it.copy(note = (e as? LocalizedException)?.text ?: UiText.of(Res.string.assistant_error_generic)) }
            } finally {
                if (current()) _uiState.update { it.copy(working = false, activity = null) }
            }
        }
    }

    /** Stops the answer being written; what has come so far stays on screen, and out of the conversation. */
    fun stop() {
        asking?.cancel()
    }

    fun newConversation() {
        stop()
        generation++
        history.clear()
        watching.values.forEach { it.cancel() }
        watching.clear()
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

    /** Seals [key] into the household's vault on the server, for every phone in the household. */
    fun saveKey(key: String) {
        val trimmed = key.trim().takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            keys.save(HouseholdKeys.ANTHROPIC, trimmed)
                .onSuccess { _uiState.update { it.copy(hasKey = true, keyNote = null) } }
                .onFailure { failure -> _uiState.update { it.copy(keyNote = failure.userMessage()) } }
        }
    }

    /** Forgets the household's key: for every phone, not only this one. */
    fun forgetKey() {
        newConversation()
        viewModelScope.launch {
            keys.save(HouseholdKeys.ANTHROPIC, null)
                .onSuccess { _uiState.update { it.copy(hasKey = false) } }
                .onFailure { failure -> _uiState.update { it.copy(keyNote = failure.userMessage()) } }
        }
    }

    private fun refreshModels() {
        val key = keys.current(HouseholdKeys.ANTHROPIC) ?: return
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

    /** [state] with what the store holds: whether there is a key, and the settings. */
    private fun stored(state: AssistantUiState): AssistantUiState = state.copy(
        hasKey = keys.current(HouseholdKeys.ANTHROPIC) != null,
        family = store.string(PREF_FAMILY)?.let { name -> AssistantModelFamily.entries.firstOrNull { it.name == name } } ?: AssistantModelFamily.Default,
        models = cachedModels() ?: state.models,
        includeScreen = store.string(PREF_INCLUDE_SCREEN) != "false",
    )

    /**
     * Takes the whole conversation back once [lock], open now, comes back on: the answers on the card
     * and what went to the API (all of it, rather than editing the history). A lock already on held
     * nothing back from this conversation, so there is nothing to watch for.
     */
    private fun behind(lock: StateFlow<Boolean>) {
        if (lock.value || lock in watching) return
        watching[lock] = viewModelScope.launch {
            lock.first { it }
            newConversation()
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
