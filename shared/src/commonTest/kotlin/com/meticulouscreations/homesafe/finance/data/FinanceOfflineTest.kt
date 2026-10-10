package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.data.InMemoryFinanceSavedDao
import com.meticulouscreations.homesafe.finance.FakeFinanceAccess
import com.meticulouscreations.homesafe.finance.FakeServerConnection
import com.meticulouscreations.homesafe.finance.domain.BankAccount
import com.meticulouscreations.homesafe.finance.domain.BankAccountType
import com.meticulouscreations.homesafe.finance.domain.BankFeed
import com.meticulouscreations.homesafe.finance.domain.BankInstitution
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSync
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.BucketSource
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetBucket
import com.meticulouscreations.homesafe.finance.domain.BudgetCard
import com.meticulouscreations.homesafe.finance.domain.BudgetConfig
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetLimits
import com.meticulouscreations.homesafe.finance.domain.CardHolder
import com.meticulouscreations.homesafe.finance.domain.CardRole
import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.MerchantRule
import com.meticulouscreations.homesafe.finance.domain.PriceHistory
import com.meticulouscreations.homesafe.finance.domain.Purchase
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.SheetProblem
import com.meticulouscreations.homesafe.finance.domain.SheetUnavailableException
import com.meticulouscreations.homesafe.network.TailnetProbe
import com.meticulouscreations.homesafe.text.LocalizedException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The finance app with nothing to fetch from: what it keeps on the device while there is, what it
 * reads back when there isn't, and that the household's figures come back only once
 * `FinanceAccess` has been unlocked.
 */
class FinanceOfflineTest {

    private val clock = object : Clock {
        var seconds = 1_790_000_000L

        override fun now(): Instant = Instant.fromEpochSeconds(seconds)
    }

    private val dao = InMemoryFinanceSavedDao()
    private val saved = FinanceSaved(dao, clock)
    private val access = FakeFinanceAccess()

    /** The network: [down] is nothing answering at all; otherwise [answers] by the request's path. */
    private var down = false
    private var requests = 0
    private val answers = HashMap<String, Pair<HttpStatusCode, String>>()

