package com.meticulouscreations.homesafe.fitness.domain

import com.meticulouscreations.homesafe.fitness.FitnessTestData
import com.meticulouscreations.homesafe.fitness.FitnessTestData.JUNE_2022
import com.meticulouscreations.homesafe.fitness.FitnessTestData.NOV_2021
import com.meticulouscreations.homesafe.fitness.FitnessTestData.SEPT_2021
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesParserTest {

    private val parsed = NotesParser.parse(FitnessTestData.NOTES)

    private fun exercise(name: String, section: BodyPart?): ParsedExercise =
        parsed.exercises.single { it.name == name && it.section == section }

    private fun pounds(weight: Double, reps: Int, bodyweight: Double? = null, note: String = "", epochSeconds: Long? = null) =
        ParsedSet(weight, reps, ParsedLoad.POUNDS, bodyweight = bodyweight, note = note, epochSeconds = epochSeconds)

    private fun repsOnly(reps: Int, note: String = "", recent: Boolean = false) =
        ParsedSet(0.0, reps, ParsedLoad.NONE, note = note, recent = recent)

    // ---- The whole note -----------------------------------------------------------------------

    @Test
    fun theNotesParseWithNoSkippedLines() {
        assertEquals(emptyList(), parsed.skipped)
    }

    @Test
    fun everyExerciseSitsOnTheShelfOfTheHeadingAboveIt() {
        val expected = listOf(
            "Shoulder press machine" to BodyPart.SHOULDERS,
            "Rear delt fly" to BodyPart.SHOULDERS,
            "Middle delt cable" to BodyPart.SHOULDERS,
            "Middle delt cable (strict home)" to BodyPart.SHOULDERS,
            "Shoulder dumbbell press" to BodyPart.SHOULDERS,
            "Hack squat" to BodyPart.LEGS,
            "Hack squat quad focused" to BodyPart.LEGS,
            "Leg press" to BodyPart.LEGS,
            "Calf machine" to BodyPart.LEGS,
            "Calf dumbbell (2 legs)" to BodyPart.LEGS,
            "45 degree leg press calf raise" to BodyPart.LEGS,
            "Lat pulldown (strict)" to BodyPart.BACK,
            "Lat pulldown (other machine)" to BodyPart.BACK,
            "Ring pull ups" to BodyPart.BACK,
            "Weighted pullups" to BodyPart.BACK,
            "Extension machine" to BodyPart.TRICEPS,
            "Single arm cable" to BodyPart.TRICEPS,
            "Dips" to BodyPart.TRICEPS,
            "Single arm cable" to BodyPart.BICEPS,
            "Ez curl" to BodyPart.BICEPS,
            "Ab machine" to null,
            "Pull-ups" to null,
            "Ring dips" to null,
            "Pistol squats" to null,
            "Lat pulldown" to null,
            "45 degree row" to null,
            "Dip press" to null,
            "Leg press" to null,
            "Seated leg curl" to null,
            "Smith squat" to BodyPart.LEGS,
            "Hamstring machine curl" to BodyPart.LEGS,
            "Smith bench" to BodyPart.CHEST,
            "Calf raises" to null,
        )
        assertEquals(expected, parsed.exercises.map { it.name to it.section })
    }

    @Test
    fun everySetInTheNotesIsCounted() {
        assertEquals(60, parsed.setCount)
    }

    // ---- How a set is written -----------------------------------------------------------------

    @Test
    fun aDashRunTogetherWithTheWeightStillStartsASet() {
        assertEquals(
            listOf(pounds(120.0, 14), pounds(130.0, 13), pounds(140.0, 12)),
            exercise("Shoulder press machine", BodyPart.SHOULDERS).sets,
        )
    }

    @Test
    fun aSpaceAfterTheRepsChangesNothing() {
        assertEquals(
            listOf(pounds(180.0, 12), pounds(200.0, 12), pounds(220.0, 11), pounds(300.0, 10, note = "3plates+15lbs")),
            exercise("Hack squat", BodyPart.LEGS).sets,
        )
    }

    @Test
    fun aWeightWithTheRepsLeftBlankIsAnOpenWeightNotASet() {
        val rearDeltFly = exercise("Rear delt fly", BodyPart.SHOULDERS)
        assertEquals(listOf(pounds(70.0, 16), pounds(80.0, 15)), rearDeltFly.sets)
        assertEquals(listOf(90.0), rearDeltFly.openWeights)
    }

    @Test
    fun aWeightFollowedByTheWordRepsAloneIsAnOpenWeightToo() {
        val extension = exercise("Extension machine", BodyPart.TRICEPS)
        assertEquals(listOf(pounds(60.0, 16)), extension.sets)
        assertEquals(listOf(80.0), extension.openWeights)
    }

    @Test
    fun anExerciseWithNoBlankRungHasNoOpenWeights() {
        assertEquals(emptyList(), exercise("Shoulder press machine", BodyPart.SHOULDERS).openWeights)
    }

    @Test
    fun repsGluedToTheirNumberAreStillReps() {
        val press = exercise("Shoulder dumbbell press", BodyPart.SHOULDERS)
        assertEquals(14, press.sets[1].reps)
        assertEquals(55.0, press.sets[1].weight)
    }

    @Test
    fun timesTwoMarksAPairOfDumbbellsAndTheWeightIsOneOfThem() {
        val press = exercise("Shoulder dumbbell press", BodyPart.SHOULDERS)
        assertEquals(
            listOf(
                ParsedSet(40.0, 20, ParsedLoad.POUNDS, pair = true),
                ParsedSet(55.0, 14, ParsedLoad.POUNDS, pair = true),
                ParsedSet(60.0, 6, ParsedLoad.POUNDS, pair = true),
            ),
            press.sets,
        )
    }

    @Test
    fun aPairWrittenWithTwoSpacesBeforeTheTimesIsStillAPair() {
        assertEquals(
            listOf(ParsedSet(25.0, 9, ParsedLoad.POUNDS, pair = true)),
            exercise("Ez curl", BodyPart.BICEPS).sets,
        )
    }

    @Test
    fun aPlateCountIsFortyFivePoundsASideKeptAsTheTotal() {
        val legPress = exercise("Leg press", BodyPart.LEGS)
        assertEquals(270.0, legPress.sets[0].weight)
        assertEquals(13, legPress.sets[0].reps)
        assertEquals(ParsedLoad.PLATES, legPress.sets[0].load)
    }

    @Test
    fun poundsAfterThePlatesAreExtraOnEachSideAndSoAreDoubled() {
        val legPress = exercise("Leg press", BodyPart.LEGS)
        // (3 * 45 + 25) * 2
        assertEquals(320.0, legPress.sets[1].weight)
        assertEquals(ParsedLoad.PLATES, legPress.sets[1].load)
        // (4 * 45 + 25) * 2, with the plus sign
        assertEquals(410.0, legPress.sets[2].weight)
        assertEquals(450.0, legPress.sets[3].weight)
    }

    @Test
    fun aPlateCountAloneOnAHeadedExerciseIsPlatesWithAPlusAfterTheRepsDropped() {
        assertEquals(
            listOf(ParsedSet(360.0, 15, ParsedLoad.PLATES)),
            exercise("45 degree leg press calf raise", BodyPart.LEGS).sets,
        )
    }

    @Test
    fun plateMathInBracketsIsARemarkAndTheWeightStaysPounds() {
        val last = exercise("Hack squat", BodyPart.LEGS).sets.last()
        assertEquals(ParsedLoad.POUNDS, last.load)
        assertEquals(300.0, last.weight)
        assertEquals("3plates+15lbs", last.note)
    }

    @Test
    fun aBareNumberBeforeTheRepsIsAMachinePinLevel() {
        assertEquals(
            listOf(
                ParsedSet(14.0, 29, ParsedLoad.LEVEL),
                ParsedSet(15.0, 30, ParsedLoad.LEVEL),
                ParsedSet(16.0, 25, ParsedLoad.LEVEL),
            ),
            exercise("Calf machine", BodyPart.LEGS).sets,
        )
    }

    @Test
    fun aLevelWithTheDashRunIntoTheRepsIsStillALevel() {
        val sixteen = exercise("Calf machine", BodyPart.LEGS).sets[2]
        assertEquals(16.0, sixteen.weight)
        assertEquals(25, sixteen.reps)
    }

    @Test
    fun levelsOnAnUnheadedMachineAreLevelsToo() {
        assertEquals(
            listOf(ParsedSet(10.0, 16, ParsedLoad.LEVEL), ParsedSet(11.0, 16, ParsedLoad.LEVEL)),
            exercise("Ab machine", null).sets,
        )
    }

    @Test
    fun repsWrittenBeforeTheWeightAreRead() {
        assertEquals(
            listOf(pounds(55.0, 29), pounds(60.5, 24)),
            exercise("Calf dumbbell (2 legs)", BodyPart.LEGS).sets,
        )
    }

    @Test
    fun aBracketOnAnExerciseHeadingStaysInItsName() {
        assertTrue(parsed.exercises.any { it.name == "Calf dumbbell (2 legs)" })
        assertTrue(parsed.exercises.any { it.name == "Middle delt cable (strict home)" })
    }

    @Test
    fun aBracketedVariantIsAnExerciseOfItsOwnBesideThePlainOne() {
        assertEquals(
            listOf(pounds(15.0, 21)),
            exercise("Middle delt cable (strict home)", BodyPart.SHOULDERS).sets,
        )
        assertEquals(3, exercise("Middle delt cable", BodyPart.SHOULDERS).sets.size)
    }

    @Test
    fun repsAloneAreRepsWithNoLoadAndNoWeight() {
        assertEquals(listOf(repsOnly(15)), exercise("Ring pull ups", BodyPart.BACK).sets)
        assertEquals(listOf(repsOnly(35)), exercise("Dips", BodyPart.TRICEPS).sets)
        assertEquals(listOf(repsOnly(15)), exercise("Ring dips", null).sets)
    }

    @Test
    fun allTimeAfterTheRepsIsARemarkAndNotARecentMark() {
        val allTime = exercise("Pull-ups", null).sets[0]
        assertEquals(22, allTime.reps)
        assertEquals("all time", allTime.note)
        assertEquals(false, allTime.recent)
    }

    @Test
    fun recentlyAfterTheRepsMarksTheSetAsWhereTheLifterStandsNow() {
        assertEquals(repsOnly(17, note = "recently", recent = true), exercise("Pull-ups", null).sets[1])
    }

    @Test
    fun aBodyweightAfterTheSetIsKeptAndNotTakenForTheWeight() {
        assertEquals(
            listOf(pounds(25.0, 11, bodyweight = 180.0), pounds(40.0, 8, bodyweight = 176.0)),
            exercise("Weighted pullups", BodyPart.BACK).sets,
        )
    }

    @Test
    fun aBodyweightInBracketsBeforeTheWordIsKeptToo() {
        assertEquals(
            listOf(pounds(25.0, 12, bodyweight = 180.0), pounds(35.0, 12, bodyweight = 175.0)),
            exercise("Pistol squats", null).sets,
        )
    }

    @Test
    fun aBodyweightInBracketsLeavesNoRemark() {
        assertEquals("", exercise("Pistol squats", null).sets[1].note)
    }

    // ---- Remarks ------------------------------------------------------------------------------

    @Test
    fun aRemarkInBracketsAfterTheSetLandsInTheNote() {
        assertEquals(
            pounds(16.5, 20, note = "gym version"),
            exercise("Middle delt cable", BodyPart.SHOULDERS).sets[2],
        )
    }

    @Test
    fun wordsAfterTheRepsOnAnOtherwiseBareSetAreTheNote() {
        val set = exercise("Hack squat quad focused", BodyPart.LEGS).sets[1]
        assertEquals(210.0, set.weight)
        assertEquals(8, set.reps)
        assertEquals("pause full rom", set.note)
    }

    @Test
    fun ishAfterTheRepsIsANote() {
        assertEquals(pounds(27.5, 14, note = "ish"), exercise("Single arm cable", BodyPart.BICEPS).sets[1])
    }

    @Test
    fun adjustedBetweenTheWeightAndTheRepsIsANote() {
        assertEquals(
            listOf(pounds(185.0, 8, note = "adjusted", epochSeconds = SEPT_2021)),
            exercise("Smith bench", BodyPart.CHEST).sets,
        )
    }

    @Test
    fun straightLegAfterTheRepsIsANote() {
        assertEquals(
            listOf(pounds(35.0, 10, note = "straight leg", epochSeconds = NOV_2021)),
            exercise("Calf raises", null).sets,
        )
    }

    // ---- Names --------------------------------------------------------------------------------

    @Test
    fun otherMachineUnderAnExerciseIsThatExerciseOnTheOtherMachine() {
        val other = exercise("Lat pulldown (other machine)", BodyPart.BACK)
        assertEquals(listOf(pounds(210.0, 12), pounds(220.0, 12)), other.sets)
        assertEquals(listOf(pounds(170.0, 13), pounds(187.0, 12)), exercise("Lat pulldown (strict)", BodyPart.BACK).sets)
    }

    @Test
    fun otherMachineWithNothingAboveItKeepsItsOwnName() {
        val result = NotesParser.parse("Other machine\n- 100lbs - 10 reps")
        assertEquals(listOf("Other machine"), result.exercises.map { it.name })
    }

    @Test
    fun maxIsDroppedFromAPersonalRecordsName() {
        val names = parsed.exercises.map { it.name }
        assertTrue("Pull-ups" in names && "Ring dips" in names && "Pistol squats" in names)
        assertTrue(names.none { it.startsWith("Max", ignoreCase = true) })
    }

    @Test
    fun aLowercaseNameStartsWithACapital() {
        assertEquals(
            listOf(pounds(110.0, 12, epochSeconds = SEPT_2021)),
            exercise("Hamstring machine curl", BodyPart.LEGS).sets,
        )
    }

    @Test
    fun aNameThatOpensWithANumberIsAnExerciseHeading() {
        val heading = exercise("45 degree leg press calf raise", BodyPart.LEGS)
        assertEquals(1, heading.sets.size)
    }

    @Test
    fun aNameThatOpensWithANumberCanBeFollowedByASetOnTheSameLine() {
        assertEquals(
            listOf(pounds(65.0, 8, epochSeconds = JUNE_2022)),
            exercise("45 degree row", null).sets,
        )
    }

    @Test
    fun singleArmCableIsKeptSeparatelyUnderTrisAndBis() {
        assertEquals(
            listOf(pounds(15.0, 21), pounds(17.5, 13)),
            exercise("Single arm cable", BodyPart.TRICEPS).sets,
        )
        assertEquals(
            listOf(pounds(22.0, 17), pounds(27.5, 14, note = "ish")),
            exercise("Single arm cable", BodyPart.BICEPS).sets,
        )
    }

    // ---- One-line sets, and dated headings ----------------------------------------------------

    @Test
    fun aNameAndAWholeSetOnOneLineIsAnExerciseWithThatSet() {
        assertEquals(listOf(pounds(225.0, 4, epochSeconds = JUNE_2022)), exercise("Lat pulldown", null).sets)
    }

    @Test
    fun aDashRunIntoTheWeightAfterAOneLineNameIsNotPartOfTheName() {
        assertEquals(listOf(pounds(150.0, 10, epochSeconds = JUNE_2022)), exercise("Dip press", null).sets)
    }

    @Test
    fun aLineWithOnlyAWeightAndRepsContinuesTheExerciseAbove() {
        assertEquals(
            listOf(pounds(160.0, 6, epochSeconds = JUNE_2022), pounds(110.0, 8, epochSeconds = JUNE_2022)),
            exercise("Seated leg curl", null).sets,
        )
    }

    @Test
    fun anIndentedContinuationDoesNotStartAnExercise() {
        assertEquals(
            1,
            parsed.exercises.count { it.name == "Seated leg curl" },
        )
        assertTrue(parsed.exercises.none { it.name.startsWith("110") })
    }

    @Test
    fun aMonthAndTwoDigitYearHeadingIsTheMiddleOfThatMonthInTwentyTwentyTwo() {
        assertEquals(JUNE_2022, exercise("Leg press", null).sets.single().epochSeconds)
        assertEquals(NotesParser.epochDay(2022, 6, 15) * 86_400L + 43_200L, JUNE_2022)
    }

    @Test
    fun aMonthAndFourDigitYearHeadingIsTheMiddleOfThatMonth() {
        assertEquals(SEPT_2021, exercise("Hamstring machine curl", BodyPart.LEGS).sets.single().epochSeconds)
        assertEquals(NotesParser.epochDay(2021, 9, 15) * 86_400L + 43_200L, SEPT_2021)
        assertEquals(NotesParser.epochDay(2021, 11, 15) * 86_400L + 43_200L, NOV_2021)
    }

    @Test
    fun undatedLadderSetsCarryNoDate() {
        assertTrue(exercise("Hack squat", BodyPart.LEGS).sets.all { it.epochSeconds == null })
        assertTrue(exercise("Ab machine", null).sets.all { it.epochSeconds == null })
    }

    @Test
    fun aLegsHeadingUnderADatedHeadingKeepsTheDate() {
        val squat = exercise("Smith squat", BodyPart.LEGS)
        assertEquals(SEPT_2021, squat.sets.first().epochSeconds)
        assertEquals(BodyPart.LEGS, squat.section)
    }

    @Test
    fun aDatedHeadingResetsTheShelf() {
        // "Philly June 22" comes after the shoulders, legs, back and arms: nothing below it sits on one of their shelves.
        assertNull(exercise("Lat pulldown", null).section)
    }

    @Test
    fun theSameExerciseUnderTwoDatedHeadingsIsOneExerciseWithTwoDatedSets() {
        assertEquals(
            listOf(
                pounds(225.0, 3, epochSeconds = SEPT_2021),
                pounds(225.0, 5, epochSeconds = NOV_2021),
            ),
            exercise("Smith squat", BodyPart.LEGS).sets,
        )
    }

    @Test
    fun aPartHeadingChangesTheShelfButNotTheDate() {
        val result = NotesParser.parse("Philly Sept 2021\nChest\nBench\n- 100lbs - 10 reps\nBack\nRow\n- 90lbs - 10 reps")
        assertEquals(listOf(BodyPart.CHEST, BodyPart.BACK), result.exercises.map { it.section })
        assertEquals(listOf(SEPT_2021, SEPT_2021), result.exercises.map { it.sets.single().epochSeconds })
    }

    // ---- Headings -----------------------------------------------------------------------------

    @Test
    fun bisAndTrisClearsTheShelfSoTheNextExerciseIsPlacedByItsName() {
        val result = NotesParser.parse("Back\nRow\n- 100lbs - 10 reps\nBis & Tris\nCurl\n- 30lbs - 10 reps")
        assertEquals(listOf(BodyPart.BACK, null), result.exercises.map { it.section })
    }

    @Test
    fun theHeadingsNamingABodyPartMapToItsShelf() {
        val text = listOf(
            "Chest" to BodyPart.CHEST,
            "Back" to BodyPart.BACK,
            "Legs" to BodyPart.LEGS,
            "Leg day" to BodyPart.LEGS,
            "Shoulders" to BodyPart.SHOULDERS,
            "Delts" to BodyPart.SHOULDERS,
            "Tris" to BodyPart.TRICEPS,
            "Triceps" to BodyPart.TRICEPS,
            "Bis" to BodyPart.BICEPS,
            "Biceps" to BodyPart.BICEPS,
            "Abs" to BodyPart.CORE,
            "Core" to BodyPart.CORE,
        )
        for ((heading, part) in text) {
            val result = NotesParser.parse("$heading\nThing\n- 10lbs - 10 reps")
            assertEquals(part, result.exercises.single().section, heading)
        }
    }

    @Test
    fun personalRecordsAndGymAreTitlesThatResetTheShelfAndTheDate() {
        val result = NotesParser.parse(
            "Philly June 22\nRow - 100lbs 5 reps\nGym\nCurl - 30lbs 8 reps\nLegs\nPress\n- 200lbs - 10 reps\nPersonal Records\nDips - 20 reps",
        )
        val byName = result.exercises.associateBy { it.name }
        assertEquals(JUNE_2022, byName.getValue("Row").sets.single().epochSeconds)
        assertNull(byName.getValue("Curl").sets.single().epochSeconds)
        assertEquals(BodyPart.LEGS, byName.getValue("Press").section)
        assertNull(byName.getValue("Dips").section)
    }

    @Test
    fun aTitleGoesBackToTheDefaultShelfWhenOneWasGiven() {
        val result = NotesParser.parse("Legs\nSquat\n- 200lbs - 5 reps\nPersonal Records\nCurl\n- 30lbs - 8 reps", defaultPart = BodyPart.BICEPS)
        assertEquals(listOf(BodyPart.LEGS, BodyPart.BICEPS), result.exercises.map { it.section })
    }

    @Test
    fun theDefaultShelfIsWhereAnExerciseWithNoHeadingSits() {
        val result = NotesParser.parse("Curl\n- 30lbs - 8 reps", defaultPart = BodyPart.BICEPS)
        assertEquals(BodyPart.BICEPS, result.exercises.single().section)
    }

    @Test
    fun aMonthWithoutAYearIsNotADatedHeading() {
        val result = NotesParser.parse("Philly June\nRow\n- 100lbs - 5 reps")
        // Not a date, so it reads as a heading with nothing under it, which is handed back.
        assertEquals(listOf("Philly June"), result.skipped)
        assertNull(result.exercises.single { it.name == "Row" }.sets.single().epochSeconds)
    }

    // ---- Units and odd characters -------------------------------------------------------------

    @Test
    fun kilogramsAreConvertedToPoundsToTheNearestHalf() {
        val set = NotesParser.parse("Row\n- 40kg - 10 reps").exercises.single().sets.single()
        assertEquals(88.0, set.weight)
        assertEquals(ParsedLoad.POUNDS, set.load)
    }

    @Test
    fun bulletsAndDashesInFrontOfALineAreIgnored() {
        val result = NotesParser.parse("• Row\n* 100lbs - 10 reps\n– 110lbs - 8 reps\n— 120lbs - 6 reps")
        assertEquals(listOf(100.0, 110.0, 120.0), result.exercises.single().sets.map { it.weight })
    }

    @Test
    fun aZeroRepLineIsAnOpenWeightNotASet() {
        val row = NotesParser.parse("Row\n- 100lbs - 0 reps").exercises.single()
        assertEquals(emptyList(), row.sets)
        assertEquals(listOf(100.0), row.openWeights)
    }

    @Test
    fun aPoundSignIsPounds() {
        val set = NotesParser.parse("Row\n- 100# - 10 reps").exercises.single().sets.single()
        assertEquals(100.0, set.weight)
    }

    @Test
    fun aDecimalWeightIsKept() {
        assertEquals(12.5, exercise("Middle delt cable", BodyPart.SHOULDERS).sets[1].weight)
    }

    // ---- What it will not guess at ------------------------------------------------------------

    @Test
    fun aSetBeforeAnyExerciseIsHandedBackAsSkipped() {
        val result = NotesParser.parse("- 100lbs - 10 reps\nRow\n- 90lbs - 10 reps")
        assertEquals(listOf("- 100lbs - 10 reps"), result.skipped)
        assertEquals(listOf(90.0), result.exercises.single().sets.map { it.weight })
    }

    @Test
    fun aSetAfterATitleIsSkippedBecauseNoExerciseIsOpen() {
        val result = NotesParser.parse("Row\n- 100lbs - 10 reps\nPersonal Records\n- 90lbs - 10 reps")
        assertEquals(listOf("- 90lbs - 10 reps"), result.skipped)
    }

    @Test
    fun blankTextParsesToNothing() {
        val result = NotesParser.parse("  \n\n \t\n")
        assertEquals(emptyList(), result.exercises)
        assertEquals(emptyList(), result.skipped)
        assertEquals(0, result.setCount)
    }

    @Test
    fun aHeadingWithNothingUnderItIsHandedBackNotMadeAnExercise() {
        val result = NotesParser.parse("Cable fly\nwhat was this again")
        assertEquals(emptyList(), result.exercises)
        assertEquals(listOf("Cable fly", "What was this again"), result.skipped)
    }

    @Test
    fun aHeadingWithOnlyABlankRungIsStillAnExercise() {
        val exercise = NotesParser.parse("Cable fly\n- 40lbs - ").exercises.single()
        assertEquals("Cable fly", exercise.name)
        assertEquals(listOf(40.0), exercise.openWeights)
    }
}
