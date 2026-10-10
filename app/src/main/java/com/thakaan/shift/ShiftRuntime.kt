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
    private var job: Job? = null
    private var chain: ExposureChain? = null
    private var signer: ChainSigner? = null
    private var peakSeen = com.thakaan.risk.Level.COOL
    private var lastChainMinute = -1L

    /** @param startEpochSec what the first reading's timestamp means in wall-clock time (replay: the recording's date). */
    fun start(context: Context, scope: CoroutineScope, startEpochSec: Long = System.currentTimeMillis() / 1000) {
        stop()
        val weather = try {
            context.assets.open("weather/bengaluru_open-meteo.json").bufferedReader().use { WeatherCache.fromOpenMeteoJson(it.readText()) }
        } catch (_: Exception) { null } // missing cache: WBGT unknown, which the risk engine treats as hot
        val e = ShiftEngine(ShiftConfig(startEpochSec), weather, Wbgt())
        engine = e
        val sg: ChainSigner = try { KeystoreSigner(context = context) } catch (_: Throwable) { SoftwareSigner() }
        signer = sg
        chain = ExposureChain(sg)
        peakSeen = com.thakaan.risk.Level.COOL
        job = scope.launch {
            SensorHub.readings.collect { r ->
                e.onReading(r)?.let { s ->
                    _snapshot.value = s
                    if (s.level > peakSeen) peakSeen = s.level
                    val minute = s.tNanos / 60_000_000_000L
                    if (minute % 15 == 0L && minute != lastChainMinute) { // one log entry per 15 minutes
                        lastChainMinute = minute
                        chain?.append(System.currentTimeMillis(), s.sunDoseMin, s.minutesSinceRest, peakSeen.name, s.provenance.name)
                    }
                }
            }
        }
    }

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

