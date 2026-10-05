package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.CarProfile
import com.meticulouscreations.homesafe.domain.model.CarProfiles
import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.CropBox
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
import com.meticulouscreations.homesafe.domain.model.carColourName
import com.meticulouscreations.homesafe.domain.model.checkNote
import com.meticulouscreations.homesafe.domain.model.makeName
import com.meticulouscreations.homesafe.domain.model.subLabelDisplayName
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.ClassifierLabelingUiState
import com.meticulouscreations.homesafe.viewmodel.ClassifierLabelingViewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.common_cancel
import homesafe.shared.generated.resources.common_retry
import homesafe.shared.generated.resources.common_save
import homesafe.shared.generated.resources.labeling_add
import homesafe.shared.generated.resources.labeling_car_describe
import homesafe.shared.generated.resources.labeling_car_edit
import homesafe.shared.generated.resources.labeling_car_looks_and_plate
import homesafe.shared.generated.resources.labeling_car_not_described
import homesafe.shared.generated.resources.labeling_car_plate_on_file
import homesafe.shared.generated.resources.labeling_cars_body
import homesafe.shared.generated.resources.labeling_cars_title
import homesafe.shared.generated.resources.labeling_categories_title
import homesafe.shared.generated.resources.labeling_category_count
import homesafe.shared.generated.resources.labeling_clear_count
import homesafe.shared.generated.resources.labeling_confident_body
import homesafe.shared.generated.resources.labeling_confident_more
import homesafe.shared.generated.resources.labeling_discard_crop
import homesafe.shared.generated.resources.labeling_edit_car_action
import homesafe.shared.generated.resources.labeling_filed_as
import homesafe.shared.generated.resources.labeling_frigate_calls_it
import homesafe.shared.generated.resources.labeling_hide
import homesafe.shared.generated.resources.labeling_model_no_guess
import homesafe.shared.generated.resources.labeling_model_thinks
import homesafe.shared.generated.resources.labeling_model_thinks_score
import homesafe.shared.generated.resources.labeling_never_trained
import homesafe.shared.generated.resources.labeling_new_category_label
import homesafe.shared.generated.resources.labeling_new_since_training
import homesafe.shared.generated.resources.labeling_not_ours
import homesafe.shared.generated.resources.labeling_not_ours_explainer
import homesafe.shared.generated.resources.labeling_objects_or_separator
import homesafe.shared.generated.resources.labeling_profile_any_make
import homesafe.shared.generated.resources.labeling_profile_colour
import homesafe.shared.generated.resources.labeling_profile_forget
import homesafe.shared.generated.resources.labeling_profile_make
import homesafe.shared.generated.resources.labeling_profile_model_label
import homesafe.shared.generated.resources.labeling_profile_plate
import homesafe.shared.generated.resources.labeling_profile_plate_hint
import homesafe.shared.generated.resources.labeling_queue_empty_body
import homesafe.shared.generated.resources.labeling_queue_empty_title
import homesafe.shared.generated.resources.labeling_queue_only_not_ours
import homesafe.shared.generated.resources.labeling_queue_waiting
import homesafe.shared.generated.resources.labeling_refresh
import homesafe.shared.generated.resources.labeling_show
import homesafe.shared.generated.resources.labeling_subtitle
import homesafe.shared.generated.resources.labeling_train
import homesafe.shared.generated.resources.labeling_train_hint
import homesafe.shared.generated.resources.labeling_train_needs_two
import homesafe.shared.generated.resources.labeling_up_to_date
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Teach a Frigate classifier by labelling what it saw. Each queued crop shows the model's own
 * guess; one tap files it under a category (or throws it away), and Train retrains on the box
 * once there's something new. This is the loop that turns "car" into "Sarah's Tesla".
 */
