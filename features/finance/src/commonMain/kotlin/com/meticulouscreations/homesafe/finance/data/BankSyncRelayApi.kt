package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.BankAccount
import com.meticulouscreations.homesafe.finance.domain.BankAccountType
import com.meticulouscreations.homesafe.finance.domain.BankFeed
import com.meticulouscreations.homesafe.finance.domain.BankInstitution
import com.meticulouscreations.homesafe.finance.domain.BankLinkProgress
import com.meticulouscreations.homesafe.finance.domain.BankLinkStart
import com.meticulouscreations.homesafe.finance.domain.BankLinkStatus
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSync
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.SheetChange
import com.meticulouscreations.homesafe.finance.domain.SheetFill
import com.meticulouscreations.homesafe.finance.domain.SheetFillLine
import com.meticulouscreations.homesafe.finance.domain.SheetFillStatus
import com.meticulouscreations.homesafe.finance.domain.SheetWrite
import com.meticulouscreations.homesafe.finance.domain.StockPlan
import com.meticulouscreations.homesafe.finance.domain.StockVest
import com.meticulouscreations.homesafe.network.PushRelayApi
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_bank_error_plaid
import homesafe.shared.generated.resources.fin_bank_error_relay_outdated
import homesafe.shared.generated.resources.fin_data_error_relay_answered
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The bank sync, through the relay (`/finance/bank…` in `relay/relay.py`, "bank sync"). The relay
 * talks to Plaid and keeps each institution's access token; these calls carry the Frigate session
 * cookie like every relay call, and never a bank credential or a token.
 */
@Inject
class BankSyncRelayApi(private val httpClient: HttpClient) {

    suspend fun status(serverUrl: String): Result<BankSync> = suspendRunCatching {
        httpClient.get(PushRelayApi.relayUrl(serverUrl, "/finance/bank")).answer<RelayBank>().toDomain()
    }

    /** A new institution of [kind], or a fresh sign-in to [institutionId]. */
    suspend fun startLink(serverUrl: String, kind: String, institutionId: String? = null): Result<BankLinkStart> = suspendRunCatching {
        val response = httpClient.post(PushRelayApi.relayUrl(serverUrl, "/finance/bank/link")) {
            contentType(ContentType.Application.Json)
            setBody(RelayBankLinkRequest(kind = kind, institution = institutionId))
            // The relay asks Plaid before it answers.
            timeout { requestTimeoutMillis = 40_000 }
        }
        val link = response.answer<RelayBankLink>()
        BankLinkStart(link.token, link.url, link.expiresAt.toLong())
    }

    suspend fun linkProgress(serverUrl: String, token: String): Result<BankLinkProgress> = suspendRunCatching {
        val response = httpClient.get(PushRelayApi.relayUrl(serverUrl, "/finance/bank/link/$token")) {
            // When the link has just been made the relay swaps Plaid's token for its own before answering.
            timeout { requestTimeoutMillis = 60_000 }
        }
        val progress = response.answer<RelayBankLinkProgress>()
        BankLinkProgress(
            status = when (progress.status) {
                "linked" -> BankLinkStatus.LINKED
                "exited" -> BankLinkStatus.EXITED
                "expired" -> BankLinkStatus.EXPIRED
                else -> BankLinkStatus.PENDING
            },
            institutions = progress.institutions.filter { it.isNotBlank() },
            bank = progress.bank?.toDomain(),
        )
    }

    suspend fun syncNow(serverUrl: String): Result<BankSync> = suspendRunCatching {
        httpClient.post(PushRelayApi.relayUrl(serverUrl, "/finance/bank/sync")).answer<RelayBank>().toDomain()
    }

    suspend fun unlink(serverUrl: String, institutionId: String): Result<BankSync> = suspendRunCatching {
        val response = httpClient.delete(PushRelayApi.relayUrl(serverUrl, "/finance/bank/institutions/$institutionId")) {
            timeout { requestTimeoutMillis = 40_000 }
        }
        response.answer<RelayBank>().toDomain()
    }

    suspend fun undoWrite(serverUrl: String, writeId: Long): Result<BankSync> = suspendRunCatching {
        val response = httpClient.post(PushRelayApi.relayUrl(serverUrl, "/finance/bank/fill/writes/$writeId/undo")) {
            // The relay waits for a sync under way, then reads the sheet and writes to it before answering.
            timeout { requestTimeoutMillis = 60_000 }
        }
        response.answer<RelayBank>().toDomain()
    }

