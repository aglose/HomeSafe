package com.meticulouscreations.homesafe.domain.model

/** A point on the floor plan, in feet from the plan's origin, y downward. */
data class PlanPoint(val x: Float, val y: Float)

/** A room's name where the plan writes it. The name is data: it comes from the plan file on the box. */
data class PlanRoom(val name: String, val at: PlanPoint)

/** An inside wall, from [from] to [to], in feet. */
data class PlanWall(val from: PlanPoint, val to: PlanPoint)

/** One level of the house: its outside walls as one closed outline, the walls inside it, its rooms' names, and where the stairs are. */
data class PlanFloor(
    val id: String,
    /** As the plan file names it, e.g. "First floor". Data, not copy. */
    val name: String,
    val outline: List<PlanPoint>,
    val rooms: List<PlanRoom> = emptyList(),
    val stairs: List<PlanPoint> = emptyList(),
    val walls: List<PlanWall> = emptyList(),
) {
    /** The outline's extent, with every room label and stair inside it: left, top, right, bottom, in feet. */
    val bounds: PlanBounds = (outline + rooms.map { it.at } + stairs).let { points ->
        PlanBounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
}

data class PlanBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * The house's floor plan, served by the relay from a file kept only on the box (docs/doors.md,
 * "The floor plan"). [floors] in the order the file lists them; the first is shown first.
 */
data class FloorPlan(val floors: List<PlanFloor>) {
    fun floor(id: String?): PlanFloor? = floors.firstOrNull { it.id == id }
}

/** Where a door is on the floor plan: which floor, and the spot on it in feet. */
data class DoorSpot(val floorId: String, val x: Float, val y: Float)
