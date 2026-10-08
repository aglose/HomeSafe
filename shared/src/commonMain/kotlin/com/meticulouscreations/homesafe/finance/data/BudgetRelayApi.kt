package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.BucketSource
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetAlertSwitches
import com.meticulouscreations.homesafe.finance.domain.BudgetBucket
import com.meticulouscreations.homesafe.finance.domain.BudgetCard
import com.meticulouscreations.homesafe.finance.domain.BudgetConfig
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetLimits
import com.meticulouscreations.homesafe.finance.domain.BudgetPastMonth
import com.meticulouscreations.homesafe.finance.domain.BudgetSheetFigures
import com.meticulouscreations.homesafe.finance.domain.BudgetSlice
import com.meticulouscreations.homesafe.finance.domain.CardRole
import com.meticulouscreations.homesafe.finance.domain.MerchantRule
import com.meticulouscreations.homesafe.finance.domain.Purchase
import com.meticulouscreations.homesafe.network.PushRelayApi
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_budget_error_relay_outdated
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The month's card spending and the budget's settings, through the relay (`/finance/budget…` in
 * `relay/relay.py`, "budget"). The relay reads the cards from Plaid and does the sums; every
 * answer is the month as it now stands, so a change shows without asking again.
 */
@Inject
class BudgetRelayApi(private val httpClient: HttpClient) {

    suspend fun budget(serverUrl: String, month: String?): Result<Budget> = suspendRunCatching {
        httpClient.get(PushRelayApi.relayUrl(serverUrl, "/finance/budget")) { month?.let { parameter("month", it) } }.answer()
    }

    suspend fun save(serverUrl: String, patch: BudgetConfigPatch): Result<Budget> = suspendRunCatching {
        httpClient.put(PushRelayApi.relayUrl(serverUrl, "/finance/budget/config")) {
            contentType(ContentType.Application.Json)
            setBody(patch.toJson())
        }.answer()
    }

    suspend fun tag(serverUrl: String, purchaseId: String, bucket: BucketId, remember: Boolean): Result<Budget> = suspendRunCatching {
        httpClient.put(PushRelayApi.relayUrl(serverUrl, "/finance/budget/transactions/$purchaseId")) {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("bucket", bucket.wire)
                    put("remember", remember)
                },
            )
        }.answer()
    }

    suspend fun forgetRule(serverUrl: String, merchantKey: String): Result<Budget> = suspendRunCatching {
        httpClient.delete(PushRelayApi.relayUrl(serverUrl, "/finance/budget/rules/$merchantKey")).answer()
    }

    suspend fun syncNow(serverUrl: String): Result<Budget> = suspendRunCatching {
        httpClient.post(PushRelayApi.relayUrl(serverUrl, "/finance/budget/sync")) {
            timeout { requestTimeoutMillis = 40_000 }
        }.answer()
    }

    private suspend fun HttpResponse.answer(): Budget {
        if (status.isSuccess()) return body<RelayBudget>().toDomain()
        val problem = BankSyncRelayApi.problemFrom(status, bodyAsText())
        // A relay from before the budget answers its own 404, which bank sync's words would misname.
        throw if (problem.problem == BankProblem.RELAY_OUTDATED) BankSyncException(BankProblem.RELAY_OUTDATED, UiText.of(Res.string.fin_budget_error_relay_outdated), "HTTP 404") else problem
    }
}

/** Only what is being changed is sent; inside the limits a null is sent as one, which takes that limit away. */
internal fun BudgetConfigPatch.toJson(): JsonObject = buildJsonObject {
    people?.let { names -> put("people", JsonArray(names.map(::JsonPrimitive))) }
    limits?.let { limits ->
        put(
            "limits",
            buildJsonObject {
                put("total", limits.total)
                put("family", limits.family)
                put("people", buildJsonObject { limits.people.forEach { (person, limit) -> put(person, limit) } })
            },
        )
    }
    roles?.let { roles -> put("roles", buildJsonObject { roles.forEach { (card, role) -> put(card, role.wire) } }) }
    cardPaidLines?.let { lines -> put("card_paid_lines", JsonArray(lines.map(::JsonPrimitive))) }
    alerts?.let { alerts ->
        put(
            "alerts",
            buildJsonObject {
                put("total", alerts.total)
                put("savings", alerts.savings)
                put("buckets", alerts.buckets)
            },
        )
    }
    sheet?.let { sheet ->
        put(
            "sheet",
            buildJsonObject {
                put("take_home", sheet.takeHome)
                put("bills_off_card", sheet.billsOffCard)
            },
        )
    }
}

