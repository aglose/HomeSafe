package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.indicator_cadence_daily
import homesafe.shared.generated.resources.indicator_cadence_monthly
import homesafe.shared.generated.resources.indicator_cadence_quarterly
import homesafe.shared.generated.resources.indicator_cadence_weekly
import homesafe.shared.generated.resources.indicator_ccdelinq_danger_note
import homesafe.shared.generated.resources.indicator_ccdelinq_short_title
import homesafe.shared.generated.resources.indicator_ccdelinq_title
import homesafe.shared.generated.resources.indicator_ccdelinq_why
import homesafe.shared.generated.resources.indicator_corecpi_short_title
import homesafe.shared.generated.resources.indicator_corecpi_title
import homesafe.shared.generated.resources.indicator_corecpi_why
import homesafe.shared.generated.resources.indicator_corepce_short_title
import homesafe.shared.generated.resources.indicator_corepce_title
import homesafe.shared.generated.resources.indicator_corepce_why
import homesafe.shared.generated.resources.indicator_cpi_danger_note
import homesafe.shared.generated.resources.indicator_cpi_short_title
import homesafe.shared.generated.resources.indicator_cpi_title
import homesafe.shared.generated.resources.indicator_cpi_why
import homesafe.shared.generated.resources.indicator_debtgdp_danger_note
import homesafe.shared.generated.resources.indicator_debtgdp_short_title
import homesafe.shared.generated.resources.indicator_debtgdp_title
import homesafe.shared.generated.resources.indicator_debtgdp_why
import homesafe.shared.generated.resources.indicator_dff_short_title
import homesafe.shared.generated.resources.indicator_dff_title
import homesafe.shared.generated.resources.indicator_dff_why
import homesafe.shared.generated.resources.indicator_dgs10_danger_note
import homesafe.shared.generated.resources.indicator_dgs10_short_title
import homesafe.shared.generated.resources.indicator_dgs10_title
import homesafe.shared.generated.resources.indicator_dgs10_why
import homesafe.shared.generated.resources.indicator_dgs2_short_title
import homesafe.shared.generated.resources.indicator_dgs2_title
import homesafe.shared.generated.resources.indicator_dgs2_why
import homesafe.shared.generated.resources.indicator_dgs30_short_title
import homesafe.shared.generated.resources.indicator_dgs30_title
import homesafe.shared.generated.resources.indicator_dgs30_why
import homesafe.shared.generated.resources.indicator_gdp_danger_note
import homesafe.shared.generated.resources.indicator_gdp_short_title
import homesafe.shared.generated.resources.indicator_gdp_title
import homesafe.shared.generated.resources.indicator_gdp_why
import homesafe.shared.generated.resources.indicator_group_credit_blurb
import homesafe.shared.generated.resources.indicator_group_credit_title
import homesafe.shared.generated.resources.indicator_group_debt_blurb
import homesafe.shared.generated.resources.indicator_group_debt_title
import homesafe.shared.generated.resources.indicator_group_inflation_blurb
import homesafe.shared.generated.resources.indicator_group_inflation_title
import homesafe.shared.generated.resources.indicator_group_recession_blurb
import homesafe.shared.generated.resources.indicator_group_recession_title
import homesafe.shared.generated.resources.indicator_homeprices_danger_note
import homesafe.shared.generated.resources.indicator_homeprices_short_title
import homesafe.shared.generated.resources.indicator_homeprices_title
import homesafe.shared.generated.resources.indicator_homeprices_why
import homesafe.shared.generated.resources.indicator_hy_danger_note
import homesafe.shared.generated.resources.indicator_hy_short_title
import homesafe.shared.generated.resources.indicator_hy_title
import homesafe.shared.generated.resources.indicator_hy_why
import homesafe.shared.generated.resources.indicator_icsa_danger_note
import homesafe.shared.generated.resources.indicator_icsa_short_title
import homesafe.shared.generated.resources.indicator_icsa_title
import homesafe.shared.generated.resources.indicator_icsa_why
import homesafe.shared.generated.resources.indicator_interest_danger_note
import homesafe.shared.generated.resources.indicator_interest_short_title
import homesafe.shared.generated.resources.indicator_interest_title
import homesafe.shared.generated.resources.indicator_interest_why
import homesafe.shared.generated.resources.indicator_m2_danger_note
import homesafe.shared.generated.resources.indicator_m2_short_title
import homesafe.shared.generated.resources.indicator_m2_title
import homesafe.shared.generated.resources.indicator_m2_why
import homesafe.shared.generated.resources.indicator_mortgage_danger_note
import homesafe.shared.generated.resources.indicator_mortgage_short_title
import homesafe.shared.generated.resources.indicator_mortgage_title
import homesafe.shared.generated.resources.indicator_mortgage_why
import homesafe.shared.generated.resources.indicator_reference_contraction
import homesafe.shared.generated.resources.indicator_reference_fed_target
import homesafe.shared.generated.resources.indicator_reference_inversion
import homesafe.shared.generated.resources.indicator_reference_normal
import homesafe.shared.generated.resources.indicator_reference_recession_trigger
import homesafe.shared.generated.resources.indicator_reference_shrinking
import homesafe.shared.generated.resources.indicator_sahm_danger_note
import homesafe.shared.generated.resources.indicator_sahm_short_title
import homesafe.shared.generated.resources.indicator_sahm_title
import homesafe.shared.generated.resources.indicator_sahm_why
import homesafe.shared.generated.resources.indicator_signal_calm
import homesafe.shared.generated.resources.indicator_signal_danger
import homesafe.shared.generated.resources.indicator_signal_watch
import homesafe.shared.generated.resources.indicator_stlfsi_danger_note
import homesafe.shared.generated.resources.indicator_stlfsi_short_title
import homesafe.shared.generated.resources.indicator_stlfsi_title
import homesafe.shared.generated.resources.indicator_stlfsi_why
import homesafe.shared.generated.resources.indicator_stress_calm
import homesafe.shared.generated.resources.indicator_stress_elevated
import homesafe.shared.generated.resources.indicator_stress_high
import homesafe.shared.generated.resources.indicator_stress_severe
import homesafe.shared.generated.resources.indicator_t10y2y_danger_note
import homesafe.shared.generated.resources.indicator_t10y2y_short_title
import homesafe.shared.generated.resources.indicator_t10y2y_title
import homesafe.shared.generated.resources.indicator_t10y2y_why
import homesafe.shared.generated.resources.indicator_t10y3m_danger_note
import homesafe.shared.generated.resources.indicator_t10y3m_short_title
import homesafe.shared.generated.resources.indicator_t10y3m_title
import homesafe.shared.generated.resources.indicator_t10y3m_why
import homesafe.shared.generated.resources.indicator_tenor_10y
import homesafe.shared.generated.resources.indicator_tenor_1m
import homesafe.shared.generated.resources.indicator_tenor_1y
import homesafe.shared.generated.resources.indicator_tenor_20y
import homesafe.shared.generated.resources.indicator_tenor_2y
import homesafe.shared.generated.resources.indicator_tenor_30y
import homesafe.shared.generated.resources.indicator_tenor_3m
import homesafe.shared.generated.resources.indicator_tenor_3y
import homesafe.shared.generated.resources.indicator_tenor_5y
import homesafe.shared.generated.resources.indicator_tenor_6m
import homesafe.shared.generated.resources.indicator_tenor_7y
import homesafe.shared.generated.resources.indicator_umcsent_danger_note
import homesafe.shared.generated.resources.indicator_umcsent_short_title
import homesafe.shared.generated.resources.indicator_umcsent_title
import homesafe.shared.generated.resources.indicator_umcsent_why
import homesafe.shared.generated.resources.indicator_unrate_danger_note
import homesafe.shared.generated.resources.indicator_unrate_short_title
import homesafe.shared.generated.resources.indicator_unrate_title
import homesafe.shared.generated.resources.indicator_unrate_why
import homesafe.shared.generated.resources.indicator_vix_danger_note
import homesafe.shared.generated.resources.indicator_vix_short_title
import homesafe.shared.generated.resources.indicator_vix_title
import homesafe.shared.generated.resources.indicator_vix_why
import org.jetbrains.compose.resources.StringResource

