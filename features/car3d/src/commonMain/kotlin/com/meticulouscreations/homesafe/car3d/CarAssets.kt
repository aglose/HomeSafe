package com.meticulouscreations.homesafe.car3d

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** How much of the car this platform draws: the Mac the full mesh, the phones the lighter one. */
internal class CarTier(
    val name: String,
    val samples: Int,
    val maxLongEdgePx: Int,
    /** The sharpest the coat reflects: fewer triangles, on a lower-resolution sky, want a softer mirror. */
    val coatSoftness: Float = 0f,
)

internal val HIGH_TIER = CarTier("high", samples = 4, maxLongEdgePx = 2800)
internal val MOBILE_TIER = CarTier("mobile", samples = 4, maxLongEdgePx = 1800, coatSoftness = 0.08f)

internal expect val carTier: CarTier

/** A file from tools/car-model/pack.sh. The phones' are in the shared resources, the Mac's beside its code. */
internal expect suspend fun readCarFile(name: String): ByteArray

internal suspend fun loadCarAssets(model: CarModel): CarAssets = withContext(Dispatchers.IO) {
    val tier = carTier.name
    val wheels = model.look.wheels
    val front = CarMeshFile(readCarFile("wheel_${wheels}_front_$tier.car"))
    val rear = if (model.look.rearWheelsDiffer) CarMeshFile(readCarFile("wheel_${wheels}_rear_$tier.car")) else front
    CarAssets(
        body = CarMeshFile(readCarFile("body_$tier.car")),
        frontWheel = front,
        rearWheel = rear,
        environment = CarEnvironmentFile(readCarFile("day_$tier.env")),
        shadow = readCarFile("shadow.bin"),
        shadowSize = SHADOW_SIZE,
    )
}

private const val SHADOW_SIZE = 512
