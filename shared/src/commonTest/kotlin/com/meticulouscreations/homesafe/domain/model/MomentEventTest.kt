package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.moments_date_short
import homesafe.shared.generated.resources.moments_date_today
import homesafe.shared.generated.resources.moments_date_yesterday
import homesafe.shared.generated.resources.moments_label_dog
import homesafe.shared.generated.resources.moments_month_aug
import homesafe.shared.generated.resources.moments_month_sep
import homesafe.shared.generated.resources.moments_weekday_sunday
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MomentEventTest {

    private val utc = TimeZone.UTC
    private fun event(label: String = "person", start: Double, end: Double? = start + 15, sub: String? = null, zones: List<String> = emptyList()) = MomentEvent(
        id = "e", cameraName = "hikvision_1", label = label, subLabel = sub,
        startEpochSeconds = start, endEpochSeconds = end, topScore = 0.9, hasClip = true, hasSnapshot = true, zones = zones,
    )

    @Test
    fun labelsMapOntoTheThreeChips() {
        assertEquals(MomentCategory.PEOPLE, categoryForLabel("person"))
        assertEquals(MomentCategory.VEHICLES, categoryForLabel("car"))
        assertEquals(MomentCategory.VEHICLES, categoryForLabel("Truck"))
        assertEquals(MomentCategory.ANIMALS, categoryForLabel("dog"))
        // Unknown labels are still shown under All Events, just not under any specific chip.
        assertEquals(MomentCategory.ALL, categoryForLabel("package"))
    }

    @Test
    fun groupsAsTodayYesterdayWeekdayThenDate() {
        val today = LocalDate(2026, 9, 2)                // a Wednesday
        val noon = { d: LocalDate -> d.toEpochDays() * 86_400.0 + 12 * 3_600 }
        assertEquals(UiText.of(Res.string.moments_date_today), event(start = noon(LocalDate(2026, 9, 2))).present(today, utc).dateGroup)
        assertEquals(UiText.of(Res.string.moments_date_yesterday), event(start = noon(LocalDate(2026, 9, 1))).present(today, utc).dateGroup)
        assertEquals(UiText.of(Res.string.moments_weekday_sunday), event(start = noon(LocalDate(2026, 8, 30))).present(today, utc).dateGroup)
        assertEquals(shortDate(Res.string.moments_month_aug, 20), event(start = noon(LocalDate(2026, 8, 20))).present(today, utc).dateGroup)
        assertEquals(shortDate(Res.string.moments_month_sep, 2), event(start = noon(LocalDate(2026, 9, 2))).present(today, utc).dateSubLabel)
    }

    @Test
    fun formatsTimeDurationTitleAndBadge() {
        val today = LocalDate(2026, 9, 2)
        val start = LocalDate(2026, 9, 2).toEpochDays() * 86_400.0 + 8 * 3_600 + 42 * 60   // 08:42 UTC
        val p = event(start = start, end = start + 15, sub = "andrew").present(today, utc)
        assertEquals("8:42 AM".asUiText(), p.timeLabel)
        assertEquals("0:15", p.durationLabel)
        assertEquals(MomentTexts.detected(MomentTexts.named("Andrew")), p.title, "a recognised sub-label becomes the subject")
        assertEquals(MomentTexts.person, p.badgeLabel, "the name is in the title; the pill doesn't repeat it")
        assertEquals(MomentTexts.detected(MomentTexts.car), event(label = "car", start = start).present(today, utc).title)
        assertNull(p.sightingsLabel, "a single sighting has no sightings line")
    }

    @Test
    fun titlesSayWhoAndWhereWhenFrigateKnows() {
        val today = LocalDate(2026, 9, 5)
        val start = today.toEpochDays() * 86_400.0 + 9 * 3_600
        fun title(label: String, sub: String? = null, zones: List<String> = emptyList()) = event(label = label, start = start, sub = sub, zones = zones).present(today, utc).title
        assertEquals(MomentTexts.inThe(MomentTexts.named("Sarah's Tesla"), "driveway"), title("car", "sarahs_tesla", listOf("street", "driveway")))
        assertEquals(MomentTexts.onThe(MomentTexts.person, "front lawn"), title("person", zones = listOf("sidewalk", "front_lawn")))
        assertEquals(MomentTexts.onThe(UiText.of(Res.string.moments_label_dog), "sidewalk"), title("dog", zones = listOf("sidewalk")))
        assertEquals(MomentTexts.onThe(MomentTexts.car, "street"), title("car", zones = listOf("street")))
        assertEquals(MomentTexts.detected(MomentTexts.named("Sarah's Tesla")), title("car", "sarahs_tesla"))
        assertEquals(MomentTexts.car, event(label = "car", start = start, sub = "sarahs_tesla").present(today, utc).badgeLabel)
    }

    @Test
    fun labelsTheAppHasNoWordForAreShownAsFrigateWroteThem() {
        assertEquals(MomentTexts.person, labelName("Person"))
        assertEquals("Umbrella".asUiText(), labelName("umbrella"))
        assertEquals("Shopping cart".asUiText(), labelName("shopping_cart"))
    }

    @Test
    fun namesAreHumanisedFromFrigateKeys() {
        assertEquals("Sarah's Tesla", subLabelDisplayName("sarahs_tesla"))
        assertEquals("Delivery Van", subLabelDisplayName("delivery_van"))
        assertEquals("Andrew", subLabelDisplayName("andrew"))
        assertEquals("front lawn", zoneDisplayName("front_lawn"))
        assertTrue(isEnclosedZone("driveway"), "a driveway is somewhere to be in")
        assertTrue(isEnclosedZone("back_yard"), "a yard is somewhere to be in")
        assertFalse(isEnclosedZone("front_lawn"), "a lawn is somewhere to be on")
    }

    @Test
    fun anOwnersApostropheLostToTheKeyIsPutBack() {
        // The household's own categories, as the labelling screen's slug filed them.
        assertEquals("Andrew's Tesla", subLabelDisplayName("andrews_tesla"))
        assertEquals("Yaya's Car", subLabelDisplayName("yayas_car"))
        assertEquals("In-Laws' Mercedes", subLabelDisplayName("in_laws_mercedes"))
        assertEquals("Andrew's Model 3", subLabelDisplayName("andrews_model_3"))
        assertEquals("Yaya's BMW", subLabelDisplayName("yayas_bmw"))
        assertEquals("Parents' Van", subLabelDisplayName("parents_van"))
        // Names with an s of their own, with or without the apostrophe's s left in the key.
        assertEquals("James's Car", subLabelDisplayName("james_car"))
        assertEquals("James's Car", subLabelDisplayName("jamess_car"))
        assertEquals("Chris's Truck", subLabelDisplayName("chris_truck"))
        // Not the shape of an owner and a vehicle: left as it was.
        assertEquals("Andrews", subLabelDisplayName("andrews"))
        assertEquals("Andrew", subLabelDisplayName("andrew"))
        assertEquals("Bus Stop", subLabelDisplayName("bus_stop"))
        assertEquals("Andrews Delivery", subLabelDisplayName("andrews_delivery"))
        assertEquals("Boss Car", subLabelDisplayName("boss_car"), "a double s is left alone rather than guessed at")
        assertEquals("Known Cars", subLabelDisplayName("known_cars"))
        // The spelled-out names still win.
        assertEquals("Ron and Judy's Mercedes", subLabelDisplayName("ron_judys_mercedes"))
    }

    private fun shortDate(month: StringResource, day: Int): UiText = UiText.of(Res.string.moments_date_short, UiText.of(month), day)

    @Test
    fun inProgressEventHasNoDuration() {
        val today = LocalDate(2026, 9, 2)
        val e = event(start = today.toEpochDays() * 86_400.0, end = null)
        assertEquals(true, e.isInProgress)
        assertNull(e.present(today, utc).durationLabel)
    }
}
