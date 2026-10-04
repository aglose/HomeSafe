package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.ServerUptime
import com.meticulouscreations.homesafe.domain.model.UptimeCheck
import com.meticulouscreations.homesafe.domain.model.UptimeCheckKind
import com.meticulouscreations.homesafe.domain.model.UptimeDevice
import com.meticulouscreations.homesafe.domain.model.UptimeOutage
import com.meticulouscreations.homesafe.domain.model.UptimeRange
import com.meticulouscreations.homesafe.domain.model.UptimeState
import com.meticulouscreations.homesafe.domain.model.formatUpFraction
import com.meticulouscreations.homesafe.domain.model.formatUptime
import com.meticulouscreations.homesafe.domain.model.uptimeMoment
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.viewmodel.ServerUptimeUiState
import com.meticulouscreations.homesafe.viewmodel.ServerUptimeViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.common_retry
import homesafe.shared.generated.resources.settings_refresh
import homesafe.shared.generated.resources.uptime_bar_description
import homesafe.shared.generated.resources.uptime_device_last_seen
import homesafe.shared.generated.resources.uptime_device_never_seen
import homesafe.shared.generated.resources.uptime_device_online
import homesafe.shared.generated.resources.uptime_device_state_seen
import homesafe.shared.generated.resources.uptime_device_state_unseen
import homesafe.shared.generated.resources.uptime_devices_about
import homesafe.shared.generated.resources.uptime_devices_none
import homesafe.shared.generated.resources.uptime_devices_title
import homesafe.shared.generated.resources.uptime_headline_all_up
import homesafe.shared.generated.resources.uptime_headline_down
import homesafe.shared.generated.resources.uptime_headline_unknown
import homesafe.shared.generated.resources.uptime_list_separator
import homesafe.shared.generated.resources.uptime_loading
import homesafe.shared.generated.resources.uptime_measured
import homesafe.shared.generated.resources.uptime_measured_since
import homesafe.shared.generated.resources.uptime_not_kept_yet
import homesafe.shared.generated.resources.uptime_not_measured
import homesafe.shared.generated.resources.uptime_outage_ended
import homesafe.shared.generated.resources.uptime_outage_ongoing
import homesafe.shared.generated.resources.uptime_outages_none
import homesafe.shared.generated.resources.uptime_outages_title
import homesafe.shared.generated.resources.uptime_reached_over_local
import homesafe.shared.generated.resources.uptime_reached_over_tailscale
import homesafe.shared.generated.resources.uptime_span_detail
import homesafe.shared.generated.resources.uptime_span_hint
import homesafe.shared.generated.resources.uptime_state_down
import homesafe.shared.generated.resources.uptime_state_not_measured
import homesafe.shared.generated.resources.uptime_state_partial
import homesafe.shared.generated.resources.uptime_state_up
import homesafe.shared.generated.resources.uptime_subtitle
import homesafe.shared.generated.resources.uptime_title
import homesafe.shared.generated.resources.uptime_up_share
import homesafe.shared.generated.resources.uptime_up_share_with_down
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// The status colours a span is drawn in. They are the same in every theme: the meaning has to
// survive a screenshot sent to someone else. A span is never colour alone: tapping it says its
// state in words, and each row carries its own figures.
private val UpColor = Color(0xFF0CA30C)
private val PartialColor = Color(0xFFFAB219)
private val DownColor = Color(0xFFD03B3B)

/** A row's spans, oldest first, as one stable value: a bare `List` would stop the row and its bar from skipping. */
@Immutable
private class Timeline(val states: List<UptimeState>)

/** The span someone tapped: which row, and which of its spans. */
private data class SpanSelection(val rowKey: String, val index: Int)

/**
 * The server's uptime record, one tap below the Server page: each link between a phone and a
 * camera as the box saw it, minute by minute, the household's devices as Tailscale saw them, and
 * the stretches something was down. It answers "was it the server, or was it my phone?" after
 * the app couldn't connect, and it reads over whichever address the app is on, so it works on the
 * home network as well as over Tailscale.
 */
@Composable
fun ServerUptimeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: ServerUptimeViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ServerUptimeContent(state = state, onBack = onBack, onRefresh = viewModel::refresh, onSelectRange = viewModel::selectRange, modifier = modifier)
}

