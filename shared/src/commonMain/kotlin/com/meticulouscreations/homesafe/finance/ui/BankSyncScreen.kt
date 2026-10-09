package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.finance.BankSyncUiState
import com.meticulouscreations.homesafe.finance.BankSyncViewModel
import com.meticulouscreations.homesafe.finance.domain.BankAccount
import com.meticulouscreations.homesafe.finance.domain.BankFeed
import com.meticulouscreations.homesafe.finance.domain.BankInstitution
import com.meticulouscreations.homesafe.finance.domain.BankLinkKind
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSync
import com.meticulouscreations.homesafe.finance.ui.components.Shimmer
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.resolve
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_cancel
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_try_again
import homesafe.shared.generated.resources.fin_bank_account_apr
import homesafe.shared.generated.resources.fin_bank_account_holdings
import homesafe.shared.generated.resources.fin_bank_account_masked
import homesafe.shared.generated.resources.fin_bank_action_link
import homesafe.shared.generated.resources.fin_bank_action_link_starting
import homesafe.shared.generated.resources.fin_bank_action_sync
import homesafe.shared.generated.resources.fin_bank_action_syncing
import homesafe.shared.generated.resources.fin_bank_entry_body
import homesafe.shared.generated.resources.fin_bank_entry_title
import homesafe.shared.generated.resources.fin_bank_feed_failed
import homesafe.shared.generated.resources.fin_bank_feed_failed_plain
import homesafe.shared.generated.resources.fin_bank_feed_how
import homesafe.shared.generated.resources.fin_bank_feed_not_configured
import homesafe.shared.generated.resources.fin_bank_feed_not_found
import homesafe.shared.generated.resources.fin_bank_feed_not_shared
import homesafe.shared.generated.resources.fin_bank_feed_not_written
import homesafe.shared.generated.resources.fin_bank_feed_open
import homesafe.shared.generated.resources.fin_bank_feed_subtitle
import homesafe.shared.generated.resources.fin_bank_feed_title
import homesafe.shared.generated.resources.fin_bank_feed_written
import homesafe.shared.generated.resources.fin_bank_how
import homesafe.shared.generated.resources.fin_bank_institution_failed
import homesafe.shared.generated.resources.fin_bank_institution_no_accounts
import homesafe.shared.generated.resources.fin_bank_institution_read
import homesafe.shared.generated.resources.fin_bank_institution_relink
import homesafe.shared.generated.resources.fin_bank_institution_relink_action
import homesafe.shared.generated.resources.fin_bank_institution_unlink
import homesafe.shared.generated.resources.fin_bank_institution_unlinking
import homesafe.shared.generated.resources.fin_bank_kind_title
import homesafe.shared.generated.resources.fin_bank_linking_body
import homesafe.shared.generated.resources.fin_bank_linking_reopen
import homesafe.shared.generated.resources.fin_bank_linking_title
import homesafe.shared.generated.resources.fin_bank_problem_other_title
import homesafe.shared.generated.resources.fin_bank_problem_outdated_title
import homesafe.shared.generated.resources.fin_bank_problem_signed_out_body
import homesafe.shared.generated.resources.fin_bank_problem_signed_out_title
import homesafe.shared.generated.resources.fin_bank_problem_tailscale_off_title
import homesafe.shared.generated.resources.fin_bank_setup_body
import homesafe.shared.generated.resources.fin_bank_setup_title
import homesafe.shared.generated.resources.fin_bank_status_intro
import homesafe.shared.generated.resources.fin_bank_status_last_read
import homesafe.shared.generated.resources.fin_bank_status_last_read_next
import homesafe.shared.generated.resources.fin_bank_status_linked
import homesafe.shared.generated.resources.fin_bank_status_never_read
import homesafe.shared.generated.resources.fin_bank_status_none
import homesafe.shared.generated.resources.fin_bank_status_reading
import homesafe.shared.generated.resources.fin_bank_status_sandbox
import homesafe.shared.generated.resources.fin_bank_unlink_body
import homesafe.shared.generated.resources.fin_bank_unlink_confirm
import homesafe.shared.generated.resources.fin_bank_unlink_title
import homesafe.shared.generated.resources.fin_sheet_setup_api_disabled_body
import homesafe.shared.generated.resources.fin_sheet_setup_not_allowed_body
import homesafe.shared.generated.resources.fin_sheet_setup_not_allowed_title
import homesafe.shared.generated.resources.fin_sheet_setup_open_cloud
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * The way in to bank sync, on the sheet sync page: the balances the sheet's cells look up can be
 * filled in from the banks themselves.
 */
