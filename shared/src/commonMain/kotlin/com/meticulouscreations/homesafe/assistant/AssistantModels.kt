package com.meticulouscreations.homesafe.assistant

import androidx.compose.runtime.Immutable
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_model_fable
import homesafe.shared.generated.resources.assistant_model_fable_hint
import homesafe.shared.generated.resources.assistant_model_haiku
import homesafe.shared.generated.resources.assistant_model_haiku_hint
import homesafe.shared.generated.resources.assistant_model_opus
import homesafe.shared.generated.resources.assistant_model_opus_hint
import homesafe.shared.generated.resources.assistant_model_sonnet
import homesafe.shared.generated.resources.assistant_model_sonnet_hint
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Instant

/**
 * The four kinds of Claude the assistant offers. The person picks a family, never a version: which
 * model that means is whatever Anthropic's Models API lists as the newest of it ([latestModels]),
 * so a new release turns up in the picker without an update to the app. [fallbackId] is used until
 * the list has been read once, and if it can't be.
 */
enum class AssistantModelFamily(
    /** How the family's model ids begin, `claude-opus-5-5` being an Opus. */
    val idPrefix: String,
    val fallbackId: String,
    val label: StringResource,
    val hint: StringResource,
    /**
     * Whether a request may ask for Anthropic's server-side refusal fallback (`fallbacks: "default"`).
     * Haiku has none to fall back to.
     */
    val refusalFallback: Boolean,
) {
    Haiku("claude-haiku-", "claude-haiku-5-5", Res.string.assistant_model_haiku, Res.string.assistant_model_haiku_hint, refusalFallback = false),
    Sonnet("claude-sonnet-", "claude-sonnet-5-5", Res.string.assistant_model_sonnet, Res.string.assistant_model_sonnet_hint, refusalFallback = true),
    Opus("claude-opus-", "claude-opus-5-5", Res.string.assistant_model_opus, Res.string.assistant_model_opus_hint, refusalFallback = true),
    Fable("claude-fable-", "claude-fable-5-1", Res.string.assistant_model_fable, Res.string.assistant_model_fable_hint, refusalFallback = true),
    ;

    companion object {
        /** What a new install starts on. */
        val Default = Opus

        fun ofId(id: String): AssistantModelFamily? = entries.firstOrNull { id.startsWith(it.idPrefix) }
    }
}

/** One model as Anthropic lists it: its id, and the name it goes by ("Claude Opus 5.5"), which is data, shown as it comes. */
@Immutable
data class AssistantModel(val family: AssistantModelFamily, val id: String, val displayName: String?) {
    companion object {
        fun fallback(family: AssistantModelFamily) = AssistantModel(family, family.fallbackId, displayName = null)
    }
}

/** A model the Models API lists, with when it was released (RFC 3339), which is how the newest of a family is told. */
data class ListedModel(val id: String, val displayName: String?, val createdAt: String?)

/**
 * The newest model of each family in [listed], by release date. A family the list has nothing for
 * keeps its [AssistantModelFamily.fallbackId]. A listed model with no readable date ranks below
 * every dated one, so an odd entry can't displace a real release.
 */
fun latestModels(listed: List<ListedModel>): Map<AssistantModelFamily, AssistantModel> =
    AssistantModelFamily.entries.associateWith { family ->
        listed
            .filter { it.id.startsWith(family.idPrefix) }
            .maxByOrNull { model -> model.createdAt?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() } ?: Long.MIN_VALUE }
            ?.let { AssistantModel(family, it.id, it.displayName) }
            ?: AssistantModel.fallback(family)
    }
