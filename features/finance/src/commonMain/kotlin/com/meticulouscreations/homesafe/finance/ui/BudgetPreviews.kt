package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.finance.BudgetUiState
import com.meticulouscreations.homesafe.finance.domain.AmazonItem
import com.meticulouscreations.homesafe.finance.domain.AmazonMatch
import com.meticulouscreations.homesafe.finance.domain.AmazonNeed
import com.meticulouscreations.homesafe.finance.domain.AmazonOrder
import com.meticulouscreations.homesafe.finance.domain.AmazonSummary
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.BucketSource
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetBucket
import com.meticulouscreations.homesafe.finance.domain.BudgetCard
import com.meticulouscreations.homesafe.finance.domain.BudgetConfig
import com.meticulouscreations.homesafe.finance.domain.BudgetLimits
import com.meticulouscreations.homesafe.finance.domain.BudgetLine
import com.meticulouscreations.homesafe.finance.domain.BudgetPastMonth
import com.meticulouscreations.homesafe.finance.domain.BudgetSheetFigures
import com.meticulouscreations.homesafe.finance.domain.BudgetSlice
import com.meticulouscreations.homesafe.finance.domain.CardHolder
import com.meticulouscreations.homesafe.finance.domain.CardRole
import com.meticulouscreations.homesafe.finance.domain.LineRule
import com.meticulouscreations.homesafe.finance.domain.LineSource
import com.meticulouscreations.homesafe.finance.domain.MerchantRule
import com.meticulouscreations.homesafe.finance.domain.NeedSource
import com.meticulouscreations.homesafe.finance.domain.Purchase
import com.meticulouscreations.homesafe.finance.domain.PurchaseShare
import com.meticulouscreations.homesafe.finance.domain.RecurringCharge
import com.meticulouscreations.homesafe.finance.domain.RecurringCharges
import com.meticulouscreations.homesafe.finance.domain.RecurringRhythm
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_budget_error_relay_outdated
import kotlin.time.Clock

/**
 * A made-up household's month for the Budget previews and tests: two people on a shared travel
 * card, a family card, and a month that can be dialled from comfortably under its limit to well
 * into the savings. Nothing here is anyone's real spending.
 */
object BudgetFixtures {
    private val now = Clock.System.now().epochSeconds
    const val TRAVEL = "Summit Bank Voyager 4410"
    const val FAMILY = "Northwind Platinum 2207"
    const val CHECKING = "Summit Bank Everyday Checking 3381"
    val people = listOf("Alex", "Sam")

    val cards = listOf(
        BudgetCard(
            TRAVEL,
            "item-1",
            "Summit Bank",
            "Voyager",
            "4410",
            CardRole.Split,
            balance = 1_840.0,
            needsRelink = false,
            readsPurchases = true,
            holders = listOf(CardHolder("4410", 61, "Alex"), CardHolder("7726", 38, "Sam")),
        ),
        BudgetCard(FAMILY, "item-2", "Northwind", "Platinum", "2207", CardRole.Family, balance = 2_410.0, needsRelink = false, readsPurchases = true),
    )

    private data class Shop(
        val name: String,
        val category: String,
        val card: String,
        val bucket: BucketId,
        val source: BucketSource,
        val amount: Double,
        val line: String? = null,
        val lineSource: LineSource = LineSource.NONE,
    )

    const val GROCERIES = "Groceries"

    /** The budget sheet's lines paid by card, and what it plans for each a month. */
    val planned = mapOf(GROCERIES to 600.0, "Meal kits" to 240.0, "Streaming" to 45.0)

