package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.FitnessRecordedRunEntity
import com.meticulouscreations.homesafe.data.FitnessRunDao
import com.meticulouscreations.homesafe.data.FitnessRunEventEntity
import com.meticulouscreations.homesafe.fitness.domain.RecordedRun
import com.meticulouscreations.homesafe.fitness.domain.RecordedRuns
import com.meticulouscreations.homesafe.fitness.domain.RunEvent
import com.meticulouscreations.homesafe.fitness.domain.RunEventKind
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [RecordedRuns] in the app's database ([FitnessRunDao]). */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RecordedRunsImpl(private val dao: FitnessRunDao) : RecordedRuns {
    // Appends are numbered from what is there, one at a time.
    private val appending = Mutex()

    override val runs: Flow<List<RecordedRun>> = dao.observeFinished().map { rows ->
        rows.mapNotNull { row -> row.endEpochMillis?.let { RecordedRun(row.id, row.startEpochMillis, it, row.utcOffsetSeconds, row.meters, row.movingMillis) } }
    }

    override suspend fun begin(id: String, startEpochMillis: Long, utcOffsetSeconds: Int) {
        dao.upsertRun(FitnessRecordedRunEntity(id, startEpochMillis, null, utcOffsetSeconds, 0.0, 0))
    }

    override suspend fun append(id: String, events: List<RunEvent>) {
        if (events.isEmpty()) return
        appending.withLock {
            val from = (dao.lastSeq(id) ?: -1) + 1
            dao.upsertEvents(events.mapIndexed { index, event -> FitnessRunEventEntity(id, from + index, event.kind.name, event.epochMillis, event.latitude, event.longitude, event.accuracyMeters) })
        }
    }

    override suspend fun finish(run: RecordedRun) {
        dao.upsertRun(FitnessRecordedRunEntity(run.id, run.startEpochMillis, run.endEpochMillis, run.utcOffsetSeconds, run.meters, run.movingMillis))
    }

    override suspend fun events(id: String): List<RunEvent> = dao.events(id).mapNotNull { row ->
        // A kind this build doesn't know is passed over.
        val kind = RunEventKind.entries.firstOrNull { it.name == row.kind } ?: return@mapNotNull null
        RunEvent(kind, row.epochMillis, row.latitude, row.longitude, row.accuracyMeters)
    }

    override suspend fun delete(id: String) = dao.removeRun(id)

    override suspend fun unfinished(): List<Pair<String, Long>> = dao.unfinished().map { it.id to it.startEpochMillis }

    override suspend fun offsetOf(id: String): Int? = dao.run(id)?.utcOffsetSeconds
}
