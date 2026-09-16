package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.shortLabel
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.DownloadUiState
import com.meticulouscreations.homesafe.viewmodel.MomentCameraOption
import com.meticulouscreations.homesafe.viewmodel.MomentItem
import com.meticulouscreations.homesafe.viewmodel.MomentsUiState
import com.meticulouscreations.homesafe.viewmodel.MomentsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** How many of the list's last items may be on screen before the next page is asked for. */
private const val LOAD_OLDER_LOOKAHEAD = 4

private val MomentCategory.label: String
    get() = when (this) {
        MomentCategory.ALL -> "All events"
        MomentCategory.PEOPLE -> "People"
        MomentCategory.VEHICLES -> "Vehicles"
        MomentCategory.ANIMALS -> "Animals"
    }

private val MomentCategory.icon: ImageVector?
    get() = when (this) {
        MomentCategory.ALL -> null
        MomentCategory.PEOPLE -> Icons.Filled.Person
        MomentCategory.VEHICLES -> Icons.Filled.DirectionsCar
        MomentCategory.ANIMALS -> Icons.Filled.Pets
    }

/**
 * The "Moments" tab: Frigate's detections, newest first, filterable, each expandable to play its clip.
 *
 * The feed pages: it opens at now (or at the end of a day picked from the calendar chip) and
 * fetches the next page down as the list nears its end, so every day Frigate still holds is
 * reachable by scrolling — and an old one directly, without scrolling through everything since.
 *
 * [onOpenFullScreen] hands a detection off to its camera's detail screen, which plays the
 * recording from that instant on the full-width player — the way out of the card-sized one.
 */
@Composable
fun MomentsTabContent(onOpenFullScreen: (MomentEvent) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: MomentsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()

    MomentsFeed(
        state = state,
        downloadState = downloadState,
        onSelectCategory = viewModel::selectCategory,
        onSelectCamera = viewModel::selectCamera,
        onShowDay = viewModel::showDay,
        onLoadOlder = viewModel::loadOlder,
        onCardClick = viewModel::toggleExpanded,
        onClipBuffering = viewModel::onClipBuffering,
        onClipError = viewModel::onClipPlaybackError,
        onDownloadClick = viewModel::downloadClip,
        onFullScreenClick = { event ->
            // Close the inline player on the way out: playback moves to the
            // detail screen, and two players on one clip would both hold audio.
            viewModel.collapse()
            onOpenFullScreen(event)
        },
        modifier = modifier,
    )
}

