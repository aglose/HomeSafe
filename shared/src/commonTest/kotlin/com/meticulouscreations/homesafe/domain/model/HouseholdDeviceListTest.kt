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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        build: String? = null,
        decides: Boolean = false,
    ) = PresenceDevice(
        name = name,
        platform = if (name.startsWith("Apple")) "ios" else "android",
        away = away,
        isThisDevice = thisDevice,
        countsForAway = counts,
        id = id,
        lastSeenEpochSeconds = seenAgo?.let { now - it },
        build = build,
        decides = decides,
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
    fun underAnAuthorityTheOtherHouseholdPhonesStayUpFrontUncounted() {
        // The Pixel decides alone, so the relay counts nobody else — but the iPhone in use is still
        // the household's, and only the debug install and the week-old iPhone fold away.
        val devices = listOf(
            device("pixel-debug", "Google Pixel 10 Pro XL", seenAgo = 2 * hour, counts = false, build = "debug", thisDevice = true),
            device("iphone-old", "Apple iPhone", seenAgo = 12 * day, counts = false, build = "debug"),
            device("iphone", "Apple iPhone", seenAgo = 30 * 60.0, counts = false, build = "debug", away = true),
            device("pixel", "Google Pixel 10 Pro XL", seenAgo = 5 * 60.0, build = "release", decides = true),
            device("emulator", "Google sdk_gphone64_arm64", seenAgo = hour, counts = false, build = "debug"),
        )

        val list = HouseholdDeviceList.of(devices, now)

        assertEquals(listOf("pixel", "iphone", "pixel-debug"), list.primary.map { it.id }, "the decider, the other phone in use, then this debug install")
        assertEquals(listOf("emulator", "iphone-old"), list.others.map { it.id })
        assertEquals(0, list.countedOthers, "nothing folded away still votes")
    }

    @Test
    fun theDecidingPhoneIsNeverFoldedAway() {
        val list = HouseholdDeviceList.of(listOf(device("pixel", "Google Pixel", seenAgo = 30 * day, build = "release", decides = true)), now)
        assertEquals(listOf("pixel"), list.primary.map { it.id })
    }

    @Test
    fun aTestInstallIsAnAndroidDebugBuildButNeverAnIphone() {
        assertTrue(device("a", "Google Pixel", seenAgo = null, build = "debug").isTestInstall)
        assertFalse(device("b", "Google Pixel", seenAgo = null, counts = false, build = "release").isTestInstall)
        assertFalse(device("c", "Apple iPhone", seenAgo = null, counts = false, build = "debug").isTestInstall)
        assertTrue(device("d", "Google Pixel", seenAgo = null, counts = false).isTestInstall, "no build said: whatever doesn't count")
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
        val justNow = UiText.of(Res.string.presence_last_seen_just_now)
        assertEquals(justNow, formatLastSeen(now - 30, now))
        assertEquals(justNow, formatLastSeen(now + 90, now), "a clock skew never reads as the future")
        assertEquals(UiText.plural(Res.plurals.presence_last_seen_minutes, 12), formatLastSeen(now - 12 * 60, now))
        assertEquals(UiText.plural(Res.plurals.presence_last_seen_hours, 3), formatLastSeen(now - 3 * hour - 59 * 60, now))
        assertEquals(UiText.of(Res.string.presence_last_seen_yesterday), formatLastSeen(now - 30 * hour, now))
        assertEquals(UiText.plural(Res.plurals.presence_last_seen_days, 5), formatLastSeen(now - 5 * day, now))
    }
}
