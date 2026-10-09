package com.thakaan.sensorhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns every live sensor and feeds [SensorHub].
 * OriginOS kills background work, so this must stay a foreground service with the app locked in Recents.
 */
class SensorHubService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sm: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val stream = when {
                e.sensor.type == Sensor.TYPE_ACCELEROMETER -> Stream.ACCEL
                e.sensor.type == Sensor.TYPE_GYROSCOPE -> Stream.GYRO
                e.sensor.type == Sensor.TYPE_LIGHT -> Stream.LIGHT
                e.sensor.stringType == CCT_TYPE -> Stream.CCT
                else -> return
            }
            SensorHub.publishLive(stream, e.timestamp, e.values.copyOf())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val locationListener = LocationListener { l: Location ->
        SensorHub.publishLive(
            Stream.GNSS, l.elapsedRealtimeNanos,
            floatArrayOf(l.latitude.toFloat(), l.longitude.toFloat(), l.accuracy, l.speed),
        )
    }

    private val ticker = object : Runnable {
        override fun run() {
            SensorHub.snapshot()
            Log.i("THK_HUB", SensorHub.stats.value.entries.joinToString(" ") { (s, st) ->
                "${s.name}=${st.count}@%.0fHz/${st.lastProvenance}%s".format(st.ratePerSec, if (st.sawNonReal) "!NONREAL" else "")
            })
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("sensor-hub").also { it.start() }
        handler = Handler(thread.looper)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        SensorHub.reset()
        registerSensors()
        registerLocation()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "thakaan:hub").apply { acquire(4 * 60 * 60 * 1000L) }
        handler.post(ticker)
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Thakaan sensing", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Thakaan is watching")
            .setContentText("Sensing on this phone only")
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun registerSensors() {
        // 5000 us = 200 Hz, the framework maximum without HIGH_SAMPLING_RATE_SENSORS beyond it (see docs/SMOKE_TEST.md).
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(sensorListener, it, 5000, handler) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(sensorListener, it, 5000, handler) }
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        val cct = sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.stringType == CCT_TYPE }
        // The vendor CCT sensor refuses some sampling periods; take the first one the framework accepts.
        val ok = cct?.let { s ->
            intArrayOf(SensorManager.SENSOR_DELAY_NORMAL, 0, 1_000_000, 5_000_000).firstOrNull { us ->
                sm.registerListener(sensorListener, s, us, handler).also { Log.i("THK_HUB", "CCT try ${us}us -> $it") }
            }
        }
        Log.i("THK_HUB", "CCT sensor=${cct?.name} type=${cct?.type} registered=$ok")
    }

    private fun registerLocation() {
        val granted = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        @Suppress("MissingPermission")
        locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, thread.looper)
    }

    override fun onDestroy() {
        sm.unregisterListener(sensorListener)
        locationManager?.removeUpdates(locationListener)
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.thakaan.STOP"
        private const val CHANNEL = "sensing"
        private const val CCT_TYPE = "android.sensor.light_cct"
    }
}
