package com.meticulouscreations.homesafe.ui.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.assistant.AssistantModelFamily
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import com.meticulouscreations.homesafe.viewmodel.AssistantTurn
import com.meticulouscreations.homesafe.viewmodel.AssistantUiState
import com.meticulouscreations.homesafe.viewmodel.AssistantViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_choose_model
import homesafe.shared.generated.resources.assistant_close
import homesafe.shared.generated.resources.assistant_forget_key
import homesafe.shared.generated.resources.assistant_include_screen
import homesafe.shared.generated.resources.assistant_input_placeholder
import homesafe.shared.generated.resources.assistant_key_hint
import homesafe.shared.generated.resources.assistant_key_placeholder
import homesafe.shared.generated.resources.assistant_key_save
import homesafe.shared.generated.resources.assistant_new_chat
import homesafe.shared.generated.resources.assistant_send
import homesafe.shared.generated.resources.assistant_stop
import homesafe.shared.generated.resources.assistant_thinking
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Installs the assistant over [content], which is the whole app in its window: provides
 * [LocalAssistant] for the bottom bars to open it from ([assistantTrigger]) and the screens to say
 * what they are ([AssistantScreenContext]), and draws it over everything when it is up.
 *
 * Up, it is a small box floating just above the bar it was opened from, with Claude's answers on a
 * card above that. Nothing else is covered and nothing behind is dimmed or blocked: the screen
 * beneath keeps scrolling and the tabs keep switching while it is up, and a question is asked about
 * whatever is showing by then. While it is up the app beneath draws through a layer, so a picture
 * of it can go with a question; the assistant itself is never in that picture.
 *
 * [host] is made here unless a platform host hands one in to open from outside Compose (the iOS 26
 * shell, whose tab bar is native).
 */
@Composable
fun AssistantRoot(modifier: Modifier = Modifier, host: AssistantHost = remember { AssistantHost() }, content: @Composable () -> Unit) {
    val screen = rememberGraphicsLayer()
    DisposableEffect(host, screen) {
        host.capture = { screen.toImageBitmap() }
        onDispose { host.capture = null }
    }
    CompositionLocalProvider(LocalAssistant provides host) {
        Box(modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    if (host.isOpen) {
                        screen.record { this@drawWithContent.drawContent() }
                        drawLayer(screen)
                    } else {
                        drawContent()
                    }
                },
            ) { content() }
            AssistantLayer(host)
        }
    }
}

/** The assistant while it is up or on its way in or out; nothing at all otherwise. */
@Composable
private fun AssistantLayer(host: AssistantHost) {
    // 0 is folded into the bar it came from, 1 is open; the spring overshoots a little as it lands.
    val appear = remember { Animatable(0f) }
    var present by remember { mutableStateOf(false) }
    LaunchedEffect(host.isOpen, host.openings) {
        if (host.isOpen) {
            present = true
            appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 340f))
        } else {
            appear.animateTo(0f, tween(CLOSE_MS, easing = FastOutSlowInEasing))
            present = false
        }
    }
    if (!present) return

    val viewModel: AssistantViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    // Only ever in memory: saved state would put the key, in the clear, in the platform's saved-instance Bundle.
    var keyDraft by remember { mutableStateOf("") }

    // Back folds the box back toward its bar under the finger, and on release carries on from there.
    val back = rememberPredictiveBack(enabled = host.isOpen) { releasedAt ->
        scope.launch {
            appear.snapTo(appear.value - BACK_REACH * releasedAt)
            host.close()
        }
    }
    val morph = { appear.value - BACK_REACH * back.progress }

    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = ((it - start) / 1e9f) % CLOCK_WRAP_SECONDS }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(host.isOpen, host.openings) {
        if (host.isOpen) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    val actions = remember(viewModel, host) {
        AssistantPanelActions(
            onSend = { question ->
                scope.launch {
                    val picture = if (viewModel.uiState.value.includeScreen) runCatching { host.capture?.invoke() }.getOrNull() else null
                    viewModel.ask(question, host.describeScreen(), picture)
                }
            },
            onStop = viewModel::stop,
            onClose = host::close,
            onNewConversation = viewModel::newConversation,
            onSelectFamily = viewModel::selectFamily,
            onIncludeScreen = viewModel::setIncludeScreen,
            onSaveKey = viewModel::saveKey,
            onForgetKey = viewModel::forgetKey,
        )
    }

    AssistantFrame(
        origin = host.origin,
        morph = morph,
        time = { time.floatValue },
        working = state.working,
    ) { inputModifier, cardModifier, contentAlpha ->
        AssistantPanel(
            state = state,
            draft = draft,
            onDraftChange = { draft = it },
            keyDraft = keyDraft,
            onKeyDraftChange = { keyDraft = it },
            actions = actions,
            inputModifier = inputModifier,
            cardModifier = cardModifier,
            contentAlpha = contentAlpha,
            time = { time.floatValue },
            focusRequester = focus,
            onClearDraft = { draft = "" },
            onClearKeyDraft = { keyDraft = "" },
        )
    }
}

