package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.FitnessRecordedRunEntity
import com.meticulouscreations.homesafe.data.FitnessRunEventEntity
import com.meticulouscreations.homesafe.data.FitnessSyncDao
import com.meticulouscreations.homesafe.data.FitnessSyncRowEntity
import com.meticulouscreations.homesafe.data.InMemoryFitnessDao
import com.meticulouscreations.homesafe.data.InMemoryFitnessRunDao
import com.meticulouscreations.homesafe.data.InMemoryFitnessSyncDao
import com.meticulouscreations.homesafe.finance.FakeServerConnection
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.BodyweightEntry
import com.meticulouscreations.homesafe.fitness.domain.Equipment
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.FitnessSyncStatus
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSummary
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.Muscle
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.SetDraft
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Several installs against one server that behaves as `fitness_sync` in `relay/relay.py` does (and `FitnessSyncTest` holds that to). */
class FitnessLogSyncImplTest {

    /** The relay's side, row for row: sorted keys in, the last to send wins, tombstones kept, pages, and the answer read before the send is saved. */
    private class FakeRelay : FitnessSyncApi {
        class Held(val data: JsonObject?, val rev: Long)

        val logs = HashMap<String, LinkedHashMap<Pair<String, String>, Held>>()
        var signedIn = "andrew"
        var answering = true
        var pageRows = 1_000
        var requests = 0
        val sent = ArrayList<FitnessSyncRow>()

        /** Happens on the device while the answer to a request is on its way back. */
        var whileAnswering: (suspend () -> Unit)? = null

        fun log(owner: String = signedIn) = logs.getOrPut(owner) { LinkedHashMap() }

        fun rowsOf(kind: String, owner: String = signedIn) = log(owner).filter { it.key.first == kind && it.value.data != null }

        override suspend fun sync(url: String, request: FitnessSyncRequest): FitnessSyncAnswer {
            requests++
            check(answering) { "The relay answered 500" }
            val owner = signedIn
            if (request.owner != null && request.owner != owner) return FitnessSyncAnswer(owner)
            val log = log()
            var head = log.values.maxOfOrNull { it.rev } ?: 0L
            if (request.since > head || (request.since == 0L && head > 0 && request.changes.isNotEmpty())) return FitnessSyncAnswer(owner, reset = true)
            val after = log.entries.filter { it.value.rev > request.since }.sortedBy { it.value.rev }
            val page = after.take(pageRows)
            val more = page.size < after.size
            val rows = page.map { (key, held) -> FitnessSyncRow(key.first, key.second, held.data) }
            val reached = page.lastOrNull()?.value?.rev ?: request.since
            for (change in request.changes) {
                sent += change
                val key = change.kind to change.id
                val held = log[key]
                if ((held != null && held.data == change.data) || (held == null && change.data == null)) continue
                log.remove(key)
                log[key] = Held(change.data, ++head)
            }
            whileAnswering?.invoke()
            return FitnessSyncAnswer(owner, rev = if (more) reached else head, more = more, rows = rows)
        }
    }

    private class Install(relay: FakeRelay, scope: CoroutineScope, url: String? = URL, firstId: Long? = null, val agreedDao: FitnessSyncDao = InMemoryFitnessSyncDao()) {
        val logDao = InMemoryFitnessDao()
        val runDao = InMemoryFitnessRunDao()
        val log = if (firstId == null) FitnessRepositoryImpl(logDao) else FitnessRepositoryImpl(logDao) { firstId }
        val connection = FakeServerConnection(url)
        val sync = FitnessLogSyncImpl(log, runDao, agreedDao, relay, connection, scope)

        suspend fun logSet(exercise: String = SQUAT.id, weight: Double = 225.0, reps: Int = 5, at: Long = 1_000, workout: Long? = null) =
            log.addSets(listOf(SetDraft(exercise, weight, reps, at, workoutId = workout))).single()

        suspend fun sets() = log.sets.first().map { Triple(it.exerciseId, it.weight, it.reps) }.sortedBy { it.toString() }
    }

    private fun TestScope.install(relay: FakeRelay, url: String? = URL, firstId: Long? = null, agreedDao: FitnessSyncDao = InMemoryFitnessSyncDao()) =
        Install(relay, backgroundScope, url, firstId, agreedDao)

    private fun run(id: String, start: Long = 1_000) = FitnessRecordedRunEntity(id, start, start + 600_000, -14_400, 1_609.0, 590_000)

