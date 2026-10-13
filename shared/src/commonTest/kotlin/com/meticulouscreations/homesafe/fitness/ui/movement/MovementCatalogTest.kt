package com.meticulouscreations.homesafe.fitness.ui.movement

import com.meticulouscreations.homesafe.fitness.domain.FigureStyle
import com.meticulouscreations.homesafe.fitness.domain.MovementKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovementCatalogTest {

    @Test
    fun everyKindOfMovementHasARigFigureWithMusclesAtWorkAndSomeToName() {
        for (kind in MovementKind.entries) {
            val movement = MovementCatalog.rig(kind)
            assertTrue(movement.work().isNotEmpty(), "$kind is performed by $movement, which works nothing")
            assertTrue(MovementCatalog.named(movement).isNotEmpty(), "$kind is performed by $movement, which has no head to call out")
        }
    }

    @Test
    fun aKindTheRigHasUnderItsOwnNameIsPerformedByThatAndNotByAStandIn() {
        for (kind in MovementKind.entries) {
            val own = Movement.entries.firstOrNull { it.name == kind.name } ?: continue
            assertEquals(own, MovementCatalog.rig(kind), "$kind")
        }
        // The four the rig began with.
        assertEquals(Movement.SQUAT, MovementCatalog.rig(MovementKind.SQUAT))
        assertEquals(Movement.INCLINE_PRESS, MovementCatalog.rig(MovementKind.INCLINE_PRESS))
        assertEquals(Movement.LAT_PULLDOWN, MovementCatalog.rig(MovementKind.LAT_PULLDOWN))
        assertEquals(Movement.LATERAL_RAISE, MovementCatalog.rig(MovementKind.LATERAL_RAISE))
    }

    @Test
    fun theStoneFigureIsTheRigsMarbleAndEverythingElseItDrawsIsTheForgedStudy() {
        assertEquals(MovementStyle.MARBLE, MovementCatalog.rigStyle(FigureStyle.STONE))
        assertEquals(MovementStyle.FORGE, MovementCatalog.rigStyle(FigureStyle.FORGED))
        // Where a movement's rendered frames can't be had, the forged study stands in for them.
        assertEquals(MovementStyle.FORGE, MovementCatalog.rigStyle(FigureStyle.TRUE_3D))
    }

    @Test
    fun aKindsFramesAreKeptUnderItsNameInSmallLetters() {
        assertEquals("files/movement/squat", MovementCatalog.folder(MovementKind.SQUAT))
        assertEquals("files/movement/bench_press", MovementCatalog.folder(MovementKind.BENCH_PRESS))
    }

    @Test
    fun anchorsAreReadFrameByFrameWithTheirSideAndRole() {
        val frames = MovementAnchors.parse(
            """[[{"head":"VASTUS_LATERALIS","side":"l","role":"prime","x":0.654,"y":0.615},{"head":"SOLEUS","side":"r","role":"synergist","x":0.405,"y":0.793}],""" +
                """[{"head":"ERECTOR_SPINAE","side":"r","role":"stabilizer","x":0.487,"y":0.52}]]""",
        )

        assertEquals(listOf(2, 1), frames.map { it.size })
        val first = frames[0][0]
        assertEquals(HeadId.VASTUS_LATERALIS, first.head)
        assertEquals(false, first.right)
        assertEquals(PRIME_MOVER, first.role)
        assertEquals(0.654f, first.x)
        assertEquals(0.615f, first.y)
        assertEquals(true, frames[0][1].right)
        assertEquals(SYNERGIST, frames[0][1].role)
        assertEquals(STABILIZER, frames[1][0].role)
    }

    @Test
    fun aHeadOrARoleTheAppDoesNotKnowIsLeftOutAndTheRestStillRead() {
        val frames = MovementAnchors.parse(
            """[[{"head":"PLATYSMA","side":"l","role":"prime","x":0.5,"y":0.2},{"head":"SOLEUS","side":"l","role":"antagonist","x":0.5,"y":0.8},""" +
                """{"head":"SOLEUS","side":"l","role":"synergist","x":0.5,"y":0.8,"depth":0.3}]]""",
        )

        assertEquals(listOf(HeadId.SOLEUS), frames.single().map { it.head })
    }

    @Test
    fun everyHeadHasANameOfItsOwn() {
        assertEquals(HeadId.entries.size, HeadId.entries.map { it.label }.toSet().size)
    }
}