/**
 * Where the box sits and how it moves: just above the bar it was opened from (or above the keyboard,
 * whichever is higher), as wide as a bottom bar, growing out of [origin] as [morph] goes from 0 to 1.
 * Hands [panel] the modifiers that do that to the input box and the answer card, and how far in its
 * contents have faded.
 */
@Composable
private fun AssistantFrame(
    origin: Rect,
    morph: () -> Float,
    time: () -> Float,
    working: Boolean,
    panel: @Composable (inputModifier: Modifier, cardModifier: Modifier, contentAlpha: () -> Float) -> Unit,
) {
    val density = LocalDensity.current
    var root by remember { mutableStateOf(Rect.Zero) }
    var input by remember { mutableStateOf(Rect.Zero) }
    val glow = remember { assistantGlowShaderOrNull() }

    val gap = with(density) { BAR_GAP.toPx() }
    val ime = WindowInsets.ime.getBottom(density).toFloat()
    val navigation = WindowInsets.navigationBars.getBottom(density).toFloat()
    // A bar along the foot has the box above it; a rail down the side, or a bar along the top (an
    // iPad's tab bar), has it at the foot, clear of the system bar.
    val alongFoot = origin != Rect.Zero && root != Rect.Zero && origin.width >= origin.height && origin.center.y > root.center.y
    val fromBar = if (alongFoot) root.bottom - origin.top + gap else navigation + gap
    val clearancePx = max(fromBar, ime + gap)
    val clearance = with(density) { clearancePx.toDp() }
    val top = WindowInsets.statusBars.getTop(density).toFloat()
    // The card has what the box leaves, up to its share of the window: with the keyboard up on a phone
    // on its side there is little height, and the box keeps its own.
    val cardRoom = root.height - clearancePx - input.height.coerceAtLeast(with(density) { INPUT_HEIGHT.toPx() }) - gap - top - gap
    val cardMaxHeight = with(density) { minOf(root.height * CARD_MAX_SHARE, cardRoom).coerceAtLeast(0f).toDp() }

    val inputModifier = Modifier
        // Measured before the layer below moves it, so the morph is worked out from where it rests.
        .onGloballyPositioned { input = it.boundsInWindow() }
        .graphicsLayer {
            val m = morph()
            transformOrigin = TransformOrigin.Center
            if (origin != Rect.Zero && input.width > 0f && input.height > 0f) {
                scaleX = lerp(origin.width / input.width, 1f, m)
                scaleY = lerp(origin.height / input.height, 1f, m)
                translationX = lerp(origin.center.x - input.center.x, 0f, m)
                translationY = lerp(origin.center.y - input.center.y, 0f, m)
            } else {
                scaleX = lerp(0.85f, 1f, m)
                scaleY = scaleX
            }
            alpha = (m * 4f).coerceIn(0f, 1f)
        }
        .assistantGlow(
            shader = glow,
            cornerRadius = INPUT_HEIGHT / 2,
            time = time,
            intensity = {
                val m = morph().coerceIn(0f, 1f)
                // A burst of light as it blooms out of the bar, then a quiet rim that breathes while Claude works.
                val bloom = sin(m * PI.toFloat()) * 1.1f
                val breath = if (working) 0.3f + 0.25f * sin(time() * 3.6f) else 0f
                (0.65f + bloom + breath) * (m * 3f).coerceIn(0f, 1f)
            },
        )
    val cardModifier = Modifier
        .heightIn(max = cardMaxHeight)
        .graphicsLayer {
            val shown = ((morph() - 0.55f) / 0.45f).coerceIn(0f, 1f)
            alpha = shown
            translationY = (1f - shown) * CARD_RISE.toPx()
        }

    Box(Modifier.fillMaxSize().onGloballyPositioned { root = it.boundsInWindow() }) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = clearance)
                .widthIn(max = PANEL_MAX_WIDTH)
                .fillMaxWidth(PANEL_WIDTH_SHARE),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            panel(inputModifier, cardModifier) { ((morph() - 0.4f) / 0.6f).coerceIn(0f, 1f) }
        }
    }
}