    suspend fun setLinePaused(serverUrl: String, lineId: String, paused: Boolean): Result<BankSync> = suspendRunCatching {
        val response = httpClient.post(PushRelayApi.relayUrl(serverUrl, "/finance/bank/fill/lines")) {
            contentType(ContentType.Application.Json)
            setBody(RelayFillLineRequest(line = lineId, paused = paused))
            timeout { requestTimeoutMillis = 60_000 }
        }
        response.answer<RelayBank>().toDomain()
    }

    private suspend inline fun <reified T> HttpResponse.answer(): T = if (status.isSuccess()) body<T>() else throw problemFrom(status, bodyAsText())

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The relay's `{"detail": {"error": …, "message": …}}` as a typed problem. A 404 with no
         * such detail is FastAPI's own, for a route it hasn't got: a relay from before bank sync.
         * The relay's and Plaid's messages are their own words (English), shown as written.
         */
        fun problemFrom(status: HttpStatusCode, body: String): BankSyncException {
            val detail = runCatching { json.parseToJsonElement(body).jsonObject["detail"] as? JsonObject }.getOrNull()
            fun field(name: String) = (detail?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val message = field("message")
            val technical = message ?: "The relay answered $status"
            val answered = message?.asUiText() ?: UiText.of(Res.string.fin_data_error_relay_answered, status.toString())
            return when (field("error")) {
                "not_configured" -> BankSyncException(BankProblem.NOT_CONFIGURED, answered, technical)

                "not_allowed" -> BankSyncException(BankProblem.NOT_ALLOWED, answered, technical)

                "plaid_error" -> BankSyncException(
                    BankProblem.PLAID,
                    UiText.of(Res.string.fin_bank_error_plaid, message ?: field("code").orEmpty()),
                    technical = field("code") ?: technical,
                )

                null -> when (status) {
                    HttpStatusCode.NotFound -> BankSyncException(BankProblem.RELAY_OUTDATED, UiText.of(Res.string.fin_bank_error_relay_outdated), "HTTP 404")
                    HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> BankSyncException(BankProblem.SIGNED_OUT, answered, technical)
                    else -> BankSyncException(BankProblem.OTHER, answered, technical)
                }

                else -> BankSyncException(BankProblem.OTHER, answered, technical)
            }
        }
    }
}

@Serializable
internal data class RelayBankLinkRequest(val kind: String, val institution: String? = null)

@Serializable
internal data class RelayFillLineRequest(val line: String, val paused: Boolean)

@Serializable
internal data class RelayBankLink(val token: String, val url: String, @SerialName("expires_at") val expiresAt: Double = 0.0)

@Serializable
internal data class RelayBankLinkProgress(val status: String = "pending", val institutions: List<String> = emptyList(), val bank: RelayBank? = null)

@Serializable
internal data class RelayBank(
    val configured: Boolean = false,
    val environment: String = "production",
    val institutions: List<RelayBankInstitution> = emptyList(),
    @SerialName("synced_at") val syncedAt: Double? = null,
    val syncing: Boolean = false,
    @SerialName("next_sync_at") val nextSyncAt: Double? = null,
    val feed: RelayBankFeed = RelayBankFeed(),
    // Absent from a relay older than the sheet fill, which reads as a fill that isn't set up.
    val fill: RelayFill = RelayFill(),
    @SerialName("stock_plans") val stockPlans: List<RelayStockPlan> = emptyList(),
) {
    fun toDomain() = BankSync(
        configured = configured,
        sandbox = environment == "sandbox",
        institutions = institutions.map { it.toDomain() },
        syncedAtEpochSeconds = syncedAt?.toLong(),
        syncing = syncing,
        nextSyncAtEpochSeconds = nextSyncAt?.toLong(),
        feed = BankFeed(
            configured = feed.configured,
            url = feed.url,
            writtenAtEpochSeconds = feed.writtenAt?.toLong(),
            error = feed.error,
            message = feed.message,
            serviceAccount = feed.serviceAccount,
            activationUrl = feed.activationUrl,
        ),
        fill = fill.toDomain(),
        stockPlans = stockPlans.map { it.toDomain() },
    )
}

@Serializable
internal data class RelayBankInstitution(
    val id: String,
    val name: String = "",
    @SerialName("synced_at") val syncedAt: Double? = null,
    val error: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("needs_relink") val needsRelink: Boolean = false,
    val accounts: List<RelayBankAccount> = emptyList(),
) {
    fun toDomain() = BankInstitution(
        id = id,
        name = name,
        syncedAtEpochSeconds = syncedAt?.toLong(),
        error = error,
        errorMessage = errorMessage?.takeIf { it.isNotBlank() },
        needsRelink = needsRelink,
        accounts = accounts.map { it.toDomain() },
    )
}

