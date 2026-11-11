package com.meticulouscreations.homesafe.weather.ski.data

import com.meticulouscreations.homesafe.data.SkiDao
import com.meticulouscreations.homesafe.data.SkiKeptEntity
import com.meticulouscreations.homesafe.weather.ski.domain.SkiBackgroundRefresh
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPoint
import com.meticulouscreations.homesafe.weather.ski.domain.SkiRepository
import com.meticulouscreations.homesafe.weather.ski.domain.SkiStartingPoint
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * Brings the followed resorts up to date from the background weather check, so the tab has this
 * morning's numbers when it opens with no connection, or before it has fetched its own. It goes
 * through the repository's own ages, and only every few hours and one resort at a time: the resorts'
 * bot protection doesn't take long to stop answering a household's address. A shop's browser is a
 * whole page and needs a window, so prices wait for the tab.
 */
@Inject
@ContributesBinding(AppScope::class)
class SkiBackgroundRefreshImpl(
    private val repository: SkiRepository,
    private val dao: SkiDao,
    private val start: SkiStartingPoint,
    private val clock: Clock,
) : SkiBackgroundRefresh {
    /** How long one service gets to answer: a stalled one shouldn't use up the time the check gives this. */
    internal var attemptMillis = ATTEMPT_MS
    internal var betweenResortsMillis = BETWEEN_RESORTS_MS

    override suspend fun refresh() {
        val plan = repository.plan().first()
        // Nobody has opened the tab yet, so nobody's waiting on it.
        if (!plan.tabOpened || plan.resorts.isEmpty()) return
        // The check gives this only a little time, so a round may take a few of them: it says which resort is next.
        val kept = dao.kept(LAST_KEY)
        val saved = kept?.json?.toIntOrNull() ?: 0
        if (kept != null && saved == 0 && clock.now().epochSeconds - kept.savedAtEpochSeconds < REFRESH_EVERY_SECONDS) return
        // Resorts may have gone from the list since.
        val next = saved.takeIf { it in plan.resorts.indices } ?: 0

        val from = withTimeoutOrNull(attemptMillis) { start.approximateOf(plan.origin) }
        if (next == 0) {
            plan.resorts.map { it.regionSlug }.distinct().forEach { region -> withTimeoutOrNull(attemptMillis) { repository.reports(region) } }
        }
        plan.resorts.withIndex().drop(next).forEach { (index, ref) ->
            delay(betweenResortsMillis)
            // Said before it's tried: one that stalls every time is passed over next time, not tried for ever.
            val following = index + 1
            dao.keep(SkiKeptEntity(LAST_KEY, if (following < plan.resorts.size) following.toString() else "0", clock.now().epochSeconds))
            withTimeoutOrNull(attemptMillis) {
                repository.resortNews(ref.slug)
                repository.resortPage(ref).getOrNull()?.facts?.let { facts ->
                    repository.forecast(ref, facts)
                    if (from != null) repository.drive(from, SkiPoint(facts.latitude, facts.longitude))
                }
            }
        }
    }

    private companion object {
        const val LAST_KEY = "background-refresh"
        const val REFRESH_EVERY_SECONDS = 3 * 3_600L
        const val BETWEEN_RESORTS_MS = 5_000L
        const val ATTEMPT_MS = 8_000L
    }
}
