package com.meticulouscreations.homesafe.fitness.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class MovementClassifierTest {

    /** The movement a name comes out as when everything else about it is read from the name too, as an import does. */
    private fun movementOf(name: String, section: BodyPart? = null): MovementKind {
        val classification = ExerciseClassifier.classify(name, section)
        return MovementClassifier.movement(name, classification.bodyPart, classification.primary)
    }

    @Test
    fun theNotesTheAppGrewOutOfAllComeOutAsTheMovementALifterWouldCallThem() {
        val expected = mapOf(
            "Hack squat" to MovementKind.SQUAT,
            "Leg press" to MovementKind.LEG_PRESS,
            "Leg extension" to MovementKind.LEG_EXTENSION,
            "Leg curl" to MovementKind.LEG_CURL,
            "Calf machine" to MovementKind.CALF_RAISE,
            "Incline press machine" to MovementKind.INCLINE_PRESS,
            "Chest fly machine" to MovementKind.CHEST_FLY,
            "Incline dumbbell" to MovementKind.INCLINE_PRESS,
            "Shoulder press machine" to MovementKind.OVERHEAD_PRESS,
            "Middle delt cable" to MovementKind.LATERAL_RAISE,
            "Lat pulldown (strict)" to MovementKind.LAT_PULLDOWN,
            "High row" to MovementKind.SEATED_ROW,
            "Weighted pullups" to MovementKind.PULL_UP,
            "Hammer curls" to MovementKind.BICEPS_CURL,
        )
        assertEquals(expected, expected.mapValues { (name, _) -> movementOf(name) })
    }

    @Test
    fun aTricepsExerciseIsAPushdownUnlessItIsADip() {
        assertEquals(MovementKind.TRICEPS_PUSHDOWN, movementOf("Triangle bar cable", BodyPart.TRICEPS))
        assertEquals(MovementKind.TRICEPS_PUSHDOWN, movementOf("Skull crushers", BodyPart.TRICEPS))
        assertEquals(MovementKind.DIP, movementOf("Weighted dips", BodyPart.TRICEPS))
    }

    @Test
    fun theShouldersTellARaiseFromAPressAndTheFrontFromTheRear() {
        assertEquals(MovementKind.REAR_DELT_FLY, movementOf("Rear delt cable perp"))
        assertEquals(MovementKind.FRONT_RAISE, movementOf("Anterior delt cable", BodyPart.SHOULDERS))
        assertEquals(MovementKind.OVERHEAD_PRESS, movementOf("Military press"))
        assertEquals(MovementKind.LATERAL_RAISE, movementOf("Lateral raises"))
    }

    @Test
    fun theHipsHingeAbductAndAdduct() {
        assertEquals(MovementKind.HIP_HINGE, movementOf("Romanian deadlift"))
        assertEquals(MovementKind.HIP_ABDUCTION, movementOf("Abductor machine"))
        assertEquals(MovementKind.HIP_ADDUCTION, movementOf("Adductor machine"))
    }

    @Test
    fun aNameThatSaysOnlyItsShelfIsThatShelfsPlainestMovement() {
        assertEquals(MovementKind.BENCH_PRESS, movementOf("Thing one", BodyPart.CHEST))
        assertEquals(MovementKind.LAT_PULLDOWN, movementOf("Thing two", BodyPart.BACK))
        assertEquals(MovementKind.SQUAT, movementOf("Thing three", BodyPart.LEGS))
        assertEquals(MovementKind.CRUNCH, movementOf("Thing four", BodyPart.CORE))
    }

    // ---- Whether the name said so ----------------------------------------------------------------

    /** As [movementOf], with whether the name gave the movement. */
    private fun matchOf(name: String, section: BodyPart? = null): MovementMatch {
        val classification = ExerciseClassifier.classify(name, section)
        return MovementClassifier.match(name, classification.bodyPart, classification.primary)
    }

    @Test
    fun aNameThatSaysItsMovementIsNotMarkedAsAStandIn() {
        val named = listOf(
            "Hack squat", "Leg press", "Leg extension", "Leg curl", "Calf machine", "Romanian deadlift", "Abductor machine", "Adductor machine",
            "Bench press", "Incline press machine", "Chest fly machine", "Shoulder press machine", "Lateral raises", "Middle delt cable",
            "Rear delt cable perp", "Lat pulldown (strict)", "High row", "Weighted pullups", "Hammer curls",
        )
        assertEquals(emptyList(), named.filterNot { matchOf(it).named })
        assertEquals(true, matchOf("Weighted dips", BodyPart.TRICEPS).named)
        assertEquals(true, matchOf("Triangle bar cable", BodyPart.TRICEPS).named)
        assertEquals(true, matchOf("Anterior delt cable", BodyPart.SHOULDERS).named)
        assertEquals(true, matchOf("Cable crunch", BodyPart.CORE).named)
    }

    @Test
    fun aNameThatSaysOnlyItsShelfGetsTheShelfsMovementMarkedAsNearest() {
        assertEquals(MovementMatch(MovementKind.BENCH_PRESS, named = false), matchOf("Thing one", BodyPart.CHEST))
        assertEquals(MovementMatch(MovementKind.LAT_PULLDOWN, named = false), matchOf("Thing two", BodyPart.BACK))
        assertEquals(MovementMatch(MovementKind.SQUAT, named = false), matchOf("Thing three", BodyPart.LEGS))
        assertEquals(MovementMatch(MovementKind.CRUNCH, named = false), matchOf("Plank", BodyPart.CORE))
        assertEquals(MovementMatch(MovementKind.TRICEPS_PUSHDOWN, named = false), matchOf("Skull crushers", BodyPart.TRICEPS))
        assertEquals(MovementMatch(MovementKind.BICEPS_CURL, named = false), matchOf("Thing five", BodyPart.BICEPS))
    }

    @Test
    fun aMovementReachedOnlyThroughTheMuscleIsMarkedAsNearestToo() {
        // Nothing in the name says "calf raise": it is one because that is the muscle the exercise was set to.
        assertEquals(MovementMatch(MovementKind.CALF_RAISE, named = false), MovementClassifier.match("Donkey thing", BodyPart.LEGS, Muscle.CALVES))
        assertEquals(MovementMatch(MovementKind.SHRUG, named = false), MovementClassifier.match("Thing six", BodyPart.BACK, Muscle.TRAPS))
    }
}
