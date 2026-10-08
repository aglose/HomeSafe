package com.meticulouscreations.homesafe.fitness.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ExerciseClassifierTest {

    private fun classification(
        bodyPart: BodyPart,
        equipment: Equipment,
        loadKind: LoadKind = LoadKind.WEIGHT,
        primary: Muscle,
        secondary: List<Muscle> = emptyList(),
        repBand: IntRange,
        increment: Double,
        restSeconds: Int,
    ) = Classification(bodyPart, equipment, loadKind, primary, secondary, repBand, increment, restSeconds)

    @Test
    fun aMachineIsolationOfASmallMuscleIsWorkedLightAndLong() {
        assertEquals(
            classification(BodyPart.SHOULDERS, Equipment.MACHINE, primary = Muscle.REAR_DELTS, repBand = 15..20, increment = 10.0, restSeconds = 90),
            ExerciseClassifier.classify("Rear delt fly"),
        )
    }

    @Test
    fun aMachineCompoundIsEightToTwelveWithTwoAndAHalfMinutesRest() {
        assertEquals(
            classification(BodyPart.LEGS, Equipment.MACHINE, primary = Muscle.QUADS, secondary = listOf(Muscle.GLUTES), repBand = 8..12, increment = 10.0, restSeconds = 150),
            ExerciseClassifier.classify("Hack squat"),
        )
    }

    @Test
    fun aLegPressIsAMachineForTheQuads() {
        assertEquals(
            classification(BodyPart.LEGS, Equipment.MACHINE, primary = Muscle.QUADS, secondary = listOf(Muscle.GLUTES), repBand = 8..12, increment = 10.0, restSeconds = 150),
            ExerciseClassifier.classify("Leg press"),
        )
    }

    @Test
    fun aBarbellPressIsSixToTenWithThreeMinutesRest() {
        assertEquals(
            classification(
                BodyPart.CHEST,
                Equipment.BARBELL,
                primary = Muscle.CHEST,
                secondary = listOf(Muscle.FRONT_DELTS, Muscle.TRICEPS),
                repBand = 6..10,
                increment = 5.0,
                restSeconds = 180,
            ),
            ExerciseClassifier.classify("Barbell bench press"),
        )
    }

    @Test
    fun aBarbellLegLiftJumpsByTen() {
        assertEquals(
            classification(BodyPart.LEGS, Equipment.BARBELL, primary = Muscle.QUADS, secondary = listOf(Muscle.GLUTES), repBand = 6..10, increment = 10.0, restSeconds = 180),
            ExerciseClassifier.classify("Barbell squat"),
        )
    }

    @Test
    fun aDeadliftWorksTheGlutesWithTheHamstringsHelping() {
        assertEquals(
            classification(BodyPart.LEGS, Equipment.BARBELL, primary = Muscle.GLUTES, secondary = listOf(Muscle.HAMSTRINGS), repBand = 6..10, increment = 10.0, restSeconds = 180),
            ExerciseClassifier.classify("Deadlift"),
        )
    }

    @Test
    fun aSmithSquatIsASmithMachineLift() {
        assertEquals(
            classification(BodyPart.LEGS, Equipment.SMITH, primary = Muscle.QUADS, secondary = listOf(Muscle.GLUTES), repBand = 8..12, increment = 10.0, restSeconds = 150),
            ExerciseClassifier.classify("Smith squat"),
        )
    }

    @Test
    fun aCalfMachineIsLightAndLongWithAMinuteOfRest() {
        assertEquals(
            classification(BodyPart.LEGS, Equipment.MACHINE, primary = Muscle.CALVES, repBand = 15..20, increment = 10.0, restSeconds = 60),
            ExerciseClassifier.classify("Calf machine"),
        )
    }

    @Test
    fun anAbMachineIsOnTheCoreShelfWithAMinuteOfRest() {
        assertEquals(
            classification(BodyPart.CORE, Equipment.MACHINE, primary = Muscle.ABS, repBand = 15..20, increment = 10.0, restSeconds = 60),
            ExerciseClassifier.classify("Ab machine"),
        )
    }

    @Test
    fun pullUpsAreBodyweightAndTheLoadIsWhatIsHungFromThem() {
        assertEquals(
            classification(
                BodyPart.BACK,
                Equipment.BODYWEIGHT,
                loadKind = LoadKind.BODYWEIGHT,
                primary = Muscle.BACK,
                secondary = listOf(Muscle.BICEPS),
                repBand = 8..15,
                increment = 5.0,
                restSeconds = 150,
            ),
            ExerciseClassifier.classify("Pull-ups"),
        )
    }

    @Test
    fun theCaseAndHyphensOfANameMakeNoDifference() {
        assertEquals(ExerciseClassifier.classify("pull ups"), ExerciseClassifier.classify("PULL-UPS"))
    }

    @Test
    fun dipsAreBodyweightForTheTricepsWithTheChestAndFrontDeltsHelping() {
        val expected = classification(
            BodyPart.TRICEPS,
            Equipment.BODYWEIGHT,
            loadKind = LoadKind.BODYWEIGHT,
            primary = Muscle.TRICEPS,
            secondary = listOf(Muscle.CHEST, Muscle.FRONT_DELTS),
            repBand = 8..15,
            increment = 5.0,
            restSeconds = 150,
        )
        assertEquals(expected, ExerciseClassifier.classify("Dips"))
        assertEquals(expected, ExerciseClassifier.classify("Dips", BodyPart.TRICEPS))
    }

    @Test
    fun anEzCurlIsABarbellIsolationForTheBiceps() {
        assertEquals(
            classification(BodyPart.BICEPS, Equipment.BARBELL, primary = Muscle.BICEPS, repBand = 10..15, increment = 5.0, restSeconds = 90),
            ExerciseClassifier.classify("Ez curl"),
        )
    }

    @Test
    fun aHammerCurlIsADumbbellCurlWithTheForearmsHelping() {
        assertEquals(
            classification(BodyPart.BICEPS, Equipment.DUMBBELL, primary = Muscle.BICEPS, secondary = listOf(Muscle.FOREARMS), repBand = 10..15, increment = 5.0, restSeconds = 90),
            ExerciseClassifier.classify("Hammer curl"),
        )
    }

    @Test
    fun aLegCurlIsOnTheLegsShelfNotTheBicepsOne() {
        assertEquals(
            classification(BodyPart.LEGS, Equipment.MACHINE, primary = Muscle.HAMSTRINGS, repBand = 10..15, increment = 10.0, restSeconds = 90),
            ExerciseClassifier.classify("Leg curl"),
        )
    }

    @Test
    fun aRearDeltRowIsOnTheShouldersShelfNotTheBackOne() {
        assertEquals(
            classification(
                BodyPart.SHOULDERS,
                Equipment.OTHER,
                primary = Muscle.REAR_DELTS,
                secondary = listOf(Muscle.BACK),
                repBand = 15..20,
                increment = 5.0,
                restSeconds = 90,
            ),
            ExerciseClassifier.classify("Rear delt row"),
        )
    }

    @Test
    fun aCableIsolationJumpsByTwoAndAHalf() {
        assertEquals(
            classification(BodyPart.SHOULDERS, Equipment.CABLE, primary = Muscle.SIDE_DELTS, repBand = 15..20, increment = 2.5, restSeconds = 90),
            ExerciseClassifier.classify("Cable lateral raise"),
        )
        assertEquals(
            classification(BodyPart.TRICEPS, Equipment.CABLE, primary = Muscle.TRICEPS, repBand = 10..15, increment = 2.5, restSeconds = 90),
            ExerciseClassifier.classify("Tricep pushdown"),
        )
    }

    @Test
    fun aCableCompoundJumpsByFive() {
        assertEquals(
            classification(
                BodyPart.BACK,
                Equipment.CABLE,
                primary = Muscle.BACK,
                secondary = listOf(Muscle.BICEPS, Muscle.REAR_DELTS),
                repBand = 8..12,
                increment = 5.0,
                restSeconds = 150,
            ),
            ExerciseClassifier.classify("Cable row"),
        )
    }

    @Test
    fun aDumbbellPressCreditsTheFrontDeltsWithTheSideDeltsAndTricepsHelping() {
        assertEquals(
            classification(
                BodyPart.SHOULDERS,
                Equipment.DUMBBELL,
                primary = Muscle.FRONT_DELTS,
                secondary = listOf(Muscle.SIDE_DELTS, Muscle.TRICEPS),
                repBand = 8..12,
                increment = 5.0,
                restSeconds = 150,
            ),
            ExerciseClassifier.classify("Dumbbell shoulder press"),
        )
    }

    @Test
    fun aPlateLoadedPressIsRecognisedByItsPlates() {
        assertEquals(
            classification(
                BodyPart.CHEST,
                Equipment.PLATE_LOADED,
                primary = Muscle.CHEST,
                secondary = listOf(Muscle.FRONT_DELTS, Muscle.TRICEPS),
                repBand = 8..12,
                increment = 10.0,
                restSeconds = 150,
            ),
            ExerciseClassifier.classify("Plate loaded chest press"),
        )
    }

    @Test
    fun theNotesOwnMisspellingOfAnteriorIsStillTheFrontDelts() {
        assertEquals(Muscle.FRONT_DELTS, ExerciseClassifier.classify("Antieror delt raise").primary)
    }

    @Test
    fun aHeadingOverridesWhereTheNameWouldPutAnExercise() {
        assertEquals(
            classification(
                BodyPart.BACK,
                Equipment.MACHINE,
                primary = Muscle.REAR_DELTS,
                secondary = listOf(Muscle.TRAPS),
                repBand = 15..20,
                increment = 10.0,
                restSeconds = 90,
            ),
            ExerciseClassifier.classify("Rear delt fly", BodyPart.BACK),
        )
    }

    @Test
    fun aNameItCannotPlaceFallsToTheCoreWithNothingAssumedAboutHowItIsDone() {
        assertEquals(
            classification(BodyPart.CORE, Equipment.OTHER, primary = Muscle.ABS, repBand = 15..20, increment = 5.0, restSeconds = 60),
            ExerciseClassifier.classify("Mystery"),
        )
    }

    @Test
    fun onlyBodyweightEquipmentIsLoadedByWhatIsHungFromTheBody() {
        for (name in listOf("Pull-ups", "Dips", "Ring pull ups", "Pistol squats", "Push ups")) {
            assertEquals(LoadKind.BODYWEIGHT, ExerciseClassifier.classify(name).loadKind, name)
        }
        for (name in listOf("Bench press", "Hack squat", "Cable row", "Ez curl", "Mystery")) {
            assertEquals(LoadKind.WEIGHT, ExerciseClassifier.classify(name).loadKind, name)
        }
    }
}
