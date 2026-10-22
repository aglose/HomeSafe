package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.presence_last_seen_days
import homesafe.shared.generated.resources.presence_last_seen_hours
import homesafe.shared.generated.resources.presence_last_seen_just_now
import homesafe.shared.generated.resources.presence_last_seen_minutes
import homesafe.shared.generated.resources.presence_last_seen_yesterday
import kotlin.test.Test
import kotlin.test.assertEquals

/** How long since the relay heard from a tracked phone, as its Away mode row says it. */
class FormatLastSeenTest {

    private val now = 1_790_000_000.0
    private val hour = 3_600.0
    private val day = 24 * hour

    @Test
    fun lastSeenReadsInTheLargestUnitThatApplies() {
        val justNow = UiText.of(Res.string.presence_last_seen_just_now)
        assertEquals(justNow, formatLastSeen(now - 30, now))
        assertEquals(justNow, formatLastSeen(now + 90, now), "a clock skew never reads as the future")
        assertEquals(UiText.plural(Res.plurals.presence_last_seen_minutes, 12), formatLastSeen(now - 12 * 60, now))
        assertEquals(UiText.plural(Res.plurals.presence_last_seen_hours, 3), formatLastSeen(now - 3 * hour - 59 * 60, now))
        assertEquals(UiText.of(Res.string.presence_last_seen_yesterday), formatLastSeen(now - 30 * hour, now))
        assertEquals(UiText.plural(Res.plurals.presence_last_seen_days, 5), formatLastSeen(now - 5 * day, now))
    }
}
