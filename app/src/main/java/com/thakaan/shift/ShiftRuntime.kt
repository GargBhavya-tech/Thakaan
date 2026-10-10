package com.thakaan.shift

import android.content.Context
import com.thakaan.audit.ChainSigner
import com.thakaan.audit.ConsoleSafe
import com.thakaan.audit.CrewStatus
import com.thakaan.audit.CrewStatusCodec
import com.thakaan.audit.ExposureChain
import com.thakaan.audit.KeystoreSigner
import com.thakaan.audit.SoftwareSigner
import com.thakaan.exposure.Wbgt
import com.thakaan.exposure.WeatherCache
import com.thakaan.sensorhub.SensorHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Phone-side owner of the [ShiftEngine]: subscribes to the sensor hub (live or replay readings arrive through the same
 * flow, with their provenance already stamped) and publishes the latest snapshot for the screens. DEVICE-UNTESTED.
 */
object ShiftRuntime {
    private val _snapshot = MutableStateFlow<ShiftSnapshot?>(null)
    val snapshot: StateFlow<ShiftSnapshot?> = _snapshot

    @Volatile var engine: ShiftEngine? = null; private set

    /** The running shift's report data; replaced when a new shift starts and kept after it stops so the report can still be read. */
    @Volatile var log = ShiftLog(); private set
    private var job: Job? = null
    private var chain: ExposureChain? = null
    private var signer: ChainSigner? = null
    private var peakSeen = com.thakaan.risk.Level.COOL
    private var lastChainMinute = -1L

    /** @param startEpochSec what the first reading's timestamp means in wall-clock time (replay: the recording's date). */
    fun start(
        context: Context, scope: CoroutineScope, startEpochSec: Long = System.currentTimeMillis() / 1000,
        profile: com.thakaan.settings.UserProfile? = null,
        /** Called when the level RISES (never when it falls), with the spoken and haptic plan in the worker's language. */
        onAlert: ((com.thakaan.risk.AlertPlan) -> Unit)? = null,
    ) {
        stop()
        val weather = try {
            context.assets.open("weather/bengaluru_open-meteo.json").bufferedReader().use { WeatherCache.fromOpenMeteoJson(it.readText()) }
        } catch (_: Exception) { null } // missing cache: WBGT unknown, which the risk engine treats as hot
        val air = try {
            context.assets.open("airquality/bengaluru_open-meteo_pm25.json").bufferedReader().use { com.thakaan.exposure.AirQualityCache.fromOpenMeteoJson(it.readText()) }
        } catch (_: Exception) { null } // missing cache: the card says there is no air data
        val e = ShiftEngine(profile?.toShiftConfig(startEpochSec) ?: ShiftConfig(startEpochSec), weather, Wbgt(), airQuality = air)
        val lang = profile?.language ?: com.thakaan.risk.Lang.HI
        var lastAlerted = com.thakaan.risk.Level.COOL
        engine = e
        log = ShiftLog()
        val sg: ChainSigner = try { KeystoreSigner(context = context) } catch (_: Throwable) { SoftwareSigner() }
        signer = sg
        chain = ExposureChain(sg)
        peakSeen = com.thakaan.risk.Level.COOL
        job = scope.launch {
            SensorHub.readings.collect { r ->
                e.onReading(r)?.let { s ->
                    _snapshot.value = s
                    log.add(s.tNanos, s.level, s.tss, s.provenance)
                    if (s.level > peakSeen) peakSeen = s.level
                    if (s.level > lastAlerted && s.level > com.thakaan.risk.Level.COOL) {
                        onAlert?.invoke(com.thakaan.risk.AlertLadder.plan(s.level, lang))
                        com.thakaan.mesh.CrewMesh.onLevelRise(s.level) // buddies hear Strained and Danger only, level only
                    }
                    lastAlerted = s.level // falling resets so the same level can alert again on the next rise
                    val minute = s.tNanos / 60_000_000_000L
                    if (minute % 15 == 0L && minute != lastChainMinute) { // one log entry per 15 minutes
                        lastChainMinute = minute
                        chain?.append(System.currentTimeMillis(), s.sunDoseMin, s.minutesSinceRest, peakSeen.name, s.provenance.name)
                    }
                }
            }
        }
    }

    /** True while the engine is collecting readings (live or replay). The screen reads this so it does not forget a running shift. */
    val isRunning: Boolean get() = job?.isActive == true

    fun stop() { job?.cancel(); job = null }

    fun exportChain(): String? = chain?.export()

    /** The sealed crew status for the console: level and coarse reasons only (see ConsoleSafe). */
    fun crewStatusSealed(member: String, nonce: String?, shareLocation: Boolean): String? {
        val s = _snapshot.value ?: return null
        val sg = signer ?: return null
        val pos = engine?.position
        val status = CrewStatus(
            member, s.level.name, s.trend, s.minutesSinceRest.toInt(), s.sunDoseMin.toInt(), s.provenance.name,
            ConsoleSafe.labels(s.driverCodes), System.currentTimeMillis(), if (shareLocation) pos?.first else null, if (shareLocation) pos?.second else null, nonce,
        )
        return CrewStatusCodec.seal(status, sg)
    }
}

