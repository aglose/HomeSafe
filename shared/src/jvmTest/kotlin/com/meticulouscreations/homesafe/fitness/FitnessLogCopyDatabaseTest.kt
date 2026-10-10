package com.meticulouscreations.homesafe.fitness

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.meticulouscreations.homesafe.data.AppDatabase
import com.meticulouscreations.homesafe.data.FitnessDao
import com.meticulouscreations.homesafe.fitness.FitnessTestData.exercise
import com.meticulouscreations.homesafe.fitness.data.FitnessRepositoryImpl
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.BodyweightEntry
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSettings
import com.meticulouscreations.homesafe.fitness.domain.HeartSummary
import com.meticulouscreations.homesafe.fitness.domain.LogCopy
import com.meticulouscreations.homesafe.fitness.domain.LogMerge
import com.meticulouscreations.homesafe.fitness.domain.LoggedSet
import com.meticulouscreations.homesafe.fitness.domain.Phase
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.SetDraft
import com.meticulouscreations.homesafe.fitness.domain.Workout
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A copy of a log brought in through the real database, on a SQLite file: the repository tests
 * run on [com.meticulouscreations.homesafe.data.InMemoryFitnessDao], which has no transactions,
 * so that a copy is written as one (all of it or none) is only shown here.
 */
class FitnessLogCopyDatabaseTest {

    private val squat = exercise(id = "legs/squat", name = "Squat", bodyPart = BodyPart.LEGS)
    private val heart = HeartSummary(belowMillis = 4_000, zoneMillis = listOf(1_000L, 2_000L, 3_000L, 0L, 500L), bpmMillis = 1_260_000, peakBpm = 171)

    private val copy = LogCopy(
        exercises = listOf(exercise(), squat),
        sets = listOf(LoggedSet(1, exercise().id, 135.0, 10, 0, imported = true), LoggedSet(2, squat.id, 225.0, 5, 5_100, workoutId = 1, bodyweight = 180.0, note = "belt")),
        workouts = listOf(Workout(1, WorkoutFocus.LEGS, 5_000, 6_000)),
        phases = listOf(Phase(1, PhaseKind.CUT, 4_000)),
        bodyweights = listOf(BodyweightEntry(19_000, 180.0)),
        heart = HeartSettings(HeartProfile(maxBpm = 188), HeartSensor("AA:BB:CC:00:00:01", "Band")),
        heartSummaries = mapOf(1L to heart),
    )

    /** The app's database on a fresh SQLite file; [prepare] gets the file once its tables are there, before [body] opens it again. */
    private fun withDatabase(prepare: (SQLiteConnection) -> Unit = {}, body: suspend (FitnessDao) -> Unit) = runBlocking {
        val file = File.createTempFile("homesafe-log-copy", ".db").apply { delete() }
        val driver = BundledSQLiteDriver()
        fun open() = Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
        try {
            // Reading anything makes Room create the tables.
            open().run {
                fitnessDao().sets()
                close()
            }
            driver.open(file.absolutePath).run {
                prepare(this)
                close()
            }
            val database = open()
            try {
                body(database.fitnessDao())
            } finally {
                database.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun aCopyBroughtInThroughTheDatabaseIsAllThereAndASecondTimeAddsNothing() = withDatabase { dao ->
        val repository = FitnessRepositoryImpl(dao)
        val merge = repository.bringIn(copy)
        assertEquals(2, merge.newExercises)
        assertEquals(copy, repository.logCopy())
        assertEquals(LogMerge(), repository.bringIn(copy))
    }

    @Test
    fun aCopyThatFailsPartWayLeavesNothingOfItselfBehind() = withDatabase(
        // The last table a copy is written to refuses every row, so the write fails with everything else already in.
        prepare = { it.execSQL("CREATE TRIGGER refuse_heart BEFORE INSERT ON FitnessHeartSummaryEntity BEGIN SELECT RAISE(ABORT, 'refused'); END") },
    ) { dao ->
        val repository = FitnessRepositoryImpl(dao)
        repository.saveExercises(listOf(squat))

        assertFailsWith<Exception> { repository.bringIn(copy) }

        assertEquals(listOf(squat), repository.exercises.first())
        assertEquals(emptyList(), repository.sets.first())
        assertEquals(emptyList(), repository.workouts.first())
        assertEquals(emptyList(), repository.phases.first())
        assertEquals(emptyList(), repository.bodyweights.first())
        assertEquals(HeartSettings(), repository.heartSettings.first())
        // The lock was let go: the log can still be written to.
        assertEquals(1L, repository.addSets(listOf(SetDraft(squat.id, 225.0, 5, 100))).single().id)
    }
}
