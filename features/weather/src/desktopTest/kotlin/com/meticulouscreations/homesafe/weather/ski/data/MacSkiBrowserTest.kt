package com.meticulouscreations.homesafe.weather.ski.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac's browser, with a shell script standing in for the program that holds WebKit: what's asked of it, and everything that can go wrong with it. */
class MacSkiBrowserTest {
    private val dir: File = Files.createTempDirectory("ski-browser").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun helper(body: String): File = File(dir, "HomeSafeBrowser").apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    @Test
    fun `the page goes as an argument, the script on stdin, and the answer comes back as is`() = runBlocking {
        val browser = MacSkiBrowser(helper("""script=$(cat); printf '%s|%s|%s' "$1" "$2" "${'$'}script""""))

        val answer = browser.read("https://example.test/shop", "(function(){ return 'é'; })()", timeoutMillis = 4_000)

        assertEquals("https://example.test/shop|4000|(function(){ return 'é'; })()", answer)
    }

    @Test
    fun `a page that never answered is no answer`() = runBlocking {
        assertNull(MacSkiBrowser(helper("exit 3")).read("https://example.test", "x"))
    }

    @Test
    fun `an engine that fell over is no answer, whatever it said on the way`() = runBlocking {
        assertNull(MacSkiBrowser(helper("printf partial; kill -SEGV $$")).read("https://example.test", "x"))
    }

    @Test
    fun `a program that won't start is no answer, not a crash`() = runBlocking {
        val notAProgram = File(dir, "HomeSafeBrowser").apply {
            writeBytes(byteArrayOf(0, 1, 2, 3))
            setExecutable(true)
        }
        assertNull(MacSkiBrowser(notAProgram).read("https://example.test", "x"))
        notAProgram.delete()
        assertNull(MacSkiBrowser(notAProgram).read("https://example.test", "x"))
    }

    @Test
    fun `a program that stopped answering is given up on, a little after its own time`() = runBlocking {
        val browser = MacSkiBrowser(helper("sleep 60"))
        val before = System.currentTimeMillis()

        assertNull(browser.read("https://example.test", "x", timeoutMillis = 100))

        assertTrue(System.currentTimeMillis() - before < 20_000)
    }

    @Test
    fun `cancelling a read ends the program`() = runBlocking {
        val started = File(dir, "pid")
        val browser = MacSkiBrowser(helper("cat > /dev/null; echo $$ > '${started.path}'; exec sleep 60"))
        val read = async(Dispatchers.Default) { browser.read("https://example.test", "x") }
        withContext(Dispatchers.IO) {
            withTimeout(10_000) { while (started.length() == 0L) delay(50) }
        }
        val pid = started.readText().trim().toLong()
        assertTrue(ProcessHandle.of(pid).isPresent)

        read.cancel()
        read.join()

        withContext(Dispatchers.IO) {
            withTimeout(10_000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(50) }
        }
    }

    @Test
    fun `reads take turns, as there is one hidden page at a time`() = runBlocking {
        val log = File(dir, "log")
        val browser = MacSkiBrowser(helper("cat > /dev/null; echo in >> '${log.path}'; sleep 0.3; echo out >> '${log.path}'; printf done"))

        val answers = listOf(async(Dispatchers.Default) { browser.read("https://a.test", "x") }, async(Dispatchers.Default) { browser.read("https://b.test", "x") }).map { it.await() }

        assertEquals(listOf("done", "done"), answers)
        assertEquals(listOf("in", "out", "in", "out"), log.readLines())
    }

    @Test
    fun `with the program in the resources there is a browser, and without it none`() {
        assertFalse(skiBrowserIn(null).isSupported)
        assertFalse(skiBrowserIn(dir.path).isSupported)
        File(dir, "HomeSafeBrowser").writeText("not runnable")
        assertFalse(skiBrowserIn(dir.path).isSupported)
        helper("exit 0")
        assertIs<MacSkiBrowser>(skiBrowserIn(dir.path))
        assertTrue(skiBrowserIn(dir.path).isSupported)
    }

    @Test
    fun `the unsupported browser answers nothing`() = runBlocking {
        assertNull(skiBrowserIn(null).read("https://example.test", "x"))
    }
}
