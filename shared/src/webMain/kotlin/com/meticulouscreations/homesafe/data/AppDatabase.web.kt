package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext

// androidx.sqlite:sqlite-bundled has no JS/Wasm driver yet, so the web target can't back
// a real Room database. Fall back to an in-memory implementation of the same interface.
actual fun createConnectionHistoryDao(context: PlatformContext): ConnectionHistoryDao =
    InMemoryConnectionHistoryDao()
