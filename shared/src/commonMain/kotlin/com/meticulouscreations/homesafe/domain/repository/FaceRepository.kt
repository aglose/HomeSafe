package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.FaceLibrary

/**
 * Frigate's face library and the human-in-the-loop naming that grows it. The server owns all
 * of it; nothing is cached here. Requires face recognition to be enabled in Frigate's config.
 */
interface FaceRepository {
    suspend fun getLibrary(): Result<FaceLibrary>
    /** [name] is a Frigate-safe key (see [FaceLibrary.personKey]). */
    suspend fun createPerson(name: String): Result<Unit>
    /** Files one attempt under [name]; Frigate registers the face immediately, no training step. */
    suspend fun labelAttempt(fileName: String, name: String): Result<Unit>
    suspend fun discardAttempts(fileNames: List<String>): Result<Unit>
    /** Removes registered images from a person; deleting their last image effectively forgets them. */
    suspend fun deleteImages(name: String, fileNames: List<String>): Result<Unit>
    /** Where an image lives for the current server — [folder] is a person's key or the attempts folder; null when disconnected. */
    fun imageUrl(folder: String, fileName: String): String?
}
