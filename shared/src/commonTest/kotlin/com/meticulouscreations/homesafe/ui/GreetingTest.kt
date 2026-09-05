package com.meticulouscreations.homesafe.ui

import com.meticulouscreations.homesafe.ui.screens.greetingForHour
import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTest {
    @Test
    fun greetingFollowsTheHour() {
        assertEquals("Good Evening", greetingForHour(0))
        assertEquals("Good Evening", greetingForHour(4))
        assertEquals("Good Morning", greetingForHour(5))
        assertEquals("Good Morning", greetingForHour(11))
        assertEquals("Good Afternoon", greetingForHour(12))
        assertEquals("Good Afternoon", greetingForHour(16))
        assertEquals("Good Evening", greetingForHour(17))
        assertEquals("Good Evening", greetingForHour(23))
    }
}
