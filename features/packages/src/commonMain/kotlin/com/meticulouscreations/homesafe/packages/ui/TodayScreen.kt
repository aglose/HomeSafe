package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.packages.PackagesUiState
import com.meticulouscreations.homesafe.packages.domain.Parcel
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_retry
import homesafe.shared.generated.resources.packages_empty_body
import homesafe.shared.generated.resources.packages_empty_title
import homesafe.shared.generated.resources.packages_hero_away
import homesafe.shared.generated.resources.packages_hero_next
import homesafe.shared.generated.resources.packages_hero_nothing
import homesafe.shared.generated.resources.packages_hero_on_way
import homesafe.shared.generated.resources.packages_hero_porch
import homesafe.shared.generated.resources.packages_hero_today
import homesafe.shared.generated.resources.packages_no_mail_body
import homesafe.shared.generated.resources.packages_no_mail_title
import homesafe.shared.generated.resources.packages_peek_description
import homesafe.shared.generated.resources.packages_peek_label
import homesafe.shared.generated.resources.packages_peek_refresh
import homesafe.shared.generated.resources.packages_section_arrived
import homesafe.shared.generated.resources.packages_section_attention
import homesafe.shared.generated.resources.packages_section_later
import homesafe.shared.generated.resources.packages_section_porch
import homesafe.shared.generated.resources.packages_section_today
import homesafe.shared.generated.resources.packages_section_tomorrow
import homesafe.shared.generated.resources.packages_title_add
import homesafe.shared.generated.resources.packages_whose_body
import homesafe.shared.generated.resources.packages_whose_title
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What's on its way, what needs someone, and what's at the door, with a box under the porch light at the head of it. */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun TodayScreen(state: PackagesUiState, padding: PaddingValues, active: Boolean, actions: PackagesActions, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val board = state.board
    val snapshot = state.snapshot
    val colors = PackagesTheme.colors
    val today = snapshot.today
    val zone = state.zone
    // A parcel that has just landed, since this page was first seen: for the box's thump and the scraps.
    var seenDelivered by remember { mutableStateOf<Set<String>?>(null) }
    val deliveredNow = remember(snapshot.parcels) { snapshot.parcels.filter { it.delivered }.mapTo(HashSet()) { it.id } }
    val newlyLanded = seenDelivered?.let { (deliveredNow - it).firstOrNull() }
    LaunchedEffect(deliveredNow) {
        if (seenDelivered != null) delay(2200)
        seenDelivered = deliveredNow
    }
    // Only a pull shows the spinner: the list is asked for again every minute without anyone asking.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.sync.syncing) { if (!state.sync.syncing) pulled = false }
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = pulled,
            onRefresh = {
                pulled = true
                actions.onRefresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("packages_today"), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                item(key = "hero") { TodayHero(state, active, newlyLanded != null) }
                if (snapshot.viewer == null && snapshot.people.isNotEmpty()) {
                    item(key = "whose") { WhosePhoneCard(snapshot.people, actions.onSetPhoneOwner, Modifier.padding(horizontal = 16.dp)) }
                }
                // What is shown is what the relay last said: when it can't be asked, say so, or an old list reads as today's.
                state.sync.message?.let { trouble ->
                    item(key = "trouble") {
                        PackagesCard(Modifier.padding(horizontal = 16.dp).testTag("packages_trouble")) {
                            Text(trouble.resolve(), style = PackagesTheme.type.body, color = colors.warn)
                            PackagesButton(stringResource(Res.string.common_retry), actions.onRefresh, Modifier.padding(top = 10.dp), primary = false)
                        }
                    }
                }
                if (!snapshot.mailOn) {
                    item(key = "no_mail") {
                        PackagesCard(Modifier.padding(horizontal = 16.dp)) {
                            Text(stringResource(Res.string.packages_no_mail_title), style = PackagesTheme.type.headline, color = colors.text)
                            Text(stringResource(Res.string.packages_no_mail_body), style = PackagesTheme.type.body, color = colors.textMuted, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                fun section(key: String, title: @Composable () -> String, list: List<Parcel>, tint: @Composable () -> Color, route: Boolean = true) {
                    if (list.isEmpty()) return
                    item(key = "head_$key") { SectionHead(title(), list.size, tint(), Modifier.padding(horizontal = 16.dp)) }
                    items(list, key = { "$key${it.id}" }) { parcel ->
                        ParcelCard(parcel, today, zone, active, onOpen, Modifier.padding(horizontal = 16.dp).animateItem(), index = list.indexOf(parcel), showRoute = route && !parcel.delivered)
                    }
                }
                section("attention", { stringResource(Res.string.packages_section_attention) }, board.attention, { colors.warn })
                if (board.onPorch.any { !it.masked } && snapshot.cameraOn) {
                    item(key = "porch_peek") { PorchPeek(snapshot.cameraName, active, actions, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
                }
                section("porch", { stringResource(Res.string.packages_section_porch) }, board.onPorch, { colors.tape }, route = false)
                section("today", { stringResource(Res.string.packages_section_today) }, board.today, { colors.tape })
                section("tomorrow", { stringResource(Res.string.packages_section_tomorrow) }, board.tomorrow, { colors.onTheWay })
                section("later", { stringResource(Res.string.packages_section_later) }, board.later, { colors.kraft })
                section("arrived", { stringResource(Res.string.packages_section_arrived) }, board.arrived, { colors.good }, route = false)
                if (board.isEmpty) {
                    item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(Res.string.packages_empty_title), style = PackagesTheme.type.headline, color = colors.text)
                            Text(stringResource(Res.string.packages_empty_body), style = PackagesTheme.type.body, color = colors.textMuted, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                            PackagesButton(stringResource(Res.string.packages_title_add), onAdd, primary = false)
                        }
                    }
                }
            }
        }
        CelebrationBurst(newlyLanded, Modifier.fillMaxSize())
    }
}

@Composable
private fun TodayHero(state: PackagesUiState, active: Boolean, landing: Boolean) {
    val colors = PackagesTheme.colors
    val board = state.board
    val headline = when {
        board.onPorch.isNotEmpty() -> pluralStringResource(Res.plurals.packages_hero_porch, board.onPorch.size, rememberCounted(board.onPorch.size))
        board.today.isNotEmpty() -> pluralStringResource(Res.plurals.packages_hero_today, board.today.size, rememberCounted(board.today.size))
        board.onTheWay > 0 -> pluralStringResource(Res.plurals.packages_hero_on_way, board.onTheWay, rememberCounted(board.onTheWay))
        else -> stringResource(Res.string.packages_hero_nothing)
    }
    val next = board.today.firstOrNull() ?: board.tomorrow.firstOrNull() ?: board.later.firstOrNull()
    Box(Modifier.fillMaxWidth().height(250.dp)) {
        ParcelBox(Modifier.align(Alignment.TopEnd).padding(top = 18.dp).size(220.dp), warm = packageWarmth(state), running = active, landed = landing)
        Column(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, end = 22.dp, bottom = 14.dp)) {
            Text(headline, style = PackagesTheme.type.display, color = colors.text, modifier = Modifier.testTag("packages_headline"))
            when {
                state.snapshot.away && board.today.isNotEmpty() -> Text(stringResource(Res.string.packages_hero_away), style = PackagesTheme.type.body, color = colors.tape, modifier = Modifier.padding(top = 6.dp))

                next != null -> promiseText(next, state.snapshot.today)?.let { promise ->
                    Text(stringResource(Res.string.packages_hero_next, promise), style = PackagesTheme.type.body, color = colors.textMuted, modifier = Modifier.padding(top = 6.dp))
                }

                else -> Unit
            }
        }
    }
}

/** Whose phone this is, which decides whose surprises it may be told about. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WhosePhoneCard(people: List<String>, onPick: (String?) -> Unit, modifier: Modifier = Modifier) {
    val colors = PackagesTheme.colors
    PackagesCard(modifier) {
        Text(stringResource(Res.string.packages_whose_title), style = PackagesTheme.type.headline, color = colors.text)
        Text(stringResource(Res.string.packages_whose_body), style = PackagesTheme.type.body, color = colors.textMuted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            people.forEach { person -> PackagesChoice(PackagesFormat.person(person), selected = false, onClick = { onPick(person) }) }
        }
    }
}

/** The landing as the door camera sees it this moment, asked for again every so often while it's up. */
@Composable
private fun PorchPeek(cameraName: String, active: Boolean, actions: PackagesActions, modifier: Modifier = Modifier) {
    val colors = PackagesTheme.colors
    var bump by remember { mutableIntStateOf(0) }
    val url by produceState<String?>(null, bump) { value = actions.pictureUrl(null, "now")?.let { "$it&t=$bump" } }
    LaunchedEffect(active) {
        while (active) {
            delay(30_000)
            bump++
        }
    }
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(colors.surfaceRaised).border(1.dp, colors.hairline, shape).pressable({ bump++ }, stringResource(Res.string.packages_peek_refresh))) {
        url?.let { AsyncImage(model = it, contentDescription = stringResource(Res.string.packages_peek_description), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xA6000000))))
        Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Videocam, contentDescription = null, tint = colors.camera, modifier = Modifier.size(15.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(Res.string.packages_peek_label, cameraName), style = PackagesTheme.type.label, color = Color.White)
        }
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(16.dp))
    }
}