/** How an indicator is shown: a raw level, or its change from a year before (a price index → inflation). */
enum class Transform { LEVEL, YEAR_OVER_YEAR }

/** How a reading is written. */
enum class IndicatorUnit { PERCENT, INDEX, THOUSANDS, RATIO }

/** Calm, worth watching, or flashing red. */
enum class Signal(val label: StringResource) {
    CALM(Res.string.indicator_signal_calm),
    WATCH(Res.string.indicator_signal_watch),
    DANGER(Res.string.indicator_signal_danger),
}

/** The group an indicator sits in on the Risk tab. */
enum class IndicatorGroup(val title: StringResource, val blurb: StringResource) {
    RECESSION(Res.string.indicator_group_recession_title, Res.string.indicator_group_recession_blurb),
    CREDIT(Res.string.indicator_group_credit_title, Res.string.indicator_group_credit_blurb),
    DEBT(Res.string.indicator_group_debt_title, Res.string.indicator_group_debt_blurb),
    INFLATION(Res.string.indicator_group_inflation_title, Res.string.indicator_group_inflation_blurb),
}

/**
 * Where an indicator turns from calm to watch to danger. With [higherIsWorse] a reading at or
 * above [danger] is danger and at or above [watch] is watch; otherwise the same, downwards.
 */
