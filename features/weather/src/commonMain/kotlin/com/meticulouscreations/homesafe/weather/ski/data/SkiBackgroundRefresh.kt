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
    override suspend fun refresh() {
        // Nobody has opened the tab yet, so nobody's waiting on it.
        if (dao.kept(SITE_HEALTH_KEY) == null) return
        val now = clock.now().epochSeconds
        val last = dao.kept(LAST_KEY)?.savedAtEpochSeconds
        if (last != null && now - last < REFRESH_EVERY_SECONDS) return
        dao.keep(SkiKeptEntity(LAST_KEY, "{}", now))

        val plan = repository.plan().first()
        val from = start.of(plan.origin)
        plan.resorts.map { it.regionSlug }.distinct().forEach { repository.reports(it) }
        plan.resorts.forEach { ref ->
            delay(BETWEEN_RESORTS_MS)
            repository.resortNews(ref.slug)
            val facts = repository.resortPage(ref).getOrNull()?.facts ?: return@forEach
            repository.forecast(ref, facts)
            if (from != null) repository.drive(from, SkiPoint(facts.latitude, facts.longitude))
        }
    }

    private companion object {
        const val LAST_KEY = "background-refresh"
        const val REFRESH_EVERY_SECONDS = 3 * 3_600L
        const val BETWEEN_RESORTS_MS = 5_000L
    }
}