    private val alex = BucketId.Person("Alex")
    private val sam = BucketId.Person("Sam")
    private val shops = listOf(
        Shop("Harvest Market", "FOOD_AND_DRINK", FAMILY, BucketId.Family, BucketSource.ACCOUNT, 182.40, GROCERIES, LineSource.AUTO),
        Shop("Corner Coffee", "FOOD_AND_DRINK", TRAVEL, alex, BucketSource.RULE, 6.75),
        Shop("Blue Line Transit", "TRANSPORTATION", TRAVEL, sam, BucketSource.MANUAL, 48.00),
        Shop("Warehouse Club", "GENERAL_MERCHANDISE", FAMILY, BucketId.Family, BucketSource.ACCOUNT, 311.18, GROCERIES, LineSource.RULE),
        Shop("Trailhead Outfitters", "GENERAL_MERCHANDISE", TRAVEL, BucketId.Unassigned, BucketSource.NONE, 129.99),
        Shop("Luna Pizzeria", "FOOD_AND_DRINK", TRAVEL, BucketId.Unassigned, BucketSource.NONE, 54.20),
        Shop("City Power", "RENT_AND_UTILITIES", FAMILY, BucketId.Family, BucketSource.ACCOUNT, 141.02),
        Shop("Skyway Air", "TRAVEL", TRAVEL, sam, BucketSource.BANK, 412.60),
        Shop("Page & Spine Books", "ENTERTAINMENT", TRAVEL, alex, BucketSource.MANUAL, 37.45),
        Shop("Petal & Stem", "GENERAL_MERCHANDISE", TRAVEL, BucketId.Unassigned, BucketSource.NONE, 68.00),
        Shop("Greenway Pharmacy", "MEDICAL", FAMILY, BucketId.Family, BucketSource.ACCOUNT, 23.80),
        Shop("Hilltop Hardware", "HOME_IMPROVEMENT", TRAVEL, alex, BucketSource.RULE, 96.30),
    )

    /** The five months before October, the oldest first. */
    private val pastMonths = listOf(
        BudgetPastMonth("2026-05", 3_910.0, 31),
        BudgetPastMonth("2026-06", 4_720.0, 30),
        BudgetPastMonth("2026-07", 4_180.0, 31),
        BudgetPastMonth("2026-08", 5_260.0, 31),
        BudgetPastMonth("2026-09", 4_050.0, 30),
    )

