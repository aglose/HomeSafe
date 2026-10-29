package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.ClipDownloadProgress
import com.meticulouscreations.homesafe.domain.platform.ClipDownloader
import com.meticulouscreations.homesafe.isMacOs
import com.meticulouscreations.homesafe.network.requireSuccess
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.download_frigate_refused
import homesafe.shared.generated.resources.download_write_failed
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * Saves the clip into the person's Downloads folder, as a browser would, and shows it there:
 * selected in a Finder window on a Mac. Fetched with the app's shared, already-authenticated
 * [httpClient] and streamed to the file a chunk at a time (a clip can run to hundreds of
 * megabytes), with the request timeout lifted for this one call, as on iOS. A name already taken
 * gets a number, as Safari's downloads do, rather than overwriting what is there.
 */
private class DesktopClipDownloader(private val httpClient: HttpClient) : ClipDownloader {

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        fileName: String,
        onProgress: (ClipDownloadProgress) -> Unit,
    ): Result<Unit> = runCatching {
        onProgress(ClipDownloadProgress.Preparing)
        val folder = downloadsFolder()
        // Claimed before the request, so a second save of the same clip started meanwhile takes the next name.
        val partial = withContext(Dispatchers.IO) { claimPartial(folder, fileName) }
        try {
            httpClient.prepareGet(url) {
                headers.forEach { (key, value) -> header(key, value) }
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                }
            }.execute { response ->
                response.requireSuccess(Res.string.download_frigate_refused)
                writeToFile(partial, response.bodyAsChannel(), response.contentLength(), onProgress)
            }
            withContext(Dispatchers.IO) { reveal(publish(partial, fileName)) }
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
    }

    private suspend fun writeToFile(file: File, body: ByteReadChannel, totalBytes: Long?, onProgress: (ClipDownloadProgress) -> Unit) = withContext(Dispatchers.IO) {
        try {
            file.outputStream().buffered().use { out ->
                val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
                var received = 0L
                while (true) {
                    val read = body.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    received += read
                    val fraction = totalBytes?.takeIf { it > 0L }?.let { (received.toDouble() / it).toFloat().coerceIn(0f, 1f) }
                    onProgress(ClipDownloadProgress.Downloading(fraction))
                }
            }
        } catch (e: IOException) {
            file.delete()
            // A connection that dropped mid-body is the network's to explain, not the disk's.
            if (body.isClosedForRead && body.closedCause != null) throw e
            throw LocalizedException(UiText.of(Res.string.download_write_failed), technical = "Couldn't write the clip to $file: ${e.message}")
        }
    }

    private fun downloadsFolder(): File = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }

    /** [name] in [folder], then "name (2).mp4" and so on, as Safari numbers a download whose name is taken. */
    private fun candidates(folder: File, name: String): Sequence<File> {
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        return generateSequence(1) { it + 1 }.map { n -> File(folder, if (n == 1) name else "$base ($n)$extension") }
    }

    /** Creates the first "name.download" whose name is free, the creation itself being the claim. */
    private fun claimPartial(folder: File, name: String): File = candidates(folder, name)
        .filter { !it.exists() }
        .map { File(folder, "${it.name}$PARTIAL_SUFFIX") }
        .first { it.createNewFile() }

    /** Gives the finished [partial] its own name, or the next free one if a file took it meanwhile; never over another. */
    private fun publish(partial: File, name: String): File {
        val claimed = File(partial.parentFile, partial.name.removeSuffix(PARTIAL_SUFFIX))
        val names = sequenceOf(claimed) + candidates(partial.parentFile, name).filter { it != claimed }
        for (target in names) {
            if (target.exists() || File(target.parentFile, "${target.name}$PARTIAL_SUFFIX").let { it != partial && it.exists() }) continue
            try {
                return Files.move(partial.toPath(), target.toPath()).toFile()
            } catch (_: FileAlreadyExistsException) {
                continue
            } catch (e: IOException) {
                throw LocalizedException(UiText.of(Res.string.download_write_failed), technical = "Couldn't rename $partial: ${e.message}")
            }
        }
        error("Ran out of names")
    }

    /** Selects [file] in a Finder window on a Mac; elsewhere opens the folder it is in. */
    private fun reveal(file: File) {
        runCatching {
            when {
                isMacOs -> ProcessBuilder("open", "-R", file.absolutePath).start()
                Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR) -> Desktop.getDesktop().browseFileDirectory(file)
                Desktop.isDesktopSupported() -> Desktop.getDesktop().open(file.parentFile)
            }
        }
    }
}

/** What a clip is called while it is still arriving, as Safari marks one. */
private const val PARTIAL_SUFFIX = ".download"

/** How much of the clip is held in memory at once on its way to the file. */
private const val DOWNLOAD_CHUNK_BYTES = 256 * 1024

/** How long the connection may go quiet (Frigate still cutting, or the network gone) before the save gives up. */
private const val SOCKET_TIMEOUT_MS = 120_000L

actual fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader = DesktopClipDownloader(httpClient)
