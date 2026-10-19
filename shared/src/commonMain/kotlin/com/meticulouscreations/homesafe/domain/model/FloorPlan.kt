package com.meticulouscreations.homesafe.domain.model

/** A point on the floor plan, in feet from the plan's origin, y downward. */
data class PlanPoint(val x: Float, val y: Float)

/** A room's name where the plan writes it. The name is data: it comes from the plan file on the box. */
data class PlanRoom(val name: String, val at: PlanPoint)

/** An inside wall, from [from] to [to], in feet. */
data class PlanWall(val from: PlanPoint, val to: PlanPoint)

/**
 * A doorway in a wall, hinged at [from] and as wide as [from]→[to], swinging to the left of that
 * line ([swing] 1) or the right (-1). [sensorId] is the door sensor on it, if one is linked: the
 * relay puts that sensor's spot at the doorway's [middle].
 */
data class PlanDoor(
    val from: PlanPoint,
    val to: PlanPoint,
    val swing: Int = 1,
    /** As the plan names it ("Back door"). Data, not copy. */
    val name: String? = null,
    val sensorId: String? = null,
) {
    val middle: PlanPoint get() = PlanPoint((from.x + to.x) / 2, (from.y + to.y) / 2)
}

/** One level of the house: its outside walls as one closed outline, the walls inside it, its rooms' names, and where the stairs are. */
data class PlanFloor(
    val id: String,
    /** As the plan file names it, e.g. "First floor". Data, not copy. */
    val name: String,
    val outline: List<PlanPoint>,
    val rooms: List<PlanRoom> = emptyList(),
    val stairs: List<PlanPoint> = emptyList(),
    val walls: List<PlanWall> = emptyList(),
    val doors: List<PlanDoor> = emptyList(),
) {
    /** The outline's extent, with every room label and stair inside it: left, top, right, bottom, in feet. */
    val bounds: PlanBounds = (outline + rooms.map { it.at } + stairs + doors.flatMap { listOf(it.from, it.to) }).let { points ->
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

/** The doorway on [floor] nearest [at] within [reach] feet, which a door placed there snaps to; null when none is that close. */
fun nearestDoorway(floor: PlanFloor, at: PlanPoint, reach: Float = 2.5f): PlanDoor? =
    floor.doors
        .map { it to kotlin.math.hypot(it.middle.x - at.x, it.middle.y - at.y) }
        .filter { (_, distance) -> distance <= reach }
        .minByOrNull { (_, distance) -> distance }
        ?.first

/** Where a door is on the floor plan: which floor, and the spot on it in feet. */
data class DoorSpot(val floorId: String, val x: Float, val y: Float)