    /**
     * The month on its [day]th day, with every purchase [scale] times its listed size: about 0.4
     * is a month comfortably under [limit], 0.6 one that ends well over it. October, the month
     * under way, unless [yearMonth] says another.
     */
    fun month(
        day: Int = 12,
        scale: Double = 0.42,
        limit: Double? = 4_500.0,
        sandbox: Boolean = false,
        cards: List<BudgetCard> = this.cards,
        yearMonth: String = "2026-10",
        daysInMonth: Int = 31,
        history: List<BudgetPastMonth> = pastMonths,
    ): Budget {
        val purchases = (1..day).flatMap { d ->
            // Two or three purchases a day, walking round the shops, the newest day first.
            (0 until 2 + d % 2).map { n ->
                val shop = shops[(d * 3 + n * 5) % shops.size]
                Purchase(
                    id = "p-$d-$n",
                    date = "$yearMonth-${d.toString().padStart(2, '0')}",
                    amount = ((shop.amount * scale) * 100).toLong() / 100.0,
                    name = shop.name,
                    category = shop.category,
                    pending = d == day && n == 0,
                    cardKey = shop.card,
                    bucket = shop.bucket,
                    source = shop.source,
                    remembered = shop.source == BucketSource.RULE,
                    line = shop.line,
                    lineSource = shop.lineSource,
                    lineRemembered = shop.lineSource == LineSource.RULE,
                )
            }
        }.sortedByDescending { it.date }
        val limits = BudgetLimits(total = limit, people = mapOf("Alex" to 900.0, "Sam" to 1_200.0), family = 2_400.0)
        fun bucket(id: BucketId, limit: Double?) = purchases.filter { it.bucket == id }.let { BudgetBucket(id, it.sumOf { p -> p.amount }, limit, it.size) }
        return Budget(
            configured = true,
            sandbox = sandbox,
            month = yearMonth,
            isCurrentMonth = yearMonth == "2026-10",
            day = day,
            daysInMonth = daysInMonth,
            syncedAtEpochSeconds = now - 12 * 60,
            changedAtEpochSeconds = now - 5 * 3_600,
            syncing = false,
            nextSyncAtEpochSeconds = now + 48 * 60,
            ready = true,
            cards = cards,
            config = BudgetConfig(
                people = people,
                limits = limits,
                cardPaidLines = planned.keys.toList(),
                rules = listOf(MerchantRule("corner coffee", alex), MerchantRule("hilltop hardware", alex)),
                lineRules = listOf(LineRule("warehouse club", GROCERIES)),
                sheet = BudgetSheetFigures(takeHome = 14_170.0, billsOffCard = 9_817.0, lines = planned),
            ),
            savingsLine = 4_353.0,
            spent = purchases.sumOf { it.amount },
            pending = purchases.filter { it.pending }.sumOf { it.amount },
            buckets = listOf(bucket(alex, limits.people["Alex"]), bucket(sam, limits.people["Sam"]), bucket(BucketId.Family, limits.family), bucket(BucketId.Unassigned, null)),
            // Groceries usually come to more than the sheet plans for them.
            lines = planned.map { (line, amount) ->
                purchases.filter { it.line == line }.let { BudgetLine(line, amount, it.sumOf { p -> p.amount }, it.size, usual = if (line == GROCERIES) 720.0 else null) }
            },
            outsideLines = purchases.filter { it.line == null }.let { BudgetSlice("", it.sumOf { p -> p.amount }, it.size) },
            daily = (1..day).map { d -> purchases.filter { it.date.endsWith("-${d.toString().padStart(2, '0')}") }.sumOf { it.amount } },
            categories = purchases.groupBy { it.category.orEmpty() }.map { (name, list) -> BudgetSlice(name, list.sumOf { it.amount }, list.size) }.sortedByDescending { it.spent },
            merchants = purchases.groupBy { it.name }.map { (name, list) -> BudgetSlice(name, list.sumOf { it.amount }, list.size) }.sortedByDescending { it.spent }.take(8),
            purchases = purchases,
            history = history,
        )
    }

    /** On the 12th, comfortably under. */
    val onPace = month(scale = 0.42)

    /** On the 24th, within a fifth of the limit. */
    val close = month(day = 24, scale = 0.48)

    /** On the 26th, over the limit and past what take-home leaves. */
    val dipping = month(day = 26, scale = 0.56)

    /** Two cards just linked and nothing said about either. */
    val unsorted = month(day = 3).let { it.copy(cards = it.cards.map { card -> card.copy(role = CardRole.Unset) }, purchases = emptyList(), spent = 0.0, pending = 0.0, daily = emptyList(), buckets = emptyList(), categories = emptyList(), merchants = emptyList()) }

    /** The shared card's bank says which card made each purchase, and nobody has yet said whose each is. */
    val holdersUnsaid = month().let { it.copy(cards = it.cards.map { card -> card.copy(holders = card.holders.map { holder -> holder.copy(person = null) }) }) }

    val noCards = month(day = 3, cards = emptyList()).copy(purchases = emptyList(), spent = 0.0)

    /** No line of the sheet ticked as paid by card yet, so nothing is sorted into lines. */
    val noLines = onPace.let { it.copy(config = it.config.copy(cardPaidLines = emptyList(), lineRules = emptyList()), lines = emptyList(), purchases = it.purchases.map { p -> p.copy(line = null, lineSource = LineSource.NONE) }) }

