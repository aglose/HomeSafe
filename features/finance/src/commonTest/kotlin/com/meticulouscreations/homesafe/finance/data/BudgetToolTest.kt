package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.assistant.MAX_TOOL_RESULT_CHARS
import com.meticulouscreations.homesafe.finance.FakeFinanceAccess
import com.meticulouscreations.homesafe.finance.domain.AmazonItem
import com.meticulouscreations.homesafe.finance.domain.AmazonMatch
import com.meticulouscreations.homesafe.finance.domain.AmazonNeed
import com.meticulouscreations.homesafe.finance.domain.AmazonOrder
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.BucketSource
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetCard
import com.meticulouscreations.homesafe.finance.domain.BudgetConfig
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetPastMonth
import com.meticulouscreations.homesafe.finance.domain.BudgetRepository
import com.meticulouscreations.homesafe.finance.domain.CardRole
import com.meticulouscreations.homesafe.finance.domain.LinePick
import com.meticulouscreations.homesafe.finance.domain.MerchantRule
import com.meticulouscreations.homesafe.finance.domain.NeedSource
import com.meticulouscreations.homesafe.finance.domain.Purchase
import com.meticulouscreations.homesafe.finance.domain.RecurringCharge
import com.meticulouscreations.homesafe.finance.domain.RecurringCharges
import com.meticulouscreations.homesafe.finance.domain.RecurringRhythm
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_error_not_connected
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the assistant reads of the budget: all of a month that fits, what it left out when it doesn't, and what it can ask for. */
class BudgetToolTest {

    private val cards = listOf(
        BudgetCard("Capital One Venture", "ins_1", "Capital One", "Venture", "1203", CardRole.Split, 412.5, needsRelink = false, readsPurchases = true),
        BudgetCard("Amex Gold", "ins_2", "Amex", "Gold", "1006", CardRole.Family, null, needsRelink = true, readsPurchases = true),
        BudgetCard("Store card", "ins_3", "Target", "RedCard", null, CardRole.Unset, 0.0, needsRelink = false, readsPurchases = true),
        BudgetCard("Work card", "ins_4", "Chase", "Ink", null, CardRole.Ignore, 0.0, needsRelink = false, readsPurchases = true),
        BudgetCard("Car loan", "ins_5", "Ally", "Auto", null, CardRole.Unset, 0.0, needsRelink = false, readsPurchases = false),
    )

    private fun purchase(date: String, amount: Double, name: String, category: String? = "FOOD_AND_DRINK", card: String = "Capital One Venture", note: String? = null, amazon: AmazonOrder? = null) = Purchase(
        id = "a-plaid-transaction-id-of-thirty-seven",
        date = date,
        amount = amount,
        name = name,
        category = category,
        pending = false,
        cardKey = card,
        bucket = BucketId.Family,
        source = BucketSource.ACCOUNT,
        remembered = false,
        note = note,
        amazon = amazon,
        fromAmazon = amazon != null,
    )

    private fun budget(month: String, purchases: List<Purchase> = emptyList(), current: Boolean = false) = Budget(
        configured = true,
        sandbox = false,
        month = month,
        isCurrentMonth = current,
        day = 31,
        daysInMonth = 31,
        syncedAtEpochSeconds = 1_790_000_000,
        changedAtEpochSeconds = null,
        syncing = false,
        nextSyncAtEpochSeconds = null,
        ready = true,
        cards = cards,
        config = BudgetConfig(),
        savingsLine = null,
        spent = purchases.sumOf { it.amount },
        pending = 0.0,
        buckets = emptyList(),
        daily = emptyList(),
        categories = emptyList(),
        merchants = emptyList(),
        purchases = purchases,
        history = listOf(BudgetPastMonth("2026-09", 4_321.0, 30)),
        recurring = RecurringCharges(
            15.0,
            listOf(RecurringCharge("Netflix", RecurringRhythm.MONTHLY, 15.0, null, 15.0, bill = false, count = 6, first = "2026-05-01", last = "2026-10-01", next = "2026-11-01", stopped = false, cardKey = "Amex Gold", bucket = BucketId.Family)),
        ),
    )

    /** The months a test has, by `YYYY-MM`; null reads as this month, [THIS_MONTH]. */
    private val months = HashMap<String, Budget>()
    private val asked = ArrayList<String?>()
    private val access = FakeFinanceAccess()
    private val tool = BudgetTool(FakeBudgets(), access)

    private suspend fun ask(vararg input: Pair<String, String>): String = tool.run(buildJsonObject { input.forEach { (key, value) -> put(key, value) } })

    private fun many(month: String, count: Int) = List(count) { i ->
        purchase("$month-${(27 - i * 27 / count).toString().padStart(2, '0')}", 10.0 + i, "A merchant with quite a long name $i")
    }