@Immutable
data class Thresholds(val watch: Double, val danger: Double, val higherIsWorse: Boolean) {
    fun signal(value: Double): Signal = if (higherIsWorse) {
        when {
            value >= danger -> Signal.DANGER
            value >= watch -> Signal.WATCH
            else -> Signal.CALM
        }
    } else {
        when {
            value <= danger -> Signal.DANGER
            value <= watch -> Signal.WATCH
            else -> Signal.CALM
        }
    }

    /**
     * 0 (calm) to 1 (at or past danger): 0.5 at the watch line, and linear either side of it,
     * with the calm half as wide as the watch-to-danger gap. What the composite gauge averages.
     */
    fun stress(value: Double): Double {
        // Signed the way things get worse, so in both directions a positive distance past the
        // watch line, in watch-to-danger gaps, is worse.
        val gap = danger - watch
        if (gap == 0.0) {
            return when (signal(value)) {
                Signal.DANGER -> 1.0
                Signal.WATCH -> 0.5
                Signal.CALM -> 0.0
            }
        }
        val beyond = (value - watch) / gap
        return (0.5 + beyond * 0.5).coerceIn(0.0, 1.0)
    }
}

/**
 * One economic reading from FRED. [fredIds] is usually one series; a ratio indicator names two
 * and divides the first by the second ([ratioPercent]). [startDate] bounds how far back it is
 * fetched (a year further for [Transform.YEAR_OVER_YEAR], which needs the year before).
 */
@Immutable
data class Indicator(
    val id: String,
    val title: StringResource,
    val shortTitle: StringResource,
    val fredIds: List<String>,
    val unit: IndicatorUnit,
    val group: IndicatorGroup,
    val transform: Transform = Transform.LEVEL,
    val thresholds: Thresholds? = null,
    /** Why it matters, in plain words. */
    val why: StringResource,
    /** What the danger line means, said once on the detail page. */
    val dangerNote: StringResource? = null,
    /** A reference line to draw (the Fed's 2% target, a 0% inversion line). */
    val referenceLine: Double? = null,
    val referenceLabel: StringResource? = null,
    val ratioPercent: Boolean = false,
    val startDate: String = "1990-01-01",
    /** Multiplies every reading, e.g. jobless claims to thousands. */
    val scale: Double = 1.0,
    /** How much the composite stress gauge leans on it. */
    val weight: Double = 1.0,
    /** Readings arrive this often, which is how stale data is judged; its label is the detail page's "Updated". */
    val cadence: Cadence = Cadence.DAILY,
)

enum class Cadence(val label: StringResource) {
    DAILY(Res.string.indicator_cadence_daily),
    WEEKLY(Res.string.indicator_cadence_weekly),
    MONTHLY(Res.string.indicator_cadence_monthly),
    QUARTERLY(Res.string.indicator_cadence_quarterly),
}

