package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.meticulouscreations.homesafe.domain.model.VisitKind
import com.meticulouscreations.homesafe.domain.model.shortLabel
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.DownloadUiState
import com.meticulouscreations.homesafe.viewmodel.MomentCameraOption
import com.meticulouscreations.homesafe.viewmodel.MomentCarTagViewModel
import com.meticulouscreations.homesafe.viewmodel.MomentItem
import com.meticulouscreations.homesafe.viewmodel.MomentsUiState
import com.meticulouscreations.homesafe.viewmodel.MomentsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** How many of the list's last items may be on screen before the next page is asked for. */
private const val LOAD_OLDER_LOOKAHEAD = 4

/** How long after an entry opens it is followed as it grows (see [KeepGrowingEntryInView]): the length of the unfold, with room to spare. */
private const val REVEAL_WINDOW_MS = 700L

/**
 * The moment card's preview frame. Narrow enough that a card's title and its time and place
 * still fit beside it on a phone, and tall enough — it fills the card — that the
 * frame reads as a scene rather than a letterboxed strip.
 */
private val THUMBNAIL_WIDTH = 128.dp
private val THUMBNAIL_MIN_HEIGHT = 120.dp

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
    val tagViewModel: MomentCarTagViewModel = metroViewModel()
    val tagState by tagViewModel.uiState.collectAsStateWithLifecycle()

    TagCarDialog(
        state = tagState,
        onTag = tagViewModel::tag,
        onNewCarDraftChange = tagViewModel::setNewCarDraft,
        onTagAsNewCar = tagViewModel::tagAsNewCar,
        onRetry = tagViewModel::retry,
        onDismiss = tagViewModel::dismiss,
    )
    MomentsFeed(
        state = state,
        downloadState = downloadState,
        onSelectCategory = viewModel::selectCategory,
        onUnfamiliarOnlyChange = viewModel::setUnfamiliarOnly,
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
        onTagCar = tagViewModel::open,
    )
}