@Composable
internal fun ServerUptimeContent(
    state: ServerUptimeUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectRange: (UptimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        UptimeHeader(isLoading = state.isLoading, onBack = onBack, onRefresh = onRefresh)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TAB_CONTENT_HORIZONTAL_PADDING)
                .padding(top = 8.dp, bottom = bottomNavClearance()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            RangeChips(selected = state.range, onSelect = onSelectRange)
            val uptime = state.uptime
            when {
                uptime != null -> {
                    state.error?.let { UptimeFailure(it, onRefresh) }
                    UptimeRecord(uptime = uptime, route = state.route)
                }

                state.error != null -> UptimeFailure(state.error, onRefresh)

                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    SettingsCaption(stringResource(Res.string.uptime_loading))
                }
            }
        }
    }
}

@Composable
private fun UptimeHeader(isLoading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back), tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = stringResource(Res.string.uptime_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, textAlign = TextAlign.Center)
            Text(text = stringResource(Res.string.uptime_subtitle), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.settings_refresh), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun RangeChips(selected: UptimeRange, onSelect: (UptimeRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UptimeRange.entries.forEach { range ->
            FilterChip(selected = range == selected, onClick = { onSelect(range) }, label = { Text(stringResource(range.label)) })
        }
    }
}

@Composable
private fun UptimeFailure(message: UiText, onRetry: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SettingsCaption(message.resolve(), error = true, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
    }
}

@Composable
private fun UptimeRecord(uptime: ServerUptime, route: ConnectionRoute?) {
    var selection by remember(uptime) { mutableStateOf<SpanSelection?>(null) }
    val timeZone = remember { TimeZone.currentSystemDefault() }
    SettingsSection(title = headline(uptime).resolve(), icon = Icons.Filled.MonitorHeart) {
        SettingsCaption(
            uptime.recordingSinceEpochSeconds
                ?.let { stringResource(Res.string.uptime_measured_since, uptimeMoment(it, timeZone).resolve()) }
                ?: stringResource(Res.string.uptime_measured),
        )
        when (route) {
            ConnectionRoute.LOCAL_NETWORK -> SettingsCaption(stringResource(Res.string.uptime_reached_over_local))
            ConnectionRoute.TAILSCALE -> SettingsCaption(stringResource(Res.string.uptime_reached_over_tailscale))
            null -> Unit
        }
        UptimeLegend()
        SettingsCaption(selectionText(uptime, selection, timeZone)?.resolve() ?: stringResource(Res.string.uptime_span_hint))
        uptime.checks.forEach { check ->
            val title = check.title.resolve()
            UptimeRow(
                title = title,
                figures = checkFigures(check).resolve(),
                isUpNow = check.isUpNow,
                note = check.kind?.takeIf { check.isUpNow == false || check.downSeconds > 0 }?.let { stringResource(it.about) },
                timeline = remember(check.states) { Timeline(check.states) },
                selectedIndex = selection?.takeIf { it.rowKey == check.key }?.index,
                onSelect = { selection = SpanSelection(check.key, it) },
            )
        }
    }
    SettingsSection(title = stringResource(Res.string.uptime_devices_title), icon = Icons.Filled.Devices) {
        SettingsCaption(stringResource(Res.string.uptime_devices_about))
        if (uptime.devices.isEmpty()) SettingsCaption(stringResource(Res.string.uptime_devices_none))
        uptime.devices.forEach { device ->
            UptimeRow(
                title = device.name,
                figures = deviceFigures(device, timeZone).resolve(),
                isUpNow = device.isOnline,
                note = null,
                timeline = remember(device.states) { Timeline(device.states) },
                selectedIndex = selection?.takeIf { it.rowKey == deviceRowKey(device) }?.index,
                onSelect = { selection = SpanSelection(deviceRowKey(device), it) },
            )
        }
    }
    SettingsSection(title = stringResource(Res.string.uptime_outages_title), icon = Icons.Filled.History) {
        if (uptime.outages.isEmpty()) SettingsCaption(stringResource(Res.string.uptime_outages_none))
        uptime.outages.forEach { outage -> OutageRow(title = outageTitle(uptime, outage).resolve(), detail = outageDetail(outage, timeZone).resolve(), ongoing = outage.endEpochSeconds == null) }
    }
}

@Composable
private fun UptimeLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(UptimeState.Up, UptimeState.Partial, UptimeState.Down, UptimeState.NotMeasured).forEach { state ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(stateColor(state)))
                SettingsCaption(stringResource(state.label))
            }
        }
    }
}

/** One check or device: whether it's up now, its name and figures, and its timeline. */
@Composable
private fun UptimeRow(
    title: String,
    figures: String,
    isUpNow: Boolean?,
    note: String?,
    timeline: Timeline,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dot = when (isUpNow) {
                true -> UpColor
                false -> DownColor
                null -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dot))
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(text = figures, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
        UptimeBar(timeline = timeline, selectedIndex = selectedIndex, description = stringResource(Res.string.uptime_bar_description, title), onSelect = onSelect)
        note?.let { SettingsCaption(it) }
    }
}

