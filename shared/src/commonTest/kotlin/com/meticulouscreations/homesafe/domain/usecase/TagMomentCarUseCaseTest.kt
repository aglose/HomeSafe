package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.EventFrame
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentsPaging
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.model.SeenBox
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.model.TrackedObject
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
import com.meticulouscreations.homesafe.domain.repository.ClassifierRepository
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A classifier server for tagging a moment's car: a queue of crops, the detections Frigate still
 * has, a recording to cut a car out of, and a record of every call that changes something.
 */
internal class FakeCarTagClassifiers(
    var queue: List<UnlabeledCrop> = emptyList(),
    var detections: Map<String, MomentEvent> = emptyMap(),
    var knownCars: Map<String, Int> = mapOf("none" to 20, "andrews_tesla" to 12, "sarahs_car" to 6),
) : ClassifierRepository {
    var models = listOf(ClassifierModel("known_cars", listOf("car")))
    var modelsFail = false
    var labelFails = false
    var frameFails = false
    var exampleFails = false
    var nameFails = false
    var trainFails = false
    var datasetReads = 0
    val labelled = mutableListOf<Pair<String, String>>()
    val examples = mutableListOf<Pair<String, SeenBox>>()
    val names = mutableListOf<Pair<String, String?>>()
    var trained = 0
    val frame = EventFrame(byteArrayOf(1, 2, 3), SeenBox(0.4, 0.5, 0.2, 0.15, epochSeconds = 1_789_399_000.0))

    override suspend fun getModels(): Result<List<ClassifierModel>> =
        if (modelsFail) Result.failure(IllegalStateException("offline")) else Result.success(models)

    override suspend fun getDataset(modelName: String): Result<ClassifierDataset> {
        datasetReads++
        return Result.success(ClassifierDataset(models.first(), knownCars, queue = emptyList(), hasTrained = true, newImagesSinceTraining = 0))
    }

    override suspend fun getQueue(modelName: String): Result<List<UnlabeledCrop>> = Result.success(queue)
    override suspend fun getDetection(eventId: String): Result<MomentEvent?> = Result.success(detections[eventId])
    override suspend fun label(modelName: String, fileName: String, category: String): Result<Unit> {
        if (labelFails) return Result.failure(IllegalStateException("crop gone"))
        labelled += fileName to category
        queue = queue.filterNot { it.fileName == fileName }
        return Result.success(Unit)
    }

    override suspend fun getEventFrame(eventId: String): Result<EventFrame> =
        if (frameFails) Result.failure(IllegalStateException("no recording")) else Result.success(frame)

    override suspend fun addExample(modelName: String, category: String, frame: ByteArray, box: SeenBox): Result<Unit> {
        if (exampleFails) return Result.failure(IllegalStateException("relay down"))
        examples += category to box
        return Result.success(Unit)
    }

    override suspend fun nameTrackedObject(eventId: String, subLabel: String?): Result<Unit> {
        if (nameFails) return Result.failure(IllegalStateException("event gone"))
        names += eventId to subLabel
        return Result.success(Unit)
    }

    override suspend fun train(modelName: String): Result<Unit> {
        if (trainFails) return Result.failure(IllegalStateException("already training"))
        trained++
        return Result.success(Unit)
    }

    override suspend fun getTrackedObjects(cameraName: String): Result<List<TrackedObject>> = fail("unused")
    override suspend fun createCategory(modelName: String, category: String): Result<Unit> = fail("a new car needs no separate create")
    override suspend fun discard(modelName: String, fileNames: List<String>): Result<Unit> = fail("unused")
    override suspend fun getLatestFrame(cameraName: String): Result<ByteArray> = fail("unused")
    override fun queueImageUrl(modelName: String, fileName: String): String? = null
}

/** Records the names the tag hands the feed. */
internal class FakeNamingMoments : MomentsRepository {
    val named = mutableListOf<Pair<String, String>>()
    override fun nameCar(eventId: String, subLabel: String) {
        named += eventId to subLabel
    }
    override fun refreshStationaryObjects() = Unit
    override fun observeMoments(): Flow<List<MomentEvent>> = fail("unused")
    override fun observeError(): Flow<String?> = fail("unused")
    override fun observePaging(): Flow<MomentsPaging> = fail("unused")
    override suspend fun loadOlder() = fail("unused")
    override fun showBefore(epochSeconds: Double?) = fail("unused")
    override fun showCamera(cameraName: String?) = fail("unused")
    override fun observeRecentMoments(cameraName: String, limit: Int, lookbackSeconds: Double): Flow<List<MomentEvent>> = fail("unused")
    override fun observeStationaryObjects(): Flow<List<StationaryObject>> = fail("unused")
    override fun observeLatestMoment(): Flow<MomentEvent?> = fail("unused")
    override suspend fun refresh() = fail("unused")
    override suspend fun getClipStream(eventId: String): RecordingStream = fail("unused")
    override suspend fun getClipDownloadUrl(eventId: String): RecordingStream = fail("unused")
}

