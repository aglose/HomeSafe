package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.SheetIssue
import com.meticulouscreations.homesafe.finance.domain.SheetProblem
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_try_again
import homesafe.shared.generated.resources.fin_sheet_setup_api_disabled_body
import homesafe.shared.generated.resources.fin_sheet_setup_api_disabled_title
import homesafe.shared.generated.resources.fin_sheet_setup_no_key_body
import homesafe.shared.generated.resources.fin_sheet_setup_no_key_title
import homesafe.shared.generated.resources.fin_sheet_setup_not_allowed_body
import homesafe.shared.generated.resources.fin_sheet_setup_not_allowed_title
import homesafe.shared.generated.resources.fin_sheet_setup_not_configured_body
import homesafe.shared.generated.resources.fin_sheet_setup_not_configured_title
import homesafe.shared.generated.resources.fin_sheet_setup_not_found_body
import homesafe.shared.generated.resources.fin_sheet_setup_not_found_title
import homesafe.shared.generated.resources.fin_sheet_setup_not_shared_body
import homesafe.shared.generated.resources.fin_sheet_setup_not_shared_title
import homesafe.shared.generated.resources.fin_sheet_setup_open_cloud
import homesafe.shared.generated.resources.fin_sheet_setup_other_title
import homesafe.shared.generated.resources.fin_sheet_setup_relay_outdated_body
import homesafe.shared.generated.resources.fin_sheet_setup_relay_outdated_title
import homesafe.shared.generated.resources.fin_sheet_setup_signed_out_body
import homesafe.shared.generated.resources.fin_sheet_setup_signed_out_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shown on the Wallet tab when the budget sheet can't be read: what's wrong, in words, and the
 * one thing that fixes it — usually sharing the sheet with the relay's Google service account,
 * whose address is shown selectable so it can be pasted into the sheet's Share box.
 */
@Composable
internal fun SheetSetupCard(issue: SheetIssue, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val uriHandler = LocalUriHandler.current
    val (title, body) = when (issue.problem) {
        SheetProblem.NOT_SHARED -> stringResource(Res.string.fin_sheet_setup_not_shared_title) to stringResource(Res.string.fin_sheet_setup_not_shared_body)
        SheetProblem.API_DISABLED -> stringResource(Res.string.fin_sheet_setup_api_disabled_title) to stringResource(Res.string.fin_sheet_setup_api_disabled_body)
        SheetProblem.NOT_CONFIGURED -> stringResource(Res.string.fin_sheet_setup_not_configured_title) to stringResource(Res.string.fin_sheet_setup_not_configured_body)
        SheetProblem.NO_KEY -> stringResource(Res.string.fin_sheet_setup_no_key_title) to stringResource(Res.string.fin_sheet_setup_no_key_body)
        SheetProblem.RELAY_OUTDATED -> stringResource(Res.string.fin_sheet_setup_relay_outdated_title) to stringResource(Res.string.fin_sheet_setup_relay_outdated_body)
        SheetProblem.NOT_ALLOWED -> stringResource(Res.string.fin_sheet_setup_not_allowed_title) to stringResource(Res.string.fin_sheet_setup_not_allowed_body)
        SheetProblem.SIGNED_OUT -> stringResource(Res.string.fin_sheet_setup_signed_out_title) to stringResource(Res.string.fin_sheet_setup_signed_out_body)
        SheetProblem.NOT_FOUND -> stringResource(Res.string.fin_sheet_setup_not_found_title) to stringResource(Res.string.fin_sheet_setup_not_found_body)
        SheetProblem.OTHER -> stringResource(Res.string.fin_sheet_setup_other_title) to issue.message.resolve()
    }
    FinanceCard(modifier.padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.TableChart, contentDescription = null, tint = colors.accent)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = FinanceTheme.type.section, color = colors.textPrimary)
        }
        Spacer(Modifier.height(12.dp))
        Text(body, style = FinanceTheme.type.body, color = colors.textSecondary)
        issue.serviceAccount?.takeIf { issue.problem == SheetProblem.NOT_SHARED || issue.problem == SheetProblem.API_DISABLED }?.let { email ->
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(
                    email,
                    style = FinanceTheme.type.bodyStrong,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceRaised)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        issue.activationUrl?.let { url ->
            Spacer(Modifier.height(10.dp))
            Text(stringResource(Res.string.fin_sheet_setup_open_cloud), style = FinanceTheme.type.bodyStrong, color = colors.accent, modifier = Modifier.clickable { uriHandler.openUri(url) })
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(Res.string.common_try_again),
            style = FinanceTheme.type.bodyStrong,
            color = colors.background,
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.gain)
                .clickable(onClick = onRetry)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
