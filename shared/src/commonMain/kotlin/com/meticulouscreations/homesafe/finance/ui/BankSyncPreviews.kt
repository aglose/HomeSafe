package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.finance.BankLinking
import com.meticulouscreations.homesafe.finance.BankNotice
import com.meticulouscreations.homesafe.finance.BankSyncUiState
import com.meticulouscreations.homesafe.finance.domain.BankAccount
import com.meticulouscreations.homesafe.finance.domain.BankAccountType
import com.meticulouscreations.homesafe.finance.domain.BankFeed
import com.meticulouscreations.homesafe.finance.domain.BankInstitution
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSync
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_bank_error_relay_outdated
import homesafe.shared.generated.resources.fin_bank_error_tailscale_off
import homesafe.shared.generated.resources.fin_bank_notice_linked_named
import kotlin.time.Clock

/** Made-up institutions for the bank sync previews: a bank, a brokerage, and a lender that wants its sign-in again. */
internal object BankSyncFixtures {
    private val now = Clock.System.now().epochSeconds

    private fun account(id: String, name: String, mask: String?, type: BankAccountType, subtype: String, balance: Double, apr: Double? = null, holdings: Int = 0) =
        BankAccount(id, key = name, name = name, mask = mask, type = type, subtype = subtype, balance = balance, currency = "USD", apr = apr, holdings = holdings)

    private val feed = BankFeed(
        configured = true,
        url = "https://docs.google.com/spreadsheets/d/example/edit",
        writtenAtEpochSeconds = now - 3 * 3_600,
        error = null,
        message = null,
        serviceAccount = "relay@example.iam.gserviceaccount.com",
        activationUrl = null,
    )

    val linked = BankSync(
        configured = true,
        sandbox = false,
        institutions = listOf(
            BankInstitution(
                id = "chase",
                name = "Chase",
                syncedAtEpochSeconds = now - 3 * 3_600,
                error = null,
                errorMessage = null,
                needsRelink = false,
                accounts = listOf(
                    account("chk", "Total Checking", "0123", BankAccountType.CASH, "checking", 8_412.55),
                    account("sav", "Premier Savings", "4410", BankAccountType.CASH, "savings", 41_200.00),
                    account("card", "Sapphire Preferred", "9911", BankAccountType.CREDIT, "credit card", 1_284.17, apr = 21.49),
                ),
            ),
            BankInstitution(
                id = "fidelity",
                name = "Fidelity",
                syncedAtEpochSeconds = now - 3 * 3_600,
                error = null,
                errorMessage = null,
                needsRelink = false,
                accounts = listOf(
                    account("roth", "Roth IRA", "7788", BankAccountType.INVESTMENT, "roth", 96_340.12, holdings = 6),
                    account("k", "Acme Corp 401(k)", null, BankAccountType.INVESTMENT, "401k", 212_905.40, holdings = 4),
                    account("brk", "Individual", "2040", BankAccountType.INVESTMENT, "brokerage", 58_117.03, holdings = 12),
                ),
            ),
            BankInstitution(
                id = "nelnet",
                name = "Nelnet",
                syncedAtEpochSeconds = now - 4 * 86_400,
                error = "ITEM_LOGIN_REQUIRED",
                errorMessage = null,
                needsRelink = true,
                accounts = listOf(account("loan", "Student Loan", "5521", BankAccountType.LOAN, "student", 14_820.00, apr = 3.08)),
            ),
        ),
        syncedAtEpochSeconds = now - 3 * 3_600,
        syncing = false,
        nextSyncAtEpochSeconds = now + 21 * 3_600,
        feed = feed,
    )

    val empty = BankSync(configured = true, sandbox = true, institutions = emptyList(), syncedAtEpochSeconds = null, syncing = false, nextSyncAtEpochSeconds = null, feed = feed.copy(configured = false, url = null, writtenAtEpochSeconds = null))

    val unshared = linked.copy(institutions = linked.institutions.take(1), feed = feed.copy(writtenAtEpochSeconds = null, error = "not_shared"))
}

@Composable
private fun BankSyncPreview(state: BankSyncUiState) {
    FinanceStage { BankSyncScreen(state, previewPadding, onLink = {}, onRelink = {}, onCancelLink = {}, onSyncNow = {}, onUnlink = {}, onRetry = {}) }
}

@Preview(name = "Bank sync · linked", widthDp = 412, heightDp = 1500)
@Composable
private fun FinanceBankSyncPreview() {
    BankSyncPreview(BankSyncUiState(bank = BankSyncFixtures.linked, loading = false))
}

@Preview(name = "Bank sync · nothing linked", widthDp = 412, heightDp = 760)
@Composable
private fun FinanceBankSyncEmptyPreview() {
    BankSyncPreview(BankSyncUiState(bank = BankSyncFixtures.empty, loading = false))
}

@Preview(name = "Bank sync · linking", widthDp = 412, heightDp = 1000)
@Composable
private fun FinanceBankSyncLinkingPreview() {
    BankSyncPreview(
        BankSyncUiState(
            bank = BankSyncFixtures.unshared,
            loading = false,
            linking = BankLinking("link-1", "https://secure.plaid.com/hl/example", Clock.System.now().epochSeconds + 1_800, opened = true),
            notice = BankNotice(UiText.of(Res.string.fin_bank_notice_linked_named, "Chase".asUiText())),
        ),
    )
}

@Preview(name = "Bank sync · not set up", widthDp = 412, heightDp = 420)
@Composable
private fun FinanceBankSyncSetupPreview() {
    BankSyncPreview(BankSyncUiState(bank = BankSyncFixtures.empty.copy(configured = false), loading = false))
}

@Preview(name = "Bank sync · relay too old", widthDp = 412, heightDp = 420)
@Composable
private fun FinanceBankSyncProblemPreview() {
    BankSyncPreview(BankSyncUiState(loading = false, problem = BankProblem.RELAY_OUTDATED, problemText = UiText.of(Res.string.fin_bank_error_relay_outdated)))
}

@Preview(name = "Bank sync · Tailscale off", widthDp = 412, heightDp = 420)
@Composable
private fun FinanceBankSyncTailscaleOffPreview() {
    BankSyncPreview(BankSyncUiState(loading = false, problem = BankProblem.TAILSCALE_OFF, problemText = UiText.of(Res.string.fin_bank_error_tailscale_off)))
}
