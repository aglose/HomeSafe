package com.meticulouscreations.homesafe.packages.domain

import com.meticulouscreations.homesafe.text.UiText
import kotlinx.coroutines.flow.StateFlow

/** Where this device's list stands against the box's. */
data class PackagesSync(val syncing: Boolean = false, val problem: PackagesProblem? = null, val message: UiText? = null, val checkedAt: Long? = null)

/** What someone can say about a parcel; only what isn't null changes. */
data class ParcelChange(
    val nickname: String? = null,
    val notes: String? = null,
    val owner: String? = null,
    val surprise: Boolean? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val returnBy: String? = null,
    val returnState: ReturnState? = null,
    val porch: PorchState? = null,
    /** Someone looked at the camera's two pictures: there was a parcel, or there wasn't. */
    val cameraRight: Boolean? = null,
    val markDelivered: Boolean = false,
)

/** The parcels, from the relay, and what the household says about them. Each answer replaces [snapshot], so a change is on screen as soon as it is saved. */
interface PackagesRepository {
    /** What the relay last said; null until it has said anything since the app started. */
    val snapshot: StateFlow<PackagesSnapshot?>
    val sync: StateFlow<PackagesSync>

    /** Empties what is held if nobody is signed in any more: it isn't the next person's to see. */
    fun forgetIfSignedOut()

    suspend fun refresh(readMail: Boolean = false)

    /** Adds one by tracking number. A failure's text says why, to show as it is. */
    suspend fun add(tracking: String, nickname: String?, owner: String?, surprise: Boolean): Result<Unit>

    suspend fun change(parcelId: String, change: ParcelChange): Result<Unit>

    suspend fun forget(parcelId: String): Result<Unit>

    suspend fun setPhoneOwner(person: String?): Result<Unit>

    suspend fun setNotify(kind: String, on: Boolean): Result<Unit>

    suspend fun setHideNew(person: String, on: Boolean): Result<Unit>

    /** The address of a kept picture of a delivery, or the landing as it is this moment ([parcelId] null); null while signed out. */
    suspend fun pictureUrl(parcelId: String?, name: String): String?
}
