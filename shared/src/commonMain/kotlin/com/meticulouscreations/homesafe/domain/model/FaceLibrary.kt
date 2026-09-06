package com.meticulouscreations.homesafe.domain.model

/** Someone Frigate can recognise: a folder of face images registered under their name. */
data class KnownPerson(
    /** Frigate's folder key, e.g. `andrew`; also what arrives as an event's sub-label. */
    val name: String,
    /** Registered images, newest first when Frigate's timestamped names allow it. */
    val imageFiles: List<String>,
) {
    val displayName: String get() = subLabelDisplayName(name)
    val imageCount: Int get() = imageFiles.size
}

/**
 * Everything the faces screen shows: who Frigate knows, and the faces it saw recently and is
 * waiting for a person to confirm or name. Attempts reuse [UnlabeledCrop] because Frigate names
 * them the same way as classifier crops (`<eventId>-<frameEpoch>-<guess>-<score>.webp`); a guess
 * of `unknown` means no registered face came close.
 */
data class FaceLibrary(
    val people: List<KnownPerson>,
    /** Newest first. */
    val attempts: List<UnlabeledCrop>,
) {
    val hasPeople: Boolean get() = people.isNotEmpty()

    /** Names the alert rules treat as "familiar" — anyone with at least one registered face. */
    val knownNames: Set<String> get() = people.filter { it.imageCount > 0 }.map { it.name }.toSet()

    companion object {
        val EMPTY = FaceLibrary(people = emptyList(), attempts = emptyList())

        /** Frigate's marker for "a face, but nobody I know" in an attempt's file name. */
        const val UNKNOWN_GUESS = "unknown"

        /** "Andrew" -> `andrew`; "Ron & Judy" -> `ron_judy`. Frigate folder names double as sub-labels, so keep them plain. */
        fun personKey(displayName: String): String =
            displayName.trim().lowercase()
                .replace("'", "")
                .replace(Regex("[^a-z0-9_-]+"), "_")
                .trim('_')
                .ifEmpty { "person" }
    }
}