    /** Three Amazon charges: an order of a need and a want, a want that came as its own shipment, and one no order email was found for. */
    val amazonPurchases = listOf(
        Purchase(
            id = "amz-1", date = "2026-10-09", amount = 113.63, name = "Amazon", category = "GENERAL_MERCHANDISE", pending = false, cardKey = FAMILY,
            bucket = BucketId.Person("Alex"), source = BucketSource.AMAZON, remembered = false, fromAmazon = true,
            // The paper towels are the family's; the building set is Alex's, whose mailbox the order came to.
            shares = listOf(PurchaseShare(BucketId.Person("Alex"), 86.58), PurchaseShare(BucketId.Family, 27.05)),
            amazon = AmazonOrder(
                orderId = "112-0000000-0000001",
                match = AmazonMatch.ORDER,
                ordered = "2026-10-08",
                items = listOf(
                    AmazonItem(0, "Ridgeline Paper Towels, 12 Big Rolls", 1, 24.99, AmazonNeed.ESSENTIAL, NeedSource.RULE),
                    AmazonItem(1, "Starfighter Brick Building Set", 2, 39.99, AmazonNeed.DISCRETIONARY, NeedSource.USER),
                ),
                essential = 27.05,
                discretionary = 86.58,
                unsorted = 0.0,
            ),
        ),
        Purchase(
            id = "amz-2", date = "2026-10-07", amount = 70.34, name = "Amazon", category = "GENERAL_MERCHANDISE", pending = false, cardKey = TRAVEL,
            bucket = BucketId.Person("Sam"), source = BucketSource.BANK, remembered = false, fromAmazon = true,
            amazon = AmazonOrder(
                orderId = "113-0000000-0000002",
                match = AmazonMatch.SHIPMENT,
                ordered = "2026-10-05",
                items = listOf(AmazonItem(0, "Meadow Brand Adult Dog Food, 35 lb", 1, 64.98, null, NeedSource.NONE)),
                essential = 0.0,
                discretionary = 0.0,
                unsorted = 70.34,
            ),
        ),
        Purchase(
            id = "amz-3", date = "2026-10-06", amount = 15.0, name = "Amazon", category = "GENERAL_MERCHANDISE", pending = false, cardKey = TRAVEL,
            bucket = BucketId.Person("Alex"), source = BucketSource.BANK, remembered = false, fromAmazon = true,
        ),
    )

    val amazonSummary = AmazonSummary(
        configured = true,
        readAtEpochSeconds = now - 20 * 60,
        failedMailboxes = emptyList(),
        charges = 3,
        essential = 27.05,
        discretionary = 86.58,
        unsorted = 70.34,
        unmatched = 15.0,
    )

    /** On the 12th with the Amazon order emails read, and a note on one purchase. */
    val withAmazon = onPace.let { month ->
        val noted = month.purchases.map { p -> if (p.id == "p-12-0") p.copy(note = "Birthday cake for Sam") else p }
        month.copy(purchases = (noted + amazonPurchases).sortedByDescending { p -> p.date }, amazon = amazonSummary)
    }

