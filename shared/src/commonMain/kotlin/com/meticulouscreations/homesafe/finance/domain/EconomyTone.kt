package com.meticulouscreations.homesafe.finance.domain

import kotlinx.coroutines.flow.Flow

/**
 * The voice the Economy and Risk tabs speak in. Both read the same numbers; what changes is what
 * is said about them.
 */
enum class EconomyTone(val label: String, val blurb: String) {
    /** Numbers first: where each reading stands against its lines and its own history, and what has followed before. No adjectives that aren't backed by a line. */
    STRAIGHT(
        "Straight talk",
        "Just the numbers: where each reading sits against its warning lines and its own history, and what has tended to follow. No cheerleading.",
    ),

    /** The same numbers, said kindly, with the upsides a reading can bring and what tends to go right. Still honest about the risks. */
    BRIGHT_SIDE(
        "Bright side",
        "The same numbers, with the upsides they can bring and what has tended to go right. Still pragmatic about the risks.",
    ),
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
