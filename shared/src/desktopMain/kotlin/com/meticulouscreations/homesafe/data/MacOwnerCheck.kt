package com.meticulouscreations.homesafe.data

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_confirmation_cancelled
import homesafe.shared.generated.resources.biometric_name_generic
import homesafe.shared.generated.resources.biometric_name_passcode
import homesafe.shared.generated.resources.biometric_name_touch_id
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Touch ID (or the Mac's password) and the login Keychain, by way of the small Swift helper the
 * Mac app carries in its resources (`desktopApp/macos/OwnerCheck.swift`): the JVM has no
 * LocalAuthentication of its own. Absent anywhere but a packaged Mac app (a `run` from Gradle on
 * Linux, say), and then [isAvailable] is false and nothing is asked.
 */
internal object MacOwnerCheck {
    private val helper: File? by lazy {
        val resources = System.getProperty("compose.application.resources.dir") ?: return@lazy null
        File(resources, "HomeSafe").takeIf { it.canExecute() }
    }

    /** What the check asks for here: touchid, password or none. Asked once; it only changes in System Settings. */
    private val method: String by lazy { run("can").takeIf { it.exitCode == 0 }?.output?.decodeToString()?.trim() ?: "none" }

    val isAvailable: Boolean get() = method != "none"

    val methodName: StringResource
        get() = when (method) {
            "touchid" -> Res.string.biometric_name_touch_id
            "password" -> Res.string.biometric_name_passcode
            else -> Res.string.biometric_name_generic
        }

    /** Puts up the prompt, explained by [reason], and waits for it; with [input] for a command that keeps something. */
    suspend fun ask(command: String, reason: String, input: ByteArray? = null): Result<ByteArray> = withContext(Dispatchers.IO) {
        val outcome = run(command, reason, input = input, timeoutSeconds = PROMPT_TIMEOUT_SECONDS)
        when (outcome.exitCode) {
            0 -> Result.success(outcome.output)
            CANCELLED -> Result.failure(LocalizedException(UiText.of(Res.string.biometric_confirmation_cancelled), technical = "Owner check was cancelled"))
            // The system's own words when it gave any, already in the reader's language.
            else -> Result.failure(outcome.error.takeIf { it.isNotBlank() }?.let(::OwnerCheckException) ?: OwnerCheckException("Owner check failed (${outcome.exitCode})"))
        }
    }

    /** A command that puts up no prompt. */
    fun quietly(command: String): Boolean = run(command).exitCode == 0

    private class Outcome(val exitCode: Int, val output: ByteArray, val error: String)

    private fun run(vararg arguments: String, input: ByteArray? = null, timeoutSeconds: Long = 10): Outcome {
        val executable = helper ?: return Outcome(-1, ByteArray(0), "")
        return runCatching {
            val process = ProcessBuilder(listOf(executable.absolutePath) + arguments).start()
            process.outputStream.use { stream -> input?.let(stream::write) }
            val output = process.inputStream.readBytes()
            val error = process.errorStream.readBytes().decodeToString()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy()
                return Outcome(-1, ByteArray(0), "")
            }
            Outcome(process.exitValue(), output, error)
        }.getOrElse { Outcome(-1, ByteArray(0), "") }
    }

    private const val CANCELLED = 2

    /** Long enough for someone to find the sensor, or type their password. */
    private const val PROMPT_TIMEOUT_SECONDS = 120L
}

/** The system refused or gave up; the message is its own, already in the reader's language. */
internal class OwnerCheckException(message: String) : Exception(message)
