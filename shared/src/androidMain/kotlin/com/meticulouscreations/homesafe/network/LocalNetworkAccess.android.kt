package com.meticulouscreations.homesafe.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.CompletableDeferred

/** Android 17, the first release where the local network is behind a permission of its own. */
private const val ANDROID_17 = 37

/**
 * Android 17 blocks an app that targets it from every address on the Wi-Fi or Ethernet it is
 * joined to until the user grants `ACCESS_LOCAL_NETWORK` (the "Nearby devices" group). A blocked
 * TCP connection isn't refused, it times out, so without the grant the LAN address looks the way
 * it does away from home. VPN traffic is not covered, which is why Tailscale kept working.
 * Older releases have no such permission (asking them about it would read as denied), so they
 * answer true without a prompt.
 *
 * After two refusals Android stops showing the dialog and answers the request with a denial
 * straight away, so asking again on a later sign-in costs nothing; a grant made in system
 * settings is picked up by the route check the app runs when it comes back to the foreground.
 */
private class AndroidLocalNetworkAccess(private val context: Context, activity: FragmentActivity?) : LocalNetworkAccess {
    private var pendingRequest: CompletableDeferred<Boolean>? = null

    // Registered on the registry directly, as AlertNotifier.android.kt does: the graph is built
    // too late in onCreate for the lifecycle-bound register().
    private val permissionLauncher = activity?.activityResultRegistry?.register(
        "homesafe.local_network",
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingRequest?.complete(granted)
        pendingRequest = null
    }

    override suspend fun request(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_17) return true
        if (isGranted()) return true
        // No Activity (a geofence or boot receiver woke the app): nowhere to ask from.
        val launcher = permissionLauncher ?: return false
        pendingRequest?.let { return it.await() }
        val request = CompletableDeferred<Boolean>()
        pendingRequest = request
        launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        return request.await()
    }

    private fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}

actual fun createLocalNetworkAccess(context: PlatformContext): LocalNetworkAccess =
    AndroidLocalNetworkAccess(context.context.applicationContext, context.context as? FragmentActivity)