/**
 * The tab with its state hoisted: what [MomentsTabContent] draws once it has read the view
 * model. Nothing here reaches for a graph, so it can be previewed and tested from fixtures.
 * The only state it keeps is whether the day picker or a filter's menu is up — facts about this
 * composition, not about the feed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MomentsFeed(
    state: MomentsUiState,
    downloadState: DownloadUiState,
    onSelectCategory: (MomentCategory) -> Unit,
    onSelectCamera: (String?) -> Unit,
    onShowDay: (LocalDate?) -> Unit,
    onLoadOlder: () -> Unit,
    onCardClick: (MomentEvent) -> Unit,
    onClipBuffering: (Boolean) -> Unit,
    onClipError: () -> Unit,
    onDownloadClick: (MomentEvent) -> Unit,
    onFullScreenClick: (MomentEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickingDay by remember { mutableStateOf(false) }

    if (pickingDay) {
        MomentDayPicker(
            initialDayUtcMillis = state.historyDay?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds(),
            onPick = { day ->
                pickingDay = false
                onShowDay(day)
            },
            onDismiss = { pickingDay = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            // Under the shell's floating top bar (see shellTopBarClearance); the filter chips
            // stay put beneath it while the list scrolls under both.
            .padding(top = shellTopBarClearance() + 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // One chip per filter, each opening a menu of its options, rather than a chip per option:
        // a row of category chips already overran a phone's width, and a chip for every camera
        // on top of them would bury most of the choices behind a sideways scroll. The chips wrap
        // rather than scroll: with a camera, a type and a day all picked they don't fit one line
        // on a phone, and a filter in force that has scrolled out of sight reads as no filter.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            CameraFilterChip(
                cameras = state.cameras,
                selectedCameraName = state.selectedCamera?.name,
                selectedCameraLabel = state.selectedCamera?.displayName,
                onSelect = onSelectCamera,
            )
            CategoryFilterChip(selected = state.selectedCategory, onSelect = onSelectCategory)
            HistoryChip(
                dayLabel = state.historyDay?.shortLabel(),
                onClick = { pickingDay = true },
                onClear = { onShowDay(null) },
            )
        }

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        if (state.groups.isEmpty()) {
            EmptyMoments(
                category = state.selectedCategory,
                cameraLabel = state.selectedCamera?.displayName,
                historyDayLabel = state.historyDay?.shortLabel(),
                hasError = state.error != null,
                hasOlder = state.hasOlder,
                loadingOlder = state.loadingOlder,
                onLoadOlder = onLoadOlder,
            )
        } else {
            val listState = rememberLazyListState()
            LoadOlderWhenNearTheEnd(listState, hasOlder = state.hasOlder, loadingOlder = state.loadingOlder, onLoadOlder = onLoadOlder)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = bottomNavClearance()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                state.groups.forEach { group ->
                    item(key = "header-${group.dateGroup}-${group.dateSubLabel}", contentType = "date-header") {
                        MomentDateHeader(dateGroup = group.dateGroup, dateSubLabel = group.dateSubLabel)
                    }
                    items(group.items, key = { it.event.id }, contentType = { "moment-card" }) { item ->
                        val expanded = state.expandedEventId == item.event.id
                        val isDownloading = downloadState.downloadingEventId == item.event.id
                        val downloadSucceeded = downloadState.resultEventId == item.event.id && downloadState.resultError == null
                        val downloadErrorMessage = downloadState.resultError.takeIf { downloadState.resultEventId == item.event.id }
                        MomentCard(
                            item = item,
                            expanded = expanded,
                            clipRequest = if (expanded) state.clipRequest else null,
                            clipPosterUrl = if (expanded) state.clipPosterUrl else null,
                            clipError = if (expanded) state.clipError else null,
                            clipBuffering = expanded && state.clipBuffering,
                            isDownloading = isDownloading,
                            downloadSucceeded = downloadSucceeded,
                            downloadErrorMessage = downloadErrorMessage,
                            onClick = { onCardClick(item.event) },
                            onClipBuffering = onClipBuffering,
                            onClipError = onClipError,
                            onDownloadClick = { onDownloadClick(item.event) },
                            onFullScreenClick = { onFullScreenClick(item.event) },
                        )
                    }
                }
                item(key = "feed-end", contentType = "feed-end") {
                    FeedEnd(hasOlder = state.hasOlder, loadingOlder = state.loadingOlder, onLoadOlder = onLoadOlder)
                }
            }
        }
    }
}

/**
 * Asks for the next page once the last few items are on screen, so the feed reads as endless
 * rather than stopping at a button. Re-evaluated as pages land: a page whose cards the current
 * filter hides leaves the list where it was, still near the end, and the next is fetched — the
 * chip's answer to "any animals last week?" is to keep looking, not to say nothing.
 */
@Composable
private fun LoadOlderWhenNearTheEnd(listState: LazyListState, hasOlder: Boolean, loadingOlder: Boolean, onLoadOlder: () -> Unit) {
    val nearTheEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - LOAD_OLDER_LOOKAHEAD
        }
    }
    val loadOlder by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(nearTheEnd, hasOlder, loadingOlder) {
        if (nearTheEnd && hasOlder && !loadingOlder) loadOlder()
    }
}

/**
 * The list's last item: a spinner while the next page is on its way, a button when the reader
 * beat the lookahead to the end, and a full stop once the server has nothing older.
 */
@Composable
private fun FeedEnd(hasOlder: Boolean, loadingOlder: Boolean, onLoadOlder: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        when {
            loadingOlder -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)

            hasOlder -> TextButton(onClick = onLoadOlder) { Text("Load older moments") }

            else -> Text(
                text = "That's everything the server still has.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyMoments(
    category: MomentCategory,
    cameraLabel: String?,
    historyDayLabel: String?,
    hasError: Boolean,
    hasOlder: Boolean,
    loadingOlder: Boolean,
    onLoadOlder: () -> Unit,
) {
    // The feed is deliberately quiet: on a camera with zones, a detection only appears when it
    // happened in a zone whose movement list includes it, or when Frigate recognised who or
    // what it was. Say so, rather than looking broken.
    val kind = if (category == MomentCategory.ALL) "detections" else category.label.lowercase()
    val what = if (cameraLabel != null) "$kind on $cameraLabel" else kind
    val message = when {
        hasError -> "Couldn't reach the server for detections."

        // A window into the past that came up empty is a different fact from a quiet feed: the
        // pages fetched so far had none, and there may be more below.
        historyDayLabel != null && hasOlder -> "No $what in the most recent pages before $historyDayLabel."

        historyDayLabel != null -> "No $what on or before $historyDayLabel that the server still has."

        category != MomentCategory.ALL -> "No $what to show. Detections appear here when they happen in a zone set to watch for them, or when they're recognised."

        cameraLabel != null -> "No $what to show. Detections appear here when they happen in a zone set to watch for them, or when Frigate recognises who or what they are."

        else -> "Nothing to show yet. Detections appear here when they happen in a zone set to watch for them, or when Frigate recognises who or what they are."
    }
    Box(modifier = Modifier.fillMaxSize().padding(bottom = bottomNavClearance()), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            // A filter can empty the loaded pages without the server being out of moments: offer
            // the next page rather than leaving a wall the reader can't see past.
            when {
                loadingOlder -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                hasOlder -> TextButton(onClick = onLoadOlder) { Text("Look further back") }
            }
        }
    }
}

