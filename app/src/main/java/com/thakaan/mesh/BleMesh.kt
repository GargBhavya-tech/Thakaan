package com.thakaan.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * The radio for the crew mesh: Bluetooth LE extended advertisements, broadcast only. Each phone advertises one live message at
 * a time, rotating every few seconds, and scans for the others; there is no connection and no pairing at the radio level, and
 * no internet is used (the app has no INTERNET permission). The payload is the small [MeshCodec] message in the service data of
 * one fixed 128-bit UUID. Messages are anonymous at the radio (random addresses that rotate, no name, no device id); buddy
 * alerts are readable only with the shared pairing code. The relay rules live in [MeshNode]. DEVICE-UNTESTED: it needs two
 * phones, and the iQOO 15 must report extended advertising support (see [unsupportedReason]).
 */
class BleMesh(private val ctx: Context, private val node: MeshNode, private val onReceived: (Received) -> Unit) {
    companion object {
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("7b1c0a52-5a6e-4d3f-9a2e-6a4b1f7d3c10"))
        const val ROTATE_MS = 3_000L

        /** The runtime permissions to ask for before [start]. Before Android 12 Bluetooth LE scanning needs location instead. */
        fun permissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun hasPermissions(ctx: Context): Boolean = permissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var next = 0
    private var currentSet: AdvertisingSet? = null
    private val adapter get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** Why the mesh cannot run on this phone, or null when it can. */
    @SuppressLint("MissingPermission")
    fun unsupportedReason(): String? {
        val a = adapter ?: return "No Bluetooth on this phone"
        if (!a.isEnabled) return "Bluetooth is off"
        if (!a.isLeExtendedAdvertisingSupported) return "This phone does not support extended Bluetooth advertising"
        if (a.bluetoothLeAdvertiser == null || a.bluetoothLeScanner == null) return "Bluetooth LE is not available right now"
        return null
    }

    private val advCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) { currentSet = set }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val bytes = result.scanRecord?.getServiceData(SERVICE) ?: return
            val r = node.receive(bytes)
            if (r !is Received.Ignored) onReceived(r)
        }
    }

    private val rotate = object : Runnable {
        override fun run() {
            if (!running) return
            advertiseNext()
            handler.postDelayed(this, ROTATE_MS)
        }
    }

    /** Starts scanning and advertising. Returns null on success, or the reason it could not start. */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        unsupportedReason()?.let { return it }
        if (!hasPermissions(ctx)) return "Bluetooth permission not granted"
        if (running) return null
        running = true
        val filter = ScanFilter.Builder().setServiceData(SERVICE, byteArrayOf((MeshCodec.VERSION shl 4).toByte()), byteArrayOf(0xF0.toByte())).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED).build()
        adapter.bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
        handler.post(rotate)
        return null
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        handler.removeCallbacks(rotate)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCallback) }
        currentSet = null
    }

    /** Puts the next live message on the air, or goes quiet when there is nothing to say (saves battery; scanning goes on). */
    @SuppressLint("MissingPermission")
    private fun advertiseNext() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        val pending = node.pendingForAir()
        runCatching { adv.stopAdvertisingSet(advCallback) }
        currentSet = null
        if (pending.isEmpty()) return
        val payload = pending[next++ % pending.size]
        val params = AdvertisingSetParameters.Builder().setLegacyMode(false).setConnectable(false).setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM).setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM).build()
        val data = AdvertiseData.Builder().addServiceData(SERVICE, payload).setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build()
        runCatching { adv.startAdvertisingSet(params, data, null, null, null, advCallback) }
    }
}