    // --- A whole month ---

    @Test
    fun aMonthThatOverflowedAsItsToStringIsNowReadInFull() = runTest {
        val purchases = many(THIS_MONTH, 400)
        val month = budget(THIS_MONTH, purchases, current = true)
        months[THIS_MONTH] = month
        assertTrue(month.toString().length > MAX_TOOL_RESULT_CHARS, "the month this was written for, cut off before")

        val text = ask()

        assertTrue(text.length <= MAX_TOOL_RESULT_CHARS)
        purchases.forEach { assertTrue(it.name in text, "${it.name} is missing") }
        assertTrue("Purchases: 400 coming to" in text)
        assertFalse("Too many to list" in text)
    }

    @Test
    fun whatSumsUpTheMonthComesBeforeThePurchases() = runTest {
        months[THIS_MONTH] = budget(THIS_MONTH, many(THIS_MONTH, 3), current = true)

        val text = ask()

        val purchases = text.indexOf("Purchases:")
        listOf("Spent ", "The months before", "2026-09: 4321.00", "Netflix").forEach {
            assertTrue(text.indexOf(it) in 0 until purchases, "$it comes before the purchases")
        }
    }

    @Test
    fun aPurchaseIsOneLineOfWhatIsWorthReading() {
        val note = "a tire\nfor the Tesla"
        val text = budgetText(BudgetAsk(), budget(THIS_MONTH), listOf(budget(THIS_MONTH, listOf(purchase("2026-10-12", -12.5, " AMERICAN ", "TRAVEL", note = note)))))

        assertTrue("2026-10-12 | -12.50 | AMERICAN | TRAVEL | Capital One Venture | family | - | note: a tire for the Tesla" in text.lines())
        assertFalse("a-plaid-transaction-id" in text, "the assistant can't change anything, so has no use for an id")
    }

    @Test
    fun anAmazonChargeSaysWhatItBought() {
        val order = AmazonOrder(
            "112-1",
            AmazonMatch.SHIPMENT,
            "2026-10-10",
            listOf(AmazonItem(0, "Dish soap", 2, 4.5, AmazonNeed.ESSENTIAL, NeedSource.RULE)),
            essential = 9.0,
            discretionary = 0.0,
            unsorted = 0.0,
        )
        val text = budgetText(BudgetAsk(), budget(THIS_MONTH), listOf(budget(THIS_MONTH, listOf(purchase("2026-10-12", 9.0, "Amazon", amazon = order)))))

        assertTrue("Amazon order of 2026-10-10: Dish soap x2 at 4.50 (essential)" in text)
    }

    // --- What isn't there ---

    @Test
    fun whatDoesntFitIsSaidNotDropped() {
        val purchases = many(THIS_MONTH, 2_000)
        val month = budget(THIS_MONTH, purchases, current = true)

        val text = budgetText(BudgetAsk(), month, listOf(month))

        assertTrue(text.length <= MAX_TOOL_RESULT_CHARS, "${text.length} characters")
        val shown = text.lines().count { it.startsWith("$THIS_MONTH-") }
        assertTrue(shown in 1 until 2_000)
        assertTrue("Purchases: 2000 coming to 2019000.00" in text, "the total is of all of them")
        val omitted = purchases.drop(shown)
        assertTrue("only the newest $shown are below. Left out: ${omitted.size} coming to" in text)
        assertTrue("from ${omitted.last().date} to ${omitted.first().date}" in text)
        assertTrue(text.indexOf("Netflix") in 0 until text.indexOf("Purchases:"), "the month's figures survive it")
    }

    @Test
    fun cardsLeftOutOfTheBudgetAreNamedWithWhy() = runTest {
        months[THIS_MONTH] = budget(THIS_MONTH, current = true)

        listOf(ask(), ask("search" to "anything")).forEach { text ->
            assertTrue("Store card (no role given yet)" in text)
            assertTrue("Work card (set to be no part of the budget)" in text)
            assertTrue("Car loan (linked as a loan, so its purchases were never read)" in text)
            assertTrue("Need signing in to again, so their newest purchases may be missing: Amex Gold" in text)
            assertTrue("bank accounts" in text)
        }
    }

    // --- Asking for less, or further back ---

    @Test
    fun aSearchFindsAMerchantANoteOrAnAmazonItem() = runTest {
        val order = AmazonOrder("112-1", AmazonMatch.ORDER, "2026-10-01", listOf(AmazonItem(0, "American flag", 1, 20.0, null, NeedSource.NONE)), 0.0, 0.0, 20.0)
        months[THIS_MONTH] = budget(
            THIS_MONTH,
            listOf(
                purchase("2026-10-04", 528.8, "AMERICAN ", "GENERAL_MERCHANDISE"),
                purchase("2026-10-03", 40.0, "Baggage desk", "TRAVEL", note = "american bag fee"),
                purchase("2026-10-02", 20.0, "Amazon", amazon = order),
                purchase("2026-10-01", 5.0, "Coffee"),
            ),
            current = true,
        )

        val text = ask("search" to "American")

        assertTrue("Purchases: 3 coming to 588.80" in text)
        assertFalse("Coffee" in text)
        assertTrue("Purchases in $THIS_MONTH only" in text, "it says it looked at no other month")
    }