/**
 * The calendar at the end of the filter row: tapping it picks a day to open the feed at. Live
 * it is just the icon, the width the chips can spare; once a day is picked it names the day
 * ([dayLabel], null while live) and grows a close that returns the feed to now.
 */
@Composable
private fun HistoryChip(dayLabel: String?, onClick: () -> Unit, onClear: () -> Unit) {
    val selected = dayLabel != null
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), CircleShape)
                },
            )
            .clickable(onClick = onClick)
            .padding(start = if (selected) 14.dp else 9.dp, end = if (selected) 8.dp else 9.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CalendarMonth,
            contentDescription = if (selected) null else "Pick a day",
            tint = contentColor,
            modifier = Modifier.width(18.dp).aspectRatio(1f),
        )
        if (dayLabel != null) {
            Text(
                text = "From $dayLabel",
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Back to the latest moments",
                tint = contentColor,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onClear)
                    .padding(2.dp)
                    .width(16.dp)
                    .aspectRatio(1f),
            )
        }
    }
}

/**
 * Material's calendar, limited to today and earlier: there are no detections from tomorrow.
 * The picker works in UTC-midnight millis ([initialDayUtcMillis] included; null starts it on
 * today), so the chosen day is read back in UTC — reading it in the local zone would shift it
 * a day west of Greenwich.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
private fun MomentDayPicker(initialDayUtcMillis: Long?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }
    val todayUtcMillis = remember(today) { today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDayUtcMillis ?: todayUtcMillis,
        selectableDates = remember(todayUtcMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtcMillis

                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis ?: return@TextButton
                    onPick(Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date)
                },
                enabled = pickerState.selectedDateMillis != null,
            ) { Text("Show") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}

/**
 * Which camera the feed shows. The chip names the camera once one is picked ([selectedCameraLabel],
 * null for every camera) and fills in, so a narrowed feed never passes for the whole one.
 */
@Composable
private fun CameraFilterChip(
    cameras: List<MomentCameraOption>,
    selectedCameraName: String?,
    selectedCameraLabel: String?,
    onSelect: (String?) -> Unit,
) {
    FilterMenuChip(
        label = selectedCameraLabel ?: "All cameras",
        icon = Icons.Filled.Videocam,
        active = selectedCameraName != null,
        menuDescription = "Filter by camera",
    ) { dismiss ->
        FilterMenuItem(label = "All cameras", icon = null, checked = selectedCameraName == null) {
            dismiss()
            onSelect(null)
        }
        cameras.forEach { camera ->
            FilterMenuItem(label = camera.displayName, icon = Icons.Filled.Videocam, checked = camera.name == selectedCameraName) {
                dismiss()
                onSelect(camera.name)
            }
        }
    }
}

/** What kind of detection the feed shows: everything, or just people, vehicles or animals. */
@Composable
private fun CategoryFilterChip(selected: MomentCategory, onSelect: (MomentCategory) -> Unit) {
    FilterMenuChip(
        label = selected.label,
        icon = selected.icon,
        active = selected != MomentCategory.ALL,
        menuDescription = "Filter by type",
    ) { dismiss ->
        MomentCategory.entries.forEach { category ->
            FilterMenuItem(label = category.label, icon = category.icon, checked = category == selected) {
                dismiss()
                onSelect(category)
            }
        }
    }
}

/**
 * A pill that opens a menu of one filter's options: it shows the option in force, filled when
 * that option narrows the feed ([active]) and outlined when it doesn't. [menu] gets a dismiss to
 * call as an item is picked.
 */
@Composable
private fun FilterMenuChip(
    label: String,
    icon: ImageVector?,
    active: Boolean,
    menuDescription: String,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .then(
                    if (active) {
                        Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), CircleShape)
                    },
                )
                .clickable(onClickLabel = menuDescription) { expanded = true }
                .padding(start = if (icon != null) 12.dp else 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.width(18.dp).aspectRatio(1f),
                )
            }
            // A camera name too long for a line gives way to an ellipsis, keeping the arrow in view.
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.width(18.dp).aspectRatio(1f),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            // The hairline the feed's cards carry, as the camera screen's overflow menu does.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
        ) {
            menu { expanded = false }
        }
    }
}