/** What the panel's buttons do; one stable object rather than eight lambdas. */
@Immutable
internal class AssistantPanelActions(
    val onSend: (String) -> Unit,
    val onStop: () -> Unit,
    val onClose: () -> Unit,
    val onNewConversation: () -> Unit,
    val onSelectFamily: (AssistantModelFamily) -> Unit,
    val onIncludeScreen: (Boolean) -> Unit,
    val onSaveKey: (String) -> Unit,
    val onForgetKey: () -> Unit,
) {
    companion object {
        val None = AssistantPanelActions({}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * The assistant's two pieces, stateless: the answer card (once there is anything to show on it)
 * and the input box under it. Without an API key the box takes the key instead of a question.
 */
@Composable
internal fun AssistantPanel(
    state: AssistantUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    keyDraft: String,
    onKeyDraftChange: (String) -> Unit,
    actions: AssistantPanelActions,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
    contentAlpha: () -> Float = { 1f },
    time: () -> Float = { 0f },
    focusRequester: FocusRequester = remember { FocusRequester() },
    onClearDraft: () -> Unit = {},
    onClearKeyDraft: () -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!state.hasKey || state.turns.isNotEmpty()) {
            AnswerCard(state, actions, time, cardModifier)
        }
        if (state.hasKey) {
            QuestionBox(state, draft, onDraftChange, actions, contentAlpha, focusRequester, onClearDraft, inputModifier)
        } else {
            KeyBox(keyDraft, onKeyDraftChange, actions, contentAlpha, focusRequester, onClearKeyDraft, inputModifier)
        }
    }
}

@Composable
private fun AnswerCard(state: AssistantUiState, actions: AssistantPanelActions, time: () -> Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val last = state.turns.lastOrNull()
    LaunchedEffect(state.turns.size, last?.answer?.length, state.activity) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh.copy(alpha = 0.96f), CARD_SHAPE)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.35f), CARD_SHAPE)
            .testTag(ASSISTANT_CARD_TEST_TAG),
    ) {
        if (state.hasKey) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.model.displayName ?: stringResource(state.family.label),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = actions.onNewConversation) {
                    Icon(Icons.Outlined.AddComment, contentDescription = stringResource(Res.string.assistant_new_chat), tint = colors.onSurfaceVariant)
                }
                IconButton(onClick = actions.onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.assistant_close), tint = colors.onSurfaceVariant)
                }
            }
        }
        SelectionContainer {
            Column(
                Modifier.verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = if (state.hasKey) 0.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!state.hasKey) {
                    Text(stringResource(Res.string.assistant_key_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                }
                state.turns.forEachIndexed { index, turn ->
                    Turn(turn, writing = state.working && index == state.turns.lastIndex, activity = state.activity?.let { stringResource(it) }, time = time)
                }
            }
        }
    }
}

