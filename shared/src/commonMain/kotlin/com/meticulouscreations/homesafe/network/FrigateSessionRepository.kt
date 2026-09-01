package com.meticulouscreations.homesafe.network

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the current Frigate session (server + its cameras) once connected. */
@Inject
@SingleIn(AppScope::class)
class FrigateSessionRepository {
    private val _session = MutableStateFlow<FrigateSession?>(null)
    val session: StateFlow<FrigateSession?> = _session.asStateFlow()

    fun set(session: FrigateSession) {
        _session.value = session
    }
}