    @Test
    fun aCategoryOrACardKeepsToThose() = runTest {
        months[THIS_MONTH] = budget(
            THIS_MONTH,
            listOf(
                purchase("2026-10-04", 10.0, "Hotel", "TRAVEL", card = "Amex Gold"),
                purchase("2026-10-03", 20.0, "Train", "TRAVEL"),
                purchase("2026-10-02", 40.0, "Coffee", "FOOD_AND_DRINK", card = "Amex Gold"),
            ),
            current = true,
        )

        assertTrue("Purchases: 2 coming to 30.00" in ask("category" to "travel"))
        assertTrue("Purchases: 1 coming to 40.00" in ask("category" to "food and drink"))
        assertTrue("Purchases: 2 coming to 50.00" in ask("card" to "1006"), "by its last four digits")
        assertTrue("Purchases: 2 coming to 50.00" in ask("card" to "amex"))
        assertTrue("Purchases: 1 coming to 10.00" in ask("card" to "amex", "category" to "travel"))
    }

    @Test
    fun aRangeReadsEveryMonthItTouchesAndKeepsToItsDays() = runTest {
        months[THIS_MONTH] = budget(THIS_MONTH, listOf(purchase("2026-10-02", 1.0, "AMERICAN"), purchase("2026-10-20", 2.0, "AMERICAN")), current = true)
        months["2026-09"] = budget("2026-09", listOf(purchase("2026-09-23", 528.8, "AMERICAN")))
        months["2026-08"] = budget("2026-08", listOf(purchase("2026-08-31", 4.0, "AMERICAN"), purchase("2026-08-01", 8.0, "AMERICAN")))

        val text = ask("from" to "2026-08-15", "to" to "2026-10-10", "search" to "american")

        assertEquals(listOf(null, "2026-09", "2026-08"), asked, "this month is read once, to know which it is")
        assertTrue("Purchases: 3 coming to 533.80" in text, text)
        val dates = text.lines().filter { it.startsWith("2026-") }.map { it.take(10) }
        assertEquals(listOf("2026-10-02", "2026-09-23", "2026-08-31"), dates, "newest first, across the months")
    }

    @Test
    fun aRangeWithoutAnEndGoesUpToThisMonthAndNoFurther() = runTest {
        months[THIS_MONTH] = budget(THIS_MONTH, current = true)
        months["2026-09"] = budget("2026-09")

        ask("from" to "2026-09-01")
        assertEquals(listOf(null, "2026-09"), asked)

        asked.clear()
        ask("from" to "2026-09-01", "to" to "2027-03-01")
        assertEquals(listOf(null, "2026-09"), asked, "months still to come have nothing to read")
    }

    @Test
    fun aMonthThatCouldntBeReadIsSaid() = runTest {
        months[THIS_MONTH] = budget(THIS_MONTH, listOf(purchase("2026-10-02", 1.0, "AMERICAN")), current = true)

        val text = ask("from" to "2026-09-01", "search" to "american")

        assertTrue("Couldn't read 2026-09" in text)
        assertTrue("Purchases: 1 coming to 1.00" in text)
    }

    @Test
    fun aRefusalEndsARangeAndIsSaidRatherThanCalledAnUnreadMonth() = runTest {
        months[THIS_MONTH] = budget(THIS_MONTH, listOf(purchase("2026-10-02", 1.0, "AMERICAN")), current = true)
        refused["2026-09"] = BankSyncException(BankProblem.NOT_ALLOWED, UiText.of(Res.string.fin_data_error_not_connected), technical = "Not allowed")

        val text = ask("from" to "2026-07-01")

        assertEquals("Couldn't read the budget: ${refused["2026-09"]?.message}", text)
        assertEquals(listOf(null, "2026-09"), asked, "nothing further is asked of a server that refused")
    }

    @Test
    fun aSummaryTooLongToFitStillLeavesThePurchases() {
        val rules = List(3_000) { MerchantRule("a merchant someone remembered $it", BucketId.Family) }
        val month = budget(THIS_MONTH, many(THIS_MONTH, 50), current = true).copy(config = BudgetConfig(rules = rules))

        val text = budgetText(BudgetAsk(), month, listOf(month))

        assertTrue(text.length <= MAX_TOOL_RESULT_CHARS, "${text.length} characters")
        assertTrue("the rest of this summary is too long to show" in text)
        assertTrue("Purchases: 50 coming to" in text)
        assertEquals(50, text.lines().count { it.startsWith("$THIS_MONTH-") })
    }