@Serializable
internal data class RelayBudget(
    val configured: Boolean = false,
    val environment: String = "production",
    val month: String = "",
    val today: String = "",
    val day: Int = 0,
    @SerialName("days_in_month") val daysInMonth: Int = 30,
    @SerialName("synced_at") val syncedAt: Double? = null,
    @SerialName("changed_at") val changedAt: Double? = null,
    val syncing: Boolean = false,
    @SerialName("next_sync_at") val nextSyncAt: Double? = null,
    val ready: Boolean = true,
    val cards: List<RelayBudgetCard> = emptyList(),
    val config: RelayBudgetConfig = RelayBudgetConfig(),
    @SerialName("savings_line") val savingsLine: Double? = null,
    val spent: Double = 0.0,
    val pending: Double = 0.0,
    val buckets: List<RelayBudgetBucket> = emptyList(),
    val daily: List<RelayBudgetDay> = emptyList(),
    val categories: List<RelayBudgetCategory> = emptyList(),
    val merchants: List<RelayBudgetMerchant> = emptyList(),
    val transactions: List<RelayPurchase> = emptyList(),
    val history: List<RelayBudgetPastMonth> = emptyList(),
) {
    fun toDomain() = Budget(
        configured = configured,
        sandbox = environment == "sandbox",
        month = month,
        isCurrentMonth = month.isNotEmpty() && today.startsWith(month),
        day = day,
        daysInMonth = daysInMonth,
        syncedAtEpochSeconds = syncedAt?.toLong(),
        changedAtEpochSeconds = changedAt?.toLong(),
        syncing = syncing,
        nextSyncAtEpochSeconds = nextSyncAt?.toLong(),
        ready = ready,
        cards = cards.map { card ->
            BudgetCard(
                key = card.key,
                institutionId = card.institutionId,
                institution = card.institution,
                name = card.name,
                mask = card.mask?.takeIf { it.isNotBlank() },
                role = CardRole.ofWire(card.role),
                balance = card.balance,
                needsRelink = card.needsRelink,
                readsPurchases = card.transactions,
            )
        },
        config = BudgetConfig(
            people = config.people,
            limits = BudgetLimits(total = config.limits.total, people = config.limits.people, family = config.limits.family),
            cardPaidLines = config.cardPaidLines,
            alerts = BudgetAlertSwitches(total = config.alerts.total, savings = config.alerts.savings, buckets = config.alerts.buckets),
            rules = config.rules.map { MerchantRule(it.merchant, BucketId.ofWire(it.bucket)) },
            sheet = BudgetSheetFigures(takeHome = config.sheet.takeHome, billsOffCard = config.sheet.billsOffCard),
        ),
        savingsLine = savingsLine,
        spent = spent,
        pending = pending,
        // The relay's "unassigned" is no bucket's wire name, so it reads as what it is.
        buckets = buckets.map { BudgetBucket(BucketId.ofWire(it.id), it.spent, it.limit, it.count) },
        daily = daily.map { it.spent },
        categories = categories.map { BudgetSlice(it.id, it.spent, it.count) },
        merchants = merchants.map { BudgetSlice(it.name, it.spent, it.count) },
        purchases = transactions.map { txn ->
            Purchase(
                id = txn.id,
                date = txn.date,
                amount = txn.amount,
                name = txn.name,
                category = txn.category?.takeIf { it.isNotBlank() },
                pending = txn.pending,
                cardKey = txn.card,
                bucket = BucketId.ofWire(txn.bucket),
                source = when (txn.source) {
                    "manual" -> BucketSource.MANUAL
                    "account" -> BucketSource.ACCOUNT
                    "bank" -> BucketSource.BANK
                    "rule" -> BucketSource.RULE
                    else -> BucketSource.NONE
                },
                remembered = txn.remembered,
            )
        },
        history = history.map { BudgetPastMonth(it.month, it.spent, it.days) },
    )
}

@Serializable
internal data class RelayBudgetCard(
    val key: String = "",
    @SerialName("institution_id") val institutionId: String = "",
    val institution: String = "",
    val name: String = "",
    val mask: String? = null,
    val role: String? = null,
    val balance: Double? = null,
    @SerialName("needs_relink") val needsRelink: Boolean = false,
    val transactions: Boolean = true,
)

@Serializable
internal data class RelayBudgetConfig(
    val people: List<String> = emptyList(),
    val limits: RelayBudgetLimits = RelayBudgetLimits(),
    @SerialName("card_paid_lines") val cardPaidLines: List<String> = emptyList(),
    val alerts: RelayBudgetAlerts = RelayBudgetAlerts(),
    val rules: List<RelayBudgetRule> = emptyList(),
    val sheet: RelayBudgetSheet = RelayBudgetSheet(),
)

@Serializable
internal data class RelayBudgetLimits(val total: Double? = null, val people: Map<String, Double?> = emptyMap(), val family: Double? = null)

@Serializable
internal data class RelayBudgetAlerts(val total: Boolean = false, val savings: Boolean = false, val buckets: Boolean = false)

@Serializable
internal data class RelayBudgetRule(val merchant: String = "", val bucket: String? = null)

@Serializable
internal data class RelayBudgetSheet(@SerialName("take_home") val takeHome: Double? = null, @SerialName("bills_off_card") val billsOffCard: Double? = null)

@Serializable
internal data class RelayBudgetBucket(val id: String = "", val spent: Double = 0.0, val limit: Double? = null, val count: Int = 0)

@Serializable
internal data class RelayBudgetDay(val date: String = "", val spent: Double = 0.0)

@Serializable
internal data class RelayBudgetCategory(val id: String = "", val spent: Double = 0.0, val count: Int = 0)

@Serializable
internal data class RelayBudgetMerchant(val name: String = "", val spent: Double = 0.0, val count: Int = 0)

@Serializable
internal data class RelayPurchase(
    val id: String,
    val date: String = "",
    val amount: Double = 0.0,
    val name: String = "",
    val category: String? = null,
    val pending: Boolean = false,
    val card: String = "",
    val bucket: String? = null,
    val source: String = "none",
    val remembered: Boolean = false,
)

@Serializable
internal data class RelayBudgetPastMonth(val month: String = "", val spent: Double = 0.0, val days: Int = 30)
