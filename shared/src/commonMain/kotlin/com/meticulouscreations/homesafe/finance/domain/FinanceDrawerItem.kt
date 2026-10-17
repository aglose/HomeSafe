package com.meticulouscreations.homesafe.finance.domain

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_symbol_bitcoin
import homesafe.shared.generated.resources.fin_data_symbol_dollar_short
import homesafe.shared.generated.resources.fin_data_symbol_dow_short
import homesafe.shared.generated.resources.fin_data_symbol_gold_short
import homesafe.shared.generated.resources.fin_data_symbol_nasdaq_short
import homesafe.shared.generated.resources.fin_data_symbol_oil_short
import homesafe.shared.generated.resources.fin_data_symbol_russell_short
import homesafe.shared.generated.resources.fin_data_symbol_sp500
import homesafe.shared.generated.resources.fin_data_symbol_ten_year_short
import homesafe.shared.generated.resources.fin_data_symbol_vix_short
import homesafe.shared.generated.resources.finance_drawer_item_assets
import homesafe.shared.generated.resources.finance_drawer_item_cash
import homesafe.shared.generated.resources.finance_drawer_item_debt
import homesafe.shared.generated.resources.finance_drawer_item_investments
import homesafe.shared.generated.resources.finance_drawer_item_net_worth
import org.jetbrains.compose.resources.StringResource

/**
 * One thing the Finance card in the drawer can show: a market's price today with its sparkline
 * ([symbol], one of the finance app's catalog of indices and macro markets), or one of the
 * household's figures from the budget sheet. Which, chosen in Settings, is kept with the finance
 * app's preferences; up to [MAX] of them, shown in this order, markets first.
 */
enum class FinanceDrawerItem(val label: StringResource, val symbol: String? = null) {
    SP500(Res.string.fin_data_symbol_sp500, "^GSPC"),
    DOW(Res.string.fin_data_symbol_dow_short, "^DJI"),
    NASDAQ(Res.string.fin_data_symbol_nasdaq_short, "^IXIC"),
    RUSSELL(Res.string.fin_data_symbol_russell_short, "^RUT"),
    VIX(Res.string.fin_data_symbol_vix_short, "^VIX"),
    TEN_YEAR(Res.string.fin_data_symbol_ten_year_short, "^TNX"),
    GOLD(Res.string.fin_data_symbol_gold_short, "GC=F"),
    OIL(Res.string.fin_data_symbol_oil_short, "CL=F"),
    BITCOIN(Res.string.fin_data_symbol_bitcoin, "BTC-USD"),
    DOLLAR(Res.string.fin_data_symbol_dollar_short, "DX-Y.NYB"),

    /** Assets less debt, the sheet's headline number. */
    NET_WORTH(Res.string.finance_drawer_item_net_worth),
    ASSETS(Res.string.finance_drawer_item_assets),
    INVESTMENTS(Res.string.finance_drawer_item_investments),

    /** What the cash accounts hold. */
    CASH(Res.string.finance_drawer_item_cash),

    /** Everything owed but the mortgage: what net worth subtracts. */
    DEBT(Res.string.finance_drawer_item_debt),
    ;

    /** A market's price; otherwise the household's money, which is kept shut while Finance is locked. */
    val isMarket: Boolean get() = symbol != null

    companion object {
        /** As many as the card holds before it stops being a glance. */
        const val MAX = 4

        /** What the card showed before it could be chosen, and still does until something else is. */
        val DEFAULT: List<FinanceDrawerItem> = listOf(SP500, NET_WORTH)

        /** [DEFAULT] as it is stored: also the column's default, so an install from before it was stored keeps the card it had. */
        const val DEFAULT_STORED = "SP500,NET_WORTH"

        /** [items] as they are stored: their names, in their order, comma-separated. */
        fun store(items: Collection<FinanceDrawerItem>): String = normalize(items).joinToString(",") { it.name }

        /**
         * What was stored, in the card's order and no more than [MAX]. A name this build doesn't know (a
         * downgrade after a newer one added an item) is dropped; nothing stored at all means [DEFAULT],
         * while an empty string is a card chosen to show nothing but its name.
         */
        fun parse(stored: String?): List<FinanceDrawerItem> {
            if (stored == null) return DEFAULT
            val names = stored.split(',').map { it.trim() }.toSet()
            return normalize(entries.filter { it.name in names })
        }

        /** [items] with [item] taken off, or put on where there is room for it. */
        fun toggled(items: List<FinanceDrawerItem>, item: FinanceDrawerItem): List<FinanceDrawerItem> = when {
            item in items -> items - item
            items.size >= MAX -> items
            else -> normalize(items + item)
        }

        /** In the card's order, each once, and no more than [MAX]. */
        fun normalize(items: Collection<FinanceDrawerItem>): List<FinanceDrawerItem> = items.distinct().sortedBy { it.ordinal }.take(MAX)
    }
}
