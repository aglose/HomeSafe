package com.meticulouscreations.homesafe.ui.components

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberShareText(): ((text: String, title: String) -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        val share: (String, String) -> Unit = { text, title ->
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_SUBJECT, title)
                // What the sheet shows of it, in place of the text's first lines.
                .putExtra(Intent.EXTRA_TITLE, title)
            // Text too long for one app to hand another throws here, and there is nothing to open then.
            runCatching { context.startActivity(Intent.createChooser(send, title)) }
        }
        share
    }
}
