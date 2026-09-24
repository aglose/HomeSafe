package com.meticulouscreations.homesafe.fakefrigate

/** One account the fake server accepts. Frigate's roles are `admin` and `viewer`; only admins may change config. */
data class FakeUser(
    val username: String,
    val password: String,
    val role: String = "admin",
)

/** One polygon zone on a camera, as it sits in Frigate's `cameras.<name>.zones` config. */
data class FakeZone(
    val name: String,
    val coordinates: String,
    val objects: List<String> = emptyList(),
    val friendlyName: String? = null,
)

/** A camera and the parts of its config the app reads or writes. */
data class FakeCamera(
    val name: String,
    val enabled: Boolean = true,
    val detectEnabled: Boolean = true,
    val motionEnabled: Boolean = true,
    val detectWidth: Int = 1280,
    val detectHeight: Int = 720,
    val trackedObjects: List<String> = listOf("person", "car", "dog"),
    val motionMasks: List<String> = emptyList(),
    val objectMasks: List<String> = emptyList(),
    val zones: List<FakeZone> = emptyList(),
    /** `live.streams`: friendly name -> go2rtc stream name. Empty means the camera name is used for both. */
    val liveStreams: Map<String, String> = emptyMap(),
)

/** One detection, as `/api/events` reports it. Times are epoch seconds; a null [endTime] is still in progress. */
data class FakeEvent(
    val id: String,
    val camera: String,
    val label: String,
    val startTime: Double,
    val endTime: Double? = startTime + 20.0,
    val subLabel: String? = null,
    val subLabelScore: Double? = null,
    val zones: List<String> = emptyList(),
    val score: Double = 0.84,
    val hasClip: Boolean = true,
    val hasSnapshot: Boolean = true,
    /** The best frame's box as `[x, y, w, h]`, fractions of the detect frame. */
    val box: List<Double> = listOf(0.4, 0.3, 0.2, 0.5),
)

/** One recorded segment of a camera, as `/api/<camera>/recordings` reports it. */
data class FakeRecording(
    val camera: String,
    val startTime: Double,
    val endTime: Double,
    val motion: Int = 0,
    val objects: Int = 0,
)

/** A custom object classifier (`classification.custom.<name>`), its labelled dataset and its unlabelled queue. */
data class FakeClassifier(
    val name: String,
    val objects: List<String> = listOf("car"),
    val enabled: Boolean = true,
    val categories: MutableMap<String, MutableList<String>> = linkedMapOf(),
    val queue: MutableList<String> = mutableListOf(),
    var trainings: Int = 0,
)

/** The server-wide numbers `/api/stats` and the global parts of `/api/config` report. */
data class FakeServerInfo(
    val version: String = "0.17.2-test",
    val latestVersion: String? = "0.17.2-test",
    val uptimeSeconds: Long = 3 * 24 * 60 * 60 + 4 * 60 * 60,
    val cpuPercent: String = "9.4",
    val memoryPercent: String = "38.0",
    val detectorName: String = "coral",
    val detectorType: String = "edgetpu",
    val inferenceMs: Double = 8.5,
    val recordingsTotalMb: Double = 1_000_000.0,
    val recordingsUsedMb: Double = 420_000.0,
    val continuousRetentionDays: Double = 7.0,
    val motionRetentionDays: Double = 14.0,
    val alertRetentionDays: Double = 30.0,
    val detectionRetentionDays: Double = 30.0,
    val faceRecognitionEnabled: Boolean = true,
    val licensePlateRecognitionEnabled: Boolean = false,
    val semanticSearchEnabled: Boolean = true,
)

/**
 * Everything the fake Frigate knows, and the one object tests reach into to shape a scenario:
 * change it before signing in to set the scene, or while the app runs to have the "server" move
 * on underneath it. Every field is read under this object's monitor by the server, so a test
 * that mutates it from its own thread should do so inside [edit].
 */
