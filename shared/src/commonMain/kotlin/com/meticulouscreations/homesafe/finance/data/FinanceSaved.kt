package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.data.FinanceSavedDao
import com.meticulouscreations.homesafe.data.FinanceSavedEntity
import com.meticulouscreations.homesafe.finance.domain.ChartRange
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * What the finance app last fetched, kept on the device (Room, [FinanceSavedDao]) so the app has
 * something to open on when nothing answers: no Tailscale, the server off, no signal at all.
 * Each thing is one row of JSON under a key from the companion; a successful fetch overwrites it.
 *
 * Keeping it is never allowed to break a fetch, and reading it never fails: a row that can't be
 * written is skipped, and one that can't be read (written by a build that shaped it differently)
 * counts as nothing saved.
 *
 * The household's own figures (the sheet, the budget, the banks) are kept here beside the public
 * market data. Who may see them without the server is `FinanceAccess`'s to say, and the
 * repositories ask it before reading them back; [forgetPrivate] drops them when the server itself
 * refuses this account.
 */
@Inject
@SingleIn(AppScope::class)
class FinanceSaved(private val dao: FinanceSavedDao, private val clock: Clock) {

    private var pruned = false

    suspend fun <T> read(key: String, serializer: KSerializer<T>): T? {
        val row = suspendRunCatching { dao.saved(key) }.getOrNull() ?: return null
        // A sheet's worth of JSON is a lot to parse on the main thread.
        return withContext(Dispatchers.Default) { runCatching { json.decodeFromString(serializer, row.json) }.getOrNull() }
    }

    suspend fun <T> write(key: String, serializer: KSerializer<T>, value: T) {
        suspendRunCatching {
            val text = withContext(Dispatchers.Default) { json.encodeToString(serializer, value) }
            val now = clock.now().epochSeconds
            dao.upsert(FinanceSavedEntity(key, text, now))
            if (!pruned) {
                pruned = true
                // A quote or a chart for a ticker long since unfollowed is never asked for again.
                dao.deleteStartingWithSavedBefore(QUOTE_PREFIX, now - MARKET_DATA_KEPT_SECONDS)
                dao.deleteStartingWithSavedBefore(HISTORY_PREFIX, now - MARKET_DATA_KEPT_SECONDS)
            }
        }
    }

    /** Drops the household's own figures: the server answered and refused this account, so the device doesn't keep them for it. */
    suspend fun forgetPrivate() {
        suspendRunCatching {
            dao.delete(SHEET)
            dao.delete(BANK)
            dao.deleteStartingWith(BUDGET_PREFIX)
        }
    }

    companion object {
        /** The budget workbook as the relay sent it. */
        const val SHEET = "sheet"

        /** The linked banks as the relay last reported them. */
        const val BANK = "bank"

        private const val BUDGET_PREFIX = "budget:"
        private const val QUOTE_PREFIX = "quote:"
        private const val HISTORY_PREFIX = "history:"
        private const val MARKET_DATA_KEPT_SECONDS = 60L * 86_400L

        /** A month of card spending: [month] as `YYYY-MM`, or null for the month under way. */
        fun budget(month: String?): String = BUDGET_PREFIX + (month ?: "current")

        fun quote(symbol: String): String = QUOTE_PREFIX + symbol

        fun history(symbol: String, range: ChartRange): String = "$HISTORY_PREFIX$symbol|${range.name}"

        fun fred(seriesId: String, startDate: String): String = "fred:$seriesId@$startDate"

        private val json = Json {
            ignoreUnknownKeys = true
            // A series can hold a NaN where a source published one; it must not cost the whole row.
            allowSpecialFloatingPointValues = true
        }
    }
}