    @Test
    fun aPurchaseWithNoCategoryIsUnderOtherAsTheMonthCountsIt() = runTest {
        months[THIS_MONTH] = budget(THIS_MONTH, listOf(purchase("2026-10-02", 3.0, "Mystery", category = null), purchase("2026-10-01", 4.0, "Coffee")), current = true)

        assertTrue("Purchases: 1 coming to 3.00" in ask("category" to "other"))
    }

    @Test
    fun whatIsNarrowedStillSaysHowFreshItIsAndWhatItCouldntSearch() = runTest {
        months[THIS_MONTH] = budget(THIS_MONTH, listOf(purchase("2026-10-02", 3.0, "Amazon").copy(fromAmazon = true)), current = true)

        val text = ask("from" to "2026-10-01", "search" to "dish soap")

        assertTrue("Cards last read at 1790000000" in text)
        assertTrue("The newest month known is 2026-10." in text)
        assertTrue("Purchases from 2026-10-01 onward" in text)
        assertTrue("1 Amazon charges here have no order found" in text)
    }

    @Test
    fun askingForTooMuchOrInTheWrongShapeIsAnswered() = runTest {
        months[THIS_MONTH] = budget(THIS_MONTH, current = true)

        assertTrue("at most 24" in ask("from" to "2020-01-01"))
        assertEquals("month is YYYY-MM.", ask("month" to "October"))
        assertEquals("from is a real day, YYYY-MM-DD.", ask("from" to "2026-09"))
        assertEquals("from is a real day, YYYY-MM-DD.", ask("from" to "2026-02-30"))
        assertEquals("to is a real day, YYYY-MM-DD.", ask("from" to "2025-01-01", "to" to "2025-02-29"))
        assertTrue(isDay("2024-02-29"))
        assertFalse(isDay("1900-02-29"))
        assertFalse(isDay("2026-04-31"))
        assertEquals("Give from as well as to.", ask("to" to "2026-09-01"))
        assertEquals("from is after to.", ask("from" to "2026-09-02", "to" to "2026-09-01"))
        assertEquals("Give month, or from and to, not both.", ask("month" to "2026-09", "from" to "2026-09-01"))
    }

    // --- The lock ---

    @Test
    fun nothingComesOutWhileLocked() = runTest {
        months[THIS_MONTH] = budget(THIS_MONTH, listOf(purchase("2026-10-02", 1.0, "AMERICAN")), current = true)
        access.goOffline()

        listOf(tool.run(JsonObject(emptyMap())), ask("from" to "2026-09-01")).forEach {
            assertTrue(it.startsWith("The finance app is locked"), it)
            assertFalse("AMERICAN" in it)
        }
        assertEquals(emptyList(), asked)
    }

    @Test
    fun aLockThatComesOnDuringTheReadStillHoldsItBack() = runTest {
        months[THIS_MONTH] = budget(THIS_MONTH, listOf(purchase("2026-10-02", 1.0, "AMERICAN")), current = true)
        lockOnRead = true

        val text = ask()

        assertTrue(text.startsWith("The finance app is locked"), text)
        assertFalse("AMERICAN" in text)
    }

    private var lockOnRead = false
    private val refused = HashMap<String?, BankSyncException>()

    private inner class FakeBudgets : BudgetRepository {
        override suspend fun budget(month: String?): Result<Budget> {
            asked += month
            if (lockOnRead) access.goOffline()
            refused[month]?.let { return Result.failure(it) }
            return months[month ?: THIS_MONTH]?.let { Result.success(it) } ?: Result.failure(IllegalStateException("no such month"))
        }

        override suspend fun save(patch: BudgetConfigPatch): Result<Budget> = error("the assistant only reads")

        override suspend fun tag(purchaseId: String, bucket: BucketId, remember: Boolean): Result<Budget> = error("the assistant only reads")

        override suspend fun forgetRule(merchantKey: String): Result<Budget> = error("the assistant only reads")

        override suspend fun tagLine(purchaseId: String, line: LinePick, remember: Boolean): Result<Budget> = error("the assistant only reads")

        override suspend fun forgetLineRule(merchantKey: String): Result<Budget> = error("the assistant only reads")

        override suspend fun note(purchaseId: String, note: String?): Result<Budget> = error("the assistant only reads")

        override suspend fun sayNeed(orderId: String, item: AmazonItem, need: AmazonNeed?): Result<Budget> = error("the assistant only reads")

        override suspend fun syncNow(): Result<Budget> = error("the assistant only reads")
    }

    private companion object {
        const val THIS_MONTH = "2026-10"
    }
}
