package com.meticulouscreations.homesafe.network

import io.ktor.http.ContentType
import io.ktor.serialization.Configuration
import io.ktor.serialization.ContentConverter
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.Charset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Reads and writes JSON bodies with [json], as Ktor's `json()` does, but decodes them on
 * [Dispatchers.Default].
 *
 * Ktor decodes a body on whichever dispatcher calls `body()`, and most callers here are view
 * models, whose scope runs on the main thread: a camera's three hours of recordings every 20 s,
 * Frigate's whole config on every Settings visit, a year of Atlas visits were all parsed between
 * frames.
 */
fun Configuration.jsonOffMain(json: Json) {
    register(ContentType.Application.Json, OffMainConverter(KotlinxSerializationConverter(json)))
}

/** [delegate], decoding on [dispatcher] rather than the caller's. Encoding stays where it is: request bodies here are small. */
internal class OffMainConverter(
    private val delegate: ContentConverter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ContentConverter by delegate {
    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? =
        withContext(dispatcher) { delegate.deserialize(charset, typeInfo, content) }
}