@Composable
fun ClassifierLabelingScreen(
    modelName: String,
    onBack: () -> Unit,
) {
    val viewModel = assistedMetroViewModel<ClassifierLabelingViewModel, ClassifierLabelingViewModel.Factory>(key = "classifier:$modelName") {
        create(modelName)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(title = subLabelDisplayName(modelName), subtitle = stringResource(Res.string.labeling_subtitle), onBack = onBack, onRefresh = viewModel::load)

        when {
            uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            uiState.loadError != null -> ErrorPanel(message = uiState.loadError?.resolve().orEmpty(), onRetry = viewModel::load)

            else -> uiState.dataset?.let { data -> Body(uiState = uiState, data = data, viewModel = viewModel) }
        }
    }

    val draft = uiState.profileDraft
    val profiles = uiState.carProfiles
    if (draft != null && profiles != null) {
        CarProfileDialog(
            draft = draft,
            makes = profiles.makes,
            colours = profiles.colours,
            saving = uiState.isSavingProfile,
            error = uiState.profileError,
            onChange = viewModel::updateProfileDraft,
            onSave = viewModel::saveProfile,
            onDismiss = viewModel::dismissProfile,
            onForget = viewModel::deleteProfile.takeIf { profiles.profiles.any { it.name == draft.name } },
        )
    }
}

