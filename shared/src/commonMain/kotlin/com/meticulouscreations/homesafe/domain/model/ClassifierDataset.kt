package com.meticulouscreations.homesafe.domain.model

/** One of Frigate's custom classification models, e.g. `known_cars` labelling `car` objects. */
data class ClassifierModel(
    /** Config key, also the folder name on the server. */
    val name: String,
    /** Object labels it runs on (`car`), empty for a state model. */
    val objects: List<String>,
    val enabled: Boolean = true,
) {
    val displayName: String get() = subLabelDisplayName(name)
}

/** A crop Frigate saved from a live detection, waiting for a person to say what it is. */
data class UnlabeledCrop(
    val fileName: String,
    /** The tracked object it came from; null for crops Frigate mined from history. */
    val eventId: String?,
    /** When the frame was captured, epoch seconds; null when unknown. */
    val capturedEpochSeconds: Double?,
    /** What the current model guessed, e.g. "sarahs_tesla" / "none" / "unknown" (untrained). */
    val guessedCategory: String?,
    val guessedScore: Double?,
) {
    companion object {
        /**
         * Frigate names live attempts `<eventId>-<frameEpoch>-<category>-<score>.webp`, where the
         * event id itself is `<epoch>-<6 chars>`, and history-mined examples `example_NNN.jpg`.
         */
        fun fromFileName(fileName: String): UnlabeledCrop {
            val base = fileName.substringBeforeLast('.')
            val parts = base.split('-')
            // eventEpoch, eventSuffix, frameEpoch, category..., score
            if (parts.size >= 5) {
                val score = parts.last().toDoubleOrNull()
                val frame = parts[2].toDoubleOrNull()
                if (score != null && frame != null) {
                    return UnlabeledCrop(
                        fileName = fileName,
                        eventId = "${parts[0]}-${parts[1]}",
                        capturedEpochSeconds = frame,
                        guessedCategory = parts.subList(3, parts.size - 1).joinToString("-"),
                        guessedScore = score,
                    )
                }
            }
            return UnlabeledCrop(fileName, eventId = null, capturedEpochSeconds = null, guessedCategory = null, guessedScore = null)
        }
    }
}

/** Everything the labelling screen shows for one model. */
data class ClassifierDataset(
    val model: ClassifierModel,
    /** Category -> number of labelled images. Includes `none`. */
    val categoryCounts: Map<String, Int>,
    /** Newest first. */
    val queue: List<UnlabeledCrop>,
    val hasTrained: Boolean,
    /** Labelled images added since the last training; training is worthwhile when this is > 0. */
    val newImagesSinceTraining: Int,
) {
    val categories: List<String> get() = categoryCounts.keys.sortedWith(compareBy({ it == NONE_CATEGORY }, { it }))

    /** Frigate needs two classes; `none` counts as one. */
    val canTrain: Boolean get() = categoryCounts.count { it.value > 0 } >= 2

    companion object {
        /** Frigate's reserved category: "one of these objects, but not one we care about". Never becomes a sub-label. */
        const val NONE_CATEGORY = "none"
    }
}