    /**
     * What the cards and the checking account pay again and again, on the 12th: a student loan
     * and a phone bill from checking, each in its bill line, a weekly meal box, a bill, a streaming
     * service whose price went up and a storage plan whose price came down, one a few days
     * overdue, and two that stopped.
     */
    val recurring = run {
        fun charge(
            name: String,
            amount: Double,
            next: String?,
            was: Double? = null,
            rhythm: RecurringRhythm = RecurringRhythm.MONTHLY,
            monthly: Double = amount,
            bill: Boolean = false,
            card: String = TRAVEL,
            bucket: BucketId = alex,
            last: String = "2026-10-01",
            checking: Boolean = false,
            line: String? = null,
        ) = RecurringCharge(
            name, rhythm, amount, was, monthly, bill, count = 4, first = "2026-06-01", last = last, next = next, stopped = next == null,
            cardKey = if (checking) CHECKING else card, bucket = if (checking) BucketId.Unassigned else bucket, fromChecking = checking, line = line,
        )
        RecurringCharges(
            monthly = 1_925.70,
            charges = listOf(
                charge("Summit Student Loans", 1_140.0, "2026-11-07", checking = true, line = "Student loan", last = "2026-10-07"),
                charge("Fresh Plate Meals", 89.99, "2026-10-14", was = 79.99, rhythm = RecurringRhythm.WEEKLY, monthly = 389.96, card = FAMILY, bucket = BucketId.Family, last = "2026-10-07"),
                charge("City Power", 141.02, "2026-11-03", monthly = 138.40, bill = true, card = FAMILY, bucket = BucketId.Family, last = "2026-10-03"),
                charge("Bright Mobile", 118.20, "2026-10-24", monthly = 112.40, bill = true, checking = true, line = "Phone", last = "2026-09-24"),
                charge("Fiber Net", 59.99, "2026-11-09", card = FAMILY, bucket = BucketId.Family, last = "2026-10-09"),
                charge("Home Guard Monitoring", 29.99, "2026-10-09", last = "2026-09-09"),
                charge("Streamflix", 22.99, "2026-11-04", was = 19.99, card = FAMILY, bucket = BucketId.Family, last = "2026-10-04"),
                charge("Tunebox", 11.99, "2026-10-21", bucket = sam, last = "2026-09-21"),
                charge("Cloud Drive", 9.99, "2026-10-26", was = 14.99, last = "2026-09-26"),
                charge("Ride Pass", 9.99, "2026-10-30", bucket = sam, last = "2026-09-30"),
                charge("Pawsure Pet Insurance", 18.40, null, last = "2026-08-14"),
                charge("Peak Climbing Gym", 34.99, null, bucket = sam, last = "2026-08-03"),
            ),
        )
    }

    /** On the 12th with what the cards are charged again and again. */
    val withRecurring = onPace.copy(recurring = recurring)

    /** September, over and looked back at, a lighter month than October is shaping up to be. */
    val september = month(day = 30, scale = 0.32, yearMonth = "2026-09", daysInMonth = 30, history = listOf(BudgetPastMonth("2026-04", 4_330.0, 30)) + pastMonths.dropLast(1))

    fun state(budget: Budget) = BudgetUiState(budget = budget, loading = false, readAtEpochSeconds = now)

    /** September looked back at, with October (on its 12th) kept beside it, its September bar what September came to. */
    val lookingBack = BudgetUiState(
        budget = september,
        current = onPace.copy(history = pastMonths.dropLast(1) + BudgetPastMonth(september.month, september.spent, 30)),
        month = september.month,
        loading = false,
        readAtEpochSeconds = now,
    )
}

@Composable
private fun BudgetPreview(state: BudgetUiState) {
    FinanceStage {
        BudgetScreen(state, rememberLazyListState(), previewPadding, onTag = { _, _, _ -> }, onTagLine = { _, _, _ -> }, onSetRole = { _, _ -> }, onSetHolder = { _, _, _ -> }, onShowMonth = {}, onSyncNow = {}, onRetry = {}, onOpenLinkedAccounts = {}, onOpenSettings = {})
    }
}

@Composable
private fun BudgetDeskPreview(state: BudgetUiState) {
    FinanceDeskStage { padding ->
        BudgetScreen(state, rememberLazyListState(), padding, onTag = { _, _, _ -> }, onTagLine = { _, _, _ -> }, onSetRole = { _, _ -> }, onSetHolder = { _, _, _ -> }, onShowMonth = {}, onSyncNow = {}, onRetry = {}, onOpenLinkedAccounts = {}, onOpenSettings = {})
    }
}

@Preview(name = "Desk · Budget, with Amazon orders and recurring charges", widthDp = 1200, heightDp = 2600)
@Composable
private fun FinanceDeskBudgetPreview() {
    BudgetDeskPreview(BudgetFixtures.state(BudgetFixtures.withAmazon.copy(recurring = BudgetFixtures.recurring)))
}

