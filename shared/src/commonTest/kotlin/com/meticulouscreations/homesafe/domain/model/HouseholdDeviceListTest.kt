package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Away mode device list, as the household saw it on 2026-09-22: nine rows for two people,
 * most of them old installs. The phones that decide away mode come first; the rest fold away.
 */
class HouseholdDeviceListTest {

    private val now = 1_790_000_000.0
    private val hour = 3_600.0
    private val day = 24 * hour

    private fun device(
        id: String,
        name: String,
        seenAgo: Double?,
        counts: Boolean = true,
        thisDevice: Boolean = false,
        away: Boolean = false,
    ) = PresenceDevice(
        name = name,
        platform = if (name.startsWith("Apple")) "ios" else "android",
        away = away,
        isThisDevice = thisDevice,
        countsForAway = counts,
        id = id,
        lastSeenEpochSeconds = seenAgo?.let { now - it },
    )

    @Test
    fun theHouseholdsPhonesComeFirstAndTheLeftoversFoldAway() {
        val devices = listOf(
            device("pixel-debug-old", "Google Pixel 10 Pro XL", seenAgo = 20 * day, counts = false),
            device("pixel-debug", "Google Pixel 10 Pro XL", seenAgo = 2 * hour, counts = false),
            device("pixel", "Google Pixel 10 Pro XL", seenAgo = 5 * 60.0),
            device("iphone-old-1", "Apple iPhone", seenAgo = 12 * day),
            device("iphone-old-2", "Apple iPhone", seenAgo = 9 * day),
            device("iphone", "Apple iPhone", seenAgo = 30 * 60.0, away = true),
            device("emulator-old", "Google sdk_gphone64_arm64", seenAgo = 15 * day, counts = false),
            device("emulator", "Google sdk_gphone64_arm64", seenAgo = 0.0, counts = false, thisDevice = true),
            device("desktop", "HomeSafe desktop", seenAgo = 3 * day, counts = false),
        )

        val list = HouseholdDeviceList.of(devices, now)

        assertEquals(listOf("pixel", "iphone", "emulator"), list.primary.map { it.id }, "counted phones in use, then this debug install")
        assertEquals(
            listOf("iphone-old-2", "iphone-old-1", "pixel-debug", "desktop", "emulator-old", "pixel-debug-old"),
            list.others.map { it.id },
            "old installs that still count lead the fold; within each, most recently seen first",
        )
        assertEquals(2, list.countedOthers, "two dead iPhone installs still vote \"home\"")
    }

    @Test
    fun thisPhoneIsNeverFoldedAwayEvenWhenItLooksStale() {
        val me = device("me", "Google Pixel 10 Pro XL", seenAgo = 30 * day, thisDevice = true)
        val list = HouseholdDeviceList.of(listOf(me), now)
        assertEquals(listOf("me"), list.primary.map { it.id })
        assertEquals(emptyList(), list.others)
    }

    @Test
    fun aCountedPhoneIsStaleOnlyAfterAWeekOfSilence() {
        val justInside = device("a", "Google Pixel", seenAgo = HouseholdDeviceList.STALE_AFTER_SECONDS - 1)
        val justPast = device("b", "Google Pixel", seenAgo = HouseholdDeviceList.STALE_AFTER_SECONDS + 1)
        val list = HouseholdDeviceList.of(listOf(justInside, justPast), now)
        assertEquals(listOf("a"), list.primary.map { it.id })
        assertEquals(listOf("b"), list.others.map { it.id })
    }

    @Test
    fun anOlderRelayWithNoLastSeenFoldsOnlyTheDebugInstalls() {
        val list = HouseholdDeviceList.of(
            listOf(
                device("debug", "Google sdk_gphone64_arm64", seenAgo = null, counts = false),
                device("pixel", "Google Pixel", seenAgo = null),
            ),
            now,
        )
        assertEquals(listOf("pixel"), list.primary.map { it.id }, "no last_seen is not the same as stale")
        assertEquals(listOf("debug"), list.others.map { it.id })
    }

    @Test
    fun lastSeenReadsInTheLargestUnitThatApplies() {
        assertEquals("seen just now", formatLastSeen(now - 30, now))
        assertEquals("seen just now", formatLastSeen(now + 90, now), "a clock skew never reads as the future")
        assertEquals("seen 12 min ago", formatLastSeen(now - 12 * 60, now))
        assertEquals("seen 3 h ago", formatLastSeen(now - 3 * hour - 59 * 60, now))
        assertEquals("seen yesterday", formatLastSeen(now - 30 * hour, now))
        assertEquals("seen 5 days ago", formatLastSeen(now - 5 * day, now))
    }
}