@Composable
internal fun BankSyncEntry(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    FinanceCard(modifier.testTag("finance_bank_sync_entry"), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AccountBalance, contentDescription = null, tint = colors.accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.fin_bank_entry_title), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                Text(stringResource(Res.string.fin_bank_entry_body), style = FinanceTheme.type.label, color = colors.textSecondary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
        }
    }
}

/** Bank sync with its view model: reads the status as the page comes up, and hands Plaid's page to the browser once per link. */
@Composable
internal fun BankSyncRoute(contentPadding: PaddingValues) {
    val viewModel: BankSyncViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { viewModel.load() }
    val linking = state.linking
    LaunchedEffect(linking?.token, linking?.opened) {
        if (linking != null && !linking.opened) {
            viewModel.onLinkOpened()
            // No browser to hand it to: the card's "open again" is still there to try.
            runCatching { uriHandler.openUri(linking.url) }
        }
    }
    BankSyncScreen(
        state = state,
        contentPadding = contentPadding,
        onLink = viewModel::link,
        onRelink = viewModel::relink,
        onCancelLink = viewModel::cancelLink,
        onSyncNow = viewModel::syncNow,
        onUnlink = viewModel::unlink,
        onRetry = viewModel::load,
    )
}

/**
 * Bank sync: the institutions linked through Plaid with each account's latest balance, linking
 * another, and how the write to the budget sheet's feed went.
 */