/** One option in a filter's menu, ticked when it's the one in force. */
@Composable
private fun FilterMenuItem(label: String, icon: ImageVector?, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = icon?.let {
            { Icon(imageVector = it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        trailingIcon = if (checked) {
            { Icon(imageVector = Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        onClick = onClick,
    )
}

@Composable
private fun MomentDateHeader(dateGroup: String, dateSubLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = dateGroup, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = dateSubLabel.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MomentCard(
    item: MomentItem,
    expanded: Boolean,
    clipRequest: PlayerRequest?,
    clipPosterUrl: String?,
    clipError: String?,
    clipBuffering: Boolean,
    isDownloading: Boolean,
    downloadSucceeded: Boolean,
    downloadErrorMessage: String?,
    onClick: () -> Unit,
    onClipBuffering: (Boolean) -> Unit,
    onClipError: () -> Unit,
    onDownloadClick: () -> Unit,
    onFullScreenClick: () -> Unit,
) {
    val extraColors = LocalFrigateExtraColors.current
    val event = item.event
    val p = item.presentation
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                MomentThumbnail(url = item.thumbnailUrl)
                if (event.hasClip) {
                    // The play icon has the frame to itself. The download used to sit in the
                    // top-right corner here, close enough on a 120dp-wide thumbnail to read as
                    // one cluster with it; it now lives on the details' bottom row.
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play clip",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp).background(extraColors.glassFill, CircleShape).padding(4.dp),
                    )
                }
                if (p.durationLabel != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(extraColors.glassFill, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(12.dp).aspectRatio(1f),
                        )
                        Text(text = p.durationLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                } else if (event.isInProgress) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(extraColors.glassFill, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PulsingDot(color = MaterialTheme.colorScheme.error, size = 6.dp, pulsing = true)
                        Text(text = "LIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = p.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = p.timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = p.locationLabel,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    p.sightingsLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                        )
                    }
                }
                // The badge and the clip's download share the card's bottom line, at opposite
                // ends, where the download has room of its own instead of crowding the thumbnail.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = p.badgeLabel.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (event.hasClip) {
                        DownloadButton(
                            isDownloading = isDownloading,
                            succeeded = downloadSucceeded,
                            errorMessage = downloadErrorMessage,
                            onClick = onDownloadClick,
                        )
                    }
                }
            }
        }

        // The clip opens beneath the card rather than navigating away, so the feed stays in place.
        AnimatedVisibility(visible = expanded) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                // Error is checked before the player: an error that lands after the request was set
                // must win, or it would be masked behind a player that never paints a frame.
                when {
                    clipError != null -> Text(clipError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)

                    clipRequest != null -> {
                        // Keyed by event so collapsing and reopening, or scrolling the card away and
                        // back, rebinds to the same player (paused where it was) instead of reloading.
                        CameraStreamPlayer(
                            request = clipRequest,
                            modifier = Modifier.fillMaxSize(),
                            playerKey = "event:${event.id}",
                            onPositionChanged = {},
                            onBufferingChanged = onClipBuffering,
                            onPlaybackEnded = {},
                            onPlaybackError = onClipError,
                        )
                        // Drawn after the player so it sits on top of the poster, which stays until the first frame.
                        if (clipBuffering) CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }

                    else -> {
                        // The clip URL is still being resolved, but the frame at the event's start is
                        // already known — show it now so the box never opens empty.
                        if (clipPosterUrl != null) {
                            // FillBounds to match the player's own poster and video (see CameraStreamPlayer),
                            // so the hand-over to the player doesn't shift the picture.
                            AsyncImage(
                                model = clipPosterUrl,
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }

                // Last in the box so it sits over the player, and offered even while the clip is
                // still loading or failed: the detail screen plays the camera's recording from
                // this moment, which is a way through when the event's own clip won't play.
                FullScreenButton(
                    onClick = onFullScreenClick,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
        }
    }
}

/** Leaves the card-sized player for the camera's detail screen, at this moment's place in the recording. */
@Composable
private fun FullScreenButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extraColors = LocalFrigateExtraColors.current
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(extraColors.glassFill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Fullscreen,
            contentDescription = "Play full screen",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The card's download control: idle download icon, a spinner while in flight, then a brief check or error flash. */
@Composable
private fun DownloadButton(
    isDownloading: Boolean,
    succeeded: Boolean,
    errorMessage: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            // On the card's own surface now rather than over the thumbnail, so it takes the
            // badge's fill beside it instead of the glass used for overlays on video.
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !isDownloading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isDownloading -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            succeeded -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )

            errorMessage != null -> Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = "Download failed: $errorMessage",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )

            else -> Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = "Download clip",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Coil goes through the app's shared Ktor client (see App.kt), which carries Frigate's session
 * cookie — that is what lets this hit the authenticated thumbnail endpoint.
 */
@Composable
private fun MomentThumbnail(url: String?) {
    if (url == null) {
        Icon(
            imageVector = Icons.Filled.Videocam,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.width(32.dp).aspectRatio(1f),
        )
        return
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}