@Composable
private fun Turn(turn: AssistantTurn, writing: Boolean, activity: String?, time: () -> Float) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(turn.question, style = MaterialTheme.typography.labelLarge, color = colors.primary, maxLines = 4)
        if (turn.answer.isNotEmpty()) {
            Text(assistantMarkdown(turn.answer), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        }
        if (writing && (turn.answer.isEmpty() || activity != null)) {
            // Pulses with the glow's clock, read in the layer so the pulse doesn't recompose anything.
            Text(
                activity ?: stringResource(Res.string.assistant_thinking),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { alpha = 0.55f + 0.45f * sin(time() * 4f) },
            )
        }
        turn.note?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = colors.error) }
    }
}

@Composable
private fun QuestionBox(
    state: AssistantUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    actions: AssistantPanelActions,
    contentAlpha: () -> Float,
    focusRequester: FocusRequester,
    onClearDraft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val send = {
        if (draft.isNotBlank() && !state.working) {
            actions.onSend(draft)
            onClearDraft()
        }
    }
    InputShell(modifier) {
        Row(Modifier.graphicsLayer { alpha = contentAlpha() }, verticalAlignment = Alignment.CenterVertically) {
            ModelChip(state, actions)
            InputField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = stringResource(Res.string.assistant_input_placeholder),
                focusRequester = focusRequester,
                imeAction = ImeAction.Send,
                onAction = send,
                modifier = Modifier.weight(1f),
            )
            when {
                state.working -> RoundButton(Icons.Filled.Stop, stringResource(Res.string.assistant_stop), filled = true, onClick = actions.onStop)
                draft.isNotBlank() -> RoundButton(Icons.Filled.ArrowUpward, stringResource(Res.string.assistant_send), filled = true, onClick = send)
                else -> RoundButton(Icons.Filled.Close, stringResource(Res.string.assistant_close), filled = false, onClick = actions.onClose)
            }
        }
    }
}

@Composable
private fun KeyBox(
    keyDraft: String,
    onKeyDraftChange: (String) -> Unit,
    actions: AssistantPanelActions,
    contentAlpha: () -> Float,
    focusRequester: FocusRequester,
    onClearKeyDraft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val save = {
        if (keyDraft.isNotBlank()) {
            actions.onSaveKey(keyDraft)
            onClearKeyDraft()
        }
    }
    InputShell(modifier) {
        Row(Modifier.graphicsLayer { alpha = contentAlpha() }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, end = 4.dp).size(20.dp))
            InputField(
                value = keyDraft,
                onValueChange = onKeyDraftChange,
                placeholder = stringResource(Res.string.assistant_key_placeholder),
                focusRequester = focusRequester,
                imeAction = ImeAction.Done,
                onAction = save,
                secret = true,
                modifier = Modifier.weight(1f),
            )
            if (keyDraft.isNotBlank()) {
                RoundButton(Icons.Filled.Check, stringResource(Res.string.assistant_key_save), filled = true, onClick = save)
            } else {
                RoundButton(Icons.Filled.Close, stringResource(Res.string.assistant_close), filled = false, onClick = actions.onClose)
            }
        }
    }
}

/** The box itself: a pill the height of a bottom bar, opaque enough to read over anything. */
@Composable
private fun InputShell(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = INPUT_HEIGHT)
            .background(colors.surfaceContainerHighest, INPUT_SHAPE)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.4f), INPUT_SHAPE)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .testTag(ASSISTANT_INPUT_TEST_TAG),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.padding(horizontal = 8.dp).focusRequester(focusRequester),
        textStyle = style,
        cursorBrush = SolidColor(colors.primary),
        maxLines = if (secret) 1 else 4,
        singleLine = secret,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            imeAction = imeAction,
            autoCorrectEnabled = !secret,
        ),
        keyboardActions = KeyboardActions(onSend = { onAction() }, onDone = { onAction() }),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style, color = colors.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                field()
            }
        },
    )
}