@Composable
internal fun BankSyncScreen(
    state: BankSyncUiState,
    contentPadding: PaddingValues,
    onLink: (BankLinkKind) -> Unit,
    onRelink: (String) -> Unit,
    onCancelLink: () -> Unit,
    onSyncNow: () -> Unit,
    onUnlink: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val bank = state.bank
    // Picking what to link, and the institution whose unlinking is being confirmed (by id).
    var choosing by rememberSaveable { mutableStateOf(false) }
    var confirmingUnlink by rememberSaveable { mutableStateOf<String?>(null) }
    val now = Clock.System.now().epochSeconds
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.testTag("finance_bank_sync")) {
        when {
            bank == null && state.loading -> item(key = "loading") { LoadingCard() }

            bank == null -> item(key = "problem") { ProblemCard(state.problem, state.problemText, onRetry) }

            !bank.configured -> item(key = "setup") {
                MessageCard(stringResource(Res.string.fin_bank_setup_title), stringResource(Res.string.fin_bank_setup_body), onRetry)
            }

            else -> {
                item(key = "status") {
                    StatusCard(
                        state = state,
                        bank = bank,
                        now = now,
                        onLink = { choosing = true },
                        onSyncNow = onSyncNow,
                    )
                }
                when {
                    state.linking != null -> item(key = "linking") { LinkingCard(state.linking.url, onCancelLink) }

                    choosing -> item(key = "kinds") {
                        KindChooser(
                            onPick = {
                                choosing = false
                                onLink(it)
                            },
                            onCancel = { choosing = false },
                        )
                    }
                }
                bank.institutions.forEach { institution ->
                    item(key = "i-${institution.id}") { InstitutionHeader(institution, now, onRelink) }
                    if (institution.accounts.isEmpty()) {
                        item(key = "i-${institution.id}-none") { FinePrint(stringResource(Res.string.fin_bank_institution_no_accounts)) }
                    }
                    items(institution.accounts, key = { "a-${it.id}" }) { AccountRow(it) }
                    item(key = "i-${institution.id}-unlink") {
                        val busy = state.unlinking == institution.id
                        Text(
                            stringResource(if (busy) Res.string.fin_bank_institution_unlinking else Res.string.fin_bank_institution_unlink),
                            style = FinanceTheme.type.label,
                            color = if (busy) FinanceTheme.colors.textTertiary else FinanceTheme.colors.loss,
                            modifier = Modifier
                                .padding(horizontal = PageGutter - 8.dp)
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = !busy, role = Role.Button) { confirmingUnlink = institution.id }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                item(key = "feed-h") {
                    SectionHeader(stringResource(Res.string.fin_bank_feed_title), subtitle = stringResource(Res.string.fin_bank_feed_subtitle))
                }
                item(key = "feed") { FeedCard(bank.feed, now) }
                item(key = "how") { FinePrint(stringResource(Res.string.fin_bank_how), Modifier.padding(top = 20.dp)) }
            }
        }
    }

    val unlinking = bank?.institutions?.firstOrNull { it.id == confirmingUnlink }
    if (unlinking != null) {
        UnlinkDialog(
            name = unlinking.name,
            onConfirm = {
                confirmingUnlink = null
                onUnlink(unlinking.id)
            },
            onDismiss = { confirmingUnlink = null },
        )
    }
}

@Composable
private fun LoadingCard() {
    FinanceCard(Modifier.padding(top = 12.dp)) {
        Shimmer(Modifier.fillMaxWidth(0.6f).height(20.dp))
        Spacer(Modifier.height(10.dp))
        Shimmer(Modifier.fillMaxWidth().height(14.dp))
        Spacer(Modifier.height(16.dp))
        Shimmer(Modifier.width(160.dp).height(36.dp), corner = 18.dp)
    }
}

@Composable
private fun ProblemCard(problem: BankProblem?, text: UiText?, onRetry: () -> Unit) {
    val body = text?.resolve().orEmpty()
    when (problem) {
        BankProblem.RELAY_OUTDATED -> MessageCard(stringResource(Res.string.fin_bank_problem_outdated_title), body, onRetry)

        BankProblem.NOT_ALLOWED ->
            MessageCard(stringResource(Res.string.fin_sheet_setup_not_allowed_title), stringResource(Res.string.fin_sheet_setup_not_allowed_body), onRetry)

        BankProblem.SIGNED_OUT ->
            MessageCard(stringResource(Res.string.fin_bank_problem_signed_out_title), stringResource(Res.string.fin_bank_problem_signed_out_body), onRetry)

        BankProblem.NOT_CONFIGURED -> MessageCard(stringResource(Res.string.fin_bank_setup_title), stringResource(Res.string.fin_bank_setup_body), onRetry)

        BankProblem.TAILSCALE_OFF -> MessageCard(stringResource(Res.string.fin_bank_problem_tailscale_off_title), body, onRetry)

        BankProblem.PLAID, BankProblem.UNREACHABLE, BankProblem.OTHER, null -> MessageCard(stringResource(Res.string.fin_bank_problem_other_title), body, onRetry)
    }
}

/** A card that says why there's nothing to show, with a way to ask again. */
@Composable
private fun MessageCard(title: String, body: String, onRetry: () -> Unit) {
    val colors = FinanceTheme.colors
    FinanceCard(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AccountBalance, contentDescription = null, tint = colors.accent)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = FinanceTheme.type.section, color = colors.textPrimary)
        }
        if (body.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(body, style = FinanceTheme.type.body, color = colors.textSecondary)
        }
        Spacer(Modifier.height(14.dp))
        PillButton(stringResource(Res.string.common_try_again), colors.accent, onClick = onRetry)
    }
}

