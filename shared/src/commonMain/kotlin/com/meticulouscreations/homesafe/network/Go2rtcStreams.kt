package com.meticulouscreations.homesafe.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One entry of go2rtc's `GET /api/streams`. Only the producers' `url` is read: what the stream
 * was configured to pull from. A running producer carries much more (its SDP, byte counts, the
 * remote address), all ignored.
 */
@Serializable
internal data class Go2rtcStream(val producers: List<Go2rtcProducer>? = null)

@Serializable
internal data class Go2rtcProducer(val url: String? = null)

private val streamsJson = Json { ignoreUnknownKeys = true }

/** Parses go2rtc's `GET /api/streams` body: stream name -> its producers. */
internal fun parseGo2rtcStreams(body: String): Map<String, Go2rtcStream> =
    streamsJson.decodeFromString(MapSerializer(String.serializer(), Go2rtcStream.serializer()), body)

/**
 * The go2rtc streams that pull a Google Nest camera, sorted by name: those with a producer whose
 * source is go2rtc's `nest:` scheme. The source URL also holds the Google credentials, so only
 * the stream's name leaves this function.
 */
internal fun nestStreamNames(streams: Map<String, Go2rtcStream>): List<String> =
    streams
        .filterValues { stream -> stream.producers.orEmpty().any { it.url?.startsWith(NEST_SCHEME) == true } }
        .keys
        .sorted()

private const val NEST_SCHEME = "nest:"

/**
 * A Nest camera as go2rtc has it: [name], the stream that pulls it from Google, and
 * [gridStreamName], its smaller copy for the cards when the server has one.
 */
internal data class NestStream(val name: String, val gridStreamName: String? = null)

/**
 * The Nest cameras ([nestStreamNames]), each with its grid stream: the stream named like it plus
 * [GRID_STREAM_SUFFIX] (`living_room_sub`), the way the Frigate cameras' smaller streams are
 * named (`hikvision_1_sub`). It is whatever the server makes of the camera, typically
 * `ffmpeg:living_room#video=h264#width=640`, which go2rtc keeps cheap to join (see
 * docs/nest-cameras.md); its source isn't `nest:`, so it is never a camera of its own.
 */
internal fun nestStreams(streams: Map<String, Go2rtcStream>): List<NestStream> =
    nestStreamNames(streams).map { name -> NestStream(name, (name + GRID_STREAM_SUFFIX).takeIf { it in streams }) }

private const val GRID_STREAM_SUFFIX = "_sub"