/** Which Claude answers: the family's name, opening the menu of all four and the assistant's other choices. */
@Composable
private fun ModelChip(state: AssistantUiState, actions: AssistantPanelActions) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val chooseModel = stringResource(Res.string.assistant_choose_model)
    Box {
        Row(
            Modifier
                .padding(start = 2.dp)
                .background(colors.primary.copy(alpha = 0.14f), CircleShape)
                .clickable(onClickLabel = chooseModel) { open = true }
                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                .testTag(ASSISTANT_MODEL_TEST_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(state.family.label), style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.displayName ?: stringResource(model.family.label), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(model.family.hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    },
                    leadingIcon = {
                        if (model.family == state.family) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary) else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        actions.onSelectFamily(model.family)
                        open = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.assistant_include_screen)) },
                leadingIcon = { Icon(if (state.includeScreen) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, contentDescription = null) },
                onClick = { actions.onIncludeScreen(!state.includeScreen) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.assistant_new_chat)) },
                leadingIcon = { Icon(Icons.Outlined.AddComment, contentDescription = null) },
                onClick = {
                    actions.onNewConversation()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.assistant_forget_key)) },
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                onClick = {
                    actions.onForgetKey()
                    open = false
                },
            )
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, filled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Box(
            Modifier.size(36.dp).background(if (filled) colors.primary else colors.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (filled) colors.onPrimary else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Claude's answer with the little markdown it is asked to use: `**bold**`, `` `code` ``, and lines
 * starting `- ` or `* ` as bullets (a `#` heading, should one come, is just bold). Anything else is
 * shown as written, so a stray asterisk still reads.
 */
internal fun assistantMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { index, raw ->
        if (index > 0) append('\n')
        var line = raw
        val heading = line.trimStart().startsWith("#")
        if (heading) line = line.trimStart().trimStart('#').trimStart()
        val bullet = Regex("^(\\s*)[-*] ").find(line)
        if (bullet != null) line = bullet.groupValues[1] + "•  " + line.substring(bullet.range.last + 1)
        if (heading) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(line) }
        } else {
            appendInline(line)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(line: String) {
    var rest = line
    while (rest.isNotEmpty()) {
        val bold = rest.indexOf("**")
        val code = rest.indexOf('`')
        val next = listOf(bold, code).filter { it >= 0 }.minOrNull()
        if (next == null) {
            append(rest)
            return
        }
        val marker = if (next == bold) "**" else "`"
        val close = rest.indexOf(marker, next + marker.length)
        if (close < 0) {
            append(rest)
            return
        }
        append(rest.substring(0, next))
        val inner = rest.substring(next + marker.length, close)
        withStyle(if (marker == "**") SpanStyle(fontWeight = FontWeight.SemiBold) else SpanStyle(fontFamily = FontFamily.Monospace)) { append(inner) }
        rest = rest.substring(close + marker.length)
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

const val ASSISTANT_INPUT_TEST_TAG = "assistant_input"
const val ASSISTANT_CARD_TEST_TAG = "assistant_card"
const val ASSISTANT_MODEL_TEST_TAG = "assistant_model"

private val INPUT_HEIGHT = 56.dp
private val INPUT_SHAPE = RoundedCornerShape(28.dp)
private val CARD_SHAPE = RoundedCornerShape(24.dp)
private val PANEL_MAX_WIDTH = 560.dp
private const val PANEL_WIDTH_SHARE = 0.92f
private val BAR_GAP = 12.dp
private val CARD_RISE = 24.dp
private const val CARD_MAX_SHARE = 0.42f
private const val CLOSE_MS = 280

/** How far back toward its bar a full back swipe folds the box. */
private const val BACK_REACH = 0.35f

/** The glow's clock wraps this often so its float keeps its precision. */
private const val CLOCK_WRAP_SECONDS = 600f
