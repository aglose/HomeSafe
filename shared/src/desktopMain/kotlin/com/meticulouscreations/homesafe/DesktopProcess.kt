package com.meticulouscreations.homesafe

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What a finished command gave back. */
internal class ProcessOutcome(val exitCode: Int, val output: ByteArray, val error: ByteArray)

/**
 * Runs [command] with [input] on its stdin and waits at most [timeoutSeconds] for it, reading its
 * output and errors as they come so a chatty or stuck command can't hold the wait past its time.
 * Null if it couldn't start or didn't finish in time; either way nothing of it is left running.
 * An interrupt (a cancelled `runInterruptible`) ends it too, and is passed on.
 */
internal fun runProcess(command: List<String>, input: ByteArray? = null, timeoutSeconds: Long): ProcessOutcome? {
    val process = try {
        ProcessBuilder(command).start()
    } catch (_: IOException) {
        return null
    }
    try {
        val output = drain(process.inputStream)
        val error = drain(process.errorStream)
        process.outputStream.use { stream -> input?.let(stream::write) }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return null
        return ProcessOutcome(process.exitValue(), output.get(PIPE_GRACE_SECONDS, TimeUnit.SECONDS), error.get(PIPE_GRACE_SECONDS, TimeUnit.SECONDS))
    } catch (_: IOException) {
        return null
    } catch (_: TimeoutException) {
        return null
    } catch (_: ExecutionException) {
        return null
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

/** Reads [stream] to its end on a thread of its own. */
private fun drain(stream: InputStream): FutureTask<ByteArray> = FutureTask { stream.use { it.readBytes() } }.also { task ->
    Thread(task, "process-pipe").apply { isDaemon = true }.start()
}

/** How long the pipes may stay open once the command has exited (something it started may hold them). */
private const val PIPE_GRACE_SECONDS = 2L
