package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.finance.BudgetUiState
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.BucketSource
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetBucket
import com.meticulouscreations.homesafe.finance.domain.BudgetCard
import com.meticulouscreations.homesafe.finance.domain.BudgetConfig
import com.meticulouscreations.homesafe.finance.domain.BudgetLimits
import com.meticulouscreations.homesafe.finance.domain.BudgetPastMonth
import com.meticulouscreations.homesafe.finance.domain.BudgetSheetFigures
import com.meticulouscreations.homesafe.finance.domain.BudgetSlice
import com.meticulouscreations.homesafe.finance.domain.CardHolder
import com.meticulouscreations.homesafe.finance.domain.CardRole
import com.meticulouscreations.homesafe.finance.domain.MerchantRule
import com.meticulouscreations.homesafe.finance.domain.Purchase
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_budget_error_relay_outdated
import kotlin.time.Clock

/**
 * A made-up household's month for the Budget previews and tests: two people on a shared travel
 * card, a family card, and a month that can be dialled from comfortably under its limit to well
 * into the savings. Nothing here is anyone's real spending.
 */
internal object BudgetFixtures {
    private val now = Clock.System.now().epochSeconds
    const val TRAVEL = "Summit Bank Voyager 4410"
    const val FAMILY = "Northwind Platinum 2207"
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

    private data class Shop(val name: String, val category: String, val card: String, val bucket: BucketId, val source: BucketSource, val amount: Double)

    private val alex = BucketId.Person("Alex")
    private val sam = BucketId.Person("Sam")
    private val shops = listOf(
        Shop("Harvest Market", "FOOD_AND_DRINK", FAMILY, BucketId.Family, BucketSource.ACCOUNT, 182.40),
        Shop("Corner Coffee", "FOOD_AND_DRINK", TRAVEL, alex, BucketSource.RULE, 6.75),
        Shop("Blue Line Transit", "TRANSPORTATION", TRAVEL, sam, BucketSource.MANUAL, 48.00),
        Shop("Warehouse Club", "GENERAL_MERCHANDISE", FAMILY, BucketId.Family, BucketSource.ACCOUNT, 311.18),
        Shop("Trailhead Outfitters", "GENERAL_MERCHANDISE", TRAVEL, BucketId.Unassigned, BucketSource.NONE, 129.99),
        Shop("Luna Pizzeria", "FOOD_AND_DRINK", TRAVEL, BucketId.Unassigned, BucketSource.NONE, 54.20),
        Shop("City Power", "RENT_AND_UTILITIES", FAMILY, BucketId.Family, BucketSource.ACCOUNT, 141.02),
        Shop("Skyway Air", "TRAVEL", TRAVEL, sam, BucketSource.BANK, 412.60),
        Shop("Page & Spine Books", "ENTERTAINMENT", TRAVEL, alex, BucketSource.MANUAL, 37.45),
        Shop("Petal & Stem", "GENERAL_MERCHANDISE", TRAVEL, BucketId.Unassigned, BucketSource.NONE, 68.00),
        Shop("Greenway Pharmacy", "MEDICAL", FAMILY, BucketId.Family, BucketSource.ACCOUNT, 23.80),
        Shop("Hilltop Hardware", "HOME_IMPROVEMENT", TRAVEL, alex, BucketSource.RULE, 96.30),
    )

