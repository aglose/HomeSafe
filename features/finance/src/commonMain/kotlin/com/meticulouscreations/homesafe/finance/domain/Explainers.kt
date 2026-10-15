package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.explainer_allocation_one_liner
import homesafe.shared.generated.resources.explainer_allocation_technical
import homesafe.shared.generated.resources.explainer_allocation_title
import homesafe.shared.generated.resources.explainer_allocation_why_you
import homesafe.shared.generated.resources.explainer_bitcoin_one_liner
import homesafe.shared.generated.resources.explainer_bitcoin_technical
import homesafe.shared.generated.resources.explainer_bitcoin_title
import homesafe.shared.generated.resources.explainer_bitcoin_why_you
import homesafe.shared.generated.resources.explainer_budgetpace_how_it_works
import homesafe.shared.generated.resources.explainer_budgetpace_one_liner
import homesafe.shared.generated.resources.explainer_budgetpace_title
import homesafe.shared.generated.resources.explainer_budgetpace_why_you
import homesafe.shared.generated.resources.explainer_cashflow_one_liner
import homesafe.shared.generated.resources.explainer_cashflow_title
import homesafe.shared.generated.resources.explainer_cashflow_why_you
import homesafe.shared.generated.resources.explainer_ccdelinq_normal
import homesafe.shared.generated.resources.explainer_ccdelinq_one_liner
import homesafe.shared.generated.resources.explainer_ccdelinq_technical
import homesafe.shared.generated.resources.explainer_ccdelinq_title
import homesafe.shared.generated.resources.explainer_ccdelinq_why_you
import homesafe.shared.generated.resources.explainer_civpart_how_it_works
import homesafe.shared.generated.resources.explainer_civpart_normal
import homesafe.shared.generated.resources.explainer_civpart_one_liner
import homesafe.shared.generated.resources.explainer_civpart_technical
import homesafe.shared.generated.resources.explainer_civpart_title
import homesafe.shared.generated.resources.explainer_civpart_why_you
import homesafe.shared.generated.resources.explainer_corecpi_analogy
import homesafe.shared.generated.resources.explainer_corecpi_how_it_works
import homesafe.shared.generated.resources.explainer_corecpi_normal
import homesafe.shared.generated.resources.explainer_corecpi_one_liner
import homesafe.shared.generated.resources.explainer_corecpi_technical
import homesafe.shared.generated.resources.explainer_corecpi_title
import homesafe.shared.generated.resources.explainer_corecpi_why_you
import homesafe.shared.generated.resources.explainer_corepce_how_it_works
import homesafe.shared.generated.resources.explainer_corepce_normal
import homesafe.shared.generated.resources.explainer_corepce_one_liner
import homesafe.shared.generated.resources.explainer_corepce_technical
import homesafe.shared.generated.resources.explainer_corepce_title
import homesafe.shared.generated.resources.explainer_corepce_why_you
import homesafe.shared.generated.resources.explainer_cpi_analogy
import homesafe.shared.generated.resources.explainer_cpi_how_it_works
import homesafe.shared.generated.resources.explainer_cpi_normal
import homesafe.shared.generated.resources.explainer_cpi_one_liner
import homesafe.shared.generated.resources.explainer_cpi_technical
import homesafe.shared.generated.resources.explainer_cpi_title
import homesafe.shared.generated.resources.explainer_cpi_why_you
import homesafe.shared.generated.resources.explainer_debt_one_liner
import homesafe.shared.generated.resources.explainer_debt_title
import homesafe.shared.generated.resources.explainer_debt_why_you
import homesafe.shared.generated.resources.explainer_debtgdp_analogy
import homesafe.shared.generated.resources.explainer_debtgdp_normal
import homesafe.shared.generated.resources.explainer_debtgdp_one_liner
import homesafe.shared.generated.resources.explainer_debtgdp_technical
import homesafe.shared.generated.resources.explainer_debtgdp_title
import homesafe.shared.generated.resources.explainer_debtgdp_why_you
import homesafe.shared.generated.resources.explainer_dff_analogy
import homesafe.shared.generated.resources.explainer_dff_how_it_works
import homesafe.shared.generated.resources.explainer_dff_normal
import homesafe.shared.generated.resources.explainer_dff_one_liner
import homesafe.shared.generated.resources.explainer_dff_technical
import homesafe.shared.generated.resources.explainer_dff_title
import homesafe.shared.generated.resources.explainer_dff_why_you
import homesafe.shared.generated.resources.explainer_dgs10_how_it_works
import homesafe.shared.generated.resources.explainer_dgs10_normal
import homesafe.shared.generated.resources.explainer_dgs10_one_liner
import homesafe.shared.generated.resources.explainer_dgs10_technical
import homesafe.shared.generated.resources.explainer_dgs10_title
import homesafe.shared.generated.resources.explainer_dgs10_why_you
import homesafe.shared.generated.resources.explainer_dgs2_normal
import homesafe.shared.generated.resources.explainer_dgs2_one_liner
import homesafe.shared.generated.resources.explainer_dgs2_technical
import homesafe.shared.generated.resources.explainer_dgs2_title
import homesafe.shared.generated.resources.explainer_dgs2_why_you
import homesafe.shared.generated.resources.explainer_dgs30_one_liner
import homesafe.shared.generated.resources.explainer_dgs30_technical
import homesafe.shared.generated.resources.explainer_dgs30_title
import homesafe.shared.generated.resources.explainer_dgs30_why_you
import homesafe.shared.generated.resources.explainer_dollar_one_liner
import homesafe.shared.generated.resources.explainer_dollar_technical
import homesafe.shared.generated.resources.explainer_dollar_title
import homesafe.shared.generated.resources.explainer_dollar_why_you
import homesafe.shared.generated.resources.explainer_dow_one_liner
import homesafe.shared.generated.resources.explainer_dow_technical
import homesafe.shared.generated.resources.explainer_dow_title
import homesafe.shared.generated.resources.explainer_dow_why_you
import homesafe.shared.generated.resources.explainer_gdp_analogy
import homesafe.shared.generated.resources.explainer_gdp_how_it_works
import homesafe.shared.generated.resources.explainer_gdp_normal
import homesafe.shared.generated.resources.explainer_gdp_one_liner
import homesafe.shared.generated.resources.explainer_gdp_technical
import homesafe.shared.generated.resources.explainer_gdp_title
import homesafe.shared.generated.resources.explainer_gdp_why_you
import homesafe.shared.generated.resources.explainer_gold_one_liner
import homesafe.shared.generated.resources.explainer_gold_technical
import homesafe.shared.generated.resources.explainer_gold_title
import homesafe.shared.generated.resources.explainer_gold_why_you
import homesafe.shared.generated.resources.explainer_homeequity_one_liner
import homesafe.shared.generated.resources.explainer_homeequity_title
import homesafe.shared.generated.resources.explainer_homeequity_why_you
import homesafe.shared.generated.resources.explainer_homeprices_how_it_works
import homesafe.shared.generated.resources.explainer_homeprices_normal
import homesafe.shared.generated.resources.explainer_homeprices_one_liner
import homesafe.shared.generated.resources.explainer_homeprices_technical
import homesafe.shared.generated.resources.explainer_homeprices_title
import homesafe.shared.generated.resources.explainer_homeprices_why_you
import homesafe.shared.generated.resources.explainer_hy_analogy
import homesafe.shared.generated.resources.explainer_hy_normal
import homesafe.shared.generated.resources.explainer_hy_one_liner
import homesafe.shared.generated.resources.explainer_hy_technical
import homesafe.shared.generated.resources.explainer_hy_title
import homesafe.shared.generated.resources.explainer_hy_why_you
import homesafe.shared.generated.resources.explainer_icsa_how_it_works
import homesafe.shared.generated.resources.explainer_icsa_normal
import homesafe.shared.generated.resources.explainer_icsa_one_liner
import homesafe.shared.generated.resources.explainer_icsa_technical
import homesafe.shared.generated.resources.explainer_icsa_title
import homesafe.shared.generated.resources.explainer_icsa_why_you
import homesafe.shared.generated.resources.explainer_insured_how_it_works
import homesafe.shared.generated.resources.explainer_insured_normal
import homesafe.shared.generated.resources.explainer_insured_one_liner
import homesafe.shared.generated.resources.explainer_insured_technical
import homesafe.shared.generated.resources.explainer_insured_title
import homesafe.shared.generated.resources.explainer_insured_why_you
import homesafe.shared.generated.resources.explainer_interest_normal
import homesafe.shared.generated.resources.explainer_interest_one_liner
import homesafe.shared.generated.resources.explainer_interest_technical
import homesafe.shared.generated.resources.explainer_interest_title
import homesafe.shared.generated.resources.explainer_interest_why_you
import homesafe.shared.generated.resources.explainer_longterm_how_it_works
import homesafe.shared.generated.resources.explainer_longterm_normal
import homesafe.shared.generated.resources.explainer_longterm_one_liner
import homesafe.shared.generated.resources.explainer_longterm_technical
import homesafe.shared.generated.resources.explainer_longterm_title
import homesafe.shared.generated.resources.explainer_longterm_why_you
import homesafe.shared.generated.resources.explainer_m2_normal
import homesafe.shared.generated.resources.explainer_m2_one_liner
import homesafe.shared.generated.resources.explainer_m2_technical
import homesafe.shared.generated.resources.explainer_m2_title
import homesafe.shared.generated.resources.explainer_m2_why_you
import homesafe.shared.generated.resources.explainer_mortgage_how_it_works
import homesafe.shared.generated.resources.explainer_mortgage_normal
import homesafe.shared.generated.resources.explainer_mortgage_one_liner
import homesafe.shared.generated.resources.explainer_mortgage_technical
import homesafe.shared.generated.resources.explainer_mortgage_title
import homesafe.shared.generated.resources.explainer_mortgage_why_you
import homesafe.shared.generated.resources.explainer_mortgageplanner_one_liner
import homesafe.shared.generated.resources.explainer_mortgageplanner_title
import homesafe.shared.generated.resources.explainer_mortgageplanner_why_you
import homesafe.shared.generated.resources.explainer_nasdaq_one_liner
import homesafe.shared.generated.resources.explainer_nasdaq_technical
import homesafe.shared.generated.resources.explainer_nasdaq_title
import homesafe.shared.generated.resources.explainer_nasdaq_why_you
import homesafe.shared.generated.resources.explainer_networth_analogy
import homesafe.shared.generated.resources.explainer_networth_how_it_works
import homesafe.shared.generated.resources.explainer_networth_one_liner
import homesafe.shared.generated.resources.explainer_networth_title
import homesafe.shared.generated.resources.explainer_networth_why_you
import homesafe.shared.generated.resources.explainer_oil_one_liner
import homesafe.shared.generated.resources.explainer_oil_technical
import homesafe.shared.generated.resources.explainer_oil_title
import homesafe.shared.generated.resources.explainer_oil_why_you
import homesafe.shared.generated.resources.explainer_openings_how_it_works
import homesafe.shared.generated.resources.explainer_openings_normal
import homesafe.shared.generated.resources.explainer_openings_one_liner
import homesafe.shared.generated.resources.explainer_openings_technical
import homesafe.shared.generated.resources.explainer_openings_title
import homesafe.shared.generated.resources.explainer_openings_why_you
import homesafe.shared.generated.resources.explainer_prevclose_one_liner
import homesafe.shared.generated.resources.explainer_prevclose_title
import homesafe.shared.generated.resources.explainer_prevclose_why_you
import homesafe.shared.generated.resources.explainer_primeepop_analogy
import homesafe.shared.generated.resources.explainer_primeepop_how_it_works
import homesafe.shared.generated.resources.explainer_primeepop_normal
import homesafe.shared.generated.resources.explainer_primeepop_one_liner
import homesafe.shared.generated.resources.explainer_primeepop_technical
import homesafe.shared.generated.resources.explainer_primeepop_title
import homesafe.shared.generated.resources.explainer_primeepop_why_you
import homesafe.shared.generated.resources.explainer_quits_analogy
import homesafe.shared.generated.resources.explainer_quits_how_it_works
import homesafe.shared.generated.resources.explainer_quits_normal
import homesafe.shared.generated.resources.explainer_quits_one_liner
import homesafe.shared.generated.resources.explainer_quits_technical
import homesafe.shared.generated.resources.explainer_quits_title
import homesafe.shared.generated.resources.explainer_quits_why_you
import homesafe.shared.generated.resources.explainer_range52w_one_liner
import homesafe.shared.generated.resources.explainer_range52w_title
import homesafe.shared.generated.resources.explainer_range52w_why_you
import homesafe.shared.generated.resources.explainer_realrate_how_it_works
import homesafe.shared.generated.resources.explainer_realrate_normal
import homesafe.shared.generated.resources.explainer_realrate_one_liner
import homesafe.shared.generated.resources.explainer_realrate_technical
import homesafe.shared.generated.resources.explainer_realrate_title
import homesafe.shared.generated.resources.explainer_realrate_why_you
import homesafe.shared.generated.resources.explainer_realwages_how_it_works
import homesafe.shared.generated.resources.explainer_realwages_normal
import homesafe.shared.generated.resources.explainer_realwages_one_liner
import homesafe.shared.generated.resources.explainer_realwages_technical
import homesafe.shared.generated.resources.explainer_realwages_title
import homesafe.shared.generated.resources.explainer_realwages_why_you
import homesafe.shared.generated.resources.explainer_recession_how_it_works
import homesafe.shared.generated.resources.explainer_recession_normal
import homesafe.shared.generated.resources.explainer_recession_one_liner
import homesafe.shared.generated.resources.explainer_recession_title
import homesafe.shared.generated.resources.explainer_recession_why_you
import homesafe.shared.generated.resources.explainer_runway_one_liner
import homesafe.shared.generated.resources.explainer_runway_technical
import homesafe.shared.generated.resources.explainer_runway_title
import homesafe.shared.generated.resources.explainer_runway_why_you
import homesafe.shared.generated.resources.explainer_russell_one_liner
import homesafe.shared.generated.resources.explainer_russell_technical
import homesafe.shared.generated.resources.explainer_russell_title
import homesafe.shared.generated.resources.explainer_russell_why_you
import homesafe.shared.generated.resources.explainer_sahm_analogy
import homesafe.shared.generated.resources.explainer_sahm_how_it_works
import homesafe.shared.generated.resources.explainer_sahm_normal
import homesafe.shared.generated.resources.explainer_sahm_one_liner
import homesafe.shared.generated.resources.explainer_sahm_technical
import homesafe.shared.generated.resources.explainer_sahm_title
import homesafe.shared.generated.resources.explainer_sahm_why_you
import homesafe.shared.generated.resources.explainer_savingsline_how_it_works
import homesafe.shared.generated.resources.explainer_savingsline_one_liner
import homesafe.shared.generated.resources.explainer_savingsline_title
import homesafe.shared.generated.resources.explainer_savingsline_why_you
import homesafe.shared.generated.resources.explainer_savingsrate_one_liner
import homesafe.shared.generated.resources.explainer_savingsrate_title
import homesafe.shared.generated.resources.explainer_savingsrate_why_you
import homesafe.shared.generated.resources.explainer_slackgap_how_it_works
import homesafe.shared.generated.resources.explainer_slackgap_normal
import homesafe.shared.generated.resources.explainer_slackgap_one_liner
import homesafe.shared.generated.resources.explainer_slackgap_technical
import homesafe.shared.generated.resources.explainer_slackgap_title
import homesafe.shared.generated.resources.explainer_slackgap_why_you
import homesafe.shared.generated.resources.explainer_sp500_how_it_works
import homesafe.shared.generated.resources.explainer_sp500_normal
import homesafe.shared.generated.resources.explainer_sp500_one_liner
import homesafe.shared.generated.resources.explainer_sp500_technical
import homesafe.shared.generated.resources.explainer_sp500_title
import homesafe.shared.generated.resources.explainer_sp500_why_you
import homesafe.shared.generated.resources.explainer_stlfsi_normal
import homesafe.shared.generated.resources.explainer_stlfsi_one_liner
import homesafe.shared.generated.resources.explainer_stlfsi_technical
import homesafe.shared.generated.resources.explainer_stlfsi_title
import homesafe.shared.generated.resources.explainer_stlfsi_why_you
import homesafe.shared.generated.resources.explainer_stress_how_it_works
import homesafe.shared.generated.resources.explainer_stress_normal
import homesafe.shared.generated.resources.explainer_stress_one_liner
import homesafe.shared.generated.resources.explainer_stress_technical
import homesafe.shared.generated.resources.explainer_stress_title
import homesafe.shared.generated.resources.explainer_stress_why_you
import homesafe.shared.generated.resources.explainer_t10y2y_analogy
import homesafe.shared.generated.resources.explainer_t10y2y_normal
import homesafe.shared.generated.resources.explainer_t10y2y_one_liner
import homesafe.shared.generated.resources.explainer_t10y2y_technical
import homesafe.shared.generated.resources.explainer_t10y2y_title
import homesafe.shared.generated.resources.explainer_t10y2y_why_you
import homesafe.shared.generated.resources.explainer_t10y3m_normal
import homesafe.shared.generated.resources.explainer_t10y3m_one_liner
import homesafe.shared.generated.resources.explainer_t10y3m_technical
import homesafe.shared.generated.resources.explainer_t10y3m_title
import homesafe.shared.generated.resources.explainer_t10y3m_why_you
import homesafe.shared.generated.resources.explainer_taxes_one_liner
import homesafe.shared.generated.resources.explainer_taxes_title
import homesafe.shared.generated.resources.explainer_taxes_why_you
import homesafe.shared.generated.resources.explainer_temphelp_analogy
import homesafe.shared.generated.resources.explainer_temphelp_how_it_works
import homesafe.shared.generated.resources.explainer_temphelp_normal
import homesafe.shared.generated.resources.explainer_temphelp_one_liner
import homesafe.shared.generated.resources.explainer_temphelp_technical
import homesafe.shared.generated.resources.explainer_temphelp_title
import homesafe.shared.generated.resources.explainer_temphelp_why_you
import homesafe.shared.generated.resources.explainer_topic_credit
import homesafe.shared.generated.resources.explainer_topic_government
import homesafe.shared.generated.resources.explainer_topic_housing
import homesafe.shared.generated.resources.explainer_topic_jobs
import homesafe.shared.generated.resources.explainer_topic_markets
import homesafe.shared.generated.resources.explainer_topic_prices
import homesafe.shared.generated.resources.explainer_topic_rates
import homesafe.shared.generated.resources.explainer_topic_your_money
import homesafe.shared.generated.resources.explainer_totalassets_one_liner
import homesafe.shared.generated.resources.explainer_totalassets_title
import homesafe.shared.generated.resources.explainer_totalassets_why_you
import homesafe.shared.generated.resources.explainer_treasuries_analogy
import homesafe.shared.generated.resources.explainer_treasuries_how_it_works
import homesafe.shared.generated.resources.explainer_treasuries_one_liner
import homesafe.shared.generated.resources.explainer_treasuries_technical
import homesafe.shared.generated.resources.explainer_treasuries_title
import homesafe.shared.generated.resources.explainer_treasuries_why_you
import homesafe.shared.generated.resources.explainer_u6_analogy
import homesafe.shared.generated.resources.explainer_u6_how_it_works
import homesafe.shared.generated.resources.explainer_u6_normal
import homesafe.shared.generated.resources.explainer_u6_one_liner
import homesafe.shared.generated.resources.explainer_u6_technical
import homesafe.shared.generated.resources.explainer_u6_title
import homesafe.shared.generated.resources.explainer_u6_why_you
import homesafe.shared.generated.resources.explainer_umcsent_how_it_works
import homesafe.shared.generated.resources.explainer_umcsent_normal
import homesafe.shared.generated.resources.explainer_umcsent_one_liner
import homesafe.shared.generated.resources.explainer_umcsent_technical
import homesafe.shared.generated.resources.explainer_umcsent_title
import homesafe.shared.generated.resources.explainer_umcsent_why_you
import homesafe.shared.generated.resources.explainer_unrate_how_it_works
import homesafe.shared.generated.resources.explainer_unrate_normal
import homesafe.shared.generated.resources.explainer_unrate_one_liner
import homesafe.shared.generated.resources.explainer_unrate_technical
import homesafe.shared.generated.resources.explainer_unrate_title
import homesafe.shared.generated.resources.explainer_unrate_why_you
import homesafe.shared.generated.resources.explainer_vesting_one_liner
import homesafe.shared.generated.resources.explainer_vesting_technical
import homesafe.shared.generated.resources.explainer_vesting_title
import homesafe.shared.generated.resources.explainer_vesting_why_you
import homesafe.shared.generated.resources.explainer_vix_analogy
import homesafe.shared.generated.resources.explainer_vix_how_it_works
import homesafe.shared.generated.resources.explainer_vix_normal
import homesafe.shared.generated.resources.explainer_vix_one_liner
import homesafe.shared.generated.resources.explainer_vix_technical
import homesafe.shared.generated.resources.explainer_vix_title
import homesafe.shared.generated.resources.explainer_vix_why_you
import homesafe.shared.generated.resources.explainer_volume_one_liner
import homesafe.shared.generated.resources.explainer_volume_title
import homesafe.shared.generated.resources.explainer_volume_why_you
import homesafe.shared.generated.resources.explainer_yieldcurve_analogy
import homesafe.shared.generated.resources.explainer_yieldcurve_how_it_works
import homesafe.shared.generated.resources.explainer_yieldcurve_normal
import homesafe.shared.generated.resources.explainer_yieldcurve_one_liner
import homesafe.shared.generated.resources.explainer_yieldcurve_technical
import homesafe.shared.generated.resources.explainer_yieldcurve_title
import homesafe.shared.generated.resources.explainer_yieldcurve_why_you
import org.jetbrains.compose.resources.StringResource

