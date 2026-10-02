package com.meticulouscreations.homesafe.finance.domain

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.tone_bright_side_blurb
import homesafe.shared.generated.resources.tone_bright_side_label
import homesafe.shared.generated.resources.tone_straight_blurb
import homesafe.shared.generated.resources.tone_straight_label
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.StringResource

/**
 * The voice the Economy and Risk tabs speak in. Both read the same numbers; what changes is what
 * is said about them.
 */
enum class EconomyTone(val label: StringResource, val blurb: StringResource) {
    /** Numbers first: where each reading stands against its lines and its own history, and what has followed before. No adjectives that aren't backed by a line. */
    STRAIGHT(Res.string.tone_straight_label, Res.string.tone_straight_blurb),

    /** The same numbers, said kindly, with the upsides a reading can bring and what tends to go right. Still honest about the risks. */
    BRIGHT_SIDE(Res.string.tone_bright_side_label, Res.string.tone_bright_side_blurb),
    ;

    companion object {
        val DEFAULT = STRAIGHT

        /** A stored name this build doesn't know (a downgrade after a newer one added a tone) falls back to the default. */
        fun fromName(name: String?): EconomyTone = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The finance app's own preferences, kept on the device. */
interface FinancePreferencesRepository {
    fun observeEconomyTone(): Flow<EconomyTone>

    suspend fun setEconomyTone(tone: EconomyTone)
}
