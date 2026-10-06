package com.meticulouscreations.homesafe.domain.model

/**
 * Front Yard (hikvision_1), 2026-10-05: the household's two cars, each kept by Frigate in a single
 * event from the moment it turned into the street until long after it had parked. Paths, boxes
 * and times are the server's own `data.path_data` and `data.box`; each path point is x, y and the
 * seconds since the event began.
 *
 * What makes them worth keeping: Frigate adds a path point when the object has travelled ~5% of
 * the frame, so hours of sitting still add next to nothing, and each path is its arrival with a
 * few flickers of the box after it.
 */
internal object TrackedCars {
    const val CAMERA = "hikvision_1"

    /** When Sarah's event began, as Frigate counts time. */
    const val SARAH_ARRIVED = 1791248365.9

    val sarahsBox = DetectionBox(0.7328, 0.1083, 0.0938, 0.1236)

    /**
     * Sarah's car: down the street and back to the curb in 23 seconds (15 points), nothing for two
     * hours and twelve minutes, then one flicker of the box (2 points). This is the path as it
     * stood 151 minutes in, when the home screen was read.
     */
    val sarahsPathParked: List<Triple<Double, Double, Double>> = listOf(
        Triple(0.2836, 0.0569, 2.0), Triple(0.2883, 0.0569, 2.1), Triple(0.3602, 0.0625, 2.7), Triple(0.4414, 0.0639, 4.0),
        Triple(0.5031, 0.0972, 4.5), Triple(0.5672, 0.1292, 5.3), Triple(0.625, 0.1667, 6.1), Triple(0.6781, 0.1958, 6.7),
        Triple(0.7312, 0.2194, 7.5), Triple(0.8141, 0.225, 8.7), Triple(0.8797, 0.3569, 9.5), Triple(0.7969, 0.2264, 14.4),
        Triple(0.7289, 0.2292, 16.2), Triple(0.6711, 0.2361, 20.5), Triple(0.6133, 0.2292, 22.6),
        Triple(0.5523, 0.3042, 7960.5), Triple(0.5938, 0.225, 7960.7),
    )

    /** How many of [sarahsPathParked]'s points are the drive in. */
    const val SARAHS_ARRIVAL_POINTS = 15

    /** Seconds into the event at which the home screen was read: 151 minutes, 19 after the flicker. */
    const val SARAH_READ_AT = 9100.0

    /** The four points she added pulling away, 159 minutes in; the event ended five seconds after the last. */
    val sarahsPathLeaving: List<Triple<Double, Double, Double>> = sarahsPathParked +
        listOf(Triple(0.6625, 0.2306, 9532.1), Triple(0.725, 0.2403, 9534.1), Triple(0.7648, 0.3458, 9534.5), Triple(0.7797, 0.2319, 9534.9))

    const val SARAH_LEFT_AT = 9540.1

    /** When Andrew's event began, two hours before Sarah's. */
    const val ANDREW_ARRIVED = 1791241252.5

    /** The best frame is the car up close by the garage, so the box is a third of the frame tall. */
    val andrewsBox = DetectionBox(0.0453, 0.1528, 0.2828, 0.3625)