/**
 * The tab with its state hoisted: what [MomentsTabContent] draws once it has read the view
 * model. Nothing here reaches for a graph, so it can be previewed and tested from fixtures.
 * The only state it keeps is whether the day picker or a filter's menu is up, and which folded
 * entries have their clips listed — facts about this composition, not about the feed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MomentsFeed(
    state: MomentsUiState,
    downloadState: DownloadUiState,
    onSelectCategory: (MomentCategory) -> Unit,
    onUnfamiliarOnlyChange: (Boolean) -> Unit,
    onSelectCamera: (String?) -> Unit,
    onShowDay: (LocalDate?) -> Unit,
    onLoadOlder: () -> Unit,
    onCardClick: (MomentEvent) -> Unit,
    onClipBuffering: (Boolean) -> Unit,
    onClipError: () -> Unit,
    onDownloadClick: (MomentEvent) -> Unit,
    onFullScreenClick: (MomentEvent) -> Unit,
    modifier: Modifier = Modifier,
    onTagCar: (MomentEvent) -> Unit = {},
) {
    var pickingDay by remember { mutableStateOf(false) }
    // Keyed by MomentItem.key, which holds while a visit grows newer clips.
    var openEntries by remember { mutableStateOf(emptySet<String>()) }
    var justOpenedEntry by remember { mutableStateOf<String?>(null) }

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
            CategoryFilterChip(
                selected = state.selectedCategory,
                unfamiliarOnly = state.unfamiliarOnly,
                onSelect = onSelectCategory,
                onUnfamiliarOnlyChange = onUnfamiliarOnlyChange,
            )
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
                unfamiliarOnly = state.unfamiliarOnly,
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
            // Whichever entry just grew — a clip opened, or a visit's clips listed — is scrolled
            // up out from under the floating nav, rather than growing where nobody can see it.
            val playingEntry = state.expandedEventId?.let { id -> state.groups.firstNotNullOfOrNull { g -> g.items.firstOrNull { it.plays(id) }?.key } }
            KeepGrowingEntryInView(listState, trigger = state.expandedEventId, entryKey = playingEntry)
            KeepGrowingEntryInView(listState, trigger = justOpenedEntry, entryKey = justOpenedEntry)
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
                    items(group.items, key = { it.key }, contentType = { if (it.kind == VisitKind.ROUTINE) "routine-row" else "moment-card" }) { item ->
                        // The clip open in this entry, if the one playing is any of its detections.
                        val playing = state.expandedEventId?.let { id -> item.eventFor(id) }
                        val clipsOpen = item.key in openEntries
                        val onToggleClips = {
                            openEntries = if (clipsOpen) openEntries - item.key else openEntries + item.key
                            justOpenedEntry = if (clipsOpen) null else item.key
                        }
                        val player = ClipPlayerState(
                            playingEventId = playing?.id,
                            request = if (playing != null) state.clipRequest else null,
                            posterUrl = if (playing != null) state.clipPosterUrl else null,
                            error = if (playing != null) state.clipError else null,
                            buffering = playing != null && state.clipBuffering,
                        )
                        if (item.kind == VisitKind.ROUTINE) {
                            RoutineRow(
                                item = item,
                                clipsOpen = clipsOpen,
                                player = player,
                                onToggleClips = onToggleClips,
                                onPlay = onCardClick,
                                onClipBuffering = onClipBuffering,
                                onClipError = onClipError,
                                onFullScreenClick = { playing?.let(onFullScreenClick) },
                                onTagCar = onTagCar,
                            )
                        } else {
                            val isDownloading = downloadState.downloadingEventId == item.event.id
                            val downloadSucceeded = downloadState.resultEventId == item.event.id && downloadState.resultError == null
                            val downloadErrorMessage = downloadState.resultError.takeIf { downloadState.resultEventId == item.event.id }
                            MomentCard(
                                item = item,
                                clipsOpen = clipsOpen,
                                player = player,
                                isDownloading = isDownloading,
                                downloadSucceeded = downloadSucceeded,
                                downloadErrorMessage = downloadErrorMessage,
                                onPlay = onCardClick,
                                onToggleClips = onToggleClips,
                                onClipBuffering = onClipBuffering,
                                onClipError = onClipError,
                                onDownloadClick = { onDownloadClick(item.event) },
                                onFullScreenClick = { playing?.let(onFullScreenClick) },
                                onTagCar = onTagCar,
                            )
                        }
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
    unfamiliarOnly: Boolean,
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
    val kind = (if (unfamiliarOnly) "unfamiliar " else "") + if (category == MomentCategory.ALL) "detections" else category.label.lowercase()
    val what = if (cameraLabel != null) "$kind on $cameraLabel" else kind
    val message = when {
        hasError -> "Couldn't reach the server for detections."

        // A window into the past that came up empty is a different fact from a quiet feed: the
        // pages fetched so far had none, and there may be more below.
        historyDayLabel != null && hasOlder -> "No $what in the most recent pages before $historyDayLabel."

        historyDayLabel != null -> "No $what on or before $historyDayLabel that the server still has."

        category != MomentCategory.ALL || unfamiliarOnly -> "No $what to show. Detections appear here when they happen in a zone set to watch for them, or when they're recognised."

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

/**
 * What kind of detection the feed shows: everything, or just people, vehicles or animals — and,
 * beneath them, whether to leave out what Frigate recognised. "Unfamiliar only" is a switch that
 * sits alongside the type rather than one more type, so "unfamiliar people" is one pick away, and
 * the chip names both ("Unfamiliar people") so the narrowing never goes unseen.
 */
