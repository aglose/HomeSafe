package com.meticulouscreations.homesafe.fakefrigate

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A request the fake server received, for tests that assert on what the app sent. */
data class RecordedRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>>,
    val body: String,
) {
    override fun toString(): String = "$method $path${if (query.isEmpty()) "" else " $query"}${if (body.isEmpty()) "" else " $body"}"
}

/**
 * A stand-in Frigate NVR over real HTTP, for the app's integration and end-to-end tests: the real
 * app graph, its real Ktor client and its real repositories talk to this exactly as they would to
 * the box at home. It speaks the subset of Frigate's API the app uses (see `FrigateApiClient`,
 * `FrigateFaceApi`, `FrigateClassifierApi`), authenticates with a session cookie the way Frigate
 * does, applies config writes to [state] so a toggle survives a reload, and records every request
 * in [requests].
 *
 * Anything the app asks for that isn't modelled answers 404 and is listed in [unhandled], which is
 * the first place to look when a journey stalls. Images (snapshots, thumbnails, face and crop
 * files) are all one tiny PNG; live video (go2rtc, port 1984) and the push relay (port 8787) are
 * deliberately absent, so the app exercises its "unavailable" paths for those.
 */
class FakeFrigateServer(val state: FakeFrigateState = FakeFrigateState.household()) : AutoCloseable {

    private var server: EmbeddedServer<*, *>? = null

    /** The port the server listens on; valid after [start]. */
    var port: Int = 0
        private set

    /** Where the app should point, e.g. `http://127.0.0.1:43127` — what a user would type on the sign-in screen. */
    val baseUrl: String get() = "http://$advertisedHost:$port"

    private var advertisedHost: String = "127.0.0.1"

    private val log = CopyOnWriteArrayList<RecordedRequest>()
    private val notFound = CopyOnWriteArrayList<RecordedRequest>()

    /** Every request received so far, oldest first. */
    val requests: List<RecordedRequest> get() = log.toList()

    /** Requests nothing here modelled (answered 404). */
    val unhandled: List<RecordedRequest> get() = notFound.toList()

    /** Session token -> the account it was issued to. */
    private val sessions = mutableMapOf<String, FakeUser>()

    /**
     * Starts listening on [host]:[port] (0 picks a free port) and returns this server. [advertiseHost]
     * is the host [baseUrl] names — an emulator reaches a server on its host machine at 10.0.2.2.
     */
    fun start(port: Int = 0, host: String = "127.0.0.1", advertiseHost: String = host.takeUnless { it == "0.0.0.0" } ?: "127.0.0.1"): FakeFrigateServer {
        check(server == null) { "Already started" }
        val engine = embeddedServer(CIO, port = port, host = host) {
            routing {
                route("{...}") {
                    handle { answer(call) }
                }
            }
        }
        engine.start(wait = false)
        server = engine
        this.port = runBlocking { engine.engine.resolvedConnectors().first().port }
        advertisedHost = advertiseHost
        return this
    }

