package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable

/**
 * The platform's share sheet for a piece of text, as something to call with the text and a title
 * for it (the sheet's heading, and the subject where it is sent somewhere that has one). Null
 * where there is no such sheet to open, which for now is everywhere but Android: whatever offers
 * to send text hides itself there.
 */
@Composable
expect fun rememberShareText(): ((text: String, title: String) -> Unit)?