class FakeFrigateState(
    val users: MutableList<FakeUser> = mutableListOf(),
    val cameras: MutableList<FakeCamera> = mutableListOf(),
    val events: MutableList<FakeEvent> = mutableListOf(),
    val recordings: MutableList<FakeRecording> = mutableListOf(),
    /** Folder -> image files, exactly `/api/faces`: people by name, plus [TRAIN_FOLDER] for unfiled attempts. */
    val faces: MutableMap<String, MutableList<String>> = linkedMapOf(),
    val classifiers: MutableList<FakeClassifier> = mutableListOf(),
    var server: FakeServerInfo = FakeServerInfo(),
) {
    /**
     * Paths (exact, e.g. `/api/stats`) the server should fail, and with which status. For a
     * scenario like "the server is up but its stats endpoint is broken".
     */
    val failures: MutableMap<String, Int> = linkedMapOf()

    /** How long `/api/login` takes to answer, to hold the sign-in screen in its connecting state. */
    var loginDelayMillis: Long = 0

    /** Applies [block] under the lock the server reads with. */
    fun <T> edit(block: FakeFrigateState.() -> T): T = synchronized(this) { block() }

    fun camera(name: String): FakeCamera? = synchronized(this) { cameras.firstOrNull { it.name == name } }

    internal fun replaceCamera(name: String, transform: (FakeCamera) -> FakeCamera) {
        val index = cameras.indexOfFirst { it.name == name }
        if (index >= 0) cameras[index] = transform(cameras[index])
    }

    companion object {
        const val TRAIN_FOLDER = "train"

        /** The admin account every scenario accepts. */
        val ADMIN = FakeUser(username = "admin", password = "correct-horse", role = "admin")

        /** A second, read-only account: Frigate answers its config writes with 401. */
        val VIEWER = FakeUser(username = "viewer", password = "just-looking", role = "viewer")

        /**
         * A three-camera household with a few hours of activity: someone at the front door minutes
         * ago, a known car in the driveway, the dog in the back yard, and yesterday's comings and
         * goings. Times are relative to [nowEpochSeconds] so "today" is always today.
         */
        fun household(nowEpochSeconds: Double = System.currentTimeMillis() / 1000.0): FakeFrigateState {
            val now = nowEpochSeconds
            val cameras = mutableListOf(
                FakeCamera(
                    name = "front_door",
                    zones = listOf(
                        FakeZone("porch", "0.1,0.6,0.5,0.6,0.5,1.0,0.1,1.0", objects = listOf("person"), friendlyName = "Porch"),
                    ),
                ),
                FakeCamera(
                    name = "driveway",
                    zones = listOf(
                        FakeZone("parking", "0.2,0.5,0.9,0.5,0.9,1.0,0.2,1.0", objects = listOf("car"), friendlyName = "Parking"),
                    ),
                    liveStreams = mapOf("Main" to "driveway", "Sub" to "driveway_sub"),
                ),
                FakeCamera(name = "back_yard", motionMasks = listOf("0.0,0.0,0.3,0.0,0.3,0.2,0.0,0.2")),
            )
            val events = mutableListOf(
                FakeEvent("${eventEpoch(now - 180)}-frnt01", "front_door", "person", now - 180, zones = listOf("porch"), score = 0.91),
                FakeEvent(
                    "${eventEpoch(now - 1_200)}-drv001",
                    "driveway",
                    "car",
                    now - 1_200,
                    endTime = now - 1_080,
                    subLabel = "sarahs_tesla",
                    subLabelScore = 0.97,
                    zones = listOf("parking"),
                ),
                FakeEvent("${eventEpoch(now - 7_200)}-byrd01", "back_yard", "dog", now - 7_200, score = 0.78),
                FakeEvent("${eventEpoch(now - 9_000)}-frnt02", "front_door", "person", now - 9_000, subLabel = "Alice", subLabelScore = 0.93),
                FakeEvent("${eventEpoch(now - 90_000)}-drv002", "driveway", "car", now - 90_000, zones = listOf("parking")),
                FakeEvent("${eventEpoch(now - 95_000)}-frnt03", "front_door", "person", now - 95_000),
            )
            val recordings = cameras.flatMap { camera ->
                // Six hours of one-minute segments, with motion around each of that camera's events.
                // Real Frigate segments are ~10 s; the app drops anything of ten minutes or more as
                // unplayable (RecordingSegment.isPlayable), so keep these well short of that.
                (0 until RECORDED_SEGMENTS).map { i ->
                    val start = now - (RECORDED_SEGMENTS - i) * SEGMENT_SECONDS
                    val busy = events.any { it.camera == camera.name && it.startTime in (start - SEGMENT_SECONDS)..(start + SEGMENT_SECONDS) }
                    FakeRecording(camera.name, start, start + SEGMENT_SECONDS, motion = if (busy) 40 else 0, objects = if (busy) 2 else 0)
                }
            }.toMutableList()
            val driveway = events.first { it.camera == "driveway" }
            return FakeFrigateState(
                users = mutableListOf(ADMIN, VIEWER),
                cameras = cameras,
                events = events,
                recordings = recordings,
                faces = linkedMapOf(
                    TRAIN_FOLDER to mutableListOf("${eventEpoch(now - 9_000)}-frnt02-${eventEpoch(now - 8_990)}-unknown-0.62.webp"),
                    "Alice" to mutableListOf("alice-1.webp", "alice-2.webp"),
                    "Bob" to mutableListOf("bob-1.webp"),
                ),
                classifiers = mutableListOf(
                    FakeClassifier(
                        name = "household_cars",
                        objects = listOf("car"),
                        categories = linkedMapOf(
                            "sarahs_tesla" to mutableListOf("tesla-1.webp", "tesla-2.webp"),
                            "none" to mutableListOf("street-1.webp"),
                        ),
                        queue = mutableListOf(
                            "${driveway.id}-${eventEpoch(driveway.startTime + 5)}-sarahs_tesla-0.71.webp",
                            "${driveway.id}-${eventEpoch(driveway.startTime + 9)}-none-0.55.webp",
                        ),
                    ),
                ),
            )
        }

        /** A server with cameras but no history at all: the empty states. */
        fun quiet(): FakeFrigateState = household().apply {
            events.clear()
            recordings.clear()
            faces.clear()
            classifiers.clear()
        }

        private const val SEGMENT_SECONDS = 60.0
        private const val RECORDED_SEGMENTS = 6 * 60

        /** Frigate's event ids are `<epoch with 6 decimals>-<6 chars>`; the epoch part only has to parse. */
        private fun eventEpoch(seconds: Double): String = "%.6f".format(java.util.Locale.ROOT, seconds)
    }
}