/** Every FRED-backed reading the app follows. Thresholds are rules of thumb, explained on each page. */
object IndicatorCatalog {
    val yieldCurve10y2y = Indicator(
        id = "t10y2y", title = Res.string.indicator_t10y2y_title, shortTitle = Res.string.indicator_t10y2y_short_title,
        fredIds = listOf("T10Y2Y"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 0.25, danger = 0.0, higherIsWorse = false),
        why = Res.string.indicator_t10y2y_why,
        dangerNote = Res.string.indicator_t10y2y_danger_note,
        referenceLine = 0.0, referenceLabel = Res.string.indicator_reference_inversion, startDate = "1990-01-01", weight = 1.5,
    )
    val yieldCurve10y3m = Indicator(
        id = "t10y3m", title = Res.string.indicator_t10y3m_title, shortTitle = Res.string.indicator_t10y3m_short_title,
        fredIds = listOf("T10Y3M"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 0.3, danger = 0.0, higherIsWorse = false),
        why = Res.string.indicator_t10y3m_why,
        dangerNote = Res.string.indicator_t10y3m_danger_note,
        referenceLine = 0.0, referenceLabel = Res.string.indicator_reference_inversion, startDate = "1990-01-01", weight = 1.5,
    )
    val sahm = Indicator(
        id = "sahm", title = Res.string.indicator_sahm_title, shortTitle = Res.string.indicator_sahm_short_title,
        fredIds = listOf("SAHMREALTIME"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 0.3, danger = 0.5, higherIsWorse = true),
        why = Res.string.indicator_sahm_why,
        dangerNote = Res.string.indicator_sahm_danger_note,
        referenceLine = 0.5, referenceLabel = Res.string.indicator_reference_recession_trigger, startDate = "1990-01-01", weight = 1.5, cadence = Cadence.MONTHLY,
    )
    val unemployment = Indicator(
        id = "unrate", title = Res.string.indicator_unrate_title, shortTitle = Res.string.indicator_unrate_short_title,
        fredIds = listOf("UNRATE"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 4.5, danger = 5.5, higherIsWorse = true),
        why = Res.string.indicator_unrate_why,
        dangerNote = Res.string.indicator_unrate_danger_note,
        startDate = "1990-01-01", cadence = Cadence.MONTHLY,
    )
    val claims = Indicator(
        id = "icsa", title = Res.string.indicator_icsa_title, shortTitle = Res.string.indicator_icsa_short_title,
        fredIds = listOf("ICSA"), unit = IndicatorUnit.THOUSANDS, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 250.0, danger = 300.0, higherIsWorse = true),
        why = Res.string.indicator_icsa_why,
        dangerNote = Res.string.indicator_icsa_danger_note,
        startDate = "2000-01-01", scale = 0.001, cadence = Cadence.WEEKLY,
    )
    val gdp = Indicator(
        id = "gdp", title = Res.string.indicator_gdp_title, shortTitle = Res.string.indicator_gdp_short_title,
        fredIds = listOf("A191RL1Q225SBEA"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 1.0, danger = 0.0, higherIsWorse = false),
        why = Res.string.indicator_gdp_why,
        dangerNote = Res.string.indicator_gdp_danger_note,
        referenceLine = 0.0, referenceLabel = Res.string.indicator_reference_contraction, startDate = "1990-01-01", cadence = Cadence.QUARTERLY,
    )
    val sentiment = Indicator(
        id = "umcsent", title = Res.string.indicator_umcsent_title, shortTitle = Res.string.indicator_umcsent_short_title,
        fredIds = listOf("UMCSENT"), unit = IndicatorUnit.INDEX, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 70.0, danger = 55.0, higherIsWorse = false),
        why = Res.string.indicator_umcsent_why,
        dangerNote = Res.string.indicator_umcsent_danger_note,
        startDate = "1990-01-01", weight = 0.5, cadence = Cadence.MONTHLY,
    )
    val highYield = Indicator(
        id = "hy", title = Res.string.indicator_hy_title, shortTitle = Res.string.indicator_hy_short_title,
        fredIds = listOf("BAMLH0A0HYM2"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.CREDIT,
        thresholds = Thresholds(watch = 4.5, danger = 6.0, higherIsWorse = true),
        why = Res.string.indicator_hy_why,
        dangerNote = Res.string.indicator_hy_danger_note,
        startDate = "1997-01-01", weight = 1.5,
    )
    val stress = Indicator(
        id = "stlfsi", title = Res.string.indicator_stlfsi_title, shortTitle = Res.string.indicator_stlfsi_short_title,
        fredIds = listOf("STLFSI4"), unit = IndicatorUnit.INDEX, group = IndicatorGroup.CREDIT,
        thresholds = Thresholds(watch = 0.0, danger = 1.0, higherIsWorse = true),
        why = Res.string.indicator_stlfsi_why,
        dangerNote = Res.string.indicator_stlfsi_danger_note,
        referenceLine = 0.0, referenceLabel = Res.string.indicator_reference_normal, startDate = "1994-01-01", weight = 1.5, cadence = Cadence.WEEKLY,
    )
    val vix = Indicator(
        id = "vix", title = Res.string.indicator_vix_title, shortTitle = Res.string.indicator_vix_short_title,
        fredIds = listOf("VIXCLS"), unit = IndicatorUnit.INDEX, group = IndicatorGroup.CREDIT,
        thresholds = Thresholds(watch = 20.0, danger = 30.0, higherIsWorse = true),
        why = Res.string.indicator_vix_why,
        dangerNote = Res.string.indicator_vix_danger_note,
        startDate = "1990-01-01",
    )
    val delinquency = Indicator(
        id = "ccdelinq", title = Res.string.indicator_ccdelinq_title, shortTitle = Res.string.indicator_ccdelinq_short_title,
        fredIds = listOf("DRCCLACBS"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.CREDIT,
        thresholds = Thresholds(watch = 3.5, danger = 4.5, higherIsWorse = true),
        why = Res.string.indicator_ccdelinq_why,
        dangerNote = Res.string.indicator_ccdelinq_danger_note,
        startDate = "1991-01-01", cadence = Cadence.QUARTERLY,
    )
    val debtToGdp = Indicator(
        id = "debtgdp", title = Res.string.indicator_debtgdp_title, shortTitle = Res.string.indicator_debtgdp_short_title,
        fredIds = listOf("GFDEGDQ188S"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.DEBT,
        thresholds = Thresholds(watch = 100.0, danger = 125.0, higherIsWorse = true),
        why = Res.string.indicator_debtgdp_why,
        dangerNote = Res.string.indicator_debtgdp_danger_note,
        startDate = "1990-01-01", weight = 0.75, cadence = Cadence.QUARTERLY,
    )
    val interestBurden = Indicator(
        id = "interest", title = Res.string.indicator_interest_title, shortTitle = Res.string.indicator_interest_short_title,
        fredIds = listOf("A091RC1Q027SBEA", "W006RC1Q027SBEA"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.DEBT,
        thresholds = Thresholds(watch = 20.0, danger = 30.0, higherIsWorse = true),
        why = Res.string.indicator_interest_why,
        dangerNote = Res.string.indicator_interest_danger_note,
        ratioPercent = true, startDate = "1990-01-01", weight = 1.0, cadence = Cadence.QUARTERLY,
    )
    val m2 = Indicator(
        id = "m2", title = Res.string.indicator_m2_title, shortTitle = Res.string.indicator_m2_short_title,
        fredIds = listOf("M2SL"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.DEBT,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 2.0, danger = 0.0, higherIsWorse = false),
        why = Res.string.indicator_m2_why,
        dangerNote = Res.string.indicator_m2_danger_note,
        referenceLine = 0.0, referenceLabel = Res.string.indicator_reference_shrinking, startDate = "1989-01-01", weight = 0.5, cadence = Cadence.MONTHLY,
    )
    val cpi = Indicator(
        id = "cpi", title = Res.string.indicator_cpi_title, shortTitle = Res.string.indicator_cpi_short_title,
        fredIds = listOf("CPIAUCSL"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 3.0, danger = 5.0, higherIsWorse = true),
        why = Res.string.indicator_cpi_why,
        dangerNote = Res.string.indicator_cpi_danger_note,
        referenceLine = 2.0, referenceLabel = Res.string.indicator_reference_fed_target, startDate = "1989-01-01", cadence = Cadence.MONTHLY,
    )
    val coreCpi = Indicator(
        id = "corecpi", title = Res.string.indicator_corecpi_title, shortTitle = Res.string.indicator_corecpi_short_title,
        fredIds = listOf("CPILFESL"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 3.0, danger = 4.5, higherIsWorse = true),
        why = Res.string.indicator_corecpi_why,
        referenceLine = 2.0, referenceLabel = Res.string.indicator_reference_fed_target, startDate = "1989-01-01", weight = 0.75, cadence = Cadence.MONTHLY,
    )
    val corePce = Indicator(
        id = "corepce", title = Res.string.indicator_corepce_title, shortTitle = Res.string.indicator_corepce_short_title,
        fredIds = listOf("PCEPILFE"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 2.75, danger = 4.0, higherIsWorse = true),
        why = Res.string.indicator_corepce_why,
        referenceLine = 2.0, referenceLabel = Res.string.indicator_reference_fed_target, startDate = "1989-01-01", weight = 0.75, cadence = Cadence.MONTHLY,
    )
    val fedFunds = Indicator(
        id = "dff",
        title = Res.string.indicator_dff_title,
        shortTitle = Res.string.indicator_dff_short_title,
        fredIds = listOf("DFF"),
        unit = IndicatorUnit.PERCENT,
        group = IndicatorGroup.INFLATION,
        why = Res.string.indicator_dff_why,
        startDate = "1990-01-01",
    )
    val mortgage = Indicator(
        id = "mortgage", title = Res.string.indicator_mortgage_title, shortTitle = Res.string.indicator_mortgage_short_title,
        fredIds = listOf("MORTGAGE30US"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        thresholds = Thresholds(watch = 6.5, danger = 7.5, higherIsWorse = true),
        why = Res.string.indicator_mortgage_why,
        dangerNote = Res.string.indicator_mortgage_danger_note,
        startDate = "1990-01-01", weight = 0.5, cadence = Cadence.WEEKLY,
    )
    val homePrices = Indicator(
        id = "homeprices", title = Res.string.indicator_homeprices_title, shortTitle = Res.string.indicator_homeprices_short_title,
        fredIds = listOf("CSUSHPINSA"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 0.0, danger = -5.0, higherIsWorse = false),
        why = Res.string.indicator_homeprices_why,
        dangerNote = Res.string.indicator_homeprices_danger_note,
        referenceLine = 0.0, startDate = "1989-01-01", weight = 0.75, cadence = Cadence.MONTHLY,
    )
    val tenYear = Indicator(
        id = "dgs10", title = Res.string.indicator_dgs10_title, shortTitle = Res.string.indicator_dgs10_short_title,
        fredIds = listOf("DGS10"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        thresholds = Thresholds(watch = 4.75, danger = 5.5, higherIsWorse = true),
        why = Res.string.indicator_dgs10_why,
        dangerNote = Res.string.indicator_dgs10_danger_note,
        startDate = "1990-01-01",
    )
    val twoYear = Indicator(
        id = "dgs2",
        title = Res.string.indicator_dgs2_title,
        shortTitle = Res.string.indicator_dgs2_short_title,
        fredIds = listOf("DGS2"),
        unit = IndicatorUnit.PERCENT,
        group = IndicatorGroup.INFLATION,
        why = Res.string.indicator_dgs2_why,
        startDate = "1990-01-01",
    )
    val thirtyYear = Indicator(
        id = "dgs30",
        title = Res.string.indicator_dgs30_title,
        shortTitle = Res.string.indicator_dgs30_short_title,
        fredIds = listOf("DGS30"),
        unit = IndicatorUnit.PERCENT,
        group = IndicatorGroup.INFLATION,
        why = Res.string.indicator_dgs30_why,
        startDate = "1990-01-01",
    )

    /** The readings that feed the Risk tab's gauge, by group. */
    val radar: List<Indicator> = listOf(
        yieldCurve10y2y, yieldCurve10y3m, sahm, unemployment, claims, gdp, sentiment,
        highYield, stress, vix, delinquency,
        debtToGdp, interestBurden, m2,
        cpi, coreCpi, corePce, mortgage, homePrices, tenYear,
    )

    val all: List<Indicator> = radar + listOf(fedFunds, twoYear, thirtyYear)

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Indicator? = byId[id]
}

/** The Treasury curve's tenors, as FRED's constant-maturity series and how far out each is in years. [label] is the short "10Y". */
@Immutable
data class Tenor(val label: StringResource, val fredId: String, val years: Double)

object YieldCurve {
    val tenors = listOf(
        Tenor(Res.string.indicator_tenor_1m, "DGS1MO", 1.0 / 12),
        Tenor(Res.string.indicator_tenor_3m, "DGS3MO", 0.25),
        Tenor(Res.string.indicator_tenor_6m, "DGS6MO", 0.5),
        Tenor(Res.string.indicator_tenor_1y, "DGS1", 1.0),
        Tenor(Res.string.indicator_tenor_2y, "DGS2", 2.0),
        Tenor(Res.string.indicator_tenor_3y, "DGS3", 3.0),
        Tenor(Res.string.indicator_tenor_5y, "DGS5", 5.0),
        Tenor(Res.string.indicator_tenor_7y, "DGS7", 7.0),
        Tenor(Res.string.indicator_tenor_10y, "DGS10", 10.0),
        Tenor(Res.string.indicator_tenor_20y, "DGS20", 20.0),
        Tenor(Res.string.indicator_tenor_30y, "DGS30", 30.0),
    )

    /** How far back the curve's tenors are fetched: enough to draw the curve as it stood two years ago. */
    const val START_DATE = "2023-06-01"
}

/** An indicator's latest reading, its history (transformed), and what it says now. */
@Immutable
data class IndicatorReading(
    val indicator: Indicator,
    val history: Series,
) {
    val latest: Double? get() = history.lastValue
    val latestEpochSeconds: Long? get() = history.lastTime
    val signal: Signal? get() = latest?.let { v -> indicator.thresholds?.signal(v) }
    val stress: Double? get() = latest?.let { v -> indicator.thresholds?.stress(v) }

    /** Change from the reading about a year ago, in the indicator's own units. */
    val yearChange: Double? get() {
        val t = latestEpochSeconds ?: return null
        val prior = history.valueAtOrBefore(t - Series.YEAR_SECONDS) ?: return null
        return latest!! - prior
    }

    /** Change from the reading before the latest. */
    val lastChange: Double? get() =
        if (history.size < 2) null else history.values[history.size - 1] - history.values[history.size - 2]
}

/** The composite: weighted mean stress of every reading that has thresholds, 0–100. */
@Immutable
data class StressScore(val score: Double, val counted: Int, val dangers: Int, val watches: Int) {
    val label: StringResource get() = when {
        score >= 70 -> Res.string.indicator_stress_severe
        score >= 50 -> Res.string.indicator_stress_high
        score >= 30 -> Res.string.indicator_stress_elevated
        else -> Res.string.indicator_stress_calm
    }

    companion object {
        fun of(readings: Collection<IndicatorReading>): StressScore? {
            var total = 0.0
            var weights = 0.0
            var dangers = 0
            var watches = 0
            var counted = 0
            for (r in readings) {
                val s = r.stress ?: continue
                total += s * r.indicator.weight
                weights += r.indicator.weight
                counted++
                when (r.signal) {
                    Signal.DANGER -> dangers++
                    Signal.WATCH -> watches++
                    else -> Unit
                }
            }
            if (counted == 0) return null
            return StressScore(total / weights * 100.0, counted, dangers, watches)
        }
    }
}

/** A stretch of time to shade on a chart, e.g. a recession. [label] is data (a year, an event's name), not copy. */
@Immutable
data class Period(val startEpochSeconds: Long, val endEpochSeconds: Long, val label: String)

/** The US recessions since 1990, as the National Bureau of Economic Research dates them (peak to trough). */
object Recessions {
    private const val DAY = 86_400L

    // Epoch days of each month's first day, so no date library is needed here.
    val us: List<Period> = listOf(
        Period(7486 * DAY, 7729 * DAY, "1990–91"),
        Period(11382 * DAY, 11627 * DAY, "2001"),
        Period(13848 * DAY, 14396 * DAY, "2008"),
        Period(18293 * DAY, 18353 * DAY, "COVID"),
    )
}
