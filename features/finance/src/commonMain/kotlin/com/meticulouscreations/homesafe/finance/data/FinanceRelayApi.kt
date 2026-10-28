package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.BalanceAge
import com.meticulouscreations.homesafe.finance.domain.BalanceProblem
import com.meticulouscreations.homesafe.finance.domain.BalanceUpdateException
import com.meticulouscreations.homesafe.finance.domain.SheetCell
import com.meticulouscreations.homesafe.finance.domain.SheetProblem
import com.meticulouscreations.homesafe.finance.domain.SheetUnavailableException
import com.meticulouscreations.homesafe.network.PushRelayApi
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_balance_error_bank_filled
import homesafe.shared.generated.resources.fin_balance_error_busy
import homesafe.shared.generated.resources.fin_balance_error_calculated
import homesafe.shared.generated.resources.fin_balance_error_changed
import homesafe.shared.generated.resources.fin_balance_error_not_found
import homesafe.shared.generated.resources.fin_balance_error_not_shared
import homesafe.shared.generated.resources.fin_balance_error_relay_outdated
import homesafe.shared.generated.resources.fin_data_error_relay_answered
import homesafe.shared.generated.resources.fin_data_error_relay_outdated
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The budget workbook, through the relay (`/finance/sheet` in `relay/relay.py`). The sheet is
 * private to the household's Google accounts; the relay reads it with its own Google service
 * account, which the sheet is shared with read-only, so the phone never holds a Google
 * credential and the sheet never has to be made public. Like every relay call it rides on the
 * Frigate session cookie, so only a signed-in member of the household gets it.
 */
@Inject
class FinanceRelayApi(private val httpClient: HttpClient) {

    /** [refresh] skips the relay's minute-long cache, for a pull to refresh. */
    suspend fun workbook(serverUrl: String, refresh: Boolean): Result<RelayWorkbook> = suspendRunCatching {
        val response = httpClient.get(PushRelayApi.relayUrl(serverUrl, "/finance/sheet")) {
            if (refresh) parameter("refresh", "true")
            timeout { requestTimeoutMillis = 30_000 }
        }
        when {
            response.status.isSuccess() -> response.body<RelayWorkbook>()

            // FastAPI's own 404 for a route it doesn't have: a relay from before this feature.
            response.status == HttpStatusCode.NotFound ->
                throw SheetUnavailableException(SheetProblem.RELAY_OUTDATED, UiText.of(Res.string.fin_data_error_relay_outdated), technical = "HTTP 404")

            else -> throw problemFrom(response.status, response.bodyAsText())
        }
    }

