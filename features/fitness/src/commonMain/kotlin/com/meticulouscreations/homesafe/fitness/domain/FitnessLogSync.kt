package com.meticulouscreations.homesafe.fitness.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random
import kotlin.time.Clock

/** Where this device's training log stands with the server. */
enum class FitnessSyncStatus {
    /** No one is signed in, so the log is only here. */
    SIGNED_OUT,

    /** Signed in, and the server has yet to answer or couldn't be reached: what is here goes when it can. */
    WAITING,

    /** The server has all of it. */
    SAVED,

    /**
     * Signed in as someone else than the log here belongs to, and it holds changes the server hasn't
     * got. Nothing is sent, since it isn't theirs, and nothing is replaced, since it isn't saved anywhere else.
     */
    HELD_BACK,
}

/**
 * Keeps the training log and the recorded runs in step with the server, so that every device signed
 * in with one login has the same ones. The device's own copy is what the app always reads and writes,
 * so nothing waits on the network and the gym's dead spots don't matter; this carries the changes across.
 */
interface FitnessLogSync {
    val status: StateFlow<FitnessSyncStatus>

    /**
     * Keeps in step for as long as this is running, which is while the log is on screen: at once,
     * soon after each change here, and now and then to hear of changes elsewhere. What was changed
     * last still goes after it stops.
     */
    suspend fun follow()
}

/** For previews, and for a view model's tests that aren't about this. */
object NoFitnessLogSync : FitnessLogSync {
    override val status: StateFlow<FitnessSyncStatus> = MutableStateFlow(FitnessSyncStatus.SIGNED_OUT)

    override suspend fun follow() = Unit
}

/**
 * What a new row's id has to be at least. Ids rise from the log's highest, so that sets logged in one
 * second keep their order; but two devices that are apart would each take the same next number, and
 * those would then be taken for one row. So they begin here instead: the time, with twelve random bits
 * beneath it, which is above any id given before this existed. A log can hold ids from a clock ahead of
 * this one, and then two devices behind it are both past the time and at the same highest, so a step of
 * a random size past that keeps them apart too. The numbers are too long for JavaScript to read exactly
 * (from 2039), and nothing that handles them is JavaScript: the app and the relay read them as whole
 * numbers of any size.
 */
fun interface FitnessIdFloor {
    /** Where a new id begins, in a log whose [highest] id is that. */
    fun next(highest: Long): Long

    companion object {
        private const val STEP = 1L shl 24

        val FromClock = FitnessIdFloor { highest ->
            maxOf((Clock.System.now().toEpochMilliseconds() shl 12) or Random.nextLong(1L shl 12), highest + 1 + Random.nextLong(STEP))
        }
    }
}
