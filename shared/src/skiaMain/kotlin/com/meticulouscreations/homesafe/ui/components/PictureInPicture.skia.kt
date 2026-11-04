package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable

/** No mini player on iOS or the desktop (yet): see the common declaration. */
@Composable
actual fun rememberPictureInPicture(enabled: Boolean, aspectRatio: Float): PictureInPicture = NoPictureInPicture