/** The timeline: one block per span, oldest on the left. A tap picks the span under it. */
@Composable
private fun UptimeBar(timeline: Timeline, selectedIndex: Int?, description: String, onSelect: (Int) -> Unit) {
    val states = timeline.states
    val unmeasured = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val ring = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .semantics { contentDescription = description }
            .pointerInput(states.size) {
                detectTapGestures { tap ->
                    if (states.isNotEmpty()) onSelect((tap.x / size.width * states.size).toInt().coerceIn(0, states.lastIndex))
                }
            },
    ) {
        if (states.isEmpty()) return@Canvas
        val gap = 1.dp.toPx()
        val slot = size.width / states.size
        val corner = CornerRadius(2.dp.toPx())
        states.forEachIndexed { index, state ->
            val color = when (state) {
                UptimeState.Up -> UpColor.copy(alpha = 0.55f)
                UptimeState.Partial -> PartialColor
                UptimeState.Down -> DownColor
                UptimeState.NotMeasured -> unmeasured
            }
            val selected = index == selectedIndex
            drawRoundRect(
                color = if (selected && state == UptimeState.Up) UpColor else color,
                topLeft = Offset(index * slot, 0f),
                size = Size((slot - gap).coerceAtLeast(1f), size.height),
                cornerRadius = corner,
            )
            if (selected) {
                drawRect(color = ring, topLeft = Offset(index * slot, size.height - 2.dp.toPx()), size = Size((slot - gap).coerceAtLeast(1f), 2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun OutageRow(title: String, detail: String, ongoing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(if (ongoing) DownColor else MaterialTheme.colorScheme.outlineVariant))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            SettingsCaption(detail)
        }
    }
}

@Composable
private fun stateColor(state: UptimeState): Color = when (state) {
    UptimeState.Up -> UpColor.copy(alpha = 0.55f)
    UptimeState.Partial -> PartialColor
    UptimeState.Down -> DownColor
    UptimeState.NotMeasured -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
}

private val UptimeState.label: StringResource
    get() = when (this) {
        UptimeState.Up -> Res.string.uptime_state_up
        UptimeState.Partial -> Res.string.uptime_state_partial
        UptimeState.Down -> Res.string.uptime_state_down
        UptimeState.NotMeasured -> Res.string.uptime_state_not_measured
    }

private fun deviceRowKey(device: UptimeDevice): String = "device:${device.name}"

/** "Everything is up", or what is down now, or that the server hasn't said lately. */
internal fun headline(uptime: ServerUptime): UiText {
    val down = uptime.downNow
    return when {
        down.isNotEmpty() -> UiText.of(Res.string.uptime_headline_down, UiText.Joined(down.map { it.title }, UiText.of(Res.string.uptime_list_separator)))
        uptime.checks.any { it.isUpNow == true } -> UiText.of(Res.string.uptime_headline_all_up)
        else -> UiText.of(Res.string.uptime_headline_unknown)
    }
}

/** "99.9% up · 5 minutes down", "100% up", or "Not measured". */
internal fun checkFigures(check: UptimeCheck): UiText {
    val fraction = check.upFraction ?: return UiText.of(Res.string.uptime_not_measured)
    val share = formatUpFraction(fraction)
    return if (check.downSeconds > 0) {
        UiText.of(Res.string.uptime_up_share_with_down, share, formatUptime(check.downSeconds))
    } else {
        UiText.of(Res.string.uptime_up_share, share)
    }
}

/** "On the tailnet", "Last seen Oct 3, 10:25 PM", or "Not seen". */
internal fun deviceFigures(device: UptimeDevice, timeZone: TimeZone): UiText = when {
    device.isOnline == true -> UiText.of(Res.string.uptime_device_online)
    device.lastSeenEpochSeconds != null -> UiText.of(Res.string.uptime_device_last_seen, uptimeMoment(device.lastSeenEpochSeconds, timeZone))
    else -> UiText.of(Res.string.uptime_device_never_seen)
}

/** What the tapped span says: "Internet · Oct 4, 10:15 AM · Down". */
private fun selectionText(uptime: ServerUptime, selection: SpanSelection?, timeZone: TimeZone): UiText? {
    selection ?: return null
    val moment = uptimeMoment(uptime.bucketStart(selection.index), timeZone)
    uptime.checks.firstOrNull { it.key == selection.rowKey }?.let { check ->
        val state = check.states.getOrNull(selection.index) ?: return null
        return UiText.of(Res.string.uptime_span_detail, check.title, moment, UiText.of(state.label))
    }
    val device = uptime.devices.firstOrNull { deviceRowKey(it) == selection.rowKey } ?: return null
    val state = when (device.states.getOrNull(selection.index) ?: return null) {
        UptimeState.Up, UptimeState.Partial -> Res.string.uptime_device_state_seen
        UptimeState.Down -> Res.string.uptime_device_state_unseen
        UptimeState.NotMeasured -> Res.string.uptime_state_not_measured
    }
    return UiText.of(Res.string.uptime_span_detail, device.name.asUiText(), moment, UiText.of(state))
}

private fun outageTitle(uptime: ServerUptime, outage: UptimeOutage): UiText =
    uptime.checks.firstOrNull { it.key == outage.checkKey }?.title
        ?: UptimeCheckKind.of(outage.checkKey)?.let { UiText.of(it.title) }
        ?: outage.checkKey.asUiText()

/** "Oct 1, 8:19 PM · 12 minutes", or for one still going "… · still down after 12 minutes". */
internal fun outageDetail(outage: UptimeOutage, timeZone: TimeZone): UiText = UiText.of(
    if (outage.endEpochSeconds == null) Res.string.uptime_outage_ongoing else Res.string.uptime_outage_ended,
    uptimeMoment(outage.startEpochSeconds, timeZone),
    formatUptime(outage.seconds),
)

// ---- Previews ----------------------------------------------------------------------------------

private fun previewStates(pattern: String): List<UptimeState> = pattern.map(UptimeState::fromLetter)

/** A day with a short internet outage, a camera that dropped and is back, live video down now, and a phone off the tailnet since last night. */
private val previewUptime: ServerUptime = run {
    val until = 1_791_147_600L
    val up = "u".repeat(96)
    ServerUptime(
        sinceEpochSeconds = until - 86_400,
        untilEpochSeconds = until,
        bucketSeconds = 900.0,
        recordingSinceEpochSeconds = until - 9 * 86_400,
        checks = listOf(
            UptimeCheck("server", "Server running", previewStates(up), 1.0, 0, true),
            UptimeCheck("router", "Home router", previewStates(up), 1.0, 0, true),
            UptimeCheck("internet", "Internet", previewStates("u".repeat(40) + "dxxd" + "u".repeat(52)), 0.9722, 2_400, true),
            UptimeCheck("dns", "Name lookups (DNS)", previewStates("u".repeat(18) + "d" + "u".repeat(21) + "dxxd" + "u".repeat(52)), 0.9708, 2_520, true),
            UptimeCheck("tailscale", "Tailscale on the server", previewStates(up), 1.0, 0, true),
            UptimeCheck("frigate", "Frigate", previewStates(up), 1.0, 0, true),
            UptimeCheck("cameras", "Cameras sending video", previewStates("u".repeat(80) + "d" + "u".repeat(15)), 0.9951, 420, true),
            UptimeCheck("live", "Live video (go2rtc)", previewStates("u".repeat(95) + "d"), 0.9972, 240, false),
        ),
        devices = listOf(
            UptimeDevice("iphone-15-pro", "iOS", true, until - 60, previewStates("u".repeat(30) + "xx" + "u".repeat(64))),
            UptimeDevice("macbook-pro", "macOS", true, until - 60, previewStates("x".repeat(36) + "u".repeat(60))),
            UptimeDevice("pixel-10-pro-xl", "android", false, until - 13 * 3_600, previewStates("u".repeat(44) + "x".repeat(52))),
        ),
        outages = listOf(
            UptimeOutage("live", until - 240, null, 240),
            UptimeOutage("cameras", until - 14_400, until - 13_980, 420),
            UptimeOutage("internet", until - 50_400, until - 48_000, 2_400),
        ),
    )
}

@Preview(name = "Server uptime", widthDp = 412, heightDp = 1500)
@Composable
private fun ServerUptimePreview() {
    FrigatePreview {
        ServerUptimeContent(
            state = ServerUptimeUiState(isLoading = false, uptime = previewUptime, route = ConnectionRoute.LOCAL_NETWORK),
            onBack = {},
            onRefresh = {},
            onSelectRange = {},
        )
    }
}

@Preview(name = "Server uptime, relay too old", widthDp = 412, heightDp = 400)
@Composable
private fun ServerUptimeUnavailablePreview() {
    FrigatePreview {
        ServerUptimeContent(
            state = ServerUptimeUiState(isLoading = false, error = UiText.of(Res.string.uptime_not_kept_yet)),
            onBack = {},
            onRefresh = {},
            onSelectRange = {},
        )
    }
}
