package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.model.StationaryObjectPresentation
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.InViewItem
import com.meticulouscreations.homesafe.viewmodel.InViewViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.home_in_view_check
import homesafe.shared.generated.resources.home_in_view_empty
import homesafe.shared.generated.resources.home_in_view_subtitle
import homesafe.shared.generated.resources.home_in_view_title
import org.jetbrains.compose.resources.stringResource

/**
 * The In View page, from a car's pill on Home: every household car standing in view of a camera,
 * grouped by the camera that sees it, with what Home's pills leave out. A car opens its
 * camera ([onOpenCamera]); each camera's "Check and tag" opens its frame to put the cars right
 * ([onTagCars], see [CarTaggingScreen]).
 */
@Composable
fun InViewScreen(onBack: () -> Unit, onOpenCamera: (cameraName: String) -> Unit, onTagCars: (cameraName: String) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: InViewViewModel = metroViewModel()
    val items by viewModel.items.collectAsStateWithLifecycle()
    SettingsPage(
        title = stringResource(Res.string.home_in_view_title),
        subtitle = stringResource(Res.string.home_in_view_subtitle),
        onBack = onBack,
        modifier = modifier,
        onRefresh = viewModel::refresh,
    ) {
        InViewContent(items = items, onOpenCamera = onOpenCamera, onTagCars = onTagCars)
    }
}

/** [items] by camera, in the order their cameras first appear; a spinner while [items] is null. */
@Composable
internal fun InViewContent(items: List<InViewItem>?, onOpenCamera: (cameraName: String) -> Unit, onTagCars: (cameraName: String) -> Unit) {
    when {
        items == null -> Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        items.isEmpty() -> SettingsCard { SettingsCaption(stringResource(Res.string.home_in_view_empty)) }

        else -> items.groupBy { it.subject.cameraName }.forEach { (cameraName, cars) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = cameraDisplayName(cameraName),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onTagCars(cameraName) }, modifier = Modifier.testTag(IN_VIEW_CHECK_TEST_TAG)) {
                        Text(stringResource(Res.string.home_in_view_check))
                    }
                }
                cars.forEach { item -> InViewCard(item = item, onClick = { onOpenCamera(cameraName) }) }
            }
        }
    }
}

/**
 * One parked vehicle: the crop Frigate cut of its most recent sighting, what the classifier calls
 * it, and where it is. The second line is where and since when ("Driveway · since 8:12 AM"); the
 * arrival is left off when the app only ever saw the car already parked, rather than guessing one.
 * A third line appears only once the sighting has gone stale, because a card that says nothing
 * about time is claiming the camera can see the car right now.
 */
@Composable
private fun InViewCard(item: InViewItem, onClick: () -> Unit) {
    val extraColors = LocalFrigateExtraColors.current
    val presentation = item.presentation
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 112.dp, height = 72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            // Coil rides the app's authenticated Ktor client, which is what lets this hit Frigate's thumbnail endpoint.
            if (item.thumbnailUrl != null) {
                AsyncImage(model = item.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(presentation.title.resolve(), style = MaterialTheme.typography.titleMedium, color = extraColors.textPrimary, maxLines = 1)
            Text(
                text = listOfNotNull(presentation.placeLabel, presentation.sinceLabel?.resolve()).joinToString(stringResource(Res.string.common_dot_separator)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            presentation.lastSeenLabel?.let { lastSeen ->
                Text(lastSeen.resolve(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

/** A camera's "Check and tag" on the In View page, which opens the car-tagging screen. */
internal const val IN_VIEW_CHECK_TEST_TAG = "in_view_check"

private fun previewCar(cameraName: String, subLabel: String, title: String, place: String, since: String?, lastSeen: String? = null) = InViewItem(
    subject = StationaryObject(
        thumbnailEventId = "sighting-$subLabel",
        cameraName = cameraName,
        label = "car",
        subLabel = subLabel,
        zones = emptyList(),
        firstSeenEpochSeconds = 1_789_400_000.0,
        lastSeenEpochSeconds = 1_789_408_700.0,
        seenRecently = lastSeen == null,
        sightings = 4,
        sinceIsKnown = since != null,
    ),
    presentation = StationaryObjectPresentation(title = title.asUiText(), placeLabel = place, sinceLabel = since?.asUiText(), lastSeenLabel = lastSeen?.asUiText()),
    thumbnailUrl = null,
)

/** Two cars in the driveway on the Front Yard camera, and one on the street out back that the camera stopped seeing a while ago. */
@Preview(name = "In view now", widthDp = 412, heightDp = 915)
@Composable
private fun InViewPreview() {
    FrigatePreview {
        SettingsPage(title = stringResource(Res.string.home_in_view_title), subtitle = stringResource(Res.string.home_in_view_subtitle), onBack = {}, onRefresh = {}) {
            InViewContent(
                items = listOf(
                    previewCar("hikvision_1", "sarahs_tesla", "Sarah's Tesla", "Driveway", "since 3:33 PM"),
                    previewCar("hikvision_1", "andrews_tesla", "Andrew's Tesla", "Driveway", "since 8:12 AM"),
                    previewCar("hikvision_2", "ron_judys_mercedes", "Ron and Judy's Mercedes", "Alley", "since 9:02 AM", lastSeen = "last seen 4:51 PM"),
                ),
                onOpenCamera = {},
                onTagCars = {},
            )
        }
    }
}
