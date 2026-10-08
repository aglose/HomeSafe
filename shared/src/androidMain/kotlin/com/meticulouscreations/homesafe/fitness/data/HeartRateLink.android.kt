package com.meticulouscreations.homesafe.fitness.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.HeartLinkEvent
import com.meticulouscreations.homesafe.fitness.domain.HeartRateLink
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.SensorSighting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "HomeSafeHeartRate"

/** The Bluetooth SIG's base UUID with a 16-bit assigned number in it. */
private fun assigned(number: String): UUID = UUID.fromString("0000$number-0000-1000-8000-00805f9b34fb")

private val HEART_RATE_SERVICE = assigned("180d")
private val HEART_RATE_MEASUREMENT = assigned("2a37")

/** The descriptor on a characteristic that turns its notifications on. */
private val CLIENT_CONFIGURATION = assigned("2902")

private val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

/** The scan or the link ended for a reason worth knowing. */
private class LinkDropped(message: String) : Exception(message)

/**
 * Says why in the log on the way out. The monitor only needs to know that a link has gone; a
 * person with a band that won't connect needs to know what the radio said
 * (`adb logcat -s HomeSafeHeartRate`).
 */
private fun dropped(reason: String): LinkDropped {
    Log.i(TAG, reason)
    return LinkDropped(reason)
}

/**
 * A heart-rate sensor over Android's Bluetooth LE: a scan filtered on the Heart Rate Service, and
 * a GATT connection with notifications turned on for Heart Rate Measurement. Both are held only
 * while their flow is collected, so there is nothing here to leak: `HeartRateMonitorImpl` decides
 * when to look and when to give up.
 *
 * The permissions are Android 12's pair, `BLUETOOTH_SCAN` (declared `neverForLocation`, so no
 * location permission rides along) and `BLUETOOTH_CONNECT`, both in the "Nearby devices" group
 * and so one dialog. After two refusals Android stops showing it and only system settings can
 * grant it, which is why [hasPermission] asks the system afresh every time.
 *
 * Every call that needs them is made only after the monitor has checked [hasPermission]; a
 * permission pulled in system settings between the check and the call arrives as a
 * [SecurityException], which ends the flow like any other failure.
 *
 * What the radio does is logged under `HomeSafeHeartRate`, without addresses.
 */
@SuppressLint("MissingPermission")
private class AndroidHeartRateLink(private val context: Context, activity: FragmentActivity?) : HeartRateLink {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var pendingRequest: CompletableDeferred<Boolean>? = null

    // Registered on the registry directly, as LocalNetworkAccess.android.kt does: the graph is built
    // too late in onCreate for the lifecycle-bound register().
    private val permissionLauncher = activity?.activityResultRegistry?.register(
        "homesafe.heart_rate",
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        pendingRequest?.complete(PERMISSIONS.all { granted[it] == true })
        pendingRequest = null
    }

    override val isSupported: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && manager?.adapter != null

    override fun hasPermission(): Boolean =
        PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    // The monitor calls from a background thread; the dialog is put up, and its answer taken, on the main one.
    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (hasPermission()) return@withContext true
        // No Activity (something woke the app with no screen): nowhere to ask from.
        val launcher = permissionLauncher ?: return@withContext false
        val request = pendingRequest ?: CompletableDeferred<Boolean>().also { asking ->
            pendingRequest = asking
            try {
                launcher.launch(PERMISSIONS)
            } catch (e: IllegalStateException) {
                // The Activity has gone since this was built.
                Log.w(TAG, "Couldn't ask for Bluetooth", e)
                pendingRequest = null
                return@withContext false
            }
        }
        request.await()
    }

    override fun isBluetoothOn(): Boolean = manager?.adapter?.isEnabled == true

    override fun scan(): Flow<SensorSighting> = callbackFlow {
        val scanner = manager?.adapter?.bluetoothLeScanner
        val heard = HashSet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // The name is in the advertisement or its scan response, when it is anywhere; the device's own is only known once bonded.
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                if (heard.add(result.device.address)) Log.i(TAG, "Heard a heart-rate sensor: ${name ?: "(no name)"} at ${result.rssi} dBm")
                trySend(SensorSighting(HeartSensor(result.device.address, name.orEmpty()), result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(dropped("Scan failed: $errorCode"))
            }
        }
        if (scanner == null) {
            close(dropped("No scanner: Bluetooth is off"))
        } else {
            try {
                Log.i(TAG, "Scanning for the Heart Rate Service")
                scanner.startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()),
                    // Only ever for the seconds it takes to find one sensor, so the fastest scan is the cheap one.
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    callback,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Scan not allowed", e)
                close(e)
            }
        }
        awaitClose {
            // Throws if Bluetooth was switched off meanwhile, or the permission taken away; the scan is gone either way.
            runCatching { scanner?.stopScan(callback) }
        }
    }

    override fun connect(address: String): Flow<HeartLinkEvent> = callbackFlow {
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    // 133 here is the stack's catch-all: out of range, or it stopped advertising between the scan and now.
                    status != BluetoothGatt.GATT_SUCCESS -> close(dropped("Connection ended with status $status"))

                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected; discovering services")
                        if (!gatt.discoverServices()) close(dropped("Service discovery wouldn't start"))
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected")
                        close()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val measurement = gatt.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
                val configuration = measurement?.getDescriptor(CLIENT_CONFIGURATION)
                if (status != BluetoothGatt.GATT_SUCCESS || measurement == null || configuration == null) {
                    close(dropped("No Heart Rate Measurement to subscribe to (status $status, service ${if (gatt.getService(HEART_RATE_SERVICE) != null) "found" else "missing"})"))
                    return
                }
                // Two halves: tell Android to deliver the notifications, and tell the sensor to send them.
                val asked = gatt.setCharacteristicNotification(measurement, true) &&
                    gatt.writeDescriptor(configuration, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                if (!asked) close(dropped("Couldn't ask for notifications"))
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != CLIENT_CONFIGURATION) return
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.i(TAG, "Notifications on")
                        trySend(HeartLinkEvent.Ready)
                    }

                    // The sensor wants the link encrypted first. Android starts pairing by itself (the user may see its
                    // dialog); closing now would cut that short, so the link is left up. If nothing comes of it the
                    // monitor gives the attempt up, and the next one finds the pairing already made.
                    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION ->
                        Log.i(TAG, "Sensor asked to be paired before notifying (status $status)")

                    else -> close(dropped("Notifications refused: $status"))
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == HEART_RATE_MEASUREMENT) trySend(HeartLinkEvent.Measurement(value))
            }
        }
        var gatt: BluetoothGatt? = null
        try {
            // autoConnect false: connect now, to a sensor a scan has just heard, and fail fast if it has gone.
            // Android 17 deprecates this overload for one taking a BluetoothGattConnectionSettings, which only 17 has;
            // this is the one every release back to the app's oldest (13) shares.
            @Suppress("DEPRECATION")
            gatt = manager?.adapter?.getRemoteDevice(address)?.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) close(dropped("Couldn't connect: Bluetooth is off"))
        } catch (e: SecurityException) {
            Log.w(TAG, "Connecting not allowed", e)
            close(e)
        } catch (e: IllegalArgumentException) {
            // Not an address at all.
            close(e)
        }
        awaitClose {
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
        }
    }
}

actual fun createHeartRateLink(platformContext: PlatformContext): HeartRateLink =
    AndroidHeartRateLink(platformContext.context.applicationContext, platformContext.context as? FragmentActivity)