@Serializable
internal data class RelayBankAccount(
    val id: String,
    val key: String = "",
    val name: String = "",
    val mask: String? = null,
    val type: String? = null,
    val subtype: String? = null,
    val balance: Double? = null,
    val currency: String? = null,
    val apr: Double? = null,
    val holdings: Int = 0,
) {
    fun toDomain() = BankAccount(
        id = id,
        key = key,
        name = name,
        mask = mask?.takeIf { it.isNotBlank() },
        type = BankAccountType.ofPlaid(type),
        subtype = subtype?.takeIf { it.isNotBlank() },
        balance = balance,
        currency = currency,
        apr = apr,
        holdings = holdings,
    )
}

@Serializable
internal data class RelayBankFeed(
    val configured: Boolean = false,
    val url: String? = null,
    @SerialName("written_at") val writtenAt: Double? = null,
    val error: String? = null,
    val message: String? = null,
    @SerialName("service_account") val serviceAccount: String? = null,
    @SerialName("activation_url") val activationUrl: String? = null,
)

@Serializable
internal data class RelayFill(
    val configured: Boolean = false,
    @SerialName("checked_at") val checkedAt: Double? = null,
    val error: String? = null,
    val reason: String? = null,
    val line: Int? = null,
    val message: String? = null,
    @SerialName("service_account") val serviceAccount: String? = null,
    val lines: List<RelayFillLine> = emptyList(),
    val writes: List<RelayFillWrite> = emptyList(),
) {
    fun toDomain() = SheetFill(
        configured = configured,
        checkedAtEpochSeconds = checkedAt?.toLong(),
        error = error,
        errorReason = reason,
        errorLine = line,
        message = message?.takeIf { it.isNotBlank() },
        serviceAccount = serviceAccount,
        lines = lines.map { it.toDomain() },
        writes = writes.map { it.toDomain() },
    )
}

@Serializable
internal data class RelayFillLine(
    val id: String,
    val tab: String = "",
    val label: String = "",
    val accounts: List<String> = emptyList(),
    val status: String? = null,
    val cell: String? = null,
    val value: Double? = null,
    val missing: List<String> = emptyList(),
) {
    fun toDomain() = SheetFillLine(
        id = id,
        tab = tab,
        label = label,
        accounts = accounts,
        status = SheetFillStatus.ofWire(status),
        cell = cell,
        value = value,
        missing = missing,
    )
}

@Serializable
internal data class RelayFillWrite(
    val id: Long,
    val at: Double = 0.0,
    @SerialName("undone_at") val undoneAt: Double? = null,
    @SerialName("undone_by") val undoneBy: String? = null,
    val changes: List<RelayFillChange> = emptyList(),
) {
    fun toDomain() = SheetWrite(
        id = id,
        atEpochSeconds = at.toLong(),
        undoneAtEpochSeconds = undoneAt?.toLong(),
        undoneBy = undoneBy?.takeIf { it.isNotBlank() },
        changes = changes.map { SheetChange(label = it.label, tab = it.tab, cell = it.cell, before = it.before, after = it.after, restored = it.restored, typedBy = it.typedBy) },
    )
}

@Serializable
internal data class RelayFillChange(
    val label: String = "",
    val tab: String = "",
    val cell: String? = null,
    val before: Double? = null,
    val after: Double? = null,
    val restored: Boolean? = null,
    @SerialName("typed_by") val typedBy: String? = null,
)

@Serializable
internal data class RelayStockPlan(
    val account: String = "",
    val name: String = "",
    val institution: String = "",
    val ticker: String = "",
    val price: Double? = null,
    val unvested: Double = 0.0,
    @SerialName("every_months") val everyMonths: Int? = null,
    @SerialName("per_vest") val perVest: Double = 0.0,
    val vests: List<RelayStockVest> = emptyList(),
    val upcoming: List<RelayStockVest> = emptyList(),
) {
    fun toDomain() = StockPlan(
        account = account,
        name = name,
        institution = institution,
        ticker = ticker,
        price = price,
        unvested = unvested,
        everyMonths = everyMonths,
        perVest = perVest,
        vests = vests.map { StockVest(it.date, it.shares, it.value) },
        upcoming = upcoming.map { StockVest(it.date, it.shares, it.value) },
    )
}

@Serializable
internal data class RelayStockVest(val date: String = "", val shares: Double = 0.0, val value: Double? = null)
