package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.FitnessRunDao
import com.meticulouscreations.homesafe.data.FitnessSyncDao
import com.meticulouscreations.homesafe.data.FitnessSyncRowEntity
import com.meticulouscreations.homesafe.data.FitnessSyncStateEntity
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.fitness.domain.FitnessLogSync
import com.meticulouscreations.homesafe.fitness.domain.FitnessRepository
import com.meticulouscreations.homesafe.fitness.domain.FitnessSyncStatus
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * [FitnessLogSync] by comparing: each pass sets the log and the finished runs as they stand against
 * what the server last had ([FitnessSyncDao]), sends the difference, and writes in what the server
 * says changed elsewhere. See docs/fitness.md, "On the server".
 *
 * Nothing about a change has to be recorded when it is made, so no way of changing the log can
 * forget to, and being killed at the wrong moment loses nothing: what was sent and not recorded
 * is sent again, and the server takes it as no news. Each step is safe to repeat for that reason.
 *
 * The log's lock is held to look and to write, never across the network, so logging a set doesn't
 * wait on the server. A row changed here while an answer was on its way is left as it is, and goes
 * next time. A row changed in two places goes by whichever device told the server last.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FitnessLogSyncImpl(
    private val log: FitnessRepositoryImpl,
    private val runs: FitnessRunDao,
    private val agreed: FitnessSyncDao,
    private val api: FitnessSyncApi,
    private val connection: ConnectionRepository,
    appScope: CoroutineScope,
) : FitnessLogSync {
    private val _status = MutableStateFlow(FitnessSyncStatus.WAITING)
    override val status: StateFlow<FitnessSyncStatus> = _status.asStateFlow()

    private val followers = MutableStateFlow(0)
    private val passes = Mutex()

    init {
        appScope.launch {
            followers.map { it > 0 }.distinctUntilChanged().dropWhile { !it }.collectLatest { following ->
                // Off screen, what was last changed still goes: the change's own wait was cut short.
                if (following) keepInStep() else syncNow()
            }
        }
    }

    override suspend fun follow() {
        followers.update { it + 1 }
        try {
            awaitCancellation()
        } finally {
            followers.update { it - 1 }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun keepInStep() {
        val everyWhile = flow {
            while (true) {
                emit(Unit)
                delay(HEAR_EVERY_MS)
            }
        }
        val changedHere = merge(
            log.exercises.map {},
            log.sets.map {},
            log.workouts.map {},
            log.phases.map {},
            log.bodyweights.map {},
            log.heartSettings.map {},
            log.heartSummaries.map {},
            runs.observeFinished().map {},
        ).debounce(SETTLE_MS)
        val connectionChanged = merge(
            connection.activeConnection.map { it?.serverUrl }.distinctUntilChanged().map {},
            connection.serverUnreachable.map {},
        )
        merge(everyWhile, changedHere, connectionChanged).conflate().collect { syncNow() }
    }

    /** One pass, and the status that comes of it. One at a time; anything that goes wrong is tried again by the next. */
    internal suspend fun syncNow() = passes.withLock {
        val active = connection.activeConnection.value
        val url = connection.currentServerUrl.value
        _status.value = when {
            active == null || url == null -> FitnessSyncStatus.SIGNED_OUT

            connection.serverUnreachable.value -> FitnessSyncStatus.WAITING

            else -> try {
                pass(url, active.serverUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FitnessSyncStatus.WAITING
            }
        }
    }

    private suspend fun pass(url: String, server: String): FitnessSyncStatus {
        val state = agreed.state()
        return when {
            state == null -> bringTogether(url, server)
            state.handingOver || state.server != server -> handOver(url, server, state)
            else -> exchange(url, state)
        }
    }

    /**
     * The first pass of a log with the server, which is every install's first, and again if either
     * has been wiped. Its ids can't be taken to mean anything to the server: two installs each have a
     * set 1. So rather than comparing, it hears the server's whole log and fits this one in around
     * it ([FitnessRepositoryImpl.adopt]); what that leaves different is what the next exchange sends.
     */
    private suspend fun bringTogether(url: String, server: String): FitnessSyncStatus {
        val heard = hearAll(url, null) ?: return FitnessSyncStatus.WAITING
        val rows = heard.rows
        val written = rows.entries.mapNotNull { (key, data) -> data?.let { key to it } }.toMap()
        val texts = textsOf(rows)

        val logRows = written.filterKeys { FitnessSyncRows.isLog(it) && it in texts }
        if (logRows.isNotEmpty()) {
            val write = FitnessSyncRows.logWrite(logRows, emptyList())
            if (!log.adopt(write.rows, write.profile ?: HeartProfile())) return FitnessSyncStatus.WAITING
        }
        // A run has the same id wherever it is, so there is nothing to fit in: what one side hasn't got, it gets.
        for ((key, data) in written) {
            if (FitnessSyncRows.isLog(key) || key !in texts) continue
            val id = FitnessSyncRows.idOf(key)
            if (runs.run(id) == null) FitnessSyncRows.run(id, data).let { (run, events) -> runs.writeRun(run, events) }
        }
        for ((key, data) in rows) {
            if (data == null && !FitnessSyncRows.isLog(key)) FitnessSyncRows.idOf(key).let { id -> if (runs.run(id)?.endEpochMillis != null) runs.removeRun(id) }
        }

        val state = FitnessSyncStateEntity(server = server, owner = heard.owner, cursor = heard.cursor)
        agreed.record(texts.map { (key, text) -> FitnessSyncRowEntity(key, text) }, emptyList(), state)
        return exchange(url, state)
    }

    /**
     * The server has gone back to an older copy of itself: restored from a backup, or begun again. This
     * device has heard of everything that copy holds, so nothing here is new to fit in around it: the
     * ids stand, and what differs is what the server has lost since. So what it has becomes what was
     * last agreed, and the next comparison sends the rest, the deletions with it.
     */
    private suspend fun catchServerUp(url: String, start: FitnessSyncStateEntity): FitnessSyncStatus {
        val heard = hearAll(url, start.owner) ?: return FitnessSyncStatus.WAITING
        val state = start.copy(cursor = heard.cursor)
        agreed.startOver(textsOf(heard.rows).map { (key, text) -> FitnessSyncRowEntity(key, text) }, state)
        return exchange(url, state)
    }

    private class WholeLog(val rows: Map<String, JsonObject?>, val owner: String, val cursor: Long)

    /**
     * The server's whole log, page by page, each page asked for from whoever the first one came from.
     * Null if it stopped being the one log on the way (someone else signed in, the database restored),
     * since pages of two logs mustn't be taken for one: the next pass begins again.
     */
    private suspend fun hearAll(url: String, expected: String?): WholeLog? {
        val rows = LinkedHashMap<String, JsonObject?>()
        var answer: FitnessSyncAnswer
        var owner = expected
        var cursor = 0L
        do {
            answer = api.sync(url, FitnessSyncRequest(owner, cursor))
            if (answer.reset || (owner != null && answer.owner != owner)) return null
            answer.rows.forEach { rows[FitnessSyncRows.key(it.kind, it.id)] = it.data }
            owner = answer.owner
            cursor = answer.rev
        } while (answer.more)
        return WholeLog(rows, answer.owner, cursor)
    }

    /** What the server holds, as text to compare, leaving out what's deleted and what this build can't read. */
    private fun textsOf(rows: Map<String, JsonObject?>): Map<String, String> =
        rows.entries.mapNotNull { (key, data) -> data?.let { FitnessSyncRows.forComparing(FitnessSyncRows.kindOf(key), it) }?.let { key to it } }.toMap()

    /** Sends what is different and takes in what has changed elsewhere, until there is neither. */
    private suspend fun exchange(url: String, start: FitnessSyncStateEntity): FitnessSyncStatus {
        var cursor = start.cursor
        while (true) {
            val before = log.holdingLog { here() }
            val last = agreed.rows().associate { it.key to it.data }
            if (wiped(before, last)) {
                agreed.forget()
                return bringTogether(url, start.server)
            }
            val changes = differences(before, last)
            val batch = batchOf(changes)
            val answer = api.sync(url, FitnessSyncRequest(start.owner, cursor, batch.rows))
            if (answer.owner != start.owner) return handOver(url, start.server, start)
            if (answer.reset) {
                if (cursor > 0) return catchServerUp(url, start)
                // Heard nothing yet, and someone else got there first: their rows and these may share ids.
                agreed.forget()
                return bringTogether(url, start.server)
            }
            cursor = answer.rev
            land(answer, batch, changes, before, last, start.copy(cursor = cursor))
            if (!answer.more && batch.isAll) return FitnessSyncStatus.SAVED
        }
    }

    /**
     * Someone else is signed in than the log belongs to. If all of it is on the server it isn't lost by
     * going, so it makes way for theirs. If not it stays, and nothing crosses either way: the old owner
     * signing back in finds it as it was. A run being recorded is the old owner's too, and isn't on the
     * server yet.
     *
     * The looking and the emptying are one piece, so a set logged meanwhile can't go unseen. The two
     * databases can't change as one, so what is about to happen is noted first: stopped part-way, this
     * carries on from the note (handingOver), and the emptied log is never taken for someone deleting it.
     */
    private suspend fun handOver(url: String, server: String, state: FitnessSyncStateEntity): FitnessSyncStatus {
        val emptied = log.holdingLog {
            if (!state.handingOver) {
                val here = here()
                val last = agreed.rows().associate { it.key to it.data }
                val unsaved = differences(here, last).isNotEmpty() && !wiped(here, last)
                if (unsaved || runs.unfinished().isNotEmpty()) return@holdingLog false
                agreed.upsertState(state.copy(handingOver = true))
            }
            log.emptyLog()
            runs.observeFinished().first().forEach { runs.removeRun(it.id) }
            true
        }
        if (!emptied) return FitnessSyncStatus.HELD_BACK
        agreed.forget()
        return bringTogether(url, server)
    }

    /** Under [FitnessRepositoryImpl.holdingLog]: everything of this device's, by key, as text. */
    private suspend fun here(): Map<String, String> =
        FitnessSyncRows.of(log.rows(), log.heartProfile()) + runs.observeFinished().first().map { FitnessSyncRows.of(it) }

    /**
     * The log has all gone, and it was too much to have gone one row at a time: the app's database is
     * thrown away to get past a migration it has none for. That isn't someone deleting their log, and
     * mustn't tell every other device that it is. The runs are kept apart from it and aren't touched.
     */
    private fun wiped(here: Map<String, String>, last: Map<String, String>) =
        here.keys.none(FitnessSyncRows::isLog) && last.keys.count(FitnessSyncRows::isLog) >= WIPED_FROM

    /** By key, what to send: this device's text, or null for what it no longer has. */
    private fun differences(here: Map<String, String>, last: Map<String, String>): Map<String, String?> = buildMap {
        for ((key, text) in here) if (last[key] != text) put(key, text)
        for (key in last.keys) if (key !in here) put(key, null)
    }

    private class Batch(val rows: List<FitnessSyncRow>, val sent: Map<String, String?>, val isAll: Boolean)

    /** As much of [changes] as goes in one request, parents first. The first row goes whatever its size: a run may be larger than a batch. */
    private suspend fun batchOf(changes: Map<String, String?>): Batch {
        val rows = ArrayList<FitnessSyncRow>()
        val sent = HashMap<String, String?>()
        var size = 0
        val ordered = changes.keys.sortedBy { FitnessSyncRows.ORDER.indexOf(FitnessSyncRows.kindOf(it)) }
        for (key in ordered) {
            if (rows.size >= BATCH_ROWS || (rows.isNotEmpty() && size >= BATCH_BYTES)) break
            val kind = FitnessSyncRows.kindOf(key)
            val id = FitnessSyncRows.idOf(key)
            val text = changes[key]
            val data = when {
                text == null -> null

                kind == FitnessSyncRows.RUN -> {
                    // It may have been deleted since it was looked at, and then the next pass says so.
                    val run = runs.run(id) ?: continue
                    FitnessSyncRows.forSending(run, runs.events(id))
                }

                else -> FitnessSyncRows.parse(text)
            }
            rows += FitnessSyncRow(kind, id, data)
            sent[key] = text
            size += data?.toString()?.length ?: 0
        }
        return Batch(rows, sent, sent.size == changes.size)
    }

    private suspend fun land(answer: FitnessSyncAnswer, batch: Batch, changes: Map<String, String?>, before: Map<String, String>, last: Map<String, String>, state: FitnessSyncStateEntity) {
        // What the server said, as text: null for deleted. Passed over if it can't be read, and if it is what was already known.
        val heard = HashMap<String, String?>()
        val data = HashMap<String, JsonObject>()
        for (row in answer.rows) {
            val key = FitnessSyncRows.key(row.kind, row.id)
            if (row.data == null) {
                if (key in last) heard[key] = null
            } else {
                val text = FitnessSyncRows.forComparing(row.kind, row.data) ?: continue
                if (last[key] == text) continue
                heard[key] = text
                data[key] = row.data
            }
        }

        log.holdingLog {
            val now = here()
            // What this device has changed stays and goes to the server next: only what it has left alone is written over.
            val taken = heard.keys.filter { it !in changes && now[it] == before[it] && now[it] != heard[it] }
            val (logKeys, runKeys) = taken.partition(FitnessSyncRows::isLog)
            val write = FitnessSyncRows.logWrite(data.filterKeys { it in logKeys }, logKeys.filter { heard[it] == null })
            if (!write.isEmpty) log.writeFromServer(write.rows, write.profile, write.gone)
            for (key in runKeys) {
                val id = FitnessSyncRows.idOf(key)
                val row = data[key]
                if (row == null) runs.removeRun(id) else FitnessSyncRows.run(id, row).let { (run, events) -> runs.writeRun(run, events) }
            }
        }

        // What the server now has: what was sent, over what was heard, since the answer was read before those were saved.
        val held = heard + batch.sent
        agreed.record(
            rows = held.mapNotNull { (key, text) -> text?.let { FitnessSyncRowEntity(key, it) } },
            gone = held.filterValues { it == null }.keys.toList(),
            state = state,
        )
    }

    private companion object {
        /** A change is sent once things have been quiet this long: the sets of a workout's minute go together. */
        const val SETTLE_MS = 2_000L

        /** How often to hear of changes elsewhere, while the log is on screen. */
        const val HEAR_EVERY_MS = 30_000L

        const val BATCH_BYTES = 256_000
        const val BATCH_ROWS = 2_000

        /** How many rows have to have gone at once to be taken for the database being thrown away. */
        const val WIPED_FROM = 20
    }
}
