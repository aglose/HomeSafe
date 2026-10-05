package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.moments_date_short
import homesafe.shared.generated.resources.moments_month_oct
import homesafe.shared.generated.resources.uptime_moment
import homesafe.shared.generated.resources.uptime_percent_one_decimal
import homesafe.shared.generated.resources.uptime_percent_two_decimals
import homesafe.shared.generated.resources.uptime_percent_whole
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerUptimeTest {

    @Test
    fun theRelaysLettersAreStates() {
        assertEquals(
            listOf(UptimeState.Up, UptimeState.Partial, UptimeState.Down, UptimeState.NotMeasured, UptimeState.NotMeasured),
            "udxn?".map(UptimeState::fromLetter),
        )
    }

    @Test
    fun checksAreKnownByTheRelaysKeys() {
        assertEquals(UptimeCheckKind.Dns, UptimeCheckKind.of("dns"))
        assertNull(UptimeCheckKind.of("zigbee"))
        assertEquals(listOf("server", "router", "internet", "dns", "tailscale", "frigate", "cameras", "live"), UptimeCheckKind.entries.map { it.key }, "the relay's UPTIME_CHECKS, in its order")
    }

    @Test
    fun aPerfectRecordIsAHundredPercent() {
        assertEquals(UiText.of(Res.string.uptime_percent_whole, 100), formatUpFraction(1.0))
        assertEquals(UiText.of(Res.string.uptime_percent_whole, 100), formatUpFraction(1.3))
    }

    @Test
    fun anImperfectOneNeverRoundsUpToAHundred() {
        assertEquals(UiText.of(Res.string.uptime_percent_two_decimals, 99, "99"), formatUpFraction(0.99999))
        assertEquals(UiText.of(Res.string.uptime_percent_two_decimals, 99, "95"), formatUpFraction(0.9995))
        assertEquals(UiText.of(Res.string.uptime_percent_two_decimals, 99, "93"), formatUpFraction(0.99931), "one minute down in a day")
        assertEquals(UiText.of(Res.string.uptime_percent_one_decimal, 99, 9), formatUpFraction(0.999))
    }

    @Test
    fun aRoughRecordDropsTheDecimalsItDoesNotNeed() {
        assertEquals(UiText.of(Res.string.uptime_percent_one_decimal, 99, 5), formatUpFraction(0.9954))
        assertEquals(UiText.of(Res.string.uptime_percent_one_decimal, 99, 0), formatUpFraction(0.99))
        assertEquals(UiText.of(Res.string.uptime_percent_whole, 97), formatUpFraction(0.9722))
        assertEquals(UiText.of(Res.string.uptime_percent_whole, 0), formatUpFraction(0.0))
        assertEquals(UiText.of(Res.string.uptime_percent_whole, 0), formatUpFraction(-0.2))
    }

    @Test
    fun aMomentIsADayAndATimeInTheReadersZone() {
        // 2026-10-04 17:27:17 UTC.
        val moment = uptimeMoment(1_791_134_837L, TimeZone.of("America/Los_Angeles"))
        assertEquals(
            UiText.of(Res.string.uptime_moment, UiText.of(Res.string.moments_date_short, UiText.of(Res.string.moments_month_oct), 4), "10:27 AM".asUiText()),
            moment,
        )
    }

    @Test
    fun aRangeIsItsHours() {
        assertEquals(listOf(24, 168, 720), UptimeRange.entries.map { it.hours })
    }
}