/**
 * One idea in the finance app explained for someone who has never read a financial page: what it
 * is in a sentence, why it touches their own life, what's normal, how it works, and what it's
 * tied to. Indicators share their id with [IndicatorCatalog]; everything else (a stock index, net
 * worth, the yield curve as an idea) has an id of its own. The words live in
 * `strings_finance_explainers.xml` as `explainer_<id>_<part>`; the parts an idea doesn't have are null.
 */
@Immutable
data class Explainer(
    val id: String,
    /** The plain name: "Long vs short-term borrowing costs", not "10Y−2Y". */
    val title: StringResource,
    /** The name the news uses, shown small under the plain one. */
    val technical: StringResource? = null,
    val oneLiner: StringResource,
    /** "Think of it like…" — an everyday comparison. */
    val analogy: StringResource? = null,
    /** Why it matters to an ordinary household. */
    val whyYou: StringResource,
    /** What a normal reading looks like. */
    val normal: StringResource? = null,
    /** A paragraph on how it works and what moves it. */
    val howItWorks: StringResource? = null,
    /** Other explainers it's tied to, by id. */
    val related: List<String> = emptyList(),
    val topic: ExplainerTopic,
)

/** How the glossary groups explainers. */
enum class ExplainerTopic(val title: StringResource) {
    PRICES(Res.string.explainer_topic_prices),
    JOBS(Res.string.explainer_topic_jobs),
    RATES(Res.string.explainer_topic_rates),
    MARKETS(Res.string.explainer_topic_markets),
    CREDIT(Res.string.explainer_topic_credit),
    GOVERNMENT(Res.string.explainer_topic_government),
    HOUSING(Res.string.explainer_topic_housing),
    YOUR_MONEY(Res.string.explainer_topic_your_money),
}

