package com.meticulouscreations.homesafe.desktop

import com.meticulouscreations.homesafe.finance.ui.FinanceTab
import com.meticulouscreations.homesafe.navigation.DoorDeepLink
import com.meticulouscreations.homesafe.navigation.DoorDeepLinks
import com.meticulouscreations.homesafe.navigation.FinanceDeepLink
import com.meticulouscreations.homesafe.navigation.FinanceDeepLinks
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import com.meticulouscreations.homesafe.navigation.MomentDeepLinks
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.navigation.WeatherDeepLinks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A desktop notification's link, as the Mac's Notification Center keeps it and hands it back on a
 * click, opens the same place the notifier pointed at: the door, the detection, the weather or
 * the finance tab.
 */
class DesktopNotificationsTest {
    @Test
    fun aDoorsLinkOpensThatDoor() {
        val door = DoorDeepLink("front")
        DesktopNotice("Front door", "Opened", urgent = true, link = door.toUri()).open()
        assertEquals(door, DoorDeepLinks.pending.value)
        DoorDeepLinks.consume(door)
    }

    @Test
    fun aDetectionsLinkOpensThatMoment() {
        val moment = MomentDeepLink(eventId = "1700000000.1-abc", cameraName = "driveway", startEpochSeconds = 1_700_000_000.1)
        DesktopNotice("Person", "Driveway", urgent = false, link = moment.toUri()).open()
        assertEquals(moment, MomentDeepLinks.pending.value)
        MomentDeepLinks.consume(moment)
    }

    @Test
    fun aWeatherLinkOpensTheWeatherApp() {
        val weather = WeatherDeepLink()
        DesktopNotice("Rain soon", "In 20 minutes", urgent = false, link = weather.toUri()).open()
        assertEquals(weather, WeatherDeepLinks.pending.value)
        WeatherDeepLinks.consume(weather)
    }

    @Test
    fun aBudgetLinkOpensItsFinanceTab() {
        val finance = FinanceDeepLink(FinanceTab.BUDGET)
        DesktopNotice("Groceries", "Over budget", urgent = false, link = finance.toUri()).open()
        assertEquals(finance, FinanceDeepLinks.pending.value)
        FinanceDeepLinks.consume(finance)
    }
}
