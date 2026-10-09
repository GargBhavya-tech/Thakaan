# Ticket #2: Loaner smoke test

Device: iQOO 15 (`I2501`), Android 16 (SDK 36), serial `10BFAT1UPE000XP`. Run 2026-10-10 with `tools/smoketest` (build with `tools/smoketest/build.ps1`, read output with `adb logcat -d -s THK_SMOKE:I`). Sensor rows were also cross-checked with `adb shell dumpsys sensorservice`.

| # | Assumption | Result | Measured value | Fallback / note |
| --- | --- | --- | --- | --- |
| 1 | SoC is SM8850 | **YES** | `ro.soc.model = SM8850`, QTI, board `canoe` | — |
| 2 | Colour-temperature sensor exposed | **LISTED BUT NOT USABLE** (found while building #5: `registerListener` returns false for every sampling period, so a normal app cannot read it; E1 must use lux + camera white balance) | `stk_stk6b9x light_cct`, vendor type `android.sensor.light_cct` (66557), maxRange 4096, on-change | Not the standard `TYPE_LIGHT_CCT`; find it by `getStringType()`. Events carry ~16 raw channel values, not a plain kelvin number, so #17 must decode them on the phone first. Lux + camera white balance stays as the E1 fallback until decoding is proven. |
| 3 | Flicker sensor exposed | **NO** | none of the 54 sensors | No vector in the docs depends on it. Nothing to fall back from. |
| 4 | Ambient light max range | **YES** | `tcs3743`, maxRange 66110 lux, resolution 0.01. A second top-side sensor `stk6a2x` has maxRange 4096. | 66 110 lux covers shade but **not** full sun (about 100 000 lux): saturation must be treated as "sun" (fail safe), and #17 must not read it as a true irradiance. |
| 5 | Torch strength levels | **YES** | rear camera id 0: `maxLevel = 4`, `defaultLevel = 3` | Only 4 steps, so #8 can tune brightness coarsely. |
| 6 | GNSS raw measurements, full tracking, L5 carriers | **NOT VERIFIED** | Hardware advertises GPS L5 / Galileo E5a / BDS B2a / QZSS L5 and L1 (signal types in `GnssCapabilities`); `registerGnssMeasurementsCallback(fullTracking=true)` accepted, 29 events in 30 s, **0 measurements**. Gnss year 2023, modem `MPSS.DE.9.0-01805.10`. | Run was indoors with no sky view, so this proves nothing about L5. Re-run outdoors (checklist below). If L5 is absent, drop the L1−L5 feature (#21). |
| 7 | `getThermalHeadroom()` returns numbers | **YES** | 0.555, 0.555, 0.556 over 3 s (10 s horizon), thermal status 0 | Real numbers, not NaN. |
| 8 | Offline TTS hi-IN | **YES** | engine `com.google.android.tts`, 5 offline voices (e.g. `hi-in-x-hie-local`) | — |
| 9 | Offline TTS kn-IN | **YES** | 3 offline voices (e.g. `kn-in-x-knc-local`) | — |
| 10 | Offline TTS ta-IN / te-IN / en-IN (the other alert languages) | **YES** | ta: 3, te: 3, en-IN: 5 offline voices | Install state of voice data should be re-checked on the demo phone. |
| 11 | Accelerometer max sample rate | **YES** | `lsm6dsvx`: framework reports 200 Hz (`minDelay` 5000 µs); hardware list in dumpsys shows 479.85 Hz | Rates above 200 Hz need the `HIGH_SAMPLING_RATE_SENSORS` permission. 200 Hz is enough for tremor (#32). |

## Open: GNSS re-run (needs the phone outdoors with sky view)

1. Take the phone outside, away from buildings, screen on. Location setting must be ON.
2. `adb shell am start -n com.thakaan.smoke/.SmokeActivity`
3. Wait 40 s, then `adb logcat -d -s THK_SMOKE:I | findstr GNSS`
4. Pass: `measurements > 0`, `L5/E5a > 0`, `L5withCodeLock > 0`. Record the numbers above and tick row 6.
5. If `L5 = 0` while `L1 > 0`, drop the L1−L5 feature in #21. If `measurements = 0` outdoors, GNSS raw is unavailable and E12 to E14 need the fallback.

The probe app (`com.thakaan.smoke`) is still installed on the loaner; remove with `adb uninstall com.thakaan.smoke`.
