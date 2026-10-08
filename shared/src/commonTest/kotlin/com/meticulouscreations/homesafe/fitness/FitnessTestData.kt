package com.meticulouscreations.homesafe.fitness

import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.Equipment
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.LoggedSet
import com.meticulouscreations.homesafe.fitness.domain.Muscle

/**
 * The notes the parser is held to: invented numbers on the shapes the real ones take, with every
 * quirk (trailing spaces included) left as it is in a notes app.
 */
internal object FitnessTestData {
    const val DAY = 86_400L

    /** 15 September 2021, noon UTC: the middle of the month a "Philly Sept 2021" heading stands for. */
    const val SEPT_2021 = 1_631_707_200L

    /** 15 November 2021, noon UTC. */
    const val NOV_2021 = 1_636_977_600L

    /** 15 June 2022, noon UTC: "Philly June 22". */
    const val JUNE_2022 = 1_655_294_400L

    /** Noon UTC on a day well after the notes were written, so no local time zone moves it to another date. */
    const val NOW = 1_800_014_400L

    const val NOTES = """Shoulders

Shoulder press machine
- 120lbs - 14 reps
- 130lbs - 13 reps
-140lbs - 12 reps

Rear delt fly
- 70lbs - 16 reps
- 80lbs - 15 reps 
- 90lbs - 

Middle delt cable
- 10lbs - 26 reps
- 12.5lbs - 15 reps
- 16.5lbs - 20 reps (gym version)

Middle delt cable (strict home)
- 15lbs - 21 reps

Shoulder dumbbell press
- 40lbs x 2 - 20 reps
- 55lbs x 2 - 14reps
- 60lbs x 2 - 6 reps

Legs

Hack squat
- 180lbs - 12 reps 
- 200lbs - 12 reps 
- 220lbs - 11 reps 
- 300lbs (3plates+15lbs)- 10 reps

Hack squat quad focused
- 180lbs - 12 reps
- 210lbs - 8 pause full rom

Leg press
- 3 plates - 13 reps
- 3 plates 25lbs - 12 reps
- 4 plates + 25lbs - 13 reps
- 5 plates - 11 reps

Calf machine
- 14 - 29 reps
- 15 - 30 reps
- 16 -25 reps

Calf dumbbell (2 legs)
- 29 reps - 55 lbs
- 24 reps - 60.5 lbs 

45 degree leg press calf raise
- 4 plates - 15+ reps

Back
Lat pulldown (strict)
- 170lbs - 13 reps
- 187lbs - 12 reps 
Other machine
-210lbs - 12 reps
-220lbs - 12 reps

Ring pull ups
- 15 reps 

Weighted pullups
- 25lbs - 11 reps - bodyweight 180lb
- 40lbs - 8 reps - bodyweight 176lb

Bis & Tris
Tris

Extension machine
- 60lbs - 16 reps
- 80lbs - reps

Single arm cable
- 15lbs - 21 reps
- 17.5lbs -13 reps

Dips
- 35 reps

Bis

Single arm cable
- 22lbs - 17 reps
- 27.5lbs - 14 reps ish

Ez curl
- 25lbs  x 2 - 9 reps

Personal Records
Ab machine
10 - 16 reps
11 - 16 reps

Max pull-ups 
22 all time
17 recently 

Max ring dips
15 

Max pistol squats
12 reps - 25lbs - bodyweight 180lbs
12 reps - 35lbs (175lbs bodyweight)

Philly June 22
Lat pulldown - 225lbs 4 reps
45 degree row - 65lbs 8 reps
Dip press -150lbs 10 reps
Leg press - 235lbs 10 reps
Seated leg curl - 160lbs 6 reps
       110lbs 8 reps

Gym 
Philly Sept 2021

Legs
- Smith squat 225 lbs 3 reps
- hamstring machine curl 110lbs 12 reps

Chest
- smith bench 185lbs adjusted 8 reps  

Oahu Nov 2021

Calf raises
- 35 lbs 10 reps straight leg

Legs
- Smith squat 225lbs 5 reps"""

    /** A bench press on the chest shelf, with the usual band and jump, for tests that only need an exercise to hang sets on. */
    fun exercise(
        id: String = "chest/bench-press",
        name: String = "Bench press",
        bodyPart: BodyPart = BodyPart.CHEST,
        equipment: Equipment = Equipment.BARBELL,
        loadKind: LoadKind = LoadKind.WEIGHT,
        primary: Muscle = Muscle.CHEST,
        secondary: List<Muscle> = emptyList(),
        repLow: Int = 8,
        repHigh: Int = 12,
        increment: Double = 5.0,
        restSeconds: Int = 120,
        note: String = "",
        archived: Boolean = false,
    ) = Exercise(id, name, bodyPart, equipment, loadKind, primary, secondary, repLow, repHigh, increment, restSeconds, note, archived)

    fun set(
        id: Long,
        weight: Double,
        reps: Int,
        epochSeconds: Long,
        exerciseId: String = "chest/bench-press",
        workoutId: Long? = null,
        bodyweight: Double? = null,
        note: String = "",
        imported: Boolean = false,
    ) = LoggedSet(id, exerciseId, weight, reps, epochSeconds, workoutId, bodyweight, note, imported)
}