    private fun events(id: String, count: Int = 3) = List(count) { FitnessRunEventEntity(id, it, "POSITION", 1_000L + it, 40.0 + it, -75.0, 5.0) }

    @Test
    fun signedOutTheLogStaysHereAndNothingIsAsked() = runTest {
        val relay = FakeRelay()
        val phone = install(relay, url = null)
        phone.log.saveExercises(listOf(SQUAT))
        phone.sync.syncNow()
        assertEquals(FitnessSyncStatus.SIGNED_OUT, phone.sync.status.value)
        assertEquals(0, relay.requests)
    }

    @Test
    fun aLogAlreadyOnAPhoneGoesToAnEmptyServerAndEveryPartOfItArrivesOnTheNextDevice() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        val workout = phone.log.startWorkout(WorkoutFocus.LEGS, 900)
        phone.logSet(workout = workout.id)
        phone.log.saveHeartSummary(workout.id, HeartSummary(1, List(5) { it + 2L }, 3, 150))
        phone.log.finishWorkout(workout.id, 2_000)
        phone.log.startPhase(PhaseKind.BULK, 500)
        phone.log.saveBodyweight(BodyweightEntry(20_000, 180.5))
        phone.log.saveHeartProfile(HeartProfile(maxBpm = 190, restingBpm = 55))
        phone.runDao.writeRun(run("r1"), events("r1"))

        phone.sync.syncNow()
        assertEquals(FitnessSyncStatus.SAVED, phone.sync.status.value)

