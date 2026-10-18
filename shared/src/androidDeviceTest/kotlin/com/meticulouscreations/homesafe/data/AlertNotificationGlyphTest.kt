package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.navigation.DoorDeepLink
import com.meticulouscreations.homesafe.ui.notification.NotificationGlyph
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which icon a camera's notification wears in the status bar: what was seen where that is known, and what kind of news it is where that matters more. */
class AlertNotificationGlyphTest {

    private fun alert(subject: MomentCategory = MomentCategory.ALL, urgent: Boolean = false, summary: Boolean = false, door: DoorDeepLink? = null) =
        AlertNotification(id = "1", title = "t", body = "b", urgent = urgent, summary = summary, subject = subject, door = door)

    @Test
    fun whatWasSeenIsTheIconWhereItIsKnown() {
        assertEquals(NotificationGlyph.PERSON, AlertNotificationPoster.glyph(alert(MomentCategory.PEOPLE)))
        assertEquals(NotificationGlyph.CAR, AlertNotificationPoster.glyph(alert(MomentCategory.VEHICLES)))
        assertEquals(NotificationGlyph.ANIMAL, AlertNotificationPoster.glyph(alert(MomentCategory.ANIMALS)))
        assertEquals(NotificationGlyph.CAMERA, AlertNotificationPoster.glyph(alert()))
    }

    @Test
    fun someoneSeenWhileNobodyIsHomeWearsTheShieldWhateverTheyAre() {
        assertEquals(NotificationGlyph.AWAY, AlertNotificationPoster.glyph(alert(MomentCategory.PEOPLE, urgent = true)))
        assertEquals(NotificationGlyph.AWAY, AlertNotificationPoster.glyph(alert(urgent = true)))
    }

    @Test
    fun aDoorThatOpenedWearsTheDoor() {
        assertEquals(NotificationGlyph.DOOR, AlertNotificationPoster.glyph(alert(door = DoorDeepLink("back_door"))))
        assertEquals(NotificationGlyph.DOOR, AlertNotificationPoster.glyph(alert(door = DoorDeepLink())))
    }

    @Test
    fun aDoorOpenedWhileNobodyIsHomeWearsTheShieldLikeEveryAwayAlert() {
        assertEquals(NotificationGlyph.AWAY, AlertNotificationPoster.glyph(alert(urgent = true, door = DoorDeepLink("back_door"))))
    }

    @Test
    fun aSummaryWearsTheList() {
        assertEquals(NotificationGlyph.SUMMARY, AlertNotificationPoster.glyph(alert(summary = true)))
    }
}