@Preview(name = "Desk · Budget, September beside October", widthDp = 1200, heightDp = 1500)
@Composable
private fun FinanceDeskBudgetLookingBackPreview() {
    BudgetDeskPreview(BudgetFixtures.lookingBack)
}

@Preview(name = "Budget · on pace", widthDp = 412, heightDp = 3400)
@Preview(name = "Budget · on pace, in Spanish", widthDp = 412, heightDp = 3400, locale = "es")
@Composable
private fun FinanceBudgetPreview() {
    BudgetPreview(BudgetFixtures.state(BudgetFixtures.onPace))
}

@Preview(name = "Budget · September beside October", widthDp = 412, heightDp = 1700)
@Preview(name = "Budget · September beside October, in Spanish", widthDp = 412, heightDp = 1700, locale = "es")
@Composable
private fun FinanceBudgetLookingBackPreview() {
    BudgetPreview(BudgetFixtures.lookingBack)
}

@Preview(name = "Budget · with Amazon orders", widthDp = 412, heightDp = 3700)
@Composable
private fun FinanceBudgetAmazonScreenPreview() {
    BudgetPreview(BudgetFixtures.state(BudgetFixtures.withAmazon))
}

@Preview(name = "Budget · with recurring charges", widthDp = 412, heightDp = 4300)
@Composable
private fun FinanceBudgetRecurringScreenPreview() {
    BudgetPreview(BudgetFixtures.state(BudgetFixtures.withRecurring))
}

@Preview(name = "Budget · close to the limit", widthDp = 412, heightDp = 1500)
@Composable
private fun FinanceBudgetClosePreview() {
    BudgetPreview(BudgetFixtures.state(BudgetFixtures.close))
}

@Preview(name = "Budget · into savings", widthDp = 412, heightDp = 2400)
@Composable
private fun FinanceBudgetDippingPreview() {
    BudgetPreview(BudgetFixtures.state(BudgetFixtures.dipping.copy(sandbox = true)))
}

@Preview(name = "Budget · cards with no role yet", widthDp = 412, heightDp = 760)
@Composable
private fun FinanceBudgetRolesPreview() {
    BudgetPreview(BudgetFixtures.state(BudgetFixtures.unsorted))
}

@Preview(name = "Budget · whose card is which", widthDp = 412, heightDp = 1100)
@Composable
private fun FinanceBudgetHoldersPreview() {
    BudgetPreview(BudgetFixtures.state(BudgetFixtures.holdersUnsaid))
}

@Preview(name = "Budget · no cards linked", widthDp = 412, heightDp = 420)
@Composable
private fun FinanceBudgetNoCardsPreview() {
    BudgetPreview(BudgetFixtures.state(BudgetFixtures.noCards))
}

@Preview(name = "Budget · relay too old", widthDp = 412, heightDp = 420)
@Composable
private fun FinanceBudgetProblemPreview() {
    BudgetPreview(BudgetUiState(loading = false, problem = BankProblem.RELAY_OUTDATED, problemText = UiText.of(Res.string.fin_budget_error_relay_outdated)))
}

@Preview(name = "Budget · no budget lines yet", widthDp = 412, heightDp = 1500)
@Composable
private fun FinanceBudgetNoLinesPreview() {
    BudgetPreview(BudgetFixtures.state(BudgetFixtures.noLines))
}

@Preview(name = "Budget · settings", widthDp = 412, heightDp = 2000)
@Composable
private fun FinanceBudgetSettingsPreview() {
    FinanceStage { BudgetSettingsScreen(BudgetFixtures.state(BudgetFixtures.onPace), FinanceFixtures.finance, previewPadding, onSave = {}, onForgetRule = {}, onForgetLineRule = {}) }
}

@Preview(name = "Budget · the Wallet's line", widthDp = 412, heightDp = 160)
@Composable
private fun FinanceBudgetTeaserPreview() {
    FinanceStage { BudgetTeaser(BudgetFixtures.close, onOpen = {}) }
}