object Explainers {
    private val all = listOf(
        // ---------------------------------------------------------------- prices
        Explainer(
            id = "cpi", topic = ExplainerTopic.PRICES,
            title = Res.string.explainer_cpi_title, technical = Res.string.explainer_cpi_technical,
            oneLiner = Res.string.explainer_cpi_one_liner,
            analogy = Res.string.explainer_cpi_analogy,
            whyYou = Res.string.explainer_cpi_why_you,
            normal = Res.string.explainer_cpi_normal,
            howItWorks = Res.string.explainer_cpi_how_it_works,
            related = listOf("corecpi", "corepce", "dff", "realrate", "mortgage"),
        ),
        Explainer(
            id = "corecpi", topic = ExplainerTopic.PRICES,
            title = Res.string.explainer_corecpi_title, technical = Res.string.explainer_corecpi_technical,
            oneLiner = Res.string.explainer_corecpi_one_liner,
            analogy = Res.string.explainer_corecpi_analogy,
            whyYou = Res.string.explainer_corecpi_why_you,
            normal = Res.string.explainer_corecpi_normal,
            howItWorks = Res.string.explainer_corecpi_how_it_works,
            related = listOf("cpi", "corepce", "dff"),
        ),
        Explainer(
            id = "corepce", topic = ExplainerTopic.PRICES,
            title = Res.string.explainer_corepce_title, technical = Res.string.explainer_corepce_technical,
            oneLiner = Res.string.explainer_corepce_one_liner,
            whyYou = Res.string.explainer_corepce_why_you,
            normal = Res.string.explainer_corepce_normal,
            howItWorks = Res.string.explainer_corepce_how_it_works,
            related = listOf("cpi", "dff"),
        ),
        // ---------------------------------------------------------------- jobs & growth
        Explainer(
            id = "unrate", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_unrate_title, technical = Res.string.explainer_unrate_technical,
            oneLiner = Res.string.explainer_unrate_one_liner,
            whyYou = Res.string.explainer_unrate_why_you,
            normal = Res.string.explainer_unrate_normal,
            howItWorks = Res.string.explainer_unrate_how_it_works,
            related = listOf("u6", "slackgap", "primeepop", "sahm", "icsa", "gdp"),
        ),
        Explainer(
            id = "sahm", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_sahm_title, technical = Res.string.explainer_sahm_technical,
            oneLiner = Res.string.explainer_sahm_one_liner,
            analogy = Res.string.explainer_sahm_analogy,
            whyYou = Res.string.explainer_sahm_why_you,
            normal = Res.string.explainer_sahm_normal,
            howItWorks = Res.string.explainer_sahm_how_it_works,
            related = listOf("unrate", "icsa", "recession"),
        ),
        Explainer(
            id = "icsa", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_icsa_title, technical = Res.string.explainer_icsa_technical,
            oneLiner = Res.string.explainer_icsa_one_liner,
            whyYou = Res.string.explainer_icsa_why_you,
            normal = Res.string.explainer_icsa_normal,
            howItWorks = Res.string.explainer_icsa_how_it_works,
            related = listOf("unrate", "sahm"),
        ),
        Explainer(
            id = "u6", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_u6_title, technical = Res.string.explainer_u6_technical,
            oneLiner = Res.string.explainer_u6_one_liner,
            analogy = Res.string.explainer_u6_analogy,
            whyYou = Res.string.explainer_u6_why_you,
            normal = Res.string.explainer_u6_normal,
            howItWorks = Res.string.explainer_u6_how_it_works,
            related = listOf("unrate", "slackgap", "primeepop", "longterm"),
        ),
        Explainer(
            id = "slackgap", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_slackgap_title, technical = Res.string.explainer_slackgap_technical,
            oneLiner = Res.string.explainer_slackgap_one_liner,
            whyYou = Res.string.explainer_slackgap_why_you,
            normal = Res.string.explainer_slackgap_normal,
            howItWorks = Res.string.explainer_slackgap_how_it_works,
            related = listOf("u6", "unrate"),
        ),
        Explainer(
            id = "primeepop", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_primeepop_title, technical = Res.string.explainer_primeepop_technical,
            oneLiner = Res.string.explainer_primeepop_one_liner,
            analogy = Res.string.explainer_primeepop_analogy,
            whyYou = Res.string.explainer_primeepop_why_you,
            normal = Res.string.explainer_primeepop_normal,
            howItWorks = Res.string.explainer_primeepop_how_it_works,
            related = listOf("unrate", "u6", "civpart"),
        ),
        Explainer(
            id = "longterm", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_longterm_title, technical = Res.string.explainer_longterm_technical,
            oneLiner = Res.string.explainer_longterm_one_liner,
            whyYou = Res.string.explainer_longterm_why_you,
            normal = Res.string.explainer_longterm_normal,
            howItWorks = Res.string.explainer_longterm_how_it_works,
            related = listOf("unrate", "insured", "openings"),
        ),
        Explainer(
            id = "insured", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_insured_title, technical = Res.string.explainer_insured_technical,
            oneLiner = Res.string.explainer_insured_one_liner,
            whyYou = Res.string.explainer_insured_why_you,
            normal = Res.string.explainer_insured_normal,
            howItWorks = Res.string.explainer_insured_how_it_works,
            related = listOf("icsa", "longterm", "unrate"),
        ),
        Explainer(
            id = "quits", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_quits_title, technical = Res.string.explainer_quits_technical,
            oneLiner = Res.string.explainer_quits_one_liner,
            analogy = Res.string.explainer_quits_analogy,
            whyYou = Res.string.explainer_quits_why_you,
            normal = Res.string.explainer_quits_normal,
            howItWorks = Res.string.explainer_quits_how_it_works,
            related = listOf("openings", "unrate", "realwages"),
        ),
        Explainer(
            id = "openings", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_openings_title, technical = Res.string.explainer_openings_technical,
            oneLiner = Res.string.explainer_openings_one_liner,
            whyYou = Res.string.explainer_openings_why_you,
            normal = Res.string.explainer_openings_normal,
            howItWorks = Res.string.explainer_openings_how_it_works,
            related = listOf("quits", "unrate", "longterm"),
        ),
        Explainer(
            id = "realwages", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_realwages_title, technical = Res.string.explainer_realwages_technical,
            oneLiner = Res.string.explainer_realwages_one_liner,
            whyYou = Res.string.explainer_realwages_why_you,
            normal = Res.string.explainer_realwages_normal,
            howItWorks = Res.string.explainer_realwages_how_it_works,
            related = listOf("cpi", "quits", "unrate"),
        ),
        Explainer(
            id = "temphelp", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_temphelp_title, technical = Res.string.explainer_temphelp_technical,
            oneLiner = Res.string.explainer_temphelp_one_liner,
            analogy = Res.string.explainer_temphelp_analogy,
            whyYou = Res.string.explainer_temphelp_why_you,
            normal = Res.string.explainer_temphelp_normal,
            howItWorks = Res.string.explainer_temphelp_how_it_works,
            related = listOf("icsa", "unrate"),
        ),
        Explainer(
            id = "civpart", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_civpart_title, technical = Res.string.explainer_civpart_technical,
            oneLiner = Res.string.explainer_civpart_one_liner,
            whyYou = Res.string.explainer_civpart_why_you,
            normal = Res.string.explainer_civpart_normal,
            howItWorks = Res.string.explainer_civpart_how_it_works,
            related = listOf("primeepop", "unrate", "u6"),
        ),
        Explainer(
            id = "gdp", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_gdp_title, technical = Res.string.explainer_gdp_technical,
            oneLiner = Res.string.explainer_gdp_one_liner,
            analogy = Res.string.explainer_gdp_analogy,
            whyYou = Res.string.explainer_gdp_why_you,
            normal = Res.string.explainer_gdp_normal,
            howItWorks = Res.string.explainer_gdp_how_it_works,
            related = listOf("unrate", "umcsent", "recession"),
        ),
        Explainer(
            id = "umcsent", topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_umcsent_title, technical = Res.string.explainer_umcsent_technical,
            oneLiner = Res.string.explainer_umcsent_one_liner,
            whyYou = Res.string.explainer_umcsent_why_you,
            normal = Res.string.explainer_umcsent_normal,
            howItWorks = Res.string.explainer_umcsent_how_it_works,
            related = listOf("cpi", "unrate", "gdp"),
        ),
        // ---------------------------------------------------------------- rates & the Fed
        Explainer(
            id = "dff", topic = ExplainerTopic.RATES,
            title = Res.string.explainer_dff_title, technical = Res.string.explainer_dff_technical,
            oneLiner = Res.string.explainer_dff_one_liner,
            analogy = Res.string.explainer_dff_analogy,
            whyYou = Res.string.explainer_dff_why_you,
            normal = Res.string.explainer_dff_normal,
            howItWorks = Res.string.explainer_dff_how_it_works,
            related = listOf("cpi", "corepce", "mortgage", "dgs2", "realrate"),
        ),
        Explainer(
            id = "realrate", topic = ExplainerTopic.RATES,
            title = Res.string.explainer_realrate_title, technical = Res.string.explainer_realrate_technical,
            oneLiner = Res.string.explainer_realrate_one_liner,
            whyYou = Res.string.explainer_realrate_why_you,
            normal = Res.string.explainer_realrate_normal,
            howItWorks = Res.string.explainer_realrate_how_it_works,
            related = listOf("dff", "cpi"),
        ),
        Explainer(
            id = "dgs10", topic = ExplainerTopic.RATES,
            title = Res.string.explainer_dgs10_title, technical = Res.string.explainer_dgs10_technical,
            oneLiner = Res.string.explainer_dgs10_one_liner,
            whyYou = Res.string.explainer_dgs10_why_you,
            normal = Res.string.explainer_dgs10_normal,
            howItWorks = Res.string.explainer_dgs10_how_it_works,
            related = listOf("treasuries", "mortgage", "yieldcurve", "debtgdp"),
        ),
        Explainer(
            id = "dgs2",
            topic = ExplainerTopic.RATES,
            title = Res.string.explainer_dgs2_title,
            technical = Res.string.explainer_dgs2_technical,
            oneLiner = Res.string.explainer_dgs2_one_liner,
            whyYou = Res.string.explainer_dgs2_why_you,
            normal = Res.string.explainer_dgs2_normal,
            related = listOf("dff", "treasuries", "yieldcurve"),
        ),
        Explainer(
            id = "dgs30",
            topic = ExplainerTopic.RATES,
            title = Res.string.explainer_dgs30_title,
            technical = Res.string.explainer_dgs30_technical,
            oneLiner = Res.string.explainer_dgs30_one_liner,
            whyYou = Res.string.explainer_dgs30_why_you,
            related = listOf("treasuries", "dgs10", "debtgdp"),
        ),
        Explainer(
            id = "treasuries", topic = ExplainerTopic.RATES,
            title = Res.string.explainer_treasuries_title, technical = Res.string.explainer_treasuries_technical,
            oneLiner = Res.string.explainer_treasuries_one_liner,
            analogy = Res.string.explainer_treasuries_analogy,
            whyYou = Res.string.explainer_treasuries_why_you,
            howItWorks = Res.string.explainer_treasuries_how_it_works,
            related = listOf("dgs10", "yieldcurve", "dff"),
        ),
        Explainer(
            id = "yieldcurve", topic = ExplainerTopic.RATES,
            title = Res.string.explainer_yieldcurve_title, technical = Res.string.explainer_yieldcurve_technical,
            oneLiner = Res.string.explainer_yieldcurve_one_liner,
            analogy = Res.string.explainer_yieldcurve_analogy,
            whyYou = Res.string.explainer_yieldcurve_why_you,
            normal = Res.string.explainer_yieldcurve_normal,
            howItWorks = Res.string.explainer_yieldcurve_how_it_works,
            related = listOf("t10y2y", "t10y3m", "treasuries", "recession"),
        ),
        Explainer(
            id = "t10y2y", topic = ExplainerTopic.RATES,
            title = Res.string.explainer_t10y2y_title, technical = Res.string.explainer_t10y2y_technical,
            oneLiner = Res.string.explainer_t10y2y_one_liner,
            analogy = Res.string.explainer_t10y2y_analogy,
            whyYou = Res.string.explainer_t10y2y_why_you,
            normal = Res.string.explainer_t10y2y_normal,
            related = listOf("yieldcurve", "t10y3m", "recession"),
        ),
        Explainer(
            id = "t10y3m",
            topic = ExplainerTopic.RATES,
            title = Res.string.explainer_t10y3m_title,
            technical = Res.string.explainer_t10y3m_technical,
            oneLiner = Res.string.explainer_t10y3m_one_liner,
            whyYou = Res.string.explainer_t10y3m_why_you,
            normal = Res.string.explainer_t10y3m_normal,
            related = listOf("yieldcurve", "t10y2y", "recession"),
        ),
        // ---------------------------------------------------------------- housing
        Explainer(
            id = "mortgage", topic = ExplainerTopic.HOUSING,
            title = Res.string.explainer_mortgage_title, technical = Res.string.explainer_mortgage_technical,
            oneLiner = Res.string.explainer_mortgage_one_liner,
            whyYou = Res.string.explainer_mortgage_why_you,
            normal = Res.string.explainer_mortgage_normal,
            howItWorks = Res.string.explainer_mortgage_how_it_works,
            related = listOf("dgs10", "dff", "homeprices"),
        ),
        Explainer(
            id = "homeprices", topic = ExplainerTopic.HOUSING,
            title = Res.string.explainer_homeprices_title, technical = Res.string.explainer_homeprices_technical,
            oneLiner = Res.string.explainer_homeprices_one_liner,
            whyYou = Res.string.explainer_homeprices_why_you,
            normal = Res.string.explainer_homeprices_normal,
            howItWorks = Res.string.explainer_homeprices_how_it_works,
            related = listOf("mortgage", "realrate"),
        ),
        // ---------------------------------------------------------------- credit & stress
        Explainer(
            id = "hy", topic = ExplainerTopic.CREDIT,
            title = Res.string.explainer_hy_title, technical = Res.string.explainer_hy_technical,
            oneLiner = Res.string.explainer_hy_one_liner,
            analogy = Res.string.explainer_hy_analogy,
            whyYou = Res.string.explainer_hy_why_you,
            normal = Res.string.explainer_hy_normal,
            related = listOf("stlfsi", "vix", "ccdelinq"),
        ),
        Explainer(
            id = "stlfsi",
            topic = ExplainerTopic.CREDIT,
            title = Res.string.explainer_stlfsi_title,
            technical = Res.string.explainer_stlfsi_technical,
            oneLiner = Res.string.explainer_stlfsi_one_liner,
            whyYou = Res.string.explainer_stlfsi_why_you,
            normal = Res.string.explainer_stlfsi_normal,
            related = listOf("hy", "vix"),
        ),
        Explainer(
            id = "vix", topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_vix_title, technical = Res.string.explainer_vix_technical,
            oneLiner = Res.string.explainer_vix_one_liner,
            analogy = Res.string.explainer_vix_analogy,
            whyYou = Res.string.explainer_vix_why_you,
            normal = Res.string.explainer_vix_normal,
            howItWorks = Res.string.explainer_vix_how_it_works,
            related = listOf("sp500", "hy", "stlfsi"),
        ),
        Explainer(
            id = "ccdelinq",
            topic = ExplainerTopic.CREDIT,
            title = Res.string.explainer_ccdelinq_title,
            technical = Res.string.explainer_ccdelinq_technical,
            oneLiner = Res.string.explainer_ccdelinq_one_liner,
            whyYou = Res.string.explainer_ccdelinq_why_you,
            normal = Res.string.explainer_ccdelinq_normal,
            related = listOf("unrate", "dff", "umcsent"),
        ),
        // ---------------------------------------------------------------- government & money
        Explainer(
            id = "debtgdp", topic = ExplainerTopic.GOVERNMENT,
            title = Res.string.explainer_debtgdp_title, technical = Res.string.explainer_debtgdp_technical,
            oneLiner = Res.string.explainer_debtgdp_one_liner,
            analogy = Res.string.explainer_debtgdp_analogy,
            whyYou = Res.string.explainer_debtgdp_why_you,
            normal = Res.string.explainer_debtgdp_normal,
            related = listOf("interest", "dgs10", "dgs30"),
        ),
        Explainer(
            id = "interest",
            topic = ExplainerTopic.GOVERNMENT,
            title = Res.string.explainer_interest_title,
            technical = Res.string.explainer_interest_technical,
            oneLiner = Res.string.explainer_interest_one_liner,
            whyYou = Res.string.explainer_interest_why_you,
            normal = Res.string.explainer_interest_normal,
            related = listOf("debtgdp", "dgs10"),
        ),
        Explainer(
            id = "m2",
            topic = ExplainerTopic.GOVERNMENT,
            title = Res.string.explainer_m2_title,
            technical = Res.string.explainer_m2_technical,
            oneLiner = Res.string.explainer_m2_one_liner,
            whyYou = Res.string.explainer_m2_why_you,
            normal = Res.string.explainer_m2_normal,
            related = listOf("cpi", "dff"),
        ),
        Explainer(
            id = "recession",
            topic = ExplainerTopic.JOBS,
            title = Res.string.explainer_recession_title,
            oneLiner = Res.string.explainer_recession_one_liner,
            whyYou = Res.string.explainer_recession_why_you,
            normal = Res.string.explainer_recession_normal,
            howItWorks = Res.string.explainer_recession_how_it_works,
            related = listOf("sahm", "t10y2y", "gdp", "stress"),
        ),
        Explainer(
            id = "stress", topic = ExplainerTopic.CREDIT,
            title = Res.string.explainer_stress_title, technical = Res.string.explainer_stress_technical,
            oneLiner = Res.string.explainer_stress_one_liner,
            whyYou = Res.string.explainer_stress_why_you,
            normal = Res.string.explainer_stress_normal,
            howItWorks = Res.string.explainer_stress_how_it_works,
            related = listOf("recession", "t10y2y", "sahm", "hy"),
        ),
        // ---------------------------------------------------------------- markets
        Explainer(
            id = "sp500", topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_sp500_title, technical = Res.string.explainer_sp500_technical,
            oneLiner = Res.string.explainer_sp500_one_liner,
            whyYou = Res.string.explainer_sp500_why_you,
            normal = Res.string.explainer_sp500_normal,
            howItWorks = Res.string.explainer_sp500_how_it_works,
            related = listOf("vix", "dff", "dow", "nasdaq"),
        ),
        Explainer(
            id = "dow",
            topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_dow_title,
            technical = Res.string.explainer_dow_technical,
            oneLiner = Res.string.explainer_dow_one_liner,
            whyYou = Res.string.explainer_dow_why_you,
            related = listOf("sp500"),
        ),
        Explainer(
            id = "nasdaq",
            topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_nasdaq_title,
            technical = Res.string.explainer_nasdaq_technical,
            oneLiner = Res.string.explainer_nasdaq_one_liner,
            whyYou = Res.string.explainer_nasdaq_why_you,
            related = listOf("sp500", "dff"),
        ),
        Explainer(
            id = "russell",
            topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_russell_title,
            technical = Res.string.explainer_russell_technical,
            oneLiner = Res.string.explainer_russell_one_liner,
            whyYou = Res.string.explainer_russell_why_you,
            related = listOf("sp500", "dff"),
        ),
        Explainer(
            id = "gold",
            topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_gold_title,
            technical = Res.string.explainer_gold_technical,
            oneLiner = Res.string.explainer_gold_one_liner,
            whyYou = Res.string.explainer_gold_why_you,
            related = listOf("dollar", "cpi"),
        ),
        Explainer(
            id = "oil",
            topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_oil_title,
            technical = Res.string.explainer_oil_technical,
            oneLiner = Res.string.explainer_oil_one_liner,
            whyYou = Res.string.explainer_oil_why_you,
            related = listOf("cpi"),
        ),
        Explainer(
            id = "bitcoin",
            topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_bitcoin_title,
            technical = Res.string.explainer_bitcoin_technical,
            oneLiner = Res.string.explainer_bitcoin_one_liner,
            whyYou = Res.string.explainer_bitcoin_why_you,
            related = listOf("vix"),
        ),
        Explainer(
            id = "dollar",
            topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_dollar_title,
            technical = Res.string.explainer_dollar_technical,
            oneLiner = Res.string.explainer_dollar_one_liner,
            whyYou = Res.string.explainer_dollar_why_you,
            related = listOf("gold"),
        ),
        Explainer(
            id = "range52w",
            topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_range52w_title,
            oneLiner = Res.string.explainer_range52w_one_liner,
            whyYou = Res.string.explainer_range52w_why_you,
            related = listOf("sp500"),
        ),
        Explainer(
            id = "prevclose",
            topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_prevclose_title,
            oneLiner = Res.string.explainer_prevclose_one_liner,
            whyYou = Res.string.explainer_prevclose_why_you,
            related = listOf("sp500"),
        ),
        Explainer(
            id = "volume",
            topic = ExplainerTopic.MARKETS,
            title = Res.string.explainer_volume_title,
            oneLiner = Res.string.explainer_volume_one_liner,
            whyYou = Res.string.explainer_volume_why_you,
            related = listOf("sp500"),
        ),
        // ---------------------------------------------------------------- your money
        Explainer(
            id = "totalassets",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_totalassets_title,
            oneLiner = Res.string.explainer_totalassets_one_liner,
            whyYou = Res.string.explainer_totalassets_why_you,
            related = listOf("networth", "allocation"),
        ),
        Explainer(
            id = "networth",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_networth_title,
            oneLiner = Res.string.explainer_networth_one_liner,
            analogy = Res.string.explainer_networth_analogy,
            whyYou = Res.string.explainer_networth_why_you,
            howItWorks = Res.string.explainer_networth_how_it_works,
            related = listOf("totalassets", "debt"),
        ),
        Explainer(
            id = "allocation",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_allocation_title,
            technical = Res.string.explainer_allocation_technical,
            oneLiner = Res.string.explainer_allocation_one_liner,
            whyYou = Res.string.explainer_allocation_why_you,
            related = listOf("networth", "runway"),
        ),
        Explainer(
            id = "cashflow",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_cashflow_title,
            oneLiner = Res.string.explainer_cashflow_one_liner,
            whyYou = Res.string.explainer_cashflow_why_you,
            related = listOf("savingsrate"),
        ),
        Explainer(
            id = "budgetpace",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_budgetpace_title,
            oneLiner = Res.string.explainer_budgetpace_one_liner,
            whyYou = Res.string.explainer_budgetpace_why_you,
            howItWorks = Res.string.explainer_budgetpace_how_it_works,
            related = listOf("savingsline", "cashflow"),
        ),
        Explainer(
            id = "savingsline",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_savingsline_title,
            oneLiner = Res.string.explainer_savingsline_one_liner,
            whyYou = Res.string.explainer_savingsline_why_you,
            howItWorks = Res.string.explainer_savingsline_how_it_works,
            related = listOf("budgetpace", "savingsrate", "runway"),
        ),
        Explainer(
            id = "savingsrate",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_savingsrate_title,
            oneLiner = Res.string.explainer_savingsrate_one_liner,
            whyYou = Res.string.explainer_savingsrate_why_you,
            related = listOf("cashflow", "runway"),
        ),
        Explainer(
            id = "runway",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_runway_title,
            technical = Res.string.explainer_runway_technical,
            oneLiner = Res.string.explainer_runway_one_liner,
            whyYou = Res.string.explainer_runway_why_you,
            related = listOf("unrate", "sahm", "allocation"),
        ),
        Explainer(
            id = "debt",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_debt_title,
            oneLiner = Res.string.explainer_debt_one_liner,
            whyYou = Res.string.explainer_debt_why_you,
            related = listOf("dff", "networth"),
        ),
        Explainer(
            id = "homeequity",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_homeequity_title,
            oneLiner = Res.string.explainer_homeequity_one_liner,
            whyYou = Res.string.explainer_homeequity_why_you,
            related = listOf("homeprices", "mortgage"),
        ),
        Explainer(
            id = "mortgageplanner",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_mortgageplanner_title,
            oneLiner = Res.string.explainer_mortgageplanner_one_liner,
            whyYou = Res.string.explainer_mortgageplanner_why_you,
            related = listOf("mortgage", "cashflow"),
        ),
        Explainer(
            id = "vesting",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_vesting_title,
            technical = Res.string.explainer_vesting_technical,
            oneLiner = Res.string.explainer_vesting_one_liner,
            whyYou = Res.string.explainer_vesting_why_you,
            related = listOf("taxes"),
        ),
        Explainer(
            id = "taxes",
            topic = ExplainerTopic.YOUR_MONEY,
            title = Res.string.explainer_taxes_title,
            oneLiner = Res.string.explainer_taxes_one_liner,
            whyYou = Res.string.explainer_taxes_why_you,
            related = listOf("vesting"),
        ),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Explainer? = byId[id]

    /**
     * Everything, by topic, for the glossary. Within a topic the order is this file's; the titles
     * are resources, so the glossary sorts them alphabetically once they're read in the reader's language.
     */
    val glossary: List<Explainer> get() = all.sortedBy { it.topic.ordinal }

    /** Which explainer a market symbol has. */
    fun forSymbol(symbol: String): String? = when (symbol) {
        MarketCatalog.SP500.symbol -> "sp500"
        MarketCatalog.DOW.symbol -> "dow"
        MarketCatalog.NASDAQ.symbol -> "nasdaq"
        MarketCatalog.RUSSELL.symbol -> "russell"
        MarketCatalog.VIX.symbol -> "vix"
        MarketCatalog.TEN_YEAR.symbol -> "dgs10"
        MarketCatalog.GOLD.symbol -> "gold"
        MarketCatalog.OIL.symbol -> "oil"
        MarketCatalog.BITCOIN.symbol -> "bitcoin"
        MarketCatalog.DOLLAR.symbol -> "dollar"
        else -> null
    }
}
