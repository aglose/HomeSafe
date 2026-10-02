package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ClipRange
import com.meticulouscreations.homesafe.domain.model.RecordingHistory
import com.meticulouscreations.homesafe.domain.model.RecordingPlaylist
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.platform.ClipDownloadProgress
import com.meticulouscreations.homesafe.domain.platform.ClipDownloader
import com.meticulouscreations.homesafe.domain.repository.RecordingsRepository
import com.meticulouscreations.homesafe.text.asUiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SaveRecordingClipUseCaseTest {

    private object FakeRecordings : RecordingsRepository {
        override suspend fun getRecordingHistory(serverUrl: String, cameraName: String, afterEpochSeconds: Double, beforeEpochSeconds: Double): Result<RecordingHistory> =
            error("unused")

        override suspend fun getRecordingStream(serverUrl: String, cameraName: String, playlist: RecordingPlaylist): RecordingStream = error("unused")

        override suspend fun getRecordingClipDownload(serverUrl: String, cameraName: String, startEpochSeconds: Double, endEpochSeconds: Double) =
            RecordingStream(url = "$serverUrl/api/$cameraName/clip.mp4", headers = emptyMap())
    }

    /** Reports each of [steps] once, then holds until [finish] is completed with the outcome. */
    private class ScriptedDownloader(private val steps: List<ClipDownloadProgress>) : ClipDownloader {
        val finish = CompletableDeferred<Result<Unit>>()
        var requestedUrl: String? = null

        override suspend fun download(url: String, headers: Map<String, String>, fileName: String, onProgress: (ClipDownloadProgress) -> Unit): Result<Unit> {
            requestedUrl = url
            steps.forEach(onProgress)
            return finish.await()
        }
    }

    private val range = ClipRange(1_790_000_000.0, 1_790_000_030.0)

    private fun TestScope.useCase(downloader: ClipDownloader) =
        SaveRecordingClipUseCase(FakeRecordings, downloader, appScope = backgroundScope)

    @Test
    fun followsTheDownloadsProgressThenSaysSaved() = runTest {
        val downloader = ScriptedDownloader(listOf(ClipDownloadProgress.Preparing, ClipDownloadProgress.Downloading(0.4f)))

        val save = useCase(downloader)("http://frigate", "porch", range)
        assertEquals(RecordingClipSave.Running(ClipDownloadProgress.Preparing), save.value)

        runCurrent()
        assertEquals("http://frigate/api/porch/clip.mp4", downloader.requestedUrl)
        assertEquals(RecordingClipSave.Running(ClipDownloadProgress.Downloading(0.4f)), save.value)

        downloader.finish.complete(Result.success(Unit))
        runCurrent()
        assertEquals(RecordingClipSave.Saved, save.value)
    }

    @Test
    fun aFailedDownloadSaysWhy() = runTest {
        val downloader = ScriptedDownloader(emptyList())
        val save = useCase(downloader)("http://frigate", "porch", range)

        downloader.finish.complete(Result.failure(IllegalStateException("Frigate answered 500 for the clip")))
        runCurrent()

        assertEquals(RecordingClipSave.Failed("Frigate answered 500 for the clip".asUiText()), save.value)
    }

    @Test
    fun theSaveCarriesOnAfterWhoeverStartedItStopsFollowing() = runTest {
        val downloader = ScriptedDownloader(emptyList())
        val save = useCase(downloader)("http://frigate", "porch", range)

        // The editor following the save, then closed mid-download.
        val follower = launch { save.first { it !is RecordingClipSave.Running } }
        runCurrent()
        follower.cancel()

        downloader.finish.complete(Result.success(Unit))
        runCurrent()
        assertEquals(RecordingClipSave.Saved, save.value)
    }
}
