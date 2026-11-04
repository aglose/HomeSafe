package com.meticulouscreations.homesafe.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sign-in form's server field against a stored connection that loads after the form is up,
 * which can be after someone has started typing (the whole-app journeys, one after another in one
 * process, met the previous journey's record that way and signed in to its closed server).
 */
class ServerUrlFieldTest {

    private var stored: String? = null
    private val field = ServerUrlField { stored }

    @Test
    fun aFirstSignInStartsEmpty() {
        assertEquals("", field.value)
    }

    @Test
    fun theStoredConnectionFillsAnUntouchedField() {
        stored = "http://frigate.local:5000"
        assertEquals("http://frigate.local:5000", field.value)
    }

    @Test
    fun aStoredConnectionLoadingAfterTypingLeavesWhatWasTyped() {
        field.enter("http://localhost:8971")
        stored = "http://127.0.0.1:46485"
        assertEquals("http://localhost:8971", field.value)
    }

    @Test
    fun clearingTheFieldKeepsItEmptyRatherThanBringingTheStoredAddressBack() {
        stored = "http://frigate.local:5000"
        field.enter("")
        assertEquals("", field.value)
    }
}
