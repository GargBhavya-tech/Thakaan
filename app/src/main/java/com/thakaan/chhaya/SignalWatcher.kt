package com.thakaan.chhaya

import android.content.Context
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * The phone's own mobile-signal level (0 = no service to 4 = full bars), kept in memory for [RiderMapFeed]. It needs no extra
 * permission: it listens to signal strength only, not to calls, cells or the phone number. If the phone refuses (or has no SIM)
 * the level stays null and nothing is logged, which is the honest "unknown", never a made-up zero.
 */
object SignalWatcher {
    @Volatile var level: Int? = null; private set
    private var started = false
    private var callback: TelephonyCallback? = null

    @Synchronized fun start(ctx: Context) {
        if (started) return
        try {
            val tm = ctx.applicationContext.getSystemService(TelephonyManager::class.java) ?: return
            val cb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) { level = signalStrength.level.coerceIn(0, 4) }
            }
            tm.registerTelephonyCallback(ctx.applicationContext.mainExecutor, cb)
            callback = cb; started = true
        } catch (_: Throwable) { level = null } // SecurityException or no telephony: stay unknown
    }
}
