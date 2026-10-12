package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceLockUiState
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_fingerprint
import homesafe.shared.generated.resources.biometric_name_generic
import homesafe.shared.generated.resources.finance_close
import homesafe.shared.generated.resources.finance_lock_body
import homesafe.shared.generated.resources.finance_lock_body_no_check
import homesafe.shared.generated.resources.finance_lock_checking
import homesafe.shared.generated.resources.finance_lock_title
import homesafe.shared.generated.resources.finance_lock_unlock
import org.jetbrains.compose.resources.stringResource

/**
 * What the finance app shows in place of itself while it is locked (see `FinanceAccess`): there is
 * no server to ask who this is, so the household's money waits for a fingerprint or a face. Where
 * the device can check neither ([FinanceLockUiState.canUnlock] false) it says so, and that the
 * server coming back is what opens it.
 *
 * [onClose] is the ✕ in the corner where the app's own sits; Back is the overlay's around it.
 */
@Composable
internal fun FinanceLockScreen(state: FinanceLockUiState, onUnlock: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val method = stringResource(state.method)
    Box(modifier.fillMaxSize().background(colors.background).testTag(FINANCE_LOCK_TEST_TAG)) {
        Column(
            Modifier
                .align(Alignment.Center)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .widthIn(max = 420.dp)
                .padding(horizontal = 32.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(colors.gain.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.gain, modifier = Modifier.size(32.dp))
            }
            Text(stringResource(Res.string.finance_lock_title), style = type.title, color = colors.textPrimary, textAlign = TextAlign.Center)
            Text(
                if (state.canUnlock) stringResource(Res.string.finance_lock_body, method) else stringResource(Res.string.finance_lock_body_no_check),
                style = type.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (state.canUnlock) {
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .clip(CircleShape)
                        .background(if (state.checking) colors.gain.copy(alpha = 0.5f) else colors.gain)
                        .clickable(enabled = !state.checking, role = Role.Button, onClick = onUnlock)
                        .testTag(FINANCE_UNLOCK_TEST_TAG)
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(if (state.checking) Res.string.finance_lock_checking else Res.string.finance_lock_unlock, method),
                        style = type.bodyStrong,
                        color = Color.Black,
                    )
                }
            }
            state.error?.let { Text(it.resolve(), style = type.label, color = colors.loss, textAlign = TextAlign.Center) }
        }
        // After the column, so it is on top of it where a short window scrolls the two together.
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp).testTag("finance_back")) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.finance_close), tint = colors.textPrimary)
        }
    }
}

/** The lock itself and its button, for tests (and, on Android, UiAutomator as resource ids). */
const val FINANCE_LOCK_TEST_TAG = "finance_lock"
const val FINANCE_UNLOCK_TEST_TAG = "finance_unlock"

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun FinanceLockPreview() {
    FinanceStage {
        FinanceLockScreen(FinanceLockUiState(locked = true, offline = true, canUnlock = true, method = Res.string.biometric_name_fingerprint), onUnlock = {}, onClose = {})
    }
}

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun FinanceLockFailedPreview() {
    FinanceStage {
        FinanceLockScreen(
            FinanceLockUiState(locked = true, offline = true, canUnlock = true, method = Res.string.biometric_name_fingerprint, error = "Fingerprint operation cancelled by user.".asUiText()),
            onUnlock = {},
            onClose = {},
        )
    }
}

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun FinanceLockNoCheckPreview() {
    FinanceStage {
        FinanceLockScreen(FinanceLockUiState(locked = true, offline = true, canUnlock = false, method = Res.string.biometric_name_generic), onUnlock = {}, onClose = {})
    }
}

@Preview(widthDp = 915, heightDp = 412)
@Composable
private fun FinanceLockLandscapePreview() {
    FinanceStage {
        FinanceLockScreen(FinanceLockUiState(locked = true, offline = true, canUnlock = true, method = Res.string.biometric_name_fingerprint), onUnlock = {}, onClose = {})
    }
}