@Composable
private fun CategoryFilterChip(
    selected: MomentCategory,
    unfamiliarOnly: Boolean,
    onSelect: (MomentCategory) -> Unit,
    onUnfamiliarOnlyChange: (Boolean) -> Unit,
) {
    val label = when {
        !unfamiliarOnly -> selected.label
        selected == MomentCategory.ALL -> "Unfamiliar"
        else -> "Unfamiliar ${selected.label.lowercase()}"
    }
    FilterMenuChip(
        label = label,
        icon = selected.icon ?: Icons.Filled.PersonSearch.takeIf { unfamiliarOnly },
        active = selected != MomentCategory.ALL || unfamiliarOnly,
        menuDescription = "Filter by type",
    ) { dismiss ->
        MomentCategory.entries.forEach { category ->
            FilterMenuItem(label = category.label, icon = category.icon, checked = category == selected) {
                dismiss()
                onSelect(category)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        FilterMenuItem(label = "Unfamiliar only", icon = Icons.Filled.PersonSearch, checked = unfamiliarOnly) {
            dismiss()
            onUnfamiliarOnlyChange(!unfamiliarOnly)
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

/** Whether [eventId] is this entry's detection or one of its clips. */
private fun MomentItem.plays(eventId: String): Boolean = eventFor(eventId) != null

/** This entry's detection with [eventId], its lead or one of its clips; null when it holds none. */
private fun MomentItem.eventFor(eventId: String): MomentEvent? =
    if (event.id == eventId) event else clips.firstOrNull { it.event.id == eventId }?.event

/**
 * The inline player's side of [MomentsUiState], handed only to the entry whose detection is
 * playing: every other entry gets the idle one, so opening a clip recomposes one card, not the feed.
 */
@Immutable
private data class ClipPlayerState(
    /** The detection playing in this entry; null when none of its detections is. */
    val playingEventId: String? = null,
    val request: PlayerRequest? = null,
    val posterUrl: String? = null,
    val error: String? = null,
    val buffering: Boolean = false,
)

/**
 * Scrolls the entry [entryKey] up as it grows, until its bottom clears the floating nav, for a
 * moment after [trigger] changes. Opening the clip of the last card on screen used to unfold the
 * player beneath the nav, where it played unseen; this follows the expansion as it animates. It
 * never lifts the entry's top above the list's, and gives way to the reader's own scrolling.
 */
@Composable
private fun KeepGrowingEntryInView(listState: LazyListState, trigger: String?, entryKey: String?) {
    LaunchedEffect(trigger) {
        if (trigger == null || entryKey == null) return@LaunchedEffect
        withTimeoutOrNull(REVEAL_WINDOW_MS) {
            snapshotFlow {
                val info = listState.layoutInfo
                val entry = info.visibleItemsInfo.firstOrNull { it.key == entryKey } ?: return@snapshotFlow 0
                val overflow = entry.offset + entry.size - (info.viewportEndOffset - info.afterContentPadding)
                overflow.coerceAtMost(entry.offset).coerceAtLeast(0)
            }.collect { overflow -> if (overflow > 0) listState.scrollBy(overflow.toFloat()) }
        }
    }
}

/**
 * A detection, or a visit of several, as a card: the thumbnail beside what, when and where, and
 * the whole top of the card plays the clip — the thumbnail's play icon is only the hint. A visit
 * adds its clip count, which opens a list of the clips so any one of them can be played.
 */
@Composable
private fun MomentCard(
    item: MomentItem,
    clipsOpen: Boolean,
    player: ClipPlayerState,
    isDownloading: Boolean,
    downloadSucceeded: Boolean,
    downloadErrorMessage: String?,
    onPlay: (MomentEvent) -> Unit,
    onToggleClips: () -> Unit,
    onClipBuffering: (Boolean) -> Unit,
    onClipError: () -> Unit,
    onDownloadClick: () -> Unit,
    onFullScreenClick: () -> Unit,
    onTagCar: (MomentEvent) -> Unit,
) {
    val extraColors = LocalFrigateExtraColors.current
    val event = item.event
    val p = item.presentation
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
    ) {
        // Intrinsic height so the preview can fill whatever the details beside it come to:
        // a 16:9 band left the frame barely half the card's height, and squeezing Frigate's
        // roughly square object thumbnail into it cropped the detection down to a sliver.
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .clickable(
                    enabled = event.hasClip,
                    onClickLabel = if (player.playingEventId == event.id) "Close clip" else "Play clip",
                ) { onPlay(event) },
        ) {
            Box(
                modifier = Modifier
                    .width(THUMBNAIL_WIDTH)
                    // The floor is for the shortest card (no second line of detail); every
                    // other card is taller than this and the frame grows with it.
                    .defaultMinSize(minHeight = THUMBNAIL_MIN_HEIGHT)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                MomentThumbnail(url = item.thumbnailUrl)
                if (event.hasClip) {
                    // The play icon has the frame to itself. The download used to sit in the
                    // top-right corner here, close enough on a thumbnail this narrow to read as
                    // one cluster with it; it now lives on the details' bottom row.
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp).background(extraColors.glassFill, CircleShape).padding(4.dp),
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
                    // The title has the width to itself: the time used to share its line and cut
                    // a car's name down to "Andrew's Tesla on the…". The time leads the line below
                    // instead, where a long place name gives way to it rather than the other way round.
                    Text(
                        text = p.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${p.timeLabel} · ${p.locationLabel}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    p.sightingsLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                        )
                    }
                    p.clipCountLabel?.let { ClipsToggle(label = it, open = clipsOpen, onClick = onToggleClips) }
                }
                // The badge and the clip's download share the card's bottom line, at opposite
                // ends, where the download has room of its own instead of crowding the thumbnail.
                // An unnamed car's tag follows the badge that calls it just "car".
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        if (item.canTagCar) TagCarButton(onClick = { onTagCar(event) })
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

        if (item.clips.isNotEmpty()) {
            AnimatedVisibility(visible = clipsOpen) {
                MomentClipList(item = item, playingEventId = player.playingEventId, onPlay = onPlay, onTagCar = onTagCar)
            }
        }

        // The clip opens beneath the card rather than navigating away, so the feed stays in place.
        AnimatedVisibility(visible = player.playingEventId != null) {
            InlineClipPlayer(player = player, onClipBuffering = onClipBuffering, onClipError = onClipError, onFullScreenClick = onFullScreenClick)
        }
    }
}

/**
 * A household car's comings and goings, folded into one quiet row: "Andrew's Tesla came and went
 * 6×", the span and the cameras. No thumbnail and no badge — the family's own car doing what it
 * does every day is the least of the feed's news. A tap lists the sightings, any of which plays.
 */
@Composable
private fun RoutineRow(
    item: MomentItem,
    clipsOpen: Boolean,
    player: ClipPlayerState,
    onToggleClips: () -> Unit,
    onPlay: (MomentEvent) -> Unit,
    onClipBuffering: (Boolean) -> Unit,
    onClipError: () -> Unit,
    onFullScreenClick: () -> Unit,
    onTagCar: (MomentEvent) -> Unit,
) {
    val p = item.presentation
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (clipsOpen) "Hide sightings" else "Show sightings", onClick = onToggleClips)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DirectionsCar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = p.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${p.timeLabel} · ${p.locationLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (clipsOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(visible = clipsOpen) {
            MomentClipList(item = item, playingEventId = player.playingEventId, onPlay = onPlay, onTagCar = onTagCar)
        }
        AnimatedVisibility(visible = player.playingEventId != null) {
            InlineClipPlayer(player = player, onClipBuffering = onClipBuffering, onClipError = onClipError, onFullScreenClick = onFullScreenClick)
        }
    }
}

/** "5 clips ▾": what a visit folds together, and the way to list them. */
@Composable
private fun ClipsToggle(label: String, open: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClickLabel = if (open) "Hide clips" else "Show clips", onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        Icon(
            imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * A folded entry's detections, oldest first, one row each; a tap plays that one, the one playing is
 * marked. A clip of a car nobody named can be tagged from its row: a visit can hold several cars.
 */
@Composable
private fun MomentClipList(item: MomentItem, playingEventId: String?, onPlay: (MomentEvent) -> Unit, onTagCar: (MomentEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        item.clips.forEach { clip ->
            val playing = clip.event.id == playingEventId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = clip.event.hasClip, onClickLabel = if (playing) "Close clip" else "Play clip") { onPlay(clip.event) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (playing) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(text = clip.timeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = clip.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = clip.durationLabel ?: "LIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (clip.canTagCar) {
                    Icon(
                        imageVector = Icons.Filled.Sell,
                        contentDescription = "Tag this car",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onTagCar(clip.event) }
                            .padding(4.dp)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

/** The card-width player a card opens beneath itself, with the way out to the full-width one. */
@Composable
private fun InlineClipPlayer(player: ClipPlayerState, onClipBuffering: (Boolean) -> Unit, onClipError: () -> Unit, onFullScreenClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        // Error is checked before the player: an error that lands after the request was set
        // must win, or it would be masked behind a player that never paints a frame.
        when {
            player.error != null -> Text(player.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)

            player.request != null -> {
                // Keyed by event so collapsing and reopening, or scrolling the card away and
                // back, rebinds to the same player (paused where it was) instead of reloading.
                CameraStreamPlayer(
                    request = player.request,
                    modifier = Modifier.fillMaxSize(),
                    playerKey = "event:${player.playingEventId}",
                    onPositionChanged = {},
                    onBufferingChanged = onClipBuffering,
                    onPlaybackEnded = {},
                    onPlaybackError = onClipError,
                )
                // Drawn after the player so it sits on top of the poster, which stays until the first frame.
                if (player.buffering) CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }

            else -> {
                // The clip URL is still being resolved, but the frame at the event's start is
                // already known — show it now so the box never opens empty.
                if (player.posterUrl != null) {
                    // FillBounds to match the player's own poster and video (see CameraStreamPlayer),
                    // so the hand-over to the player doesn't shift the picture.
                    AsyncImage(
                        model = player.posterUrl,
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
 *
 * A BoxScope so the image can take the frame with matchParentSize rather than fillMaxSize: the
 * frame's height is the card's intrinsic height, and a child sized to the fetched bitmap would
 * be measured into that — a large thumbnail would then stretch the card to its pixel height.
 * matchParentSize is measured against the frame after the frame is sized, so it cannot.
 */
@Composable
private fun BoxScope.MomentThumbnail(url: String?) {
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
        modifier = Modifier.matchParentSize(),
    )
}