    private val client = HttpClient(
        MockEngine { request ->
            requests++
            if (down) throw IOException("Unable to resolve host")
            val (status, body) = answers[request.url.encodedPath] ?: (HttpStatusCode.NotFound to """{"detail":"Not Found"}""")
            respond(body, status, headersOf(HttpHeaders.ContentType, if (body.startsWith("{")) "application/json" else "text/csv"))
        },
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout)
    }

    private fun answer(path: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) {
        answers[path] = status to body
    }

    private val budget = Budget(
        configured = true,
        sandbox = false,
        month = "2026-10",
        isCurrentMonth = true,
        day = 10,
        daysInMonth = 31,
        syncedAtEpochSeconds = 1_789_999_000,
        changedAtEpochSeconds = null,
        syncing = true,
        nextSyncAtEpochSeconds = null,
        ready = true,
        cards = listOf(
            BudgetCard("Chase Sapphire", "ins_1", "Chase", "Sapphire", "1203", CardRole.Split, 412.5, needsRelink = false, readsPurchases = true, holders = listOf(CardHolder("1203", 4, "Sam"))),
            BudgetCard("Amex Gold", "ins_2", "Amex", "Gold", null, CardRole.Person("Alex"), null, needsRelink = true, readsPurchases = true),
            BudgetCard("Store card", "ins_3", "Target", "RedCard", null, CardRole.Unset, 0.0, needsRelink = false, readsPurchases = false),
        ),
        config = BudgetConfig(
            people = listOf("Sam", "Alex"),
            limits = BudgetLimits(total = 4000.0, people = mapOf("Sam" to 800.0, "Alex" to null), family = 2400.0),
            rules = listOf(MerchantRule("trader joes", BucketId.Family)),
        ),
        savingsLine = 5200.0,
        spent = 1234.56,
        pending = 20.0,
        buckets = listOf(BudgetBucket(BucketId.Person("Sam"), 300.0, 800.0, 7), BudgetBucket(BucketId.Family, 900.0, 2400.0, 20), BudgetBucket(BucketId.Unassigned, 34.56, null, 2)),
        daily = listOf(10.0, 0.0, 55.5),
        categories = emptyList(),
        merchants = emptyList(),
        purchases = listOf(
            Purchase("p1", "2026-10-03", 55.5, "Trader Joe's", "GROCERIES", pending = false, cardKey = "Chase Sapphire", bucket = BucketId.Family, source = BucketSource.RULE, remembered = true),
            Purchase("p2", "2026-10-04", 12.0, "Coffee", null, pending = true, cardKey = "Chase Sapphire", bucket = BucketId.Unassigned, source = BucketSource.NONE, remembered = false),
        ),
        history = emptyList(),
    )

    private val bank = BankSync(
        configured = true,
        sandbox = false,
        institutions = listOf(
            BankInstitution("ins_1", "Chase", 1_789_999_000, null, null, needsRelink = false, accounts = listOf(BankAccount("a1", "Chase Checking", "Checking", "4321", BankAccountType.CASH, "checking", 5120.33, "USD", null, 0))),
        ),
        syncedAtEpochSeconds = 1_789_999_000,
        syncing = true,
        nextSyncAtEpochSeconds = null,
        feed = BankFeed(configured = true, url = null, writtenAtEpochSeconds = null, error = null, message = null, serviceAccount = null, activationUrl = null),
    )

    private val quote = Quote("^GSPC", 5000.0, 4950.0, null, null, null, null, null, 1_789_990_000, -14_400, null, null, Series.of(listOf(1L to 4990.0, 2L to 5000.0)), name = "S&P 500")

    // --- What is kept ---

    @Test
    fun aMonthOfSpendingComesBackFromTheDeviceAsItWent() = runTest {
        saved.write("k", Budget.serializer(), budget)
        val back = assertNotNull(saved.read("k", Budget.serializer()))
        assertEquals(budget, back, "every bucket, card role and purchase, including whose each is")
        assertEquals(listOf(CardRole.Split, CardRole.Person("Alex"), CardRole.Unset), back.cards.map { it.role })
        assertEquals(listOf(BucketId.Family, BucketId.Unassigned), back.purchases.map { it.bucket })
    }

    @Test
    fun theBanksAndAQuoteAndAChartComeBackToo() = runTest {
        val history = PriceHistory("^GSPC", ChartRange.MAX, Series.of(listOf(10L to 1.0, 20L to Double.NaN)), null, 0, highest = 2.0, highestEpochSeconds = 20)
        saved.write("bank", BankSync.serializer(), bank)
        saved.write("quote", Quote.serializer(), quote)
        saved.write("history", PriceHistory.serializer(), history)

        assertEquals(bank, saved.read("bank", BankSync.serializer()))
        assertEquals(quote, saved.read("quote", Quote.serializer()))
        val back = assertNotNull(saved.read("history", PriceHistory.serializer()), "a NaN in a series doesn't cost the row")
        assertEquals(listOf(10L, 20L), back.series.times.toList())
        assertTrue(back.series.values[1].isNaN())
    }

    @Test
    fun aRowThisBuildCannotReadCountsAsNothingKept() = runTest {
        saved.write("k", String.serializer(), "not a budget")
        assertNull(saved.read("k", Budget.serializer()))
        assertNull(saved.read("never written", Budget.serializer()))
    }

    @Test
    fun forgettingTheHouseholdsFiguresLeavesTheMarketsAlone() = runTest {
        saved.write(FinanceSaved.SHEET, String.serializer(), "sheet")
        saved.write(FinanceSaved.BANK, BankSync.serializer(), bank)
        saved.write(FinanceSaved.budget(null), Budget.serializer(), budget)
        saved.write(FinanceSaved.budget("2026-09"), Budget.serializer(), budget)
        saved.write(FinanceSaved.quote("^GSPC"), Quote.serializer(), quote)

        saved.forgetPrivate()

        assertNull(saved.read(FinanceSaved.SHEET, String.serializer()))
        assertNull(saved.read(FinanceSaved.BANK, BankSync.serializer()))
        assertNull(saved.read(FinanceSaved.budget(null), Budget.serializer()))
        assertNull(saved.read(FinanceSaved.budget("2026-09"), Budget.serializer()))
        assertEquals(quote, saved.read(FinanceSaved.quote("^GSPC"), Quote.serializer()))
    }

    @Test
    fun marketDataNobodyHasAskedForInMonthsIsDroppedTheFirstTimeSomethingIsKept() = runTest {
        saved.write(FinanceSaved.quote("OLD"), Quote.serializer(), quote)
        saved.write(FinanceSaved.SHEET, String.serializer(), "sheet")
        // A new run of the app, three months on.
        clock.seconds += 90L * 86_400
        val later = FinanceSaved(dao, clock)
        later.write(FinanceSaved.quote("NEW"), Quote.serializer(), quote)

        assertNull(later.read(FinanceSaved.quote("OLD"), Quote.serializer()))
        assertNotNull(later.read(FinanceSaved.quote("NEW"), Quote.serializer()))
        assertNotNull(later.read(FinanceSaved.SHEET, String.serializer()), "the sheet is kept however old: it is all there is to open on")
    }

    // --- The budget ---

    private fun budgetRepository(connection: FakeServerConnection) = BudgetRepositoryImpl(BudgetRelayApi(client), connection, saved, access)

    private val budgetJson = """{"configured":true,"month":"2026-10","today":"2026-10-10","day":10,"days_in_month":31,"spent":1234.56}"""

    @Test
    fun aMonthTheServerAnsweredIsReadBackWhenItStopsAnswering() = runTest {
        answer("/finance/budget", budgetJson)
        val repository = budgetRepository(FakeServerConnection("https://box.ts.net"))
        assertEquals(1234.56, repository.budget().getOrThrow().spent)

        down = true
        val kept = repository.budget().getOrThrow()
        assertEquals(1234.56, kept.spent)
        assertEquals("2026-10", kept.month)
        // Asked for by its month, it is the same one.
        assertEquals(1234.56, repository.budget("2026-10").getOrThrow().spent)
    }

    @Test
    fun withTheServerKnownToBeOutOfReachTheKeptMonthIsShownWithoutAsking() = runTest {
        saved.write(FinanceSaved.budget(null), Budget.serializer(), budget)
        down = true
        access.goOffline()
        access.unlock()
        val repository = budgetRepository(FakeServerConnection("https://box.ts.net", unreachable = true))

        val kept = repository.budget().getOrThrow()
        assertEquals(budget.spent, kept.spent)
        assertFalse(kept.syncing, "kept mid-read, it isn't shown as still reading: nothing is following it")
        assertEquals(0, requests, "no waiting on a request nothing will answer")
    }

    @Test
    fun beforeSignInTheKeptMonthNeedsTheFingerprint() = runTest {
        saved.write(FinanceSaved.budget(null), Budget.serializer(), budget)
        access.goOffline()
        val repository = budgetRepository(FakeServerConnection(url = null))

        val refused = assertIs<BankSyncException>(repository.budget().exceptionOrNull())
        assertEquals(BankProblem.SIGNED_OUT, refused.problem, "locked reads as signed out, which takes the page's figures away")

        access.unlock()
        assertEquals(budget.spent, repository.budget().getOrThrow().spent)
    }

    @Test
    fun withNothingKeptAndNoServerItIsSignedOutAsBefore() = runTest {
        access.goOffline()
        access.unlock()
        val failure = assertIs<BankSyncException>(budgetRepository(FakeServerConnection(url = null)).budget().exceptionOrNull())
        assertEquals(BankProblem.SIGNED_OUT, failure.problem)
    }

    @Test
    fun theServerRefusingThisAccountDropsWhatTheDeviceKept() = runTest {
        saved.write(FinanceSaved.budget(null), Budget.serializer(), budget)
        saved.write(FinanceSaved.BANK, BankSync.serializer(), bank)
        answer("/finance/budget", """{"detail":{"error":"not_allowed","message":"Admins only"}}""", HttpStatusCode.Forbidden)
        val repository = budgetRepository(FakeServerConnection("https://box.ts.net"))

        assertEquals(BankProblem.NOT_ALLOWED, assertIs<BankSyncException>(repository.budget().exceptionOrNull()).problem)
        assertNull(saved.read(FinanceSaved.budget(null), Budget.serializer()), "not kept for an account the server won't show it to")
        assertNull(saved.read(FinanceSaved.BANK, BankSync.serializer()))
    }

    @Test
    fun aServerErrorIsReportedRatherThanPaperedOverWithAnOldMonth() = runTest {
        saved.write(FinanceSaved.budget(null), Budget.serializer(), budget)
        answer("/finance/budget", """{"detail":{"error":"not_configured","message":"No Plaid keys"}}""", HttpStatusCode.ServiceUnavailable)
        val failure = assertIs<BankSyncException>(budgetRepository(FakeServerConnection("https://box.ts.net")).budget().exceptionOrNull())
        assertEquals(BankProblem.NOT_CONFIGURED, failure.problem)
        assertNotNull(saved.read(FinanceSaved.budget(null), Budget.serializer()), "and what was kept stays kept")
    }

    @Test
    fun aChangeWithNoServerFailsAsUnsentNotAsRefused() = runTest {
        access.goOffline()
        access.unlock()
        val failure = budgetRepository(FakeServerConnection(url = null)).save(BudgetConfigPatch(people = listOf("Sam"))).exceptionOrNull()
        assertIs<LocalizedException>(failure)
        assertFalse(failure is BankSyncException, "a refusal would take the month off the screen, and stop the sheet's figures being reported again later")
    }

    @Test
    fun theAnswerToAChangeIsKeptAsTheMonthNowStands() = runTest {
        answer("/finance/budget/sync", budgetJson.replace("1234.56", "1300.0"))
        val repository = budgetRepository(FakeServerConnection("https://box.ts.net"))
        repository.syncNow().getOrThrow()
        down = true
        assertEquals(1300.0, repository.budget().getOrThrow().spent)
    }

    // --- The banks ---

    private fun bankRepository(connection: FakeServerConnection, onTailnet: Boolean? = true) =
        BankSyncRepositoryImpl(BankSyncRelayApi(client), connection, TailnetProbe { onTailnet }, saved, access)

    @Test
    fun theBanksAreReadBackWhenTheServerStopsAnswering() = runTest {
        answer("/finance/bank", """{"configured":true,"institutions":[{"id":"ins_1","name":"Chase"}],"syncing":true}""")
        val repository = bankRepository(FakeServerConnection("https://box.tail1234.ts.net"), onTailnet = false)
        assertEquals(listOf("Chase"), repository.status().getOrThrow().institutions.map { it.name })

        down = true
        val kept = repository.status().getOrThrow()
        assertEquals(listOf("Chase"), kept.institutions.map { it.name })
        assertFalse(kept.syncing)
    }

    @Test
    fun beforeSignInTheKeptBanksNeedTheFingerprint() = runTest {
        saved.write(FinanceSaved.BANK, BankSync.serializer(), bank)
        access.goOffline()
        val repository = bankRepository(FakeServerConnection(url = null))

        assertEquals(BankProblem.SIGNED_OUT, assertIs<BankSyncException>(repository.status().exceptionOrNull()).problem)
        access.unlock()
        assertEquals(bank.copy(syncing = false), repository.status().getOrThrow())
        assertEquals(0, requests)
    }

    @Test
    fun askingForASyncWithNoServerLeavesTheKeptBalancesUp() = runTest {
        access.goOffline()
        access.unlock()
        val failure = assertIs<BankSyncException>(bankRepository(FakeServerConnection(url = null)).syncNow().exceptionOrNull())
        assertEquals(BankProblem.UNREACHABLE, failure.problem, "not SIGNED_OUT, which the page answers by clearing itself")
    }

    @Test
    fun theServerRefusingTheSessionDropsTheKeptBanks() = runTest {
        saved.write(FinanceSaved.BANK, BankSync.serializer(), bank)
        answer("/finance/bank", """{"detail":"Frigate rejected the session"}""", HttpStatusCode.Unauthorized)
        val repository = bankRepository(FakeServerConnection("https://box.ts.net"))
        assertEquals(BankProblem.SIGNED_OUT, assertIs<BankSyncException>(repository.status().exceptionOrNull()).problem)
        assertNull(saved.read(FinanceSaved.BANK, BankSync.serializer()))
    }

    // --- The sheet and the markets ---

    private fun financeRepository(connection: FakeServerConnection) =
        FinanceRepositoryImpl(YahooFinanceApi(client), FredApi(client), FinanceRelayApi(client), connection, saved, access)

    private val workbookJson = """{"title":"Household budget","fetched_at":1789999000,"sheets":[]}"""

    @Test
    fun theSheetIsReadBackWhenTheServerStopsAnswering() = runTest {
        answer("/finance/sheet", workbookJson)
        val repository = financeRepository(FakeServerConnection("https://box.ts.net"))
        assertEquals("Household budget", repository.personalFinance().getOrThrow().title)

        down = true
        // A pull to refresh goes past the couple of minutes the last read is good for.
        val kept = repository.personalFinance(refresh = true).getOrThrow()
        assertEquals("Household budget", kept.title)
        assertEquals(1_789_999_000, kept.fetchedAtEpochSeconds, "it says when it was read")
    }

    @Test
    fun aNewRunOfTheAppWithNoServerOpensOnTheKeptSheetOnceUnlocked() = runTest {
        answer("/finance/sheet", workbookJson)
        financeRepository(FakeServerConnection("https://box.ts.net")).personalFinance().getOrThrow()

        // The app is opened again with Tailscale off: no session, nothing in memory.
        down = true
        access.goOffline()
        val reopened = financeRepository(FakeServerConnection(url = null))
        val locked = assertIs<SheetUnavailableException>(reopened.personalFinance().exceptionOrNull())
        assertEquals(SheetProblem.SIGNED_OUT, locked.problem)

        access.unlock()
        assertEquals("Household budget", reopened.personalFinance().getOrThrow().title)
    }

    @Test
    fun lockingTakesTheSheetAwayEvenWhileItIsFreshInMemory() = runTest {
        answer("/finance/sheet", workbookJson)
        val repository = financeRepository(FakeServerConnection("https://box.ts.net"))
        repository.personalFinance().getOrThrow()

        access.goOffline()
        assertTrue(repository.personalFinance().isFailure, "the server went away a moment after answering: not from memory either")
    }

    @Test
    fun theServerRefusingThisAccountDropsTheKeptSheet() = runTest {
        answer("/finance/sheet", workbookJson)
        val repository = financeRepository(FakeServerConnection("https://box.ts.net"))
        repository.personalFinance().getOrThrow()

        answer("/finance/sheet", """{"detail":{"error":"not_allowed","message":"Admins only"}}""", HttpStatusCode.Forbidden)
        assertTrue(repository.personalFinance(refresh = true).isFailure)
        down = true
        assertTrue(repository.personalFinance(refresh = true).isFailure, "nothing left to fall back on")
        assertNull(saved.read(FinanceSaved.SHEET, RelayWorkbook.serializer()))
    }

    @Test
    fun aSheetThatIsNotSetUpIsSaidSoRatherThanAnsweredWithAnOldOne() = runTest {
        answer("/finance/sheet", workbookJson)
        val repository = financeRepository(FakeServerConnection("https://box.ts.net"))
        repository.personalFinance().getOrThrow()

        answer("/finance/sheet", """{"detail":{"error":"not_shared","message":"Share it"}}""", HttpStatusCode.Forbidden)
        val problem = assertIs<SheetUnavailableException>(repository.personalFinance(refresh = true).exceptionOrNull())
        assertEquals(SheetProblem.NOT_SHARED, problem.problem)
    }

    @Test
    fun aQuoteThatCannotBeFetchedFailsWithTheKeptOneOfferedBesideIt() = runTest {
        answer(
            "/v7/finance/spark",
            """{"spark":{"result":[{"symbol":"^GSPC","response":[{"meta":{"regularMarketPrice":5000.0,"previousClose":4950.0,"regularMarketTime":1789990000},"timestamp":[1,2],"indicators":{"quote":[{"close":[4990.0,5000.0]}]}}]}]}}""",
        )
        financeRepository(FakeServerConnection(url = null)).quotes(listOf("^GSPC"), maxAgeMillis = 0).getOrThrow()

        down = true
        val reopened = financeRepository(FakeServerConnection(url = null))
        assertTrue(reopened.quotes(listOf("^GSPC"), maxAgeMillis = 0).isFailure, "an old price is never handed over as the live one")
        val kept = reopened.savedQuotes(listOf("^GSPC", "NEVER"))
        assertEquals(listOf("^GSPC"), kept.map { it.symbol })
        assertEquals(5000.0, kept.single().price)
        assertEquals(1_789_990_000, kept.single().marketTimeEpochSeconds, "and it carries the moment it was the price")
    }

    @Test
    fun theMarketsNeedNoFingerprint() = runTest {
        saved.write(FinanceSaved.quote("^GSPC"), Quote.serializer(), quote)
        access.goOffline()
        assertEquals(listOf(quote), financeRepository(FakeServerConnection(url = null)).savedQuotes(listOf("^GSPC")), "an index is anyone's to see")
    }

    @Test
    fun aChartIsKeptForWhenItCannotBeFetched() = runTest {
        answer(
            "/v8/finance/chart/AAPL",
            """{"chart":{"result":[{"meta":{"chartPreviousClose":190.0,"gmtoffset":-14400},"timestamp":[100,200],"indicators":{"quote":[{"close":[191.0,192.5],"high":[191.5,193.0]}]}}]}}""",
        )
        financeRepository(FakeServerConnection(url = null)).history("AAPL", ChartRange.YEAR).getOrThrow()

        down = true
        val reopened = financeRepository(FakeServerConnection(url = null))
        assertTrue(reopened.history("AAPL", ChartRange.YEAR).isFailure)
        val kept = assertNotNull(reopened.savedHistory("AAPL", ChartRange.YEAR))
        assertEquals(listOf(191.0, 192.5), kept.series.values.toList())
        assertNull(reopened.savedHistory("AAPL", ChartRange.DAY), "a range never looked at has nothing kept")
    }

    @Test
    fun anEconomicSeriesIsAnsweredFromTheDeviceWhenFredIsNot() = runTest {
        answer("/graph/fredgraph.csv", "observation_date,UNRATE\n2026-07-01,4.2\n2026-08-01,4.3\n")
        financeRepository(FakeServerConnection(url = null)).fredSeries("UNRATE", "1990-01-01").getOrThrow()

        down = true
        val reopened = financeRepository(FakeServerConnection(url = null))
        val kept = reopened.fredSeries("UNRATE", "1990-01-01").getOrThrow()
        assertEquals(listOf(4.2, 4.3), kept.values.toList(), "each reading is shown with its date, so last month's series says what it is")
        assertTrue(reopened.fredSeries("NEVER", "1990-01-01").isFailure)
    }
}