    /**
     * Andrew's Tesla: up the street, round, and down to the garage in 32 seconds (25 points), then
     * a flicker 84 minutes in — the 27 points it had when the home screen was read at 270 minutes
     * ([ANDREWS_POINTS_WHEN_READ]) — and a run of them at 279. It comes to rest just above the
     * driveway's outline, in no zone at all.
     */
    val andrewsPath: List<Triple<Double, Double, Double>> = listOf(
        Triple(0.3922, 0.0625, 4.1), Triple(0.4328, 0.0653, 4.4), Triple(0.5094, 0.1069, 4.7), Triple(0.5859, 0.1361, 5.1),
        Triple(0.6883, 0.1667, 5.1), Triple(0.7484, 0.2194, 5.6), Triple(0.8773, 0.3278, 5.7), Triple(0.9398, 0.3389, 5.9),
        Triple(0.8938, 0.2764, 9.6), Triple(0.8227, 0.2319, 10.1), Triple(0.7516, 0.1917, 10.8), Triple(0.6852, 0.2056, 11.6),
        Triple(0.6211, 0.2181, 12.3), Triple(0.5617, 0.2333, 14.1), Triple(0.5055, 0.2639, 15.1), Triple(0.4453, 0.2917, 15.7),
        Triple(0.3859, 0.3222, 16.4), Triple(0.3273, 0.3472, 17.1), Triple(0.2695, 0.3861, 17.5), Triple(0.2219, 0.4347, 18.2),
        Triple(0.1812, 0.4792, 19.2), Triple(0.2359, 0.3972, 28.5), Triple(0.1812, 0.4833, 29.1), Triple(0.2055, 0.3972, 31.4),
        Triple(0.1844, 0.4847, 31.6),
        Triple(0.2594, 0.3944, 5023.5), Triple(0.1797, 0.4861, 5023.7),
        Triple(0.2078, 0.4319, 16728.3), Triple(0.1883, 0.4875, 16728.9), Triple(0.2039, 0.4153, 16739.9), Triple(0.1938, 0.5028, 16740.2),
        Triple(0.2008, 0.4069, 16740.5), Triple(0.1805, 0.4847, 16741.0), Triple(0.1906, 0.4222, 16774.7), Triple(0.1898, 0.4861, 16775.0),
        Triple(0.2094, 0.3806, 16775.4), Triple(0.2117, 0.4639, 16775.5),
    )

    const val ANDREWS_POINTS_WHEN_READ = 27

    /** Seconds into the event at which the home screen was read: 270 minutes. */
    const val ANDREW_READ_AT = 16200.0

    /** A car that drove down the street that evening: named Andrew's Tesla by the classifier at 0.89, gone in 13 seconds. */
    val passingPath: List<Triple<Double, Double, Double>> = listOf(
        Triple(0.9602, 0.2833, 1.4), Triple(0.9477, 0.2542, 1.7), Triple(0.9492, 0.3264, 2.0), Triple(0.9133, 0.4403, 2.3),
        Triple(0.857, 0.2389, 2.7), Triple(0.793, 0.1861, 3.0), Triple(0.7305, 0.1528, 3.4), Triple(0.6523, 0.1986, 3.8),
        Triple(0.6367, 0.1111, 4.1), Triple(0.5852, 0.0806, 4.5), Triple(0.5164, 0.0639, 5.0), Triple(0.443, 0.0458, 5.9),
        Triple(0.3805, 0.0361, 6.8), Triple(0.2695, 0.0181, 7.4), Triple(0.2172, 0.0417, 7.8),
    )
    val passingBox = DetectionBox(0.5531, 0.0222, 0.1148, 0.0722)

    /** One Frigate event with a timed [path], ended [endedAfter] seconds in, or still in progress. */
    fun event(
        id: String,
        start: Double,
        box: DetectionBox,
        path: List<Triple<Double, Double, Double>>,
        subLabel: String?,
        endedAfter: Double? = null,
        zones: List<String> = emptyList(),
    ) = MomentEvent(
        id = id, cameraName = CAMERA, label = "car", subLabel = subLabel,
        startEpochSeconds = start, endEpochSeconds = endedAfter?.let { start + it }, topScore = 0.9, hasClip = true, hasSnapshot = true,
        zones = zones, pathPoints = path.map { (x, y, _) -> MaskPoint(x, y) },
        box = box, subLabelScore = 0.98,
    )

    /** Sarah's car as it stood when the home screen was read: 151 minutes in, still in progress. */
    val sarahParked = event("sarah", SARAH_ARRIVED, sarahsBox, sarahsPathParked, "sarahs_car")

    /** Andrew's Tesla as it stood when the home screen was read: 270 minutes in, still in progress. */
    val andrewParked = event("andrew", ANDREW_ARRIVED, andrewsBox, andrewsPath.take(ANDREWS_POINTS_WHEN_READ), "andrews_tesla")
}
