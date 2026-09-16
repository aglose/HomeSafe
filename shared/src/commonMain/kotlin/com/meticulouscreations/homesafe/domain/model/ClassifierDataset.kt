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
    /**
     * The model is certain of its guess: a `none-1.0` crop of a passing street car, or a 1.0 of a
     * car it already knows. Not worth a person's time by default — Frigate saves a crop on every
     * frame it classifies, so these flood the queue — but kept reachable rather than deleted: a
     * brand-new car's crops look exactly like this, and the queue is the only place it can be named.
     */
    val isConfident: Boolean get() = guessedScore != null && guessedScore >= CONFIDENT_SCORE

    companion object {
        /** Frigate rounds scores to two decimals, so 1.0 is what "100 %" looks like. */
        const val CONFIDENT_SCORE = 1.0

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
    /** Every crop waiting for a label, newest first; see [uncertainQueue] and [confidentQueue]. */
    val queue: List<UnlabeledCrop>,
    val hasTrained: Boolean,
    /** Labelled images added since the last training; training is worthwhile when this is > 0. */
    val newImagesSinceTraining: Int,
) {
    /** The crops worth a person's eye: the model's guess is under 100 %, or it has no guess. */
    val uncertainQueue: List<UnlabeledCrop> get() = queue.filterNot { it.isConfident }

    /** The crops the model is sure about, shown on request so a new car can still be labelled from one. */
    val confidentQueue: List<UnlabeledCrop> get() = queue.filter { it.isConfident }

    /**
     * Every category a crop can be filed under, `none` last.
     *
     * Wider than [categoryCounts] on purpose, because a category with no images on the server is
     * still a category a person can file into — Frigate creates the folder on the first crop that
     * lands there. Without that the labelling screen shows a queue of crops and nothing to tap:
     * `none` can never be typed into the new-category box (it would slug to `not_ours`), and a
     * model whose dataset has been cleared keeps classifying into names the dataset no longer
     * lists.
     */
    val categories: List<String>
        get() = (categoryCounts.keys + NONE_CATEGORY + queue.mapNotNull { it.guessedCategory }.filter { isNameable(it) })
            .sortedWith(compareBy({ it == NONE_CATEGORY }, { it }))

    /** Frigate needs two classes; `none` counts as one. */
    val canTrain: Boolean get() = categoryCounts.count { it.value > 0 } >= 2

    companion object {
        /** Frigate's reserved category: "one of these objects, but not one we care about". Never becomes a sub-label. */
        const val NONE_CATEGORY = "none"

        /** What Frigate writes into a crop's file name when the model has no opinion yet. */
        const val UNKNOWN_GUESS = "unknown"

        /** A guess worth offering as a category of its own: a real name, not a placeholder. */
        private fun isNameable(category: String): Boolean =
            category.isNotBlank() && category != UNKNOWN_GUESS && category != NONE_CATEGORY
    }
}