    /**
     * Types [value] into the budget sheet's [cell] (`PUT /finance/sheet/value`), signed the way the
     * sheet keeps it; the cell must still hold [SheetCell.value]. The same number as it holds
     * writes nothing and marks the balance as still right. Answers when that was, and by whom.
     */
    suspend fun setValue(serverUrl: String, cell: SheetCell, value: Double): Result<BalanceAge> = suspendRunCatching {
        val response = httpClient.put(PushRelayApi.relayUrl(serverUrl, "/finance/sheet/value")) {
            contentType(ContentType.Application.Json)
            setBody(RelaySheetValue(cell.tab, cell.label, cell.right, cell.value, cell.toSheet(value)))
            // The relay waits for a sync under way, then reads the tab and writes to it before answering.
            timeout { requestTimeoutMillis = 60_000 }
        }
        if (!response.status.isSuccess()) throw balanceProblemFrom(response.status, response.bodyAsText())
        val written = response.body<RelaySheetValueWritten>()
        BalanceAge(written.at.toLong(), written.by, fromBank = false)
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The relay's `{"detail": {"error": …, "message": …, "service_account": …}}` as a typed
         * problem. The relay's message is its own words (English, from the server), shown as written.
         */
        fun problemFrom(status: HttpStatusCode, body: String): SheetUnavailableException {
            val detail = runCatching { json.parseToJsonElement(body).jsonObject["detail"] as? JsonObject }.getOrNull()
            fun field(name: String) = (detail?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val kind = when (field("error")) {
                "not_configured" -> SheetProblem.NOT_CONFIGURED
                "not_shared" -> SheetProblem.NOT_SHARED
                "api_disabled" -> SheetProblem.API_DISABLED
                "no_key" -> SheetProblem.NO_KEY
                "not_found" -> SheetProblem.NOT_FOUND
                "not_allowed" -> SheetProblem.NOT_ALLOWED
                else -> if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) SheetProblem.SIGNED_OUT else SheetProblem.OTHER
            }
            val message = field("message")
            return SheetUnavailableException(
                problem = kind,
                text = message?.asUiText() ?: UiText.of(Res.string.fin_data_error_relay_answered, status.toString()),
                technical = message ?: "The relay answered $status",
                serviceAccount = field("service_account"),
                activationUrl = field("activation_url"),
            )
        }

        /**
         * Why the relay wouldn't type a balance in, in the app's own words for the reasons it
         * knows. FastAPI's own 404 (no `detail` object) is a relay from before typed balances.
         */
        fun balanceProblemFrom(status: HttpStatusCode, body: String): BalanceUpdateException {
            val detail = runCatching { json.parseToJsonElement(body).jsonObject["detail"] as? JsonObject }.getOrNull()
            val error = (detail?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val message = (detail?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val technical = listOfNotNull("HTTP ${status.value}", error, message).joinToString(": ")
            val (problem, text) = when {
                detail == null && status == HttpStatusCode.NotFound -> BalanceProblem.RELAY_OUTDATED to UiText.of(Res.string.fin_balance_error_relay_outdated)

                error == "changed" -> BalanceProblem.CHANGED to UiText.of(Res.string.fin_balance_error_changed)

                error == "not_a_number" -> BalanceProblem.CALCULATED to UiText.of(Res.string.fin_balance_error_calculated)

                error == "bank_filled" -> BalanceProblem.BANK_FILLED to UiText.of(Res.string.fin_balance_error_bank_filled)

                error == "no_label" || error == "ambiguous" || error == "no_tab" -> BalanceProblem.NOT_FOUND to UiText.of(Res.string.fin_balance_error_not_found)

                error == "not_shared" -> BalanceProblem.NOT_SHARED to UiText.of(Res.string.fin_balance_error_not_shared)

                error == "busy" -> BalanceProblem.BUSY to UiText.of(Res.string.fin_balance_error_busy)

                // Signed out, or anything else: the relay's own explanation, as written.
                else -> BalanceProblem.OTHER to (message?.asUiText() ?: UiText.of(Res.string.fin_data_error_relay_answered, status.toString()))
            }
            return BalanceUpdateException(problem, text, technical)
        }
    }
}

@Serializable
data class RelayWorkbook(
    val title: String = "",
    @SerialName("fetched_at") val fetchedAt: Double = 0.0,
    val url: String? = null,
    val sheets: List<RelaySheet> = emptyList(),
    /** The workbook's embedded charts; absent from a relay older than them. */
    val charts: List<RelayChart> = emptyList(),
    /** When each balance was last known to be right; absent from a relay older than typed balances. */
    val updates: List<RelayBalanceAge> = emptyList(),
    /** The balances the bank sync writes into the sheet every morning. */
    val filled: List<RelayFilledBalance> = emptyList(),
) {
    fun grids(): List<SheetGrid> = sheets.map { it.toGrid() }

    /** [updates] by each balance's [SheetCell.id]. */
    fun balanceAges(): Map<String, BalanceAge> = updates.associate { u ->
        SheetCell.idOf(u.tab, u.label, u.right) to BalanceAge(u.at.toLong(), u.by, fromBank = u.source == "bank")
    }

    /** [filled] by each balance's [SheetCell.id]. */
    fun bankFilled(): Set<String> = filled.mapTo(HashSet()) { SheetCell.idOf(it.tab, it.label, it.right) }
}

@Serializable
data class RelaySheet(
    val title: String,
    val values: List<List<JsonElement>> = emptyList(),
    val merges: List<RelayMerge> = emptyList(),
    /** The cells holding a formula, as [row, column]; absent when the relay couldn't read them, or is older than them. */
    val formulas: List<List<Int>>? = null,
) {
    fun toGrid(): SheetGrid = SheetGrid(
        title = title,
        rows = values.map { row -> row.map(::cellOf) },
        merges = merges.map { MergedRange(it.startRow, it.endRow, it.startColumn, it.endColumn) },
        formulas = formulas?.mapNotNull { cell -> if (cell.size == 2) cell[0] to cell[1] else null },
    )

    private fun cellOf(element: JsonElement): CellValue {
        val p = element as? JsonPrimitive ?: return CellValue.Empty
        return when {
            p.isString -> if (p.content.isEmpty()) CellValue.Empty else CellValue.Text(p.content)
            p.booleanOrNull != null -> CellValue.Bool(p.booleanOrNull!!)
            p.doubleOrNull != null -> CellValue.Number(p.jsonPrimitive.doubleOrNull!!)
            else -> CellValue.Empty
        }
    }
}

/** When a balance was last known to be right: typed in the app (`source` "app") or by the bank sync ("bank"). */
@Serializable
data class RelayBalanceAge(
    val tab: String,
    val label: String,
    val right: Int = 1,
    val at: Double,
    val by: String? = null,
    val source: String = "app",
)

/** A balance the bank sync writes into the sheet: a line of its sheet fill that isn't paused. */
@Serializable
data class RelayFilledBalance(val tab: String, val label: String, val right: Int = 1)

@Serializable
internal data class RelaySheetValue(val tab: String, val label: String, val right: Int, val expected: Double?, val value: Double)

@Serializable
internal data class RelaySheetValueWritten(val at: Double, val by: String? = null)

/** A merge, as the Sheets API's GridRange: 0-based, end-exclusive. */
@Serializable
data class RelayMerge(
    @SerialName("start_row") val startRow: Int,
    @SerialName("end_row") val endRow: Int,
    @SerialName("start_column") val startColumn: Int,
    @SerialName("end_column") val endColumn: Int,
)

/**
 * A chart as the relay describes it from Google's chart spec: its kind (Google's `chartType`, or
 * `PIE`, `SCORECARD`, or the name of a kind the app doesn't draw), and the ranges it plots. The
 * values themselves are in [RelayWorkbook.sheets]; `SheetChartReader` reads them from there.
 */
@Serializable
data class RelayChart(
    val id: Long? = null,
    val sheet: String = "",
    /** The tab's id, for a link straight to it (`#gid=`). */
    val gid: Long? = null,
    val title: String = "",
    val subtitle: String = "",
    val kind: String = "OTHER",
    val stacked: String = "NOT_STACKED",
    /** How many leading cells of each range are headers; null when the sheet leaves Google to guess. */
    @SerialName("header_count") val headerCount: Int? = null,
    val reversed: Boolean = false,
    val domain: List<RelayRange> = emptyList(),
    @SerialName("domain_format") val domainFormat: RelayNumberFormat? = null,
    val series: List<RelayChartSeries> = emptyList(),
)

@Serializable
data class RelayChartSeries(
    val ranges: List<RelayRange> = emptyList(),
    /** The series' own kind on a combo chart (`LINE`, `COLUMN`, `AREA`…). */
    val type: String? = null,
    /** `LEFT_AXIS` or `RIGHT_AXIS`: which vertical scale the series is drawn against. */
    val axis: String = "LEFT_AXIS",
    val format: RelayNumberFormat? = null,
)

/** A range of one tab, 0-based and end-exclusive; a null end runs to the edge of the tab's data. */
@Serializable
data class RelayRange(
    val sheet: String,
    @SerialName("start_row") val startRow: Int = 0,
    @SerialName("end_row") val endRow: Int? = null,
    @SerialName("start_column") val startColumn: Int = 0,
    @SerialName("end_column") val endColumn: Int? = null,
)

/** A cell's number format as Google reports it: `type` is DATE, CURRENCY, PERCENT, NUMBER… */
@Serializable
data class RelayNumberFormat(val type: String = "", val pattern: String = "")