    override fun close() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        server = null
    }

    /** Forgets the request log, e.g. between the set-up and the act of a test. */
    fun clearRequests() {
        log.clear()
        notFound.clear()
    }

    /**
     * Waits (in real time) for a request matching [predicate] and returns it — for asserting on
     * what a tap sent, which leaves the app on a background coroutine.
     */
    fun awaitRequest(timeout: Duration = 10.seconds, description: String = "a matching request", predicate: (RecordedRequest) -> Boolean): RecordedRequest {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            log.firstOrNull(predicate)?.let { return it }
            if (deadline.hasPassedNow()) {
                throw AssertionError("Timed out after $timeout waiting for $description. Received:\n" + log.joinToString("\n"))
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Whether any request so far matched [predicate]. */
    fun received(predicate: (RecordedRequest) -> Boolean): Boolean = log.any(predicate)

    private suspend fun answer(call: ApplicationCall) {
        val method = call.request.httpMethod
        val path = call.request.path()
        val query = call.request.queryParameters.entries().associate { (key, values) -> key to values }
        val body = if (method == HttpMethod.Post || method == HttpMethod.Put) call.receiveText() else ""
        val request = RecordedRequest(method.value, path, query, body)
        log += request

        val injected = synchronized(state) { state.failures[path] }
        if (injected != null) {
            call.respondText(FrigateJson.failure("Injected failure").toString(), ContentType.Application.Json, HttpStatusCode.fromValue(injected))
            return
        }

        if (path == "/api/login" && method == HttpMethod.Post) return login(call, body)
        if (path == "/api/version") return call.respondText(synchronized(state) { state.server.version }, ContentType.Text.Plain)

        val user = call.request.cookies[SESSION_COOKIE]?.let { token -> synchronized(sessions) { sessions[token] } }
            ?: return call.respondText("""{"message":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)

        val reply: Reply = synchronized(state) { dispatch(request, user) }
        when (reply) {
            is Reply.Body -> call.respondText(reply.body.toString(), ContentType.Application.Json, reply.status)

            is Reply.Image -> call.respondBytes(PIXEL_PNG, ContentType.Image.PNG, HttpStatusCode.OK)

            Reply.NotFound -> {
                notFound += request
                call.respondText("""{"message":"Not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            }
        }
    }

    private suspend fun login(call: ApplicationCall, body: String) {
        val delayMillis = synchronized(state) { state.loginDelayMillis }
        if (delayMillis > 0) delay(delayMillis)
        val credentials = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val username = credentials?.get("user")?.jsonPrimitive?.content
        val password = credentials?.get("password")?.jsonPrimitive?.content
        val user = synchronized(state) { state.users.firstOrNull { it.username == username && it.password == password } }
        if (user == null) {
            call.respondText("""{"message":"Login failed"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return
        }
        val token = synchronized(sessions) { "token-${user.username}-${sessionCounter++}".also { sessions[it] = user } }
        call.response.header(HttpHeaders.SetCookie, "$SESSION_COOKIE=$token; Path=/; HttpOnly")
        call.respondText("{}", ContentType.Application.Json, HttpStatusCode.OK)
    }

    private sealed interface Reply {
        data class Body(val body: JsonElement, val status: HttpStatusCode = HttpStatusCode.OK) : Reply
        data object Image : Reply
        data object NotFound : Reply
    }

    /** The authenticated API. Runs under the [state] lock. */
    private fun dispatch(request: RecordedRequest, user: FakeUser): Reply {
        val path = request.path
        val segments = path.trim('/').split('/')
        val get = request.method == HttpMethod.Get.value
        val post = request.method == HttpMethod.Post.value
        val put = request.method == HttpMethod.Put.value
        return when {
            isImage(path) -> Reply.Image

            get && path == "/api/profile" -> Reply.Body(FrigateJson.profile(user))

            get && path == "/api/config" -> Reply.Body(FrigateJson.config(state))

            put && path == "/api/config/set" -> adminOnly(user) { setConfig(request) }

            get && path == "/api/stats" -> Reply.Body(FrigateJson.stats(state))

            get && path == "/api/events" -> Reply.Body(FrigateJson.events(queryEvents(request.query)))

            get && path == "/api/event_ids" -> {
                val ids = request.query["ids"].orEmpty().flatMap { it.split(',') }.toSet()
                Reply.Body(FrigateJson.events(state.events.filter { it.id in ids }))
            }

            get && path == "/api/timeline" -> Reply.Body(FrigateJson.strings(emptyList()))

            get && segments.size == 3 && segments[0] == "api" && segments[2] == "recordings" -> {
                val after = request.query["after"]?.firstOrNull()?.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY
                val before = request.query["before"]?.firstOrNull()?.toDoubleOrNull() ?: Double.POSITIVE_INFINITY
                val camera = segments[1]
                Reply.Body(
                    FrigateJson.recordings(
                        state.recordings.filter { it.camera == camera && it.endTime > after && it.startTime < before }.sortedBy { it.startTime },
                    ),
                )
            }

            segments.getOrNull(1) == "faces" -> adminOnly(user) { faces(request, segments) }

            segments.getOrNull(1) == "classification" -> adminOnly(user) { classification(request, segments) }

            else -> Reply.NotFound
        }
    }

    private fun adminOnly(user: FakeUser, block: () -> Reply): Reply =
        if (user.role == "admin") block() else Reply.Body(FrigateJson.failure("Unauthorized"), HttpStatusCode.Unauthorized)

    private fun isImage(path: String): Boolean =
        path.startsWith("/clips/") || path.endsWith(".jpg") || path.endsWith(".png") || path.endsWith(".webp") || path.endsWith(".gif")

    private fun queryEvents(query: Map<String, List<String>>): List<FakeEvent> {
        val after = query["after"]?.firstOrNull()?.toDoubleOrNull()
        val before = query["before"]?.firstOrNull()?.toDoubleOrNull()
        val cameras = query["cameras"]?.flatMap { it.split(',') }?.filter { it.isNotBlank() }?.toSet()
        val inProgress = query["in_progress"]?.firstOrNull() == "1"
        val limit = query["limit"]?.firstOrNull()?.toIntOrNull() ?: 100
        return state.events
            .filter { after == null || it.startTime > after }
            .filter { before == null || it.startTime < before }
            .filter { cameras.isNullOrEmpty() || it.camera in cameras }
            .filter { !inProgress || it.endTime == null }
            .sortedByDescending { it.startTime }
            .take(limit)
    }

    /**
     * `PUT /api/config/set`: switches arrive in the body's `config_data`, masks and zones in the
     * query string. Both are applied, so the next `/api/config` reflects what the app saved.
     */
    private fun setConfig(request: RecordedRequest): Reply {
        val body = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
        val configData = body?.get("config_data") as? JsonObject
        (configData?.get("cameras") as? JsonObject)?.forEach { (cameraName, sections) ->
            val sectionsObject = sections as? JsonObject ?: return@forEach
            state.replaceCamera(cameraName) { camera ->
                var updated = camera
                enabledIn(sectionsObject, "detect")?.let { updated = updated.copy(detectEnabled = it) }
                enabledIn(sectionsObject, "motion")?.let { updated = updated.copy(motionEnabled = it) }
                updated
            }
        }
        request.query.forEach { (key, values) ->
            val parts = key.split('.')
            if (parts.size < 4 || parts[0] != "cameras") return@forEach
            val cameraName = parts[1]
            val polygons = values.filter { it.isNotBlank() }
            when (parts.drop(2).joinToString(".")) {
                "motion.mask" -> state.replaceCamera(cameraName) { it.copy(motionMasks = polygons) }
                "objects.mask" -> state.replaceCamera(cameraName) { it.copy(objectMasks = polygons) }
            }
            if (parts[2] == "zones" && parts.size >= 5) {
                val zoneName = parts[3]
                state.replaceCamera(cameraName) { camera ->
                    val existing = camera.zones.firstOrNull { it.name == zoneName } ?: FakeZone(zoneName, "")
                    val zone = when (parts[4]) {
                        "coordinates" -> existing.copy(coordinates = polygons.joinToString(","))
                        "objects" -> existing.copy(objects = polygons)
                        "friendly_name" -> existing.copy(friendlyName = polygons.firstOrNull())
                        else -> existing
                    }
                    camera.copy(zones = camera.zones.filter { it.name != zoneName } + zone)
                }
            } else if (parts[2] == "zones" && parts.size == 4 && polygons.isEmpty()) {
                state.replaceCamera(cameraName) { camera -> camera.copy(zones = camera.zones.filter { it.name != parts[3] }) }
            }
        }
        return Reply.Body(FrigateJson.success("Config successfully updated"))
    }

    private fun enabledIn(sections: JsonObject, section: String): Boolean? =
        ((sections[section] as? JsonObject)?.get("enabled"))?.jsonPrimitive?.booleanOrNull

    /** `/api/faces` and the calls that file, create, rename and delete. */
    private fun faces(request: RecordedRequest, segments: List<String>): Reply {
        val faces = state.faces
        val body = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
        return when {
            request.method == "GET" && segments.size == 2 -> Reply.Body(FrigateJson.faces(state))

            // POST /api/faces/<name>/create
            segments.size == 4 && segments[3] == "create" -> {
                faces.getOrPut(segments[2]) { mutableListOf() }
                Reply.Body(FrigateJson.failure("Successfully created face folder."))
            }

            // POST /api/faces/train/<name>/classify {"training_file": ...}
            segments.size == 5 && segments[2] == FakeFrigateState.TRAIN_FOLDER && segments[4] == "classify" -> {
                val file = body?.get("training_file")?.jsonPrimitive?.content
                    ?: return Reply.Body(FrigateJson.failure("Missing training_file"), HttpStatusCode.BadRequest)
                faces[FakeFrigateState.TRAIN_FOLDER]?.remove(file)
                faces.getOrPut(segments[3]) { mutableListOf() } += file
                Reply.Body(FrigateJson.success("Successfully classified face."))
            }

            // POST /api/faces/<name>/delete {"ids": [...]}
            segments.size == 4 && segments[3] == "delete" -> {
                val ids = body?.get("ids")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                faces[segments[2]]?.removeAll(ids.toSet())
                if (segments[2] != FakeFrigateState.TRAIN_FOLDER && faces[segments[2]].isNullOrEmpty()) faces.remove(segments[2])
                Reply.Body(FrigateJson.success("Successfully deleted faces."))
            }

            // PUT /api/faces/<old>/rename {"new_name": ...}
            segments.size == 4 && segments[3] == "rename" -> {
                val newName = body?.get("new_name")?.jsonPrimitive?.content
                    ?: return Reply.Body(FrigateJson.failure("Missing new_name"), HttpStatusCode.BadRequest)
                val files = faces.remove(segments[2]) ?: mutableListOf()
                faces[newName] = files
                Reply.Body(FrigateJson.success("Successfully renamed face."))
            }

            else -> Reply.NotFound
        }
    }

    /** `/api/classification/<model>/...`: dataset, queue, categorise, delete and train. */
    private fun classification(request: RecordedRequest, segments: List<String>): Reply {
        val classifier = state.classifiers.firstOrNull { it.name == segments.getOrNull(2) } ?: return Reply.NotFound
        val body = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
        val tail = segments.drop(3)
        val get = request.method == "GET"
        return when {
            get && tail == listOf("dataset") -> Reply.Body(FrigateJson.dataset(classifier))

            get && tail == listOf("train") -> Reply.Body(FrigateJson.strings(classifier.queue.toList()))

            !get && tail == listOf("train") -> {
                classifier.trainings++
                Reply.Body(FrigateJson.success("Training started"))
            }

            !get && tail == listOf("train", "delete") -> {
                val ids = body?.get("ids")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
                classifier.queue.removeAll(ids)
                Reply.Body(FrigateJson.success("Deleted"))
            }

            !get && tail == listOf("dataset", "categorize") -> {
                val file = body?.get("training_file")?.jsonPrimitive?.content
                val category = body?.get("category")?.jsonPrimitive?.content
                if (file == null || category == null) return Reply.Body(FrigateJson.failure("Missing fields"), HttpStatusCode.BadRequest)
                classifier.queue.remove(file)
                classifier.categories.getOrPut(category) { mutableListOf() } += file
                Reply.Body(FrigateJson.success("Categorized"))
            }

            !get && tail.size == 3 && tail[0] == "dataset" && tail[2] == "create" -> {
                classifier.categories.getOrPut(tail[1]) { mutableListOf() }
                Reply.Body(FrigateJson.success("Created"))
            }

            else -> Reply.NotFound
        }
    }

    private var sessionCounter = 0

    companion object {
        /** Frigate's own session cookie name. */
        const val SESSION_COOKIE = "frigate_token"

        private const val POLL_MILLIS = 25L

        /** A 1×1 PNG: every image the app loads, so Coil has something real to decode. */
        private val PIXEL_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
        )
    }
}
