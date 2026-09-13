package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext

// androidx.sqlite:sqlite-bundled has no JS/Wasm driver yet, so the web target can't back
// a real Room database. Fall back to in-memory implementations of the same interfaces.
actual fun createConnectionHistoryDao(context: PlatformContext): ConnectionHistoryDao =
    InMemoryConnectionHistoryDao()

actual fun createCameraDao(context: PlatformContext): CameraDao =
    InMemoryCameraDao()

actual fun createSettingsDao(context: PlatformContext): SettingsDao =
    InMemorySettingsDao()

actual fun createPropertyLayoutDao(context: PlatformContext): PropertyLayoutDao =
    InMemoryPropertyLayoutDao()
