package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.repository.ClassifierRepository
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import dev.zacsweers.metro.Inject

/** A detection's car — one the classifier left as plain "Car" — that someone said is [category]. */
data class MomentCarTag(
    val modelName: String,
    /** An existing known car, or a new one's key (see [com.meticulouscreations.homesafe.domain.model.CarTagging.knownCarKey]). */
    val category: String,
    val eventId: String,
)

/** What came of a [MomentCarTag] beyond the example itself, which is always saved when the tag succeeds. */
data class MomentCarTagOutcome(
    /** Frigate now calls the detection [MomentCarTag.category], so the feed and the camera screen do too. */
    val named: Boolean,
    /** A retrain is running with the new example; false when Frigate turned it down (one already running, or too few categories). */
    val trainingStarted: Boolean,
)

/**
 * Tags the car of a past detection — a moment, or the one a notification opened — the way
 * [TagCarUseCase] tags one on a live frame: an example into the classifier's dataset, the
 * detection named, a retrain.
 *
 * The example is Frigate's own crop of the car when one is still queued for it: that is exactly
 * what the classifier looked at, and filing it is what the labelling screen does. The queue keeps
 * only the newest couple of hundred crops, which a busy street turns over in minutes, so for an
 * older moment the car is cut out of the recording instead, through the same relay route the live
 * tagging uses. A category that doesn't exist yet needs no separate step: both routes create the
 * folder with the first example that lands in it, so a new known car never exists empty, even
 * when its first example fails.
 *
 * Only the example is essential; without it nothing was learnt, so its failure fails the tag.
 */
@Inject
class TagMomentCarUseCase(
    private val classifierRepository: ClassifierRepository,
    private val momentsRepository: MomentsRepository,
) {
    suspend operator fun invoke(tag: MomentCarTag): Result<MomentCarTagOutcome> {
        addExample(tag).onFailure { return Result.failure(it) }
        val named = classifierRepository.nameTrackedObject(tag.eventId, tag.category).isSuccess
        if (named) momentsRepository.nameCar(tag.eventId, tag.category)
        val trained = classifierRepository.train(tag.modelName).isSuccess
        return Result.success(MomentCarTagOutcome(named = named, trainingStarted = trained))
    }

    private suspend fun addExample(tag: MomentCarTag): Result<Unit> {
        // A failed read of the queue only costs the better example; the recording still has the car.
        val crop = classifierRepository.getQueue(tag.modelName).getOrNull().orEmpty()
            .filter { it.eventId == tag.eventId }
            .maxByOrNull { it.capturedEpochSeconds ?: 0.0 }
        if (crop != null && classifierRepository.label(tag.modelName, crop.fileName, tag.category).isSuccess) return Result.success(Unit)
        val frame = classifierRepository.getEventFrame(tag.eventId).getOrElse { return Result.failure(it) }
        return classifierRepository.addExample(tag.modelName, tag.category, frame.jpeg, frame.box)
    }
}
