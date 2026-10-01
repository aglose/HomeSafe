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
        SheetProblem.NOT_SHARED ->
            "Share your budget sheet" to
                "The server reads your sheet with its own Google account, so the sheet never has to be public. Open the sheet, tap Share, and add this address as a Viewer:"

        SheetProblem.API_DISABLED ->
            "Turn on the Google Sheets API" to
                "The server's Google Cloud project needs the Sheets API switched on once. Open the link below, tap Enable, then come back and retry."

        SheetProblem.NOT_CONFIGURED ->
            "Point the server at your sheet" to
                "Set FINANCE_SHEET_ID in the relay's docker-compose.yml to the sheet's id (the long part of its URL) and restart the relay."

        SheetProblem.NO_KEY ->
            "The server has no Google key" to
                "The relay reads the sheet with a Google service-account key. Set FINANCE_SHEET_KEY to its path (the push key works) and restart the relay."

        SheetProblem.RELAY_OUTDATED ->
            "Update the server's relay" to
                "The relay on your server is older than the finance app. Copy relay/ to the server and run docker compose up -d --build there."

        SheetProblem.NOT_ALLOWED ->
            "Not for this account" to
                "The household's finances are shown to the server's admin accounts only. Sign in with one to see them here."

        SheetProblem.SIGNED_OUT ->
            "Sign in to see your money" to
                "Your budget comes through your PercySafe server, so it shows once you're connected."

        SheetProblem.NOT_FOUND -> "Sheet not found" to "Google couldn't find the sheet the server is set to read. Check FINANCE_SHEET_ID."

        SheetProblem.OTHER -> "Couldn't read your sheet" to issue.message
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
            Text("Open Google Cloud", style = FinanceTheme.type.bodyStrong, color = colors.accent, modifier = Modifier.clickable { uriHandler.openUri(url) })
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Try again",
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