    /**
     * The month on its [day]th day, with every purchase [scale] times its listed size: about 0.4
     * is a month comfortably under [limit], 0.6 one that ends well over it.
     */
    fun month(day: Int = 12, scale: Double = 0.42, limit: Double? = 4_500.0, sandbox: Boolean = false, cards: List<BudgetCard> = this.cards): Budget {
        val purchases = (1..day).flatMap { d ->
            // Two or three purchases a day, walking round the shops, the newest day first.
            (0 until 2 + d % 2).map { n ->
                val shop = shops[(d * 3 + n * 5) % shops.size]
                Purchase(
                    id = "p-$d-$n",
                    date = "2026-10-${d.toString().padStart(2, '0')}",
                    amount = ((shop.amount * scale) * 100).toLong() / 100.0,
                    name = shop.name,
                    category = shop.category,
                    pending = d == day && n == 0,
                    cardKey = shop.card,
                    bucket = shop.bucket,
                    source = shop.source,
                    remembered = shop.source == BucketSource.RULE,
                )
            }
        }.sortedByDescending { it.date }
        val limits = BudgetLimits(total = limit, people = mapOf("Alex" to 900.0, "Sam" to 1_200.0), family = 2_400.0)
        fun bucket(id: BucketId, limit: Double?) = purchases.filter { it.bucket == id }.let { BudgetBucket(id, it.sumOf { p -> p.amount }, limit, it.size) }
        return Budget(
            configured = true,
            sandbox = sandbox,
            month = "2026-10",
            isCurrentMonth = true,
            day = day,
            daysInMonth = 31,
            syncedAtEpochSeconds = now - 12 * 60,
            changedAtEpochSeconds = now - 5 * 3_600,
            syncing = false,
            nextSyncAtEpochSeconds = now + 48 * 60,
            ready = true,
            cards = cards,
            config = BudgetConfig(
                people = people,
                limits = limits,
                cardPaidLines = listOf("Groceries", "Meal kits", "Streaming"),
                rules = listOf(MerchantRule("corner coffee", alex), MerchantRule("hilltop hardware", alex)),
                sheet = BudgetSheetFigures(takeHome = 14_170.0, billsOffCard = 9_817.0),
            ),
            savingsLine = 4_353.0,
            spent = purchases.sumOf { it.amount },
            pending = purchases.filter { it.pending }.sumOf { it.amount },
            buckets = listOf(bucket(alex, limits.people["Alex"]), bucket(sam, limits.people["Sam"]), bucket(BucketId.Family, limits.family), bucket(BucketId.Unassigned, null)),
            daily = (1..day).map { d -> purchases.filter { it.date.endsWith("-${d.toString().padStart(2, '0')}") }.sumOf { it.amount } },
            categories = purchases.groupBy { it.category.orEmpty() }.map { (name, list) -> BudgetSlice(name, list.sumOf { it.amount }, list.size) }.sortedByDescending { it.spent },
            merchants = purchases.groupBy { it.name }.map { (name, list) -> BudgetSlice(name, list.sumOf { it.amount }, list.size) }.sortedByDescending { it.spent }.take(8),
            purchases = purchases,
            history = listOf(
                BudgetPastMonth("2026-05", 3_910.0, 31),
                BudgetPastMonth("2026-06", 4_720.0, 30),
                BudgetPastMonth("2026-07", 4_180.0, 31),
                BudgetPastMonth("2026-08", 5_260.0, 31),
                BudgetPastMonth("2026-09", 4_050.0, 30),
            ),
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

    fun state(budget: Budget) = BudgetUiState(budget = budget, loading = false, readAtEpochSeconds = now)
}

@Composable
private fun BudgetPreview(state: BudgetUiState) {
    FinanceStage {
        BudgetScreen(state, rememberLazyListState(), previewPadding, onTag = { _, _, _ -> }, onSetRole = { _, _ -> }, onSetHolder = { _, _, _ -> }, onShowMonth = {}, onSyncNow = {}, onRetry = {}, onOpenLinkedAccounts = {}, onOpenSettings = {})
    }
}

@Preview(name = "Budget · on pace", widthDp = 412, heightDp = 3400)
@Composable
private fun FinanceBudgetPreview() {
    BudgetPreview(BudgetFixtures.state(BudgetFixtures.onPace))
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

@Preview(name = "Budget · settings", widthDp = 412, heightDp = 2000)
@Composable
private fun FinanceBudgetSettingsPreview() {
    FinanceStage { BudgetSettingsScreen(BudgetFixtures.state(BudgetFixtures.onPace), FinanceFixtures.finance, previewPadding, onSave = {}, onForgetRule = {}) }
}

@Preview(name = "Budget · the Wallet's line", widthDp = 412, heightDp = 160)
@Composable
private fun FinanceBudgetTeaserPreview() {
    FinanceStage { BudgetTeaser(BudgetFixtures.close, onOpen = {}) }
}