/**
 * Tagging a past detection's car: which example goes into the dataset (Frigate's own crop while
 * it's queued, else the car cut from the recording), and what follows it — the name, the feed, a
 * retrain — each of which is reported rather than failing the tag.
 */
class TagMomentCarUseCaseTest {

    private val eventId = "1789399000.5-abc123"

    private fun crop(eventId: String, frameEpoch: String, guess: String = "none") =
        UnlabeledCrop.fromFileName("$eventId-$frameEpoch-$guess-0.82.webp")

    @Test
    fun filesTheNewestQueuedCropOfTheCarWhenFrigateStillHasOne() = runTest {
        val older = crop(eventId, "1789399001.0")
        val newer = crop(eventId, "1789399004.0")
        val otherCar = crop("1789399100.1-zzz999", "1789399101.0")
        val classifiers = FakeCarTagClassifiers(queue = listOf(older, otherCar, newer))
        val moments = FakeNamingMoments()

        val outcome = TagMomentCarUseCase(classifiers, moments)(MomentCarTag("known_cars", "sarahs_car", eventId)).getOrThrow()

        assertEquals(listOf(newer.fileName to "sarahs_car"), classifiers.labelled, "the classifier's own look at the car")
        assertTrue(classifiers.examples.isEmpty(), "no recording needed")
        assertEquals(listOf<Pair<String, String?>>(eventId to "sarahs_car"), classifiers.names)
        assertEquals(listOf(eventId to "sarahs_car"), moments.named, "the feed shows the name straight away")
        assertEquals(1, classifiers.trained)
        assertEquals(MomentCarTagOutcome(named = true, trainingStarted = true), outcome)
    }

    @Test
    fun cutsTheCarOutOfTheRecordingOnceItsCropsHaveLeftTheQueue() = runTest {
        val classifiers = FakeCarTagClassifiers(queue = listOf(crop("1789399100.1-zzz999", "1789399101.0")))

        TagMomentCarUseCase(classifiers, FakeNamingMoments())(MomentCarTag("known_cars", "grandmas_van", eventId)).getOrThrow()

        assertTrue(classifiers.labelled.isEmpty())
        assertEquals(listOf("grandmas_van" to classifiers.frame.box), classifiers.examples, "a new car's first example creates its category")
    }

    @Test
    fun aCropThatVanishedBeforeItCouldBeFiledFallsBackToTheRecording() = runTest {
        val classifiers = FakeCarTagClassifiers(queue = listOf(crop(eventId, "1789399001.0"))).apply { labelFails = true }

        TagMomentCarUseCase(classifiers, FakeNamingMoments())(MomentCarTag("known_cars", "sarahs_car", eventId)).getOrThrow()

        assertEquals(listOf("sarahs_car" to classifiers.frame.box), classifiers.examples)
    }

    @Test
    fun withNoExampleNothingElseIsTried() = runTest {
        val classifiers = FakeCarTagClassifiers().apply { frameFails = true }
        val moments = FakeNamingMoments()

        val result = TagMomentCarUseCase(classifiers, moments)(MomentCarTag("known_cars", "sarahs_car", eventId))

        assertEquals("no recording", result.exceptionOrNull()?.message)
        assertTrue(classifiers.names.isEmpty())
        assertTrue(moments.named.isEmpty())
        assertEquals(0, classifiers.trained)
    }

    @Test
    fun aNameOrRetrainThatFailsIsReportedNotFatal() = runTest {
        val classifiers = FakeCarTagClassifiers().apply {
            nameFails = true
            trainFails = true
        }
        val moments = FakeNamingMoments()

        val outcome = TagMomentCarUseCase(classifiers, moments)(MomentCarTag("known_cars", "sarahs_car", eventId)).getOrThrow()

        assertEquals(MomentCarTagOutcome(named = false, trainingStarted = false), outcome)
        assertTrue(moments.named.isEmpty(), "the feed isn't told a name Frigate didn't take")
        assertFalse(classifiers.examples.isEmpty(), "the example itself was saved")
    }
}
