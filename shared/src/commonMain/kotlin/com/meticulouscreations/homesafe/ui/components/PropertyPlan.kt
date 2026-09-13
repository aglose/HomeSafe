package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The site plan of 3103 Central Avenue, Alameda — the property this app watches — as a set of
 * shapes in metres, ready to be drawn at any size.
 *
 * The geometry is real, not invented: the house and its two neighbours are the building
 * footprints OpenStreetMap holds for those addresses (ways 466290227, 466290229 and 466290224,
 * cross-checked against Google's satellite imagery of the block), and Central Avenue is that
 * way's centreline. All of it was projected to metres about the address point, then rotated by
 * the street's bearing so the road runs dead straight along one edge — which is what makes the
 * plan read as a drawing rather than a tilted photograph.
 *
 * Everything below is in **plan metres**, laid out the way a site plan conventionally is: north
 * is nowhere in particular, but the street is at the **bottom**, because that is where you
 * arrive from. `x` grows across the lot (screen left to right, which is also your left to right
 * standing on the pavement looking at the house) and `y` grows from the back fence down towards
 * the road. The lot is 11 m wide and 40 m deep, so the plan is tall and narrow — that is simply
 * what an Alameda lot is, and drawing it any squarer would be a lie about the property.
 *
 * The drawing is deliberately a *plan*, not satellite imagery: flat theme-coloured shapes that
 * sit with the rest of the app, need no map key or network round trip, and stay legible at the
 * size of a phone panel.
 */
@Immutable
object PropertyPlan {

    /** The whole drawing, in metres. Camera placements are fractions of this box. */
    val size: Size = Size(width = 30f, height = 51f)

    /** Central Avenue's asphalt, running the full width of the plan along its bottom edge. */
    val road = Rect(left = 0f, top = 43.7f, right = size.width, bottom = size.height)

    /** The grass strip between kerb and pavement, where the street trees are. */
    val plantingStrip = Rect(left = 0f, top = 42.2f, right = size.width, bottom = 43.7f)

    val pavement = Rect(left = 0f, top = 40.6f, right = size.width, bottom = 42.2f)

    /**
     * 3103's parcel: 40 m deep from the front property line, 11 m wide. The side boundaries are
     * taken as the midpoints of the real gaps to 3101 and 3107 — the houses are barely a metre
     * off their lines either side, which is why the plan shows so little garden beside the house.
     */
    val lot = Rect(left = 9.85f, top = 0.6f, right = 20.9f, bottom = 40.6f)

    /**
     * The house's main mass — 9.4 m across the front, 20.2 m deep. The two smaller boxes are the
     * stepped rear projection the footprint actually has, which is most of what stops the
     * building reading as a plain rectangle.
     */
    val house = Rect(left = 10.8f, top = 11.4f, right = 20.2f, bottom = 31.6f)
    val houseRearUpper = Rect(left = 11.1f, top = 9f, right = 16.1f, bottom = 11.4f)
    val houseRearLower = Rect(left = 11.7f, top = 7.1f, right = 15.4f, bottom = 9f)

    /** The front porch, projecting towards the street. */
    val porch = Rect(left = 12.5f, top = 31.6f, right = 18.5f, bottom = 33.6f)

    /**
     * The parking apron in the front garden, on the left-hand side: in from the kerb and up to
     * the front wall of the house. Not a drive down the side — there is no room for one. The gap
     * between this house and 3101 is under a metre, which the footprints agree on and the
     * street-level photography bears out.
     */
    val driveway = Rect(left = 9.85f, top = 31.6f, right = 13.45f, bottom = 43.7f)

    /** The path from the pavement to the porch steps. */
    val frontWalk = Rect(left = 14.9f, top = 33.6f, right = 16.1f, bottom = 40.6f)

    /**
     * The neighbours at 3101 and 3107, running off the left and right edges. They are here for
     * orientation only — they say "your house is the middle one of three", which is the single
     * most useful thing a plan of a lot this tight can say.
     */
    val neighbourLeft = Rect(left = -1.1f, top = 11f, right = 8.9f, bottom = 31.6f)
    val neighbourRight = Rect(left = 21.6f, top = 11.8f, right = 31f, bottom = 34.9f)

    /** Canopies, as centre-and-radius circles in metres. The big one is the street tree out front. */
    val trees: List<Triple<Float, Float, Float>> = listOf(
        Triple(17.5f, 37f, 3.2f),
        Triple(17.2f, 4.5f, 2.3f),
        Triple(11.7f, 2.2f, 1.7f),
    )

    /** Low planting either side of the front walk. */
    val shrubs: List<Triple<Float, Float, Float>> = listOf(
        Triple(14.3f, 34.2f, 0.9f),
        Triple(19.1f, 34.2f, 1f),
    )

    /** The front door, in plan metres — the middle of the porch's street-facing edge. */
    val frontDoor: Offset = Offset(porch.center.x, porch.bottom)
}

/** The colours the plan is drawn in, taken from the app's theme by `propertyPlanColors()`. */
@Immutable
data class PropertyPlanColors(
    val ground: Color,
    val road: Color,
    val roadMarking: Color,
    val pavement: Color,
    val lawn: Color,
    val hardstanding: Color,
    val building: Color,
    val buildingOutline: Color,
    val neighbour: Color,
    val neighbourOutline: Color,
    val foliage: Color,
    val lotLine: Color,
)

/**
 * Draws the whole site plan into the current [DrawScope], scaled so [PropertyPlan.size] fills
 * [size] exactly. Callers are expected to have already worked out a box of the plan's aspect
 * ratio — see `PropertyMapPanel` — so nothing here corrects for a mismatch.
 *
 * Order is back to front: ground, road, then the lot's surfaces, then buildings, then planting,
 * so the trees overhang the roof the way they do from the air.
 */
fun DrawScope.drawPropertyPlan(colors: PropertyPlanColors) {
    val scale = size.width / PropertyPlan.size.width

    fun rect(r: Rect, color: Color) = drawRect(
        color = color,
        topLeft = Offset(r.left * scale, r.top * scale),
        size = Size(r.width * scale, r.height * scale),
    )

    fun outline(r: Rect, color: Color, widthMetres: Float) = drawRect(
        color = color,
        topLeft = Offset(r.left * scale, r.top * scale),
        size = Size(r.width * scale, r.height * scale),
        style = Stroke(width = widthMetres * scale),
    )

    fun circle(c: Triple<Float, Float, Float>, color: Color) =
        drawCircle(color = color, radius = c.third * scale, center = Offset(c.first * scale, c.second * scale))

    // Ground first: everything that isn't road, hardstanding or roof is garden.
    drawRect(colors.ground)
    rect(PropertyPlan.lot, colors.lawn)

    rect(PropertyPlan.road, colors.road)
    rect(PropertyPlan.plantingStrip, colors.lawn)
    rect(PropertyPlan.pavement, colors.pavement)

    // The centre line of Central Avenue, dashed, along the plan's bottom edge.
    drawLine(
        color = colors.roadMarking,
        start = Offset(0f, PropertyPlan.road.center.y * scale),
        end = Offset(size.width, PropertyPlan.road.center.y * scale),
        strokeWidth = 0.25f * scale,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * scale, 2.4f * scale)),
    )

    rect(PropertyPlan.driveway, colors.hardstanding)
    rect(PropertyPlan.frontWalk, colors.pavement)

    // The neighbours sit under the lot lines, in their own flatter grey: context, not something
    // you can point a camera at.
    listOf(PropertyPlan.neighbourLeft, PropertyPlan.neighbourRight).forEach {
        rect(it, colors.neighbour)
        outline(it, colors.neighbourOutline, 0.16f)
    }

    // The side and rear lot lines, so it is clear where the property stops and the neighbours
    // start. The front line is the back edge of the pavement and needs no dashes.
    val lotLineWidth = 0.16f * scale
    val lotDash = PathEffect.dashPathEffect(floatArrayOf(1.2f * scale, 1f * scale))
    listOf(
        Offset(PropertyPlan.lot.left, PropertyPlan.lot.top) to Offset(PropertyPlan.lot.left, PropertyPlan.lot.bottom),
        Offset(PropertyPlan.lot.right, PropertyPlan.lot.top) to Offset(PropertyPlan.lot.right, PropertyPlan.lot.bottom),
        Offset(PropertyPlan.lot.left, PropertyPlan.lot.top) to Offset(PropertyPlan.lot.right, PropertyPlan.lot.top),
    ).forEach { (from, to) ->
        drawLine(
            color = colors.lotLine,
            start = Offset(from.x * scale, from.y * scale),
            end = Offset(to.x * scale, to.y * scale),
            strokeWidth = lotLineWidth,
            pathEffect = lotDash,
        )
    }

    listOf(PropertyPlan.house, PropertyPlan.houseRearUpper, PropertyPlan.houseRearLower, PropertyPlan.porch)
        .forEach { rect(it, colors.building) }
    // One outline around the whole silhouette rather than four, so the internal joins between
    // the main mass, the rear steps and the porch don't show as seams.
    drawPath(housePath(scale), colors.buildingOutline, style = Stroke(width = 0.26f * scale))

    PropertyPlan.shrubs.forEach { circle(it, colors.foliage) }
    PropertyPlan.trees.forEach { circle(it, colors.foliage) }
}

/**
 * The outline of house + rear steps + porch as a single closed path, in pixels at [scale]. Every
 * segment is horizontal or vertical: walking the silhouette corner by corner rather than joining
 * box corners directly is what keeps the porch a porch instead of a chamfered edge.
 */
private fun housePath(scale: Float): Path = Path().apply {
    val house = PropertyPlan.house
    val upper = PropertyPlan.houseRearUpper
    val lower = PropertyPlan.houseRearLower
    val porch = PropertyPlan.porch
    fun to(x: Float, y: Float) = lineTo(x * scale, y * scale)

    moveTo(porch.left * scale, porch.bottom * scale)
    to(porch.right, porch.bottom)
    to(porch.right, porch.top)
    to(house.right, house.bottom)
    to(house.right, house.top)
    to(upper.right, upper.bottom)
    to(upper.right, upper.top)
    to(lower.right, lower.bottom)
    to(lower.right, lower.top)
    to(lower.left, lower.top)
    to(lower.left, lower.bottom)
    to(upper.left, upper.top)
    to(upper.left, upper.bottom)
    to(house.left, house.top)
    to(house.left, house.bottom)
    to(porch.left, porch.top)
    close()
}
