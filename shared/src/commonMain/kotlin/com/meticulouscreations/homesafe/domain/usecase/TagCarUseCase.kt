package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.SeenBox
import com.meticulouscreations.homesafe.domain.repository.ClassifierRepository
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import dev.zacsweers.metro.Inject

/** A car someone pointed at on [frame] and said is [category]. */
class CarTag(
    val modelName: String,
    val category: String,
    /** The JPEG the person drew on, exactly as Frigate served it. */
    val frame: ByteArray,
    /** Where the car is on [frame], as fractions. */
    val box: SeenBox,
    /** The event of the tracked object [box] is about, or null when Frigate isn't tracking that car. */
    val trackedEventId: String?,
)

/** What came of a [CarTag] beyond the example itself, which is always saved when the tag succeeds. */
data class CarTagOutcome(
    /** Frigate now calls the tracked car [CarTag.category], so the in-view strip does too. False when nothing was tracked there, or naming it failed. */
    val namedInView: Boolean,
    /** A retrain is running with the new example; false when Frigate turned it down (one already running, or too few categories). */
    val trainingStarted: Boolean,
)

/**
 * Tags a car on a camera frame (see [com.meticulouscreations.homesafe.domain.model.CarTagging]):
 * adds the boxed car to the classifier's dataset, names the tracked object it is about, retrains,
 * and asks the in-view strip to look again.
 *
 * Only the example is essential. Without it nothing was learnt, so its failure fails the tag and
 * nothing else is tried; the naming and the retrain are each worth having without the other and
 * are reported rather than failed. Tagged `none` ("not ours") clears the tracked car's name
 * instead of setting one: `none` is a category for training and never a sub-label.
 */
@Inject
class TagCarUseCase(
    private val classifierRepository: ClassifierRepository,
    private val momentsRepository: MomentsRepository,
) {
    suspend operator fun invoke(tag: CarTag): Result<CarTagOutcome> {
        classifierRepository.addExample(tag.modelName, tag.category, tag.frame, tag.box).onFailure { return Result.failure(it) }
        val subLabel = tag.category.takeUnless { it == ClassifierDataset.NONE_CATEGORY }
        val named = tag.trackedEventId?.let { classifierRepository.nameTrackedObject(it, subLabel).isSuccess } ?: false
        val trained = classifierRepository.train(tag.modelName).isSuccess
        if (named) momentsRepository.refreshStationaryObjects()
        return Result.success(CarTagOutcome(namedInView = named, trainingStarted = trained))
    }
}