        val tablet = install(relay)
        tablet.sync.syncNow()
        assertEquals(phone.log.exercises.first(), tablet.log.exercises.first())
        assertEquals(phone.log.sets.first(), tablet.log.sets.first())
        assertEquals(phone.log.workouts.first(), tablet.log.workouts.first())
        assertEquals(phone.log.phases.first(), tablet.log.phases.first())
        assertEquals(phone.log.bodyweights.first(), tablet.log.bodyweights.first())
        assertEquals(phone.log.heartSummaries.first(), tablet.log.heartSummaries.first())
        assertEquals(HeartProfile(maxBpm = 190, restingBpm = 55), tablet.log.heartSettings.first().profile)
        assertEquals(listOf(run("r1")), tablet.runDao.observeFinished().first())
        assertEquals(events("r1"), tablet.runDao.events("r1"))
        assertEquals(FitnessSyncStatus.SAVED, tablet.sync.status.value)
    }

    @Test
    fun nothingChangedMeansNothingIsSentOrWritten() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.logSet()
        phone.sync.syncNow()
        val sent = relay.sent.size
        phone.sync.syncNow()
        phone.sync.syncNow()
        assertEquals(sent, relay.sent.size)
    }

    @Test
    fun aChangeOnEitherDeviceReachesTheOther() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        val tablet = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.sync.syncNow()
        tablet.sync.syncNow()

        tablet.logSet(weight = 245.0)
        tablet.sync.syncNow()
        phone.sync.syncNow()
        assertEquals(listOf(Triple(SQUAT.id, 245.0, 5)), phone.sets())

        val set = phone.log.sets.first().single()
        phone.log.updateSet(set.copy(reps = 7))
        phone.sync.syncNow()
        tablet.sync.syncNow()
        assertEquals(listOf(Triple(SQUAT.id, 245.0, 7)), tablet.sets())
    }

    @Test
    fun setsLoggedApartOnTwoDevicesAreBothKept() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.sync.syncNow()
        val tablet = install(relay)
        tablet.sync.syncNow()

        val here = phone.logSet(weight = 225.0, at = 2_000)
        val there = tablet.logSet(weight = 315.0, at = 2_000)
        assertNotEquals(here.id, there.id, "logged apart in the same moment, and each still its own row")

        phone.sync.syncNow()
        tablet.sync.syncNow()
        phone.sync.syncNow()
        val both = listOf(Triple(SQUAT.id, 225.0, 5), Triple(SQUAT.id, 315.0, 5))
        assertEquals(both, phone.sets())
        assertEquals(both, tablet.sets())
    }

    @Test
    fun whatIsDeletedOnOneDeviceGoesFromTheOthers() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        val workout = phone.log.startWorkout(WorkoutFocus.LEGS, 900)
        phone.logSet(workout = workout.id)
        phone.log.saveHeartSummary(workout.id, HeartSummary(peakBpm = 150))
        phone.log.finishWorkout(workout.id, 2_000)
        phone.log.saveBodyweight(BodyweightEntry(20_000, 180.5))
        phone.runDao.writeRun(run("r1"), events("r1"))
        phone.sync.syncNow()
        val tablet = install(relay)
        tablet.sync.syncNow()

        phone.log.deleteWorkout(workout.id)
        phone.log.deleteBodyweight(20_000)
        phone.runDao.removeRun("r1")
        phone.sync.syncNow()
        tablet.sync.syncNow()
        assertEquals(emptyList(), tablet.log.workouts.first())
        assertEquals(emptyList(), tablet.log.sets.first())
        assertEquals(emptyMap(), tablet.log.heartSummaries.first())
        assertEquals(emptyList(), tablet.log.bodyweights.first())
        assertEquals(emptyList(), tablet.runDao.observeFinished().first())
        assertEquals(emptyList(), tablet.runDao.events("r1"))
        assertEquals(1, tablet.log.exercises.first().size, "and what wasn't deleted stays")
    }

    @Test
    fun aSecondInstallWithItsOwnLogIsMergedAndNeitherLogLosesAnythingToTheOthersIds() = runTest {
        val relay = FakeRelay()
        val phone = install(relay, firstId = 1)
        phone.log.saveExercises(listOf(SQUAT))
        val first = phone.logSet(weight = 225.0, at = 1_000)
        phone.sync.syncNow()

        // Set 1 here too, which is another set altogether, and set 2 is the phone's set again.
        val second = install(relay, firstId = 1)
        second.log.saveExercises(listOf(SQUAT, BENCH))
        val own = second.logSet(exercise = BENCH.id, weight = 135.0, reps = 10, at = 1_100)
        second.logSet(weight = 225.0, at = 1_000)
        assertEquals(first.id, own.id)

        second.sync.syncNow()
        phone.sync.syncNow()
        val both = listOf(Triple(BENCH.id, 135.0, 10), Triple(SQUAT.id, 225.0, 5))
        assertEquals(both, second.sets())
        assertEquals(both, phone.sets())
        assertEquals(setOf(SQUAT, BENCH), phone.log.exercises.first().toSet())
    }

    @Test
    fun theSecondInstallBringingItsLogInTwiceStillAddsItOnce() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.logSet()
        phone.sync.syncNow()
        val second = install(relay)
        second.log.saveExercises(listOf(SQUAT))
        second.logSet(weight = 315.0)
        second.sync.syncNow()
        // As though killed after writing the log and before noting that it had.
        second.agreedDao.forget()
        second.sync.syncNow()
        phone.sync.syncNow()
        assertEquals(2, second.log.sets.first().size)
        assertEquals(2, phone.log.sets.first().size)
    }

    @Test
    fun aWorkoutOpenHereKeepsItsIdUntilItCanBeBroughtTogether() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.logSet()
        phone.sync.syncNow()

        val second = install(relay)
        second.log.saveExercises(listOf(SQUAT))
        val open = second.log.startWorkout(WorkoutFocus.LEGS, Clock.System.now().epochSeconds)
        second.logSet(workout = open.id, weight = 315.0, at = 2_100)
        second.sync.syncNow()
        assertEquals(FitnessSyncStatus.WAITING, second.sync.status.value)
        assertEquals(open.id, second.log.workouts.first().single().id)
        assertEquals(listOf(Triple(SQUAT.id, 315.0, 5)), second.sets())

        second.log.finishWorkout(open.id, Clock.System.now().epochSeconds)
        second.sync.syncNow()
        assertEquals(FitnessSyncStatus.SAVED, second.sync.status.value)
        assertEquals(2, second.log.sets.first().size)
        phone.sync.syncNow()
        assertEquals(1, phone.log.workouts.first().size)
    }

    @Test
    fun aWorkoutStartedOnOneDeviceIsTheOneOpenOnTheOther() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.sync.syncNow()
        val tablet = install(relay)
        tablet.sync.syncNow()

        val open = phone.log.startWorkout(WorkoutFocus.LEGS, 2_000)
        phone.sync.syncNow()
        tablet.sync.syncNow()
        assertEquals(open.id, tablet.log.startWorkout(WorkoutFocus.CHEST, 2_100).id, "there is only ever one")
    }

    @Test
    fun theSensorChosenOnADeviceStaysThereAndTheZonesTravel() = runTest {
        val relay = FakeRelay()
        val band = HeartSensor("AA:BB", "Phone's band")
        val phone = install(relay)
        phone.log.saveHeartSensor(band)
        phone.log.saveHeartProfile(HeartProfile(maxBpm = 190))
        phone.sync.syncNow()

        val other = HeartSensor("CC:DD", "Tablet's band")
        val tablet = install(relay)
        tablet.log.saveHeartSensor(other)
        tablet.sync.syncNow()
        assertEquals(other, tablet.log.heartSettings.first().sensor)
        assertEquals(190, tablet.log.heartSettings.first().profile.maxBpm)

        tablet.log.saveHeartProfile(HeartProfile(maxBpm = 185))
        tablet.sync.syncNow()
        phone.sync.syncNow()
        assertEquals(185, phone.log.heartSettings.first().profile.maxBpm)
        assertEquals(band, phone.log.heartSettings.first().sensor)
        assertFalse(relay.rowsOf(FitnessSyncRows.HEART_PROFILE).values.single().data.toString().contains("AA:BB"))
    }

    @Test
    fun theLastDeviceToTellTheServerWinsARowChangedInTwoPlaces() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.sync.syncNow()
        val tablet = install(relay)
        tablet.sync.syncNow()

        tablet.log.saveExercises(listOf(SQUAT.copy(note = "from the tablet")))
        tablet.sync.syncNow()
        phone.log.saveExercises(listOf(SQUAT.copy(note = "from the phone")))
        phone.sync.syncNow()
        tablet.sync.syncNow()
        assertEquals("from the phone", phone.log.exercises.first().single().note)
        assertEquals("from the phone", tablet.log.exercises.first().single().note)
    }

    @Test
    fun aRowChangedHereWhileTheAnswerWasOnItsWayStaysAsItIsAndGoesNext() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.sync.syncNow()
        val tablet = install(relay)
        tablet.sync.syncNow()
        tablet.log.saveExercises(listOf(SQUAT.copy(note = "from the tablet")))
        tablet.sync.syncNow()

        // The tablet's note is in the answer to this request, and the phone is edited while it is on its way.
        relay.whileAnswering = {
            relay.whileAnswering = null
            phone.log.saveExercises(listOf(SQUAT.copy(note = "typed while waiting")))
        }
        phone.sync.syncNow()
        assertEquals("typed while waiting", phone.log.exercises.first().single().note)

        phone.sync.syncNow()
        tablet.sync.syncNow()
        assertEquals("typed while waiting", tablet.log.exercises.first().single().note)
    }

    @Test
    fun aSetLoggedWhileAPassIsOnItsWayIsKeptAndSent() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.logSet()
        relay.whileAnswering = {
            relay.whileAnswering = null
            phone.logSet(weight = 315.0, at = 2_000)
        }
        phone.sync.syncNow()
        phone.sync.syncNow()
        assertEquals(2, phone.log.sets.first().size)
        assertEquals(2, relay.rowsOf(FitnessSyncRows.SET).size)
    }

    @Test
    fun aDeletionWhileAPassIsOnItsWayIsNotUndoneByTheAnswer() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        val set = phone.logSet()
        phone.sync.syncNow()
        val tablet = install(relay)
        tablet.sync.syncNow()
        tablet.log.updateSet(tablet.log.sets.first().single().copy(reps = 9))
        tablet.sync.syncNow()

        relay.whileAnswering = {
            relay.whileAnswering = null
            phone.log.deleteSet(set.id)
        }
        phone.sync.syncNow()
        assertEquals(emptyList(), phone.log.sets.first())
        phone.sync.syncNow()
        tablet.sync.syncNow()
        assertEquals(emptyList(), tablet.log.sets.first())
    }

    @Test
    fun aServerThatCantBeReachedLeavesTheLogWaitingAndItGoesWhenItAnswers() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.sync.syncNow()

        phone.logSet()
        relay.answering = false
        phone.sync.syncNow()
        assertEquals(FitnessSyncStatus.WAITING, phone.sync.status.value)
        assertEquals(1, phone.log.sets.first().size, "the log carries on without it")

        relay.answering = true
        phone.sync.syncNow()
        assertEquals(FitnessSyncStatus.SAVED, phone.sync.status.value)
        assertEquals(1, relay.rowsOf(FitnessSyncRows.SET).size)
    }

    @Test
    fun theAppSayingTheServerIsUnreachableIsNotAsked() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.connection.serverUnreachable.value = true
        phone.sync.syncNow()
        assertEquals(FitnessSyncStatus.WAITING, phone.sync.status.value)
        assertEquals(0, relay.requests)
    }

    @Test
    fun aLongLogComesInPagesAndAllOfItArrives() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        repeat(25) { phone.logSet(at = 1_000L + it, reps = it + 1) }
        phone.sync.syncNow()

        relay.pageRows = 4
        val tablet = install(relay)
        tablet.sync.syncNow()
        assertEquals(25, tablet.log.sets.first().size)

        // And changes that come as several pages to a device already in step.
        repeat(10) { phone.logSet(at = 2_000L + it, reps = it + 1) }
        relay.pageRows = 1_000
        phone.sync.syncNow()
        relay.pageRows = 3
        tablet.sync.syncNow()
        assertEquals(35, tablet.log.sets.first().size)
        assertEquals(FitnessSyncStatus.SAVED, tablet.sync.status.value)
    }

    @Test
    fun aLongLogGoesInBatches() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.log.addSets(List(4_500) { SetDraft(SQUAT.id, 100.0, it % 20 + 1, 1_000L + it) })
        phone.sync.syncNow()
        assertTrue(relay.requests > 2, "it was ${relay.requests}")
        assertEquals(4_500, relay.rowsOf(FitnessSyncRows.SET).size)
        assertEquals(FitnessSyncStatus.SAVED, phone.sync.status.value)
    }

    @Test
    fun aRecordedRunIsComparedWithoutItsPositionsButGoesWithThem() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.runDao.writeRun(run("r1"), events("r1", count = 50))
        phone.sync.syncNow()
        val sent = relay.sent.single { it.kind == FitnessSyncRows.RUN }
        assertTrue(sent.data.toString().contains("\"events\""))
        val tablet = install(relay)
        tablet.sync.syncNow()
        assertEquals(50, tablet.runDao.events("r1").size)
        tablet.sync.syncNow()
        phone.sync.syncNow()
        assertEquals(1, relay.sent.count { it.kind == FitnessSyncRows.RUN }, "and isn't sent again by either")
    }

    @Test
    fun aRunStillBeingRecordedStaysHereUntilItIsFinished() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.runDao.upsertRun(run("r1").copy(endEpochMillis = null))
        phone.sync.syncNow()
        assertEquals(0, relay.rowsOf(FitnessSyncRows.RUN).size)
        phone.runDao.upsertRun(run("r1"))
        phone.sync.syncNow()
        assertEquals(1, relay.rowsOf(FitnessSyncRows.RUN).size)
    }

    @Test
    fun aRunDeletedElsewhereWhileAnInstallWasNeverSyncedStaysDeleted() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.runDao.writeRun(run("r1"), events("r1"))
        phone.sync.syncNow()
        phone.runDao.removeRun("r1")
        phone.sync.syncNow()

        // Say it reached this install some other way before its first pass.
        val tablet = install(relay)
        tablet.runDao.writeRun(run("r1"), events("r1"))
        tablet.sync.syncNow()
        assertEquals(emptyList(), tablet.runDao.observeFinished().first())
        assertNull(relay.log()[FitnessSyncRows.RUN to "r1"]?.data)
    }

    @Test
    fun theAppsDatabaseBeingThrownAwayDoesNotDeleteTheLogEverywhere() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        repeat(25) { phone.logSet(at = 1_000L + it, reps = it + 1) }
        phone.sync.syncNow()

        // A migration with no path takes every table of the app's database, and this install's other database knows nothing of it.
        val log = phone.log.sets.first().map { it.id }
        phone.log.deleteExercise(SQUAT.id)
        assertEquals(emptyList(), phone.log.sets.first())
        phone.sync.syncNow()

        assertEquals(1, relay.rowsOf(FitnessSyncRows.EXERCISE).size)
        assertEquals(25, relay.rowsOf(FitnessSyncRows.SET).size)
        assertEquals(log.toSet(), phone.log.sets.first().map { it.id }.toSet(), "and it comes back from the server")
    }

    @Test
    fun aLogDeletedARowAtATimeStaysDeleted() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.logSet()
        phone.sync.syncNow()
        phone.log.deleteExercise(SQUAT.id)
        phone.sync.syncNow()
        assertEquals(emptyList(), phone.log.exercises.first())
        assertEquals(0, relay.rowsOf(FitnessSyncRows.EXERCISE).size)
        assertEquals(0, relay.rowsOf(FitnessSyncRows.SET).size)
    }

    @Test
    fun aServerThatHasLostTheLogIsGivenItAgain() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.logSet()
        phone.sync.syncNow()

        relay.logs.clear()
        phone.sync.syncNow()
        assertEquals(1, relay.rowsOf(FitnessSyncRows.EXERCISE).size)
        assertEquals(1, relay.rowsOf(FitnessSyncRows.SET).size)
        assertEquals(1, phone.log.sets.first().size)
        assertEquals(FitnessSyncStatus.SAVED, phone.sync.status.value)
    }

    @Test
    fun someoneElseSigningInWithUnsavedChangesLeavesTheLogAloneAndSendsNothing() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.sync.syncNow()
        phone.logSet()

        relay.signedIn = "sarah"
        phone.sync.syncNow()
        assertEquals(FitnessSyncStatus.HELD_BACK, phone.sync.status.value)
        assertEquals(1, phone.log.sets.first().size)
        assertEquals(emptyMap(), relay.log("sarah").toMap())

        relay.signedIn = "andrew"
        phone.sync.syncNow()
        assertEquals(FitnessSyncStatus.SAVED, phone.sync.status.value)
        assertEquals(1, relay.rowsOf(FitnessSyncRows.SET, "andrew").size)
    }

    @Test
    fun someoneElseSigningInWithEverythingSavedSeesTheirOwnLog() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.logSet()
        phone.runDao.writeRun(run("r1"), events("r1"))
        phone.log.saveHeartSensor(HeartSensor("AA:BB", "Band"))
        phone.sync.syncNow()

        relay.signedIn = "sarah"
        relay.log("sarah")[FitnessSyncRows.WEIGH_IN to "20000"] = FakeRelay.Held(Json.parseToJsonElement("""{"pounds":130.0}""").jsonObject, 1)
        phone.sync.syncNow()
        assertEquals(FitnessSyncStatus.SAVED, phone.sync.status.value)
        assertEquals(emptyList(), phone.log.exercises.first())
        assertEquals(emptyList(), phone.log.sets.first())
        assertEquals(emptyList(), phone.runDao.observeFinished().first())
        assertEquals(listOf(BodyweightEntry(20_000, 130.0)), phone.log.bodyweights.first())
        assertEquals("Band", phone.log.heartSettings.first().sensor?.name, "the device's band is still the device's")
        assertEquals(1, relay.rowsOf(FitnessSyncRows.EXERCISE, "andrew").size, "and the other's is still theirs on the server")
    }

    @Test
    fun aWorkoutWithTheSameIdThatIsAnotherWorkoutStillKeepsItsIdUntilItCloses() = runTest {
        val relay = FakeRelay()
        val phone = install(relay, firstId = 1)
        phone.log.saveExercises(listOf(SQUAT))
        val theirs = phone.log.startWorkout(WorkoutFocus.CHEST, 900)
        phone.logSet(workout = theirs.id)
        phone.log.finishWorkout(theirs.id, 1_000)
        phone.sync.syncNow()

        val second = install(relay, firstId = 1)
        second.log.saveExercises(listOf(SQUAT))
        val open = second.log.startWorkout(WorkoutFocus.LEGS, Clock.System.now().epochSeconds)
        assertEquals(theirs.id, open.id)
        second.sync.syncNow()
        assertEquals(FitnessSyncStatus.WAITING, second.sync.status.value)
        assertEquals(WorkoutFocus.LEGS, second.log.workouts.first().single { it.id == open.id }.focus)
    }

    @Test
    fun aWorkoutLeftOpenAndWalkedAwayFromDoesNotHoldTheLogBackForEver() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.sync.syncNow()

        val second = install(relay)
        second.log.saveExercises(listOf(SQUAT))
        second.log.startWorkout(WorkoutFocus.LEGS, 2_000)
        second.sync.syncNow()
        assertEquals(FitnessSyncStatus.SAVED, second.sync.status.value)
    }

    @Test
    fun twoFirstSyncsAtOnceCantOverwriteEachOthersRows() = runTest {
        val relay = FakeRelay()
        val phone = install(relay, firstId = 1)
        phone.log.saveExercises(listOf(SQUAT))
        phone.logSet(weight = 225.0, at = 1_000)
        val second = install(relay, firstId = 1)
        second.log.saveExercises(listOf(SQUAT))
        second.logSet(weight = 315.0, at = 2_000)

        // The second hears an empty log, and the phone's set 1 lands before it sends its own set 1.
        relay.whileAnswering = {
            relay.whileAnswering = null
            phone.sync.syncNow()
        }
        second.sync.syncNow()
        assertEquals(FitnessSyncStatus.SAVED, second.sync.status.value)
        phone.sync.syncNow()
        val both = listOf(Triple(SQUAT.id, 225.0, 5), Triple(SQUAT.id, 315.0, 5))
        assertEquals(2, relay.rowsOf(FitnessSyncRows.SET).size)
        assertEquals(both, second.sets())
        assertEquals(both, phone.sets())
    }

    @Test
    fun pagesFromTwoLoginsAreNeverTakenForOneLog() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        repeat(3) { phone.logSet(at = 1_000L + it, reps = it + 1) }
        phone.sync.syncNow()

        relay.pageRows = 1
        relay.whileAnswering = {
            relay.whileAnswering = null
            relay.signedIn = "sarah"
        }
        val tablet = install(relay)
        tablet.logSet(weight = 95.0, at = 5_000)
        tablet.sync.syncNow()
        assertEquals(FitnessSyncStatus.WAITING, tablet.sync.status.value)
        assertEquals(emptyList(), tablet.log.exercises.first(), "nothing of the first page was kept")
        assertEquals(emptyMap(), relay.log("sarah").toMap())
    }

    @Test
    fun aRestoredServerGetsBackTheEditsAndDeletionsItLost() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        val kept = phone.logSet(weight = 225.0, at = 1_000)
        val deleted = phone.logSet(weight = 315.0, at = 2_000)
        phone.log.saveBodyweight(BodyweightEntry(20_000, 180.0))
        phone.sync.syncNow()
        val backup = LinkedHashMap(relay.log())

        phone.log.saveBodyweight(BodyweightEntry(20_000, 175.0))
        phone.log.updateSet(kept.copy(reps = 9))
        phone.log.deleteSet(deleted.id)
        phone.sync.syncNow()
        relay.logs[relay.signedIn] = backup
        phone.sync.syncNow()

        assertEquals(FitnessSyncStatus.SAVED, phone.sync.status.value)
        assertEquals(listOf(BodyweightEntry(20_000, 175.0)), phone.log.bodyweights.first(), "the newer weigh-in stands")
        assertEquals(listOf(kept.copy(reps = 9)), phone.log.sets.first(), "one set, edited, under its own id")
        val tablet = install(relay)
        tablet.sync.syncNow()
        assertEquals(listOf(BodyweightEntry(20_000, 175.0)), tablet.log.bodyweights.first())
        assertEquals(listOf(kept.copy(reps = 9)), tablet.log.sets.first())
    }

    @Test
    fun aRunBeingRecordedWhenSomeoneElseSignsInIsNotTheirs() = runTest {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        phone.sync.syncNow()
        phone.runDao.upsertRun(run("r1").copy(endEpochMillis = null))

        relay.signedIn = "sarah"
        phone.sync.syncNow()
        assertEquals(FitnessSyncStatus.HELD_BACK, phone.sync.status.value)
        assertEquals(1, phone.log.exercises.first().size)

        phone.runDao.upsertRun(run("r1"))
        phone.sync.syncNow()
        assertEquals(FitnessSyncStatus.HELD_BACK, phone.sync.status.value)
        assertEquals(emptyMap(), relay.log("sarah").toMap())

        relay.signedIn = "andrew"
        phone.sync.syncNow()
        assertEquals(1, relay.rowsOf(FitnessSyncRows.RUN, "andrew").size)
    }

    @Test
    fun aSetLoggedAsSomeoneElseSignsInIsNotClearedAway() = runTest {
        val relay = FakeRelay()
        var phone: Install? = null
        var logged = false
        val real = InMemoryFitnessSyncDao()
        val watched = object : FitnessSyncDao by real {
            // In the middle of looking at what was last agreed, a set is saved.
            override suspend fun rows(): List<FitnessSyncRowEntity> {
                if (relay.signedIn == "sarah" && !logged) {
                    logged = true
                    launch { phone!!.logSet(weight = 315.0, at = 2_000) }
                    yield()
                }
                return real.rows()
            }
        }
        phone = install(relay, agreedDao = watched)
        phone.log.saveExercises(listOf(SQUAT))
        phone.sync.syncNow()

        relay.signedIn = "sarah"
        phone.sync.syncNow()
        advanceUntilIdle()
        assertEquals(listOf(Triple(SQUAT.id, 315.0, 5)), phone.sets(), "it waited for the hand-over, rather than going under it")
    }

    @Test
    fun beingKilledDuringAHandOverSendsNoDeletions() = runTest {
        val relay = FakeRelay()
        val real = InMemoryFitnessSyncDao()
        var killed = false
        val dying = object : FitnessSyncDao by real {
            override suspend fun forget() {
                if (relay.signedIn == "sarah" && !killed) {
                    killed = true
                    error("killed")
                }
                real.forget()
            }
        }
        val phone = install(relay, agreedDao = dying)
        phone.log.saveExercises(listOf(SQUAT))
        phone.logSet()
        phone.sync.syncNow()

        relay.signedIn = "sarah"
        phone.sync.syncNow()
        assertTrue(killed)
        assertEquals(emptyList(), phone.log.sets.first())

        // The owner signing back in before it could carry on finds the log whole, on the server and here.
        relay.signedIn = "andrew"
        phone.sync.syncNow()
        assertEquals(FitnessSyncStatus.SAVED, phone.sync.status.value)
        assertEquals(1, relay.rowsOf(FitnessSyncRows.SET).size)
        assertEquals(1, phone.log.sets.first().size)
    }

    @Test
    fun twoDevicesBehindAnIdFromAClockAheadDontTakeTheSameNext() = runTest {
        val relay = FakeRelay()
        val ahead = install(relay, firstId = Long.MAX_VALUE / 4)
        ahead.log.saveExercises(listOf(SQUAT))
        ahead.logSet()
        ahead.sync.syncNow()
        val phone = install(relay)
        val tablet = install(relay)
        phone.sync.syncNow()
        tablet.sync.syncNow()

        val here = phone.logSet(weight = 100.0, at = 2_000)
        val there = tablet.logSet(weight = 200.0, at = 2_000)
        assertNotEquals(here.id, there.id)
        assertTrue(here.id > Long.MAX_VALUE / 4 && there.id > Long.MAX_VALUE / 4, "and they still rise")
    }

    @Test
    fun aRowOfAKindThisBuildDoesntKnowIsPassedOver() = runTest {
        val relay = FakeRelay()
        relay.log()["shoes" to "1"] = FakeRelay.Held(Json.parseToJsonElement("""{"miles":3}""").jsonObject, 1)
        relay.log()[FitnessSyncRows.WEIGH_IN to "20000"] = FakeRelay.Held(Json.parseToJsonElement("""{"pounds":130.0,"note":"added later"}""").jsonObject, 2)
        val phone = install(relay)
        phone.sync.syncNow()
        phone.sync.syncNow()
        assertEquals(listOf(BodyweightEntry(20_000, 130.0)), phone.log.bodyweights.first())
        assertEquals(FitnessSyncStatus.SAVED, phone.sync.status.value)
        assertTrue(relay.sent.none { it.kind == "shoes" })
    }

    @Test
    fun followingSyncsAtOnceAfterAChangeNowAndThenAndOnceMoreOnLeaving() = runTest(StandardTestDispatcher()) {
        val relay = FakeRelay()
        val phone = install(relay)
        phone.log.saveExercises(listOf(SQUAT))
        val follower = launch { phone.sync.follow() }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(FitnessSyncStatus.SAVED, phone.sync.status.value)
        assertEquals(1, relay.rowsOf(FitnessSyncRows.EXERCISE).size, "at once")

        phone.logSet()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, relay.rowsOf(FitnessSyncRows.SET).size, "soon after a change")

        val other = install(relay)
        other.sync.syncNow()
        other.logSet(weight = 315.0, at = 3_000)
        other.sync.syncNow()
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(2, phone.log.sets.first().size, "now and then, to hear of changes elsewhere")

        phone.logSet(weight = 400.0, at = 4_000)
        follower.cancel()
        runCurrent()
        assertEquals(3, relay.rowsOf(FitnessSyncRows.SET).size, "and what was changed last still goes after leaving")

        val asked = relay.requests
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(asked, relay.requests, "and after that it asks nothing")
    }

    @Test
    fun twoFollowersAreOneFollowing() = runTest(StandardTestDispatcher()) {
        val relay = FakeRelay()
        val phone = install(relay)
        val first = launch { phone.sync.follow() }
        val second = launch { phone.sync.follow() }
        runCurrent()
        first.cancel()
        runCurrent()
        val asked = relay.requests
        advanceTimeBy(31_000)
        runCurrent()
        assertTrue(relay.requests > asked, "the other is still on screen")
        second.cancel()
    }

    private companion object {
        const val URL = "https://frigate.example.ts.net"

        val SQUAT = Exercise("legs/squat", "Squat", BodyPart.LEGS, Equipment.BARBELL, LoadKind.WEIGHT, Muscle.QUADS, listOf(Muscle.GLUTES), 6, 10, 10.0, 180, "", false)
        val BENCH = Exercise("chest/bench", "Bench", BodyPart.CHEST, Equipment.BARBELL, LoadKind.WEIGHT, Muscle.CHEST, emptyList(), 6, 10, 5.0, 150, "", false)
    }
}
