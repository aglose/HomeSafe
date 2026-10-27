package com.meticulouscreations.homesafe.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.assistant.AssistantModel
import com.meticulouscreations.homesafe.assistant.AssistantModelFamily
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.AssistantTurn
import com.meticulouscreations.homesafe.viewmodel.AssistantUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_error_key
import homesafe.shared.generated.resources.assistant_tool_doors

// The assistant's box and card as they sit over a screen, in each of the states it can be in. The
// questions and answers are sample data.

private val previewModels = listOf(
    AssistantModel(AssistantModelFamily.Haiku, "claude-haiku-5-5", "Claude Haiku 5.5"),
    AssistantModel(AssistantModelFamily.Sonnet, "claude-sonnet-5-5", "Claude Sonnet 5.5"),
    AssistantModel(AssistantModelFamily.Opus, "claude-opus-5-5", "Claude Opus 5.5"),
    AssistantModel(AssistantModelFamily.Fable, "claude-fable-5-1", "Claude Fable 5.1"),
)

@Preview(name = "Assistant, ready", widthDp = 412)
@Composable
private fun AssistantReadyPreview() {
    AssistantPanelPreview(AssistantUiState(hasKey = true, models = previewModels))
}

@Preview(name = "Assistant, typing", widthDp = 412)
@Composable
private fun AssistantTypingPreview() {
    AssistantPanelPreview(AssistantUiState(hasKey = true, models = previewModels, family = AssistantModelFamily.Sonnet), draft = "Did anyone come to the door while I was out?")
}

@Preview(name = "Assistant, looking", widthDp = 412)
@Composable
private fun AssistantLookingPreview() {
    AssistantPanelPreview(
        AssistantUiState(
            hasKey = true,
            models = previewModels,
            turns = listOf(AssistantTurn("Is the back door shut?")),
            working = true,
            activity = Res.string.assistant_tool_doors,
        ),
    )
}

@Preview(name = "Assistant, answered", widthDp = 412)
@Composable
private fun AssistantAnsweredPreview() {
    AssistantPanelPreview(
        AssistantUiState(
            hasKey = true,
            models = previewModels,
            turns = listOf(
                AssistantTurn("What's this camera?", "That's **Driveway**, the outdoor camera over the garage. It's live now."),
                AssistantTurn(
                    "Anything on it today?",
                    "Two visits so far:\n- **Mia** came home at 3:42 this afternoon\n- A delivery van stopped for about a minute at 11:05\n\nNothing since 4 PM.",
                ),
            ),
        ),
    )
}

@Preview(name = "Assistant, no key", widthDp = 412)
@Composable
private fun AssistantNoKeyPreview() {
    AssistantPanelPreview(AssistantUiState(hasKey = false))
}

@Preview(name = "Assistant, key refused", widthDp = 412)
@Composable
private fun AssistantKeyRefusedPreview() {
    AssistantPanelPreview(
        AssistantUiState(
            hasKey = true,
            models = previewModels,
            family = AssistantModelFamily.Fable,
            turns = listOf(AssistantTurn("How much did we spend on groceries this month?", note = UiText.of(Res.string.assistant_error_key))),
        ),
    )
}

@Composable
private fun AssistantPanelPreview(state: AssistantUiState, draft: String = "") {
    FrigateTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 44.dp)) {
            val glow = remember { assistantGlowShaderOrNull() }
            AssistantPanel(
                state = state,
                draft = draft,
                onDraftChange = {},
                keyDraft = "",
                onKeyDraftChange = {},
                actions = AssistantPanelActions.None,
                inputModifier = Modifier.assistantGlow(glow, cornerRadius = 28.dp, time = { 1.4f }, intensity = { if (state.working) 1.05f else 0.65f }),
            )
        }
    }
}
