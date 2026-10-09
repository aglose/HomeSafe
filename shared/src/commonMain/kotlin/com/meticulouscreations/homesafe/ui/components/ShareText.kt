package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable

/**
 * The platform's share sheet for a piece of text, as something to call with the text and a title
 * for it (the sheet's heading, and the subject where it is sent somewhere that has one). It
 * answers whether the sheet opened: text too long for one app to hand another doesn't open one.
 * Null where there is no such sheet at all, which for now is everywhere but Android: whatever
 * offers to send text hides itself there.
 */
@Composable
expect fun rememberShareText(): ((text: String, title: String) -> Boolean)?