@Composable
private fun Header(title: String, subtitle: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = nestedHeaderVerticalPadding()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back), tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, textAlign = TextAlign.Center)
            Text(text = subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.labeling_refresh), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Body(uiState: ClassifierLabelingUiState, data: ClassifierDataset, viewModel: ClassifierLabelingViewModel) {
    val gutter = contentGutter()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = gutter, end = gutter, bottom = bottomNavClearance()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "categories", contentType = "categories") { CategoriesCard(uiState = uiState, data = data, viewModel = viewModel) }
        uiState.carProfiles?.let { profiles ->
            item(key = "car-profiles", contentType = "car-profiles") {
                CarProfilesCard(cars = data.knownCars, profiles = profiles, onEdit = viewModel::editProfile)
            }
        }
        item(key = "train", contentType = "train") { TrainCard(uiState = uiState, data = data, onTrain = viewModel::train) }
        val uncertain = data.uncertainQueue
        val confident = data.confidentQueue
        // Walks the whole queue, so it's read once here rather than once per crop card.
        val categories = data.categories
        item(key = "queue-title", contentType = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (uncertain.isEmpty()) {
                        stringResource(Res.string.labeling_queue_empty_title)
                    } else {
                        pluralStringResource(Res.plurals.labeling_queue_waiting, uncertain.size, uncertain.size)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (categories.none { it != ClassifierDataset.NONE_CATEGORY }) {
                    Text(
                        text = stringResource(Res.string.labeling_queue_only_not_ours),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (data.queue.isEmpty()) {
            item(key = "queue-empty", contentType = "title") {
                Text(
                    text = stringResource(Res.string.labeling_queue_empty_body, objectsOr(data.model.objects)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(uncertain, key = { it.fileName }, contentType = { "crop" }) { crop ->
            CropCard(
                crop = crop,
                imageUrl = viewModel.imageUrl(crop.fileName),
                categories = categories,
                busy = crop.fileName in uiState.busyFiles,
                decided = uiState.decided[crop.fileName],
                onLabel = { viewModel.label(crop.fileName, it) },
                onDiscard = { viewModel.discard(crop.fileName) },
            )
        }
        if (confident.isNotEmpty()) {
            item(key = "confident-summary", contentType = "confident") {
                ConfidentCropsRow(
                    count = confident.size,
                    expanded = uiState.showConfident,
                    busy = confident.all { it.fileName in uiState.busyFiles },
                    onToggle = viewModel::toggleConfident,
                    onClear = viewModel::clearConfident,
                )
            }
        }
        if (uiState.showConfident) {
            items(confident, key = { it.fileName }, contentType = { "crop" }) { crop ->
                CropCard(
                    crop = crop,
                    imageUrl = viewModel.imageUrl(crop.fileName),
                    categories = categories,
                    busy = crop.fileName in uiState.busyFiles,
                    decided = uiState.decided[crop.fileName],
                    onLabel = { viewModel.label(crop.fileName, it) },
                    onDiscard = { viewModel.discard(crop.fileName) },
                )
            }
        }
    }
}

/**
 * The crops the model is 100 % sure about, folded away: mostly "Not ours" for every passing street
 * car. Show them when a new car needs naming; clear them to make room in Frigate's capped queue.
 * Without [onClear] there's only Show: the live view's crops are one per car, and clearing those
 * would only make room for that car's next crop.
 */
@Composable
internal fun ConfidentCropsRow(count: Int, expanded: Boolean, busy: Boolean, onToggle: () -> Unit, onClear: (() -> Unit)?) {
    Card {
        Text(
            text = pluralStringResource(Res.plurals.labeling_confident_more, count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.labeling_confident_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onToggle, enabled = !busy) { Text(stringResource(if (expanded) Res.string.labeling_hide else Res.string.labeling_show)) }
            if (onClear != null) {
                TextButton(onClick = onClear, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(stringResource(Res.string.labeling_clear_count, count))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoriesCard(uiState: ClassifierLabelingUiState, data: ClassifierDataset, viewModel: ClassifierLabelingViewModel) {
    Card {
        Text(text = stringResource(Res.string.labeling_categories_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            data.categories.forEach { category ->
                Chip(
                    label = stringResource(Res.string.labeling_category_count, categoryDisplayName(category), data.categoryCounts[category] ?: 0),
                    selected = false,
                    onClick = {},
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = uiState.newCategoryDraft,
                onValueChange = viewModel::setNewCategoryDraft,
                label = { Text(stringResource(Res.string.labeling_new_category_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = viewModel::createCategory, enabled = uiState.newCategoryDraft.isNotBlank()) { Text(stringResource(Res.string.labeling_add)) }
        }
        Text(
            text = stringResource(Res.string.labeling_not_ours_explainer, objectsOr(data.model.objects)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrainCard(uiState: ClassifierLabelingUiState, data: ClassifierDataset, onTrain: () -> Unit) {
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = when {
                        !data.hasTrained -> stringResource(Res.string.labeling_never_trained)

                        data.newImagesSinceTraining > 0 ->
                            pluralStringResource(Res.plurals.labeling_new_since_training, data.newImagesSinceTraining, data.newImagesSinceTraining)

                        else -> stringResource(Res.string.labeling_up_to_date)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(if (data.canTrain) Res.string.labeling_train_hint else Res.string.labeling_train_needs_two),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onTrain, enabled = data.canTrain && !uiState.isTraining) {
                if (uiState.isTraining) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(Res.string.labeling_train))
                }
            }
        }
        uiState.notice?.let {
            Text(
                text = it.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState.noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/**
 * One crop, framed around its object, with the model's guess and a chip per category. Shared with
 * the camera screen's live section, which passes the name Frigate already gives the car as
 * [knownAs] and no [onDiscard]: throwing away a live car's crop only makes way for its next one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CropCard(
    crop: UnlabeledCrop,
    imageUrl: String?,
    categories: List<String>,
    busy: Boolean,
    decided: String?,
    onLabel: (String) -> Unit,
    onDiscard: (() -> Unit)?,
    knownAs: String? = null,
) {
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .width(132.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUrl != null) {
                    // The crop's own pixel size, which is what places the object inside it; null until it loads.
                    var imageSize by remember(imageUrl) { mutableStateOf<IntSize?>(null) }
                    val box = imageSize?.let { crop.subject?.boxInCrop(it.width, it.height) }
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onSuccess = { imageSize = IntSize(it.result.image.width, it.result.image.height) },
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                drawContent()
                                val loaded = imageSize
                                if (box != null && loaded != null) drawSubjectFrame(box, loaded)
                            },
                    )
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val guess = crop.guessedCategory
                Text(
                    text = when {
                        guess == null || guess == ClassifierDataset.UNKNOWN_GUESS -> stringResource(Res.string.labeling_model_no_guess)

                        crop.guessedScore != null ->
                            stringResource(Res.string.labeling_model_thinks_score, categoryDisplayName(guess), (crop.guessedScore * 100).toInt())

                        else -> stringResource(Res.string.labeling_model_thinks, categoryDisplayName(guess))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                knownAs?.let {
                    Text(text = stringResource(Res.string.labeling_frigate_calls_it, subLabelDisplayName(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                crop.checkNote()?.let {
                    Text(
                        text = it.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (crop.isDoubted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                    )
                }
                crop.capturedEpochSeconds?.let {
                    Text(text = formatClockTime(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (decided != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                        Text(text = stringResource(Res.string.labeling_filed_as, categoryDisplayName(decided)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { category ->
                            Chip(label = categoryDisplayName(category), selected = category == guess, onClick = { if (!busy) onLabel(category) })
                        }
                    }
                    if (onDiscard != null) TextButton(onClick = onDiscard, enabled = !busy) { Text(stringResource(Res.string.labeling_discard_crop)) }
                }
            }
        }
    }
}

/**
 * What each known car looks like, which the relay's car check holds the classifier's names to: a
 * 100% guess is only taken as sure (and filed into its car) once the car's plate or its make,
 * model and colour agree. A car with nothing on file can't be checked, so its sure guesses always
 * wait in the queue.
 */
@Composable
private fun CarProfilesCard(cars: List<String>, profiles: CarProfiles, onEdit: (String) -> Unit) {
    Card {
        Text(text = stringResource(Res.string.labeling_cars_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = stringResource(Res.string.labeling_cars_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (cars + profiles.profiles.map { it.name }).distinct().forEach { name ->
            val profile = profiles.profileOf(name)
            val displayName = categoryDisplayName(name)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = stringResource(Res.string.labeling_edit_car_action, displayName)) { onEdit(name) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = profileSummary(profile).resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (profile.isEmpty) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = stringResource(if (profile.isEmpty) Res.string.labeling_car_describe else Res.string.labeling_car_edit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * One car's make, model, colour and plate. Make and colour are picked from what the vision model
 * can answer, since a word it never says could never match; the model name is free text, matched
 * loosely ("Model Y" is "Tesla Model Y Long Range"). One colour, on purpose: see [CarProfile].
 * [onForget] is there once the relay keeps a profile for the car, to drop one it shouldn't.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarProfileDialog(
    draft: CarProfile,
    makes: List<String>,
    colours: List<String>,
    saving: Boolean,
    error: UiText?,
    onChange: (CarProfile) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onForget: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(categoryDisplayName(draft.name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(Res.string.labeling_profile_make), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var makesOpen by remember { mutableStateOf(false) }
                val anyMake = stringResource(Res.string.labeling_profile_any_make)
                Box {
                    OutlinedButton(onClick = { makesOpen = true }) { Text(draft.make.takeIf { it.isNotBlank() }?.let(::makeName) ?: anyMake) }
                    DropdownMenu(expanded = makesOpen, onDismissRequest = { makesOpen = false }) {
                        (listOf("") + makes).forEach { make ->
                            DropdownMenuItem(
                                text = { Text(make.takeIf { it.isNotBlank() }?.let(::makeName) ?: anyMake) },
                                onClick = {
                                    makesOpen = false
                                    onChange(draft.copy(make = make))
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { onChange(draft.copy(model = it.take(CarProfile.MODEL_MAX_LENGTH))) },
                    label = { Text(stringResource(Res.string.labeling_profile_model_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(text = stringResource(Res.string.labeling_profile_colour), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    colours.forEach { colour ->
                        Chip(
                            label = carColourName(colour).resolve().replaceFirstChar { it.uppercase() },
                            selected = colour == draft.colour,
                            onClick = { onChange(draft.copy(colour = if (colour == draft.colour) "" else colour)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft.plate,
                    onValueChange = { onChange(draft.copy(plate = CarProfile.normalPlate(it))) },
                    label = { Text(stringResource(Res.string.labeling_profile_plate)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    supportingText = { Text(stringResource(Res.string.labeling_profile_plate_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(text = it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !saving) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(stringResource(Res.string.common_save))
                }
            }
        },
        dismissButton = {
            Row {
                onForget?.let { TextButton(onClick = it, enabled = !saving) { Text(stringResource(Res.string.labeling_profile_forget), color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(background, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (selected) 0f else 0.2f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
    }
}

/** `none` reads as "Not ours"; everything else gets the same humanising as sub-labels in the feed. */
@Composable
internal fun categoryDisplayName(category: String): String =
    if (category == ClassifierDataset.NONE_CATEGORY) stringResource(Res.string.labeling_not_ours) else subLabelDisplayName(category)

/** The object labels a model runs on, as data: "car", or "car or truck". */
@Composable
private fun objectsOr(objects: List<String>): String = objects.joinToString(stringResource(Res.string.labeling_objects_or_separator))

/** The line under a known car: what's on file for it, the plate only as "on file", or that nothing is. */
private fun profileSummary(profile: CarProfile): UiText {
    val looks = profile.looks
    return when {
        profile.isEmpty -> UiText.of(Res.string.labeling_car_not_described)
        profile.plate.isNotBlank() && looks != null -> UiText.of(Res.string.labeling_car_looks_and_plate, looks)
        profile.plate.isNotBlank() -> UiText.of(Res.string.labeling_car_plate_on_file)
        else -> looks ?: UiText.Empty
    }
}

/** Red, not the theme's error colour: it has to stand out against any car in any light. */
private val SubjectFrameColor = Color(0xFFFF3B30)

/**
 * Outlines the object a crop was cut around, so a crop of two overlapping cars says which one it's
 * asking about: solid when it's the box of that very frame, dashed when it's an estimate (see
 * [com.meticulouscreations.homesafe.domain.model.CropSubject.boxInCrop]). [box] is in fractions of
 * the image, which is fitted into the tile; the object's long edge runs the full width of its crop,
 * so the outline is pulled in by half its stroke to stay on screen.
 */
private fun DrawScope.drawSubjectFrame(box: CropBox, image: IntSize) {
    val scale = minOf(size.width / image.width, size.height / image.height)
    val width = image.width * scale
    val height = image.height * scale
    val originX = (size.width - width) / 2
    val originY = (size.height - height) / 2
    val stroke = 2.dp.toPx()
    val inset = stroke / 2
    val left = (originX + box.left.toFloat() * width).coerceAtLeast(originX + inset)
    val top = (originY + box.top.toFloat() * height).coerceAtLeast(originY + inset)
    val right = (originX + box.right.toFloat() * width).coerceAtMost(originX + width - inset)
    val bottom = (originY + box.bottom.toFloat() * height).coerceAtMost(originY + height - inset)
    if (right <= left || bottom <= top) return
    val dashes = if (box.exact) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
    drawRect(color = SubjectFrameColor, topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = Stroke(width = stroke, pathEffect = dashes))
}

private val previewProfiles = CarProfiles(
    profiles = listOf(
        CarProfile("andrews_tesla", make = "tesla", model = "Model Y", colour = "blue", plate = "8ABC123"),
        CarProfile("sarahs_car", make = "tesla", model = "Model Y", colour = "red"),
    ),
    makes = listOf("tesla", "toyota", "bmw", "mercedes"),
    colours = listOf("white", "black", "grey", "blue", "red", "green"),
)

/** The card with a plate on file, looks alone, and a car the classifier knows but nobody has described. */
@Preview(name = "Known cars card")
@Composable
private fun CarProfilesCardPreview() {
    FrigateTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            CarProfilesCard(cars = listOf("andrews_tesla", "sarahs_car", "yayas_car"), profiles = previewProfiles, onEdit = {})
        }
    }
}

/** Editing a car the relay keeps a profile for, so it can also be forgotten. */
@Preview(name = "Known car dialog", widthDp = 412, heightDp = 915)
@Composable
private fun CarProfileDialogPreview() {
    FrigatePreview {
        CarProfileDialog(
            draft = previewProfiles.profiles.first(),
            makes = previewProfiles.makes,
            colours = previewProfiles.colours,
            saving = false,
            error = null,
            onChange = {},
            onSave = {},
            onDismiss = {},
            onForget = {},
        )
    }
}