@Composable
private fun StatusCard(state: BankSyncUiState, bank: BankSync, now: Long, onLink: () -> Unit, onSyncNow: () -> Unit) {
    val colors = FinanceTheme.colors
    val linked = bank.institutions.size
    val troubled = bank.institutions.any { it.error != null } || bank.feed.error != null
    FinanceCard(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val light = when {
                linked == 0 -> colors.textTertiary
                troubled -> colors.watch
                else -> colors.gain
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(light))
            Spacer(Modifier.width(10.dp))
            Text(
                if (linked == 0) stringResource(Res.string.fin_bank_status_none) else pluralStringResource(Res.plurals.fin_bank_status_linked, linked, linked),
                style = FinanceTheme.type.bodyStrong,
                color = colors.textPrimary,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (linked == 0) {
            Text(stringResource(Res.string.fin_bank_status_intro), style = FinanceTheme.type.body, color = colors.textSecondary)
        } else {
            val read = bank.syncedAtEpochSeconds
            val next = bank.nextSyncAtEpochSeconds
            val line = when {
                state.syncing -> UiText.of(Res.string.fin_bank_status_reading)
                read == null -> UiText.of(Res.string.fin_bank_status_never_read)
                next == null -> UiText.of(Res.string.fin_bank_status_last_read, FinanceFormat.ago(now, read))
                else -> UiText.of(Res.string.fin_bank_status_last_read_next, FinanceFormat.ago(now, read), FinanceFormat.time(next, FinanceFormat.localOffsetSeconds(next)))
            }
            Text(line.resolve(), style = FinanceTheme.type.label, color = colors.textSecondary)
        }
        if (bank.sandbox) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(Res.string.fin_bank_status_sandbox), style = FinanceTheme.type.label, color = colors.watch)
        }
        state.notice?.let { notice ->
            Spacer(Modifier.height(6.dp))
            Text(notice.text.resolve(), style = FinanceTheme.type.label, color = if (notice.isError) colors.loss else colors.gain)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                stringResource(if (state.startingLink) Res.string.fin_bank_action_link_starting else Res.string.fin_bank_action_link),
                colors.accent,
                onClick = onLink,
                modifier = Modifier.testTag("finance_bank_link"),
            )
            if (linked > 0) {
                PillButton(stringResource(if (state.syncing) Res.string.fin_bank_action_syncing else Res.string.fin_bank_action_sync), colors.textSecondary, onClick = onSyncNow)
            }
        }
    }
}

@Composable
private fun LinkingCard(url: String, onCancel: () -> Unit) {
    val colors = FinanceTheme.colors
    val uriHandler = LocalUriHandler.current
    FinanceCard(Modifier.padding(top = 12.dp)) {
        Text(stringResource(Res.string.fin_bank_linking_title), style = FinanceTheme.type.section, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.fin_bank_linking_body), style = FinanceTheme.type.body, color = colors.textSecondary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(Res.string.fin_bank_linking_reopen), colors.accent, onClick = { runCatching { uriHandler.openUri(url) } })
            PillButton(stringResource(Res.string.common_cancel), colors.textSecondary, onClick = onCancel)
        }
    }
}

