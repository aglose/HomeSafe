package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.navigation.PackageDeepLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageAlertTest {
    private fun alert(vararg data: Pair<String, String>) = PackageAlert.from { mapOf(*data)[it] }

    @Test
    fun aDeliveryCarriesItsNameAndWhatTheCameraSaid() {
        val alert = alert("package" to "a", "package_event" to "delivered", "package_name" to "Socks", "package_verdict" to "confirmed")
        assertEquals(PackageAlert(packageId = "a", event = PackageAlert.Event.DELIVERED, name = "Socks", masked = false, verdict = "confirmed", count = 1, away = false), alert)
    }

    @Test
    fun aSurpriseKeptFromThisPhoneHasNoName() {
        val alert = alert("package" to "a", "package_event" to "out_for_delivery", "package_name" to "", "package_masked" to "1")
        assertNull(alert?.name)
        assertEquals(true, alert?.masked)
    }

    @Test
    fun theMorningsListIsAboutNoOneParcel() {
        val alert = alert("package" to "", "package_event" to "morning", "package_count" to "3", "package_away" to "1")
        assertEquals(3, alert?.count)
        assertEquals(true, alert?.away)
        assertEquals("", alert?.packageId)
    }

    @Test
    fun anEventThisBuildCantWordIsLeftToTheRelaysEnglish() {
        assertNull(alert("package" to "a", "package_event" to "teleported"))
        assertTrue(PackageAlert.isPackage { mapOf("package_event" to "teleported")[it] })
    }

    @Test
    fun aPushAboutSomethingElseIsntOne() {
        assertFalse(PackageAlert.isPackage { mapOf("door" to "front")[it] })
    }

    @Test
    fun everyEventTheRelaySendsCanBeWorded() {
        // The kinds `package_notify` and `package_morning` in relay/relay.py send.
        listOf("shipped", "out_for_delivery", "delivered", "porch", "rain", "missing", "camera_only", "problem_attempted", "problem_exception", "signature", "late", "return_closing", "morning").forEach { kind ->
            assertTrue(alert("package" to "a", "package_event" to kind) != null, kind)
        }
    }

    @Test
    fun theLinkRoundTrips() {
        assertEquals("homesafe://packages?package=a", PackageDeepLink("a").toUri())
        assertEquals("homesafe://packages", PackageDeepLink().toUri())
        assertEquals(PackageDeepLink("a"), PackageDeepLink.fromUri("homesafe://packages?package=a"))
        assertEquals(PackageDeepLink(), PackageDeepLink.fromUri("homesafe://packages"))
        assertNull(PackageDeepLink.fromUri("homesafe://doors"))
        assertEquals(PackageDeepLink("a"), PackageDeepLink.from { mapOf("package" to "a")[it] })
        assertEquals(PackageDeepLink(), PackageDeepLink.from { mapOf("package" to "")[it] })
        assertNull(PackageDeepLink.from { null })
    }
}
