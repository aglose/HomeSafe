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
import com.meticulouscreations.homesafe.finance.domain.SheetChange
import com.meticulouscreations.homesafe.finance.domain.SheetFill
import com.meticulouscreations.homesafe.finance.domain.SheetFillLine
import com.meticulouscreations.homesafe.finance.domain.SheetFillStatus
import com.meticulouscreations.homesafe.finance.domain.SheetWrite
import com.meticulouscreations.homesafe.finance.domain.StockPlan
import com.meticulouscreations.homesafe.finance.domain.StockVest
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_bank_error_relay_outdated
import homesafe.shared.generated.resources.fin_bank_error_tailscale_off
import homesafe.shared.generated.resources.fin_bank_linking_tailscale_off
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

    private fun line(label: String, accounts: List<String>, status: SheetFillStatus, cell: String?, value: Double?, missing: List<String> = emptyList()) =
        SheetFillLine(id = "Home|$label|2", tab = "Home", label = label, accounts = accounts, status = status, cell = cell, value = value, missing = missing)

    /** The sheet fill after a morning's write and an earlier one that was undone: a line written, one unchanged, one paused, and two a person has to look at. */
    val filled = linked.copy(
        fill = SheetFill(
            configured = true,
            checkedAtEpochSeconds = now - 3 * 3_600,
            serviceAccount = "relay@example.iam.gserviceaccount.com",
            lines = listOf(
                line("Roth IRA", listOf("Fidelity Roth IRA 7788"), SheetFillStatus.WRITTEN, "K10", 96_340.12),
                line("Checking and savings", listOf("Chase Total Checking 0123", "Chase Premier Savings 4410"), SheetFillStatus.SAME, "J20", 49_612.55),
                line("Credit cards", listOf("Chase Sapphire Preferred 9911"), SheetFillStatus.PAUSED, "F39", null),
                line("Old 401(k)", listOf("Vanguard Acme 401(k) 3301"), SheetFillStatus.NO_ACCOUNT, null, null, missing = listOf("Vanguard Acme 401(k) 3301")),
                line("Total", listOf("Fidelity Individual 2040"), SheetFillStatus.NOT_A_NUMBER, "K17", 58_117.03),
                line("Brokerage", listOf("Fidelity Individual 2040"), SheetFillStatus.SAME_CELL, "K11", 58_117.03),
            ),
            writes = listOf(
                SheetWrite(
                    id = 12,
                    atEpochSeconds = now - 3 * 3_600,
                    undoneAtEpochSeconds = null,
                    undoneBy = null,
                    changes = listOf(
                        SheetChange("Roth IRA", "Home", "K10", before = 95_102.40, after = 96_340.12, restored = null),
                        SheetChange("Brokerage", "Home", "K11", before = null, after = 58_117.03, restored = null),
                    ),
                ),
                SheetWrite(
                    id = 11,
                    atEpochSeconds = now - 27 * 3_600,
                    undoneAtEpochSeconds = now - 26 * 3_600,
                    undoneBy = "alex",
                    changes = listOf(
                        SheetChange("Credit cards", "Home", "F39", before = 0.0, after = -1_284.17, restored = true),
                        SheetChange("Checking and savings", "Home", "J20", before = 48_000.0, after = 49_612.55, restored = false),
                    ),
                ),
            ),
        ),
    )

    /** A stock plan five vests from its end, as its last four vests say. */
    val vesting = filled.copy(
        stockPlans = listOf(
            StockPlan(
                account = "Morgan Stanley Acme Corp 0042",
                name = "Acme Corp",
                institution = "Morgan Stanley",
                ticker = "ACME",
                price = 24.10,
                unvested = 4_150.0,
                everyMonths = 3,
                perVest = 820.0,
                vests = listOf(StockVest("2026-05-15", 820.0), StockVest("2026-08-15", 820.0)),
                upcoming = listOf(
                    StockVest("2026-11-15", 820.0, 19_762.0),
                    StockVest("2027-02-15", 820.0, 19_762.0),
                    StockVest("2027-05-15", 820.0, 19_762.0),
                    StockVest("2027-08-15", 820.0, 19_762.0),
                    StockVest("2027-11-15", 870.0, 20_967.0),
                ),
            ),
        ),
    )

    val unshared = linked.copy(institutions = linked.institutions.take(1), feed = feed.copy(writtenAtEpochSeconds = null, error = "not_shared"))
}

@Composable
private fun BankSyncPreview(state: BankSyncUiState) {
    FinanceStage { BankSyncScreen(state, previewPadding, onLink = {}, onRelink = {}, onCancelLink = {}, onSyncNow = {}, onUnlink = {}, onUndoWrite = {}, onPauseLine = { _, _ -> }, onRetry = {}) }
}

@Preview(name = "Bank sync · linked", widthDp = 412, heightDp = 1500)
@Composable
private fun FinanceBankSyncPreview() {
    BankSyncPreview(BankSyncUiState(bank = BankSyncFixtures.linked, loading = false))
}

@Preview(name = "Bank sync · sheet fill", widthDp = 412, heightDp = 2600)
@Composable
private fun FinanceBankSyncFillPreview() {
    BankSyncPreview(BankSyncUiState(bank = BankSyncFixtures.filled, loading = false))
}

@Preview(name = "Bank sync · stock plan", widthDp = 412, heightDp = 3200)
@Composable
private fun FinanceBankSyncStockPlanPreview() {
    BankSyncPreview(BankSyncUiState(bank = BankSyncFixtures.vesting, loading = false))
}

@Preview(name = "Bank sync · sheet fill can't write", widthDp = 412, heightDp = 1100)
@Composable
private fun FinanceBankSyncFillUnsharedPreview() {
    val bank = BankSyncFixtures.filled
    BankSyncPreview(
        BankSyncUiState(
            bank = bank.copy(institutions = bank.institutions.take(1), fill = bank.fill.copy(error = "not_shared", writes = emptyList())),
            loading = false,
        ),
    )
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

@Preview(name = "Bank sync · linking, Tailscale off", widthDp = 412, heightDp = 1000)
@Composable
private fun FinanceBankSyncLinkingTailscaleOffPreview() {
    BankSyncPreview(
        BankSyncUiState(
            bank = BankSyncFixtures.linked,
            loading = false,
            linking = BankLinking("link-1", "https://secure.plaid.com/hl/example", Clock.System.now().epochSeconds + 1_800, opened = true),
            notice = BankNotice(UiText.of(Res.string.fin_bank_linking_tailscale_off), isError = true),
        ),
    )
}