@Composable
private fun KindChooser(onPick: (BankLinkKind) -> Unit, onCancel: () -> Unit) {
    val colors = FinanceTheme.colors
    FinanceCard(Modifier.padding(top = 12.dp), padding = 0.dp) {
        Text(
            stringResource(Res.string.fin_bank_kind_title),
            style = FinanceTheme.type.section,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        )
        BankLinkKind.entries.forEach { kind ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPick(kind) }
                    .testTag("finance_bank_kind_${kind.wire}")
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(kind.label), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                    Text(stringResource(kind.detail), style = FinanceTheme.type.label, color = colors.textSecondary)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
            }
            Hairline(Modifier.padding(horizontal = 16.dp))
        }
        PillButton(stringResource(Res.string.common_cancel), colors.textSecondary, onClick = onCancel, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun InstitutionHeader(institution: BankInstitution, now: Long, onRelink: (String) -> Unit) {
    val colors = FinanceTheme.colors
    Column {
        SectionHeader(
            institution.name,
            trailing = institution.syncedAtEpochSeconds?.let { UiText.of(Res.string.fin_bank_institution_read, FinanceFormat.ago(now, it)).resolve() },
        )
        when {
            institution.needsRelink -> Column(Modifier.padding(horizontal = PageGutter).padding(bottom = 6.dp)) {
                Text(stringResource(Res.string.fin_bank_institution_relink, institution.name), style = FinanceTheme.type.label, color = colors.watch)
                Spacer(Modifier.height(6.dp))
                PillButton(stringResource(Res.string.fin_bank_institution_relink_action), colors.accent, onClick = { onRelink(institution.id) })
            }

            institution.error != null -> Text(
                stringResource(Res.string.fin_bank_institution_failed, institution.errorMessage ?: institution.error),
                style = FinanceTheme.type.label,
                color = colors.watch,
                modifier = Modifier.padding(horizontal = PageGutter).padding(bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun AccountRow(account: BankAccount) {
    val colors = FinanceTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                account.mask?.let { stringResource(Res.string.fin_bank_account_masked, account.name, it) } ?: account.name,
                style = FinanceTheme.type.body,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Plaid's own word for the account where it has one ("checking", "roth"), else the app's for its type.
            val kind = account.subtype?.replaceFirstChar { it.uppercase() }?.asUiText() ?: UiText.of(account.type.label)
            val details = listOfNotNull(
                kind,
                account.apr?.let { UiText.of(Res.string.fin_bank_account_apr, FinanceFormat.percent(it)) },
                account.holdings.takeIf { it > 0 }?.let { UiText.plural(Res.plurals.fin_bank_account_holdings, it) },
            )
            Text(
                UiText.Joined(details, UiText.of(Res.string.common_dot_separator)).resolve(),
                style = FinanceTheme.type.label,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        account.balance?.let { Text(FinanceFormat.money(it, currency = account.currency), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1) }
    }
}

@Composable
private fun FeedCard(feed: BankFeed, now: Long) {
    val colors = FinanceTheme.colors
    val uriHandler = LocalUriHandler.current
    FinanceCard {
        when {
            !feed.configured -> Text(stringResource(Res.string.fin_bank_feed_not_configured), style = FinanceTheme.type.body, color = colors.textSecondary)

            feed.error == "not_shared" -> {
                Text(stringResource(Res.string.fin_bank_feed_not_shared), style = FinanceTheme.type.body, color = colors.watch)
                feed.serviceAccount?.let { ServiceAccount(it) }
            }

            feed.error == "api_disabled" -> {
                Text(stringResource(Res.string.fin_sheet_setup_api_disabled_body), style = FinanceTheme.type.body, color = colors.watch)
                feed.activationUrl?.let { url ->
                    Spacer(Modifier.height(10.dp))
                    PillButton(stringResource(Res.string.fin_sheet_setup_open_cloud), colors.accent, onClick = { runCatching { uriHandler.openUri(url) } })
                }
            }

            feed.error == "not_found" -> Text(stringResource(Res.string.fin_bank_feed_not_found), style = FinanceTheme.type.body, color = colors.watch)

            feed.error != null -> Text(
                feed.message?.let { stringResource(Res.string.fin_bank_feed_failed, it) } ?: stringResource(Res.string.fin_bank_feed_failed_plain),
                style = FinanceTheme.type.body,
                color = colors.watch,
            )

            feed.writtenAtEpochSeconds != null -> Text(
                UiText.of(Res.string.fin_bank_feed_written, FinanceFormat.ago(now, feed.writtenAtEpochSeconds)).resolve(),
                style = FinanceTheme.type.body,
                color = colors.textSecondary,
            )

            else -> Text(stringResource(Res.string.fin_bank_feed_not_written), style = FinanceTheme.type.body, color = colors.textSecondary)
        }
        if (feed.configured) {
            feed.url?.let { url ->
                Spacer(Modifier.height(10.dp))
                PillButton(stringResource(Res.string.fin_bank_feed_open), colors.textSecondary, onClick = { runCatching { uriHandler.openUri(url) } })
            }
            Spacer(Modifier.height(10.dp))
            SelectionContainer { Text(stringResource(Res.string.fin_bank_feed_how), style = FinanceTheme.type.micro, color = colors.textTertiary) }
        }
    }
}

/** The Google account to share the feed sheet with, selectable so it can be pasted into the sheet's Share box. */
@Composable
private fun ServiceAccount(email: String) {
    val colors = FinanceTheme.colors
    Spacer(Modifier.height(10.dp))
    SelectionContainer {
        Text(
            email,
            style = FinanceTheme.type.bodyStrong,
            color = colors.textPrimary,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.surfaceRaised).padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun UnlinkDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceRaised,
        title = { Text(stringResource(Res.string.fin_bank_unlink_title, name), style = type.title, color = colors.textPrimary) },
        text = { Text(stringResource(Res.string.fin_bank_unlink_body), style = type.body, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.fin_bank_unlink_confirm), style = type.bodyStrong, color = colors.loss) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel), style = type.bodyStrong, color = colors.textSecondary) } },
    )
}
