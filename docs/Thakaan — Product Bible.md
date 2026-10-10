# Thakaan — Product Bible

Oct 10, 2026 · @Ashish

**Version:** v3, updated to the as-built state on 10 Oct 2026 (end of day). Where this text and `Thakaan — Build Map v1.md` disagree, this document wins on intent and the Build Map wins on execution order. Where either disagrees with what the phone has shown, `docs/DEVICE_RESULTS.md` and `docs/TICKETS.md` win on status.

## About this version

The first text of this bible described the product as designed. Three things have changed since: the design moved to v3, most of the design was built, and the build taught us where the design was wrong. This version folds all three in.

**Design changes (v2 to v3):**

- The 5-second reaction and speech tests are gone. The brain battery is now a tremor hold plus Fitts tapping (20 s), a digit code, a symptom tap, and a 3-minute PVT-B only to confirm a flag.
- The ECTemp filter uses the published sigmoid observation model, matches the seven reference vectors first, and is personalised only after that.
- WBGT is Liljegren (`lwbgt` through the NDK), never the ACSM87 formula by day. The phone's own body temperature is no longer used as a globe thermometer.
- HRV and PRV need at least 60 s of clean pulse. Windows under 60 s never produce RMSSD.
- Provenance (REAL, REPLAY, SIMULATED), the safety invariant, the hash-chained log and the honest limitations are core, not add-ons.

**Build decisions that differ from the first text** (each is marked **As built** where it applies):

- Routing is our own Dijkstra over an OSM-derived graph, not GraphHopper or Valhalla.
- Shade is computed ahead of time on the laptop, not measured, and ships as tables. Two layers exist: OSM footprints, and satellite building and canopy heights on an H3 res-11 grid.
- The spoken digit code uses an offline Vosk digit grammar. Whisper-tiny is an advisory second opinion in a lab screen only.
- The TSS weights are fixed, not fitted, and no probability calibration is applied yet.
- The four small learned models were trained on simulated data and run as shadow models. None decides a level.
- Riding smoothness (N2), passive touch drift (N5), confusion markers (N6) and the rotation advisor are not built.

**How to read status.** Three words are used on purpose:

- **Built:** code exists and laptop unit tests pass (452 test cases across 56 test files at this writing).
- **Seen on the phone:** the iQOO 15 has shown it working, recorded in `docs/DEVICE_RESULTS.md`.
- **Not verified:** built, but a human, a reference device or real outdoor conditions are still needed. Nothing in this class may be claimed on stage.

A full status table is in "Build status as of 10 Oct 2026" near the end.

## Vision

Thakaan turns the phone an outdoor worker already carries into a personal heat-strain guardian: it senses the body, the brain and the sun, and steers the worker to shade and water before heat takes them down. Everything runs on the iQOO 15, offline, with no wearable.

- **One-liner:** A phone that feels your heat strain and routes you to shade before you collapse.
- **Tagline:** *Garmi se pehle, Thakaan.* (Before the heat gets you.)
- **Name:** Thakaan (थकान) means exhaustion; the product exists so the worker never reaches it.
- **Sub-product:** Chhaya Route (छाया, shade), strain-aware routing to shade and water.
- **Platform:** iQOO 15 (Snapdragon 8 Elite Gen 5, Hexagon NPU, Adreno 840), OriginOS 6 / Android 16, Kotlin and Compose.
- **Track:** HealthTech ("heat illness is an occupational-health emergency"); fallback Open Innovation.
- **Framing:** a heat-strain wellbeing tool, not a medical device.

## Problem and users

Heat stroke attacks judgement before the body gives up, so the people most exposed keep working until they collapse. Those people are paid per task, carry a phone all day, and do not own a heat-strain wearable.

- **What fails today:** City-level heat alerts say nothing about one person. Wearables (chest straps, patches, smartwatches) cost too much and are uncomfortable in humid heat. Platform rest points exist, but workers skip them because rest costs earnings.
- **The gap Thakaan fills:** a personal, real-time heat-strain signal plus an action that fits the job: a shaded route, a water stop, a rest at the right moment.
- **Why riders are a special case:** riding is light work (about 1.5 to 2.5 MET) and the wind keeps the body cool while moving. At a red light over hot tarmac the wind vanishes and the body heats within seconds. Tools built for soldiers and labourers miss this pattern.

| User | Where the phone lives | What they need |
| --- | --- | --- |
| Delivery rider (scooter / motorcycle) | Handlebar mount, screen facing rider | Strain warnings at stops, shaded routes, water stops on the way |
| Construction worker | Pocket, put down at breaks | Break-time check-ins, rest pacing, collapse alarm |
| Traffic police, street vendors, farm workers | Pocket or hand | Exposure dose, rest prompts, collapse alarm |
| Site supervisor / fleet lead | Laptop + phone | Crew-level risk view without raw health data |

## The 12 rules Thakaan never breaks

These invariants hold for every feature; a change that breaks one needs a team decision. They are checked against every code change.

1. **Phone only.** No wearable, no external sensor, no cloud dependency for the core loop.
2. **On-device, offline.** All sensing, models, risk scoring, routing and alerts run on the phone. No network call may exist on the demo path (fonts, tiles and analytics included). Network is used only to sync the shade map, weather and optional layers. **As built:** the app declares no `INTERNET` permission at all (checked with `dumpsys package` on the phone), and Chhaya re-routes with airplane mode on.
3. **Strain, not temperature.** The UI shows Cool, Warming, Strained and Danger, each with colour, shape and words. It never shows a body-temperature number.
4. **Sense when the body is still.** Heart checks fire at hand-overs, stops and breaks; motion sensors run continuously.
5. **Prevention over detection.** The best alert is a shaded route or a rest taken early, not an alarm at collapse.
6. **Act early.** Every core-temperature action fires at or below 38.5 °C (38.0 °C before acclimatisation). No action ever waits for an estimate above 39 °C, where the heart-rate method is weakest. An independent rule (sustained rest HR of 90 to 100 bpm or more) backs it up.
7. **The worker owns the data.** Raw physiology (HR, HRV, core trend, brain scores, symptoms) never leaves the phone. Camera frames and audio are RAM-only. The supervisor console sees levels, never raw HR or voice.
8. **Speak the worker's language.** Every instruction is spoken in Kannada, Hindi, Tamil, Telugu or English, short and actionable.
9. **Earnings-aware.** Every alert names one action and shows its time cost; detours are capped by current strain.
10. **Never block the job.** Navigation and the delivery flow always stay usable; Thakaan sits on top, not in the way.
11. **Fail safe.** Losing or degrading information may never lower the level. Missing or stale HR, lost GNSS (unknown shade counts as sun), a blocked light sensor, missing weather or an unavailable model all hold the level or raise it. If the safety property test fails, fix the engine, never the test.
12. **Provenance on every reading.** Each value carries REAL, REPLAY or SIMULATED, stamped at the sensor hub and inherited by anything computed from it. Replay never shows as REAL.

Two further rules sit beside these. The language model never changes a level: it only phrases what the risk engine decided, from a fixed action set, and template lines are the fallback. And a check that cannot fail proves nothing, so every safety check has a hidden guard switch that turns it red.

## System architecture

Five sensor groups feed three detection layers; one strain score drives every action, all on the phone.

&#91;embedded content: Thakaan architecture · sensors to actions\]

E1 light readings and E12 sky-view readings also flow into the shared shade map that Chhaya Route uses, so every rider's ride improves everyone's routes. **As built:** the rider's own readings correct the computed layer on the phone; sharing shade readings between phones is design only, and only crew flood reports travel between phones (over the Bluetooth LE mesh).

A plain way to hold the picture: a **nurse** watches the body (heart and the hidden core trend), a **teacher** watches the mind (hands, taps, speech, gait), and a **weather reporter** watches the street (sun, shade, heat). All three report to a **coach**, the strain score, which decides what to say and where to send the worker. The weather reporter is always available, so the score never goes blank.

## Body model (physiological detection)

Thakaan simulates the worker's body heat balance every minute and corrects the simulation with every clean heart-rate reading. Fingertip PPG is the primary heart channel; chest vibration (SCG) is a supporting channel; the front camera is optional and display-only.

### Core idea: a physics prior corrected by heart rate

Two guesses are blended every minute. Guess one is physics: given weather, sun, workload, clothing and time out, how much would the body warm this minute? Guess two is the heart: a hotter body usually means a faster heartbeat, so a clean reading checks guess one. A clean reading is trusted a lot; a noisy one barely counts. Between readings only the physics runs, and the uncertainty grows.

- **Process model (prediction between readings):** a Gagge two-node thermoregulation model steps core and skin temperature forward. It is added to the ECTemp prediction step and sits behind a switch, so the plain ECTemp path still runs. A simplified ISO 7933 Predicted Heat Strain (PHS) model gives sweat rate and water loss.
- **Model inputs:** metabolic rate from activity (E6; riding about 1.5 to 2.5 MET, walking and loads from Pandolf and MET tables), air temperature and humidity (E9, E16), radiant load from solar irradiance (E15), air speed as apparent wind (E17), and clothing and helmet insulation from onboarding.
- **Filter state:** core temperature (a trend, never shown), its variance, hydration deficit, acclimatisation level and a personal HR offset.
- **Cautious asymmetries (as built):** the heat-balance prior may cool the estimate by at most 0.002 °C per minute and warm it by at most 0.05 °C per minute, so a wrong prior can never talk the level down quickly. The adaptive filter feeds anchors only through the larger of the plain and adaptive values.

**Observation model (v3).** ECTemp maps core temperature to expected heart rate with a sigmoid. The floor A is learned from the worker's lowest sustained resting HR (a 5-minute rest minimum) and the ceiling K comes from age (HRmax = 208 − 0.7 × age, Tanaka).

```latex
HR_{exp} = A + \frac{K - A}{\left(1 + 0.06\,e^{-0.89(\hat{CT}_t - 37.84)}\right)^{1/0.07}}
```

```latex
k_t = \frac{\hat{v}_t\,m_t}{m_t^2\,\hat{v}_t + R_t}, \quad CT_t = \hat{CT}_t + k_t\,(HR_t - HR_{exp}), \quad v_t = (1 - k_t m_t)\,\hat{v}_t
```

Here m is the slope of expected HR against core temperature, Q = 0.000484 is the process noise and R = 356.4544 is the heart-rate noise (about 19 bpm SD). A reading is rejected when its normalised innovation squared exceeds 3.84.

**Cause terms.** Measured HR is also explained as workload HR plus a thermal term, a dehydration term, a posture term and a stop-transient term. Literature priors, refined per person: about 15 to 18 bpm per °C core rise (range 10 to 25) and about 4 to 5 bpm per 1% body-mass loss (range 3 to 8).

- **Hydration state:** PHS sweat loss minus logged drinks (E8) gives the running hydration deficit; each 1% deficit also adds about 0.15 to 0.20 °C to the core prior. A missed drink log inflates the deficit, so it is shown as a range and the app nudges "did you drink?" before escalating on hydration alone.

**Test discipline.** The Kotlin filter must reproduce all seven cases in `ectemp_vectors.json` with personalisation off before anything is personalised (it matches to 1e-9; the Diagnostics screen shows 7/7 on the phone). The reference cases assume a reading every minute, but Thakaan feeds readings mainly at stops, so gaps are predict-only and variance must grow, never shrink. That has its own tests, including a 12-minute gap and an injected 160 bpm spike at rest.

**Patent.** ECTemp is covered by US Patent 10,702,165 (US Army, USARIEM). A commercial product needs a licence through the Defense Health Agency.

### Heart-rate channels

| ID | Channel | Sensor | Method | When | Outputs |
| --- | --- | --- | --- | --- | --- |
| P2 | Fingertip PPG (primary) | Rear camera + torch | Camera2 locked at 640 × 480, 30 fps, AE/AWB/AF off, exposure aimed during the 10 s settle then frozen; cubic-spline resample, 0.5 to 4.0 Hz band-pass, Daubechies-4 wavelet denoise, derivative peak detection with 300 ms refractory, Viterbi tracking; quality gates | Delivery hand-over, breaks, "tap to check", prompted stops | HR, RMSSD (full check only), pulse amplitude, perfusion, respiration |
| P3 | Chest SCG (supporting) | Accelerometer + gyroscope, high rate | Stillness-gated pipeline, per-window principal axis, band-pass, gyro-referenced adaptive cancelling, beat-spacing plus autocorrelation agreement, learned denoiser as a second opinion | Phone in chest pocket, worker seated and still | HR, breathing by-product |
| P1 | Face rPPG (optional, display only) | Front camera | Per-region POS and CHROM pulse in 16 s windows, EfficientPhys as a second opinion, background and motion cancelling, 60 s analysis window | Red lights, face clear | HR, breathing, rough variability, experimental oxygen estimate |

**Quick vs full check.** A quick check is a 10 s settle (discarded) plus a measured window, about 35 s in the app, and returns HR only. A full check is a 10 s settle plus 60 s, and returns HR, RMSSD, perfusion index, pulse amplitude and respiration. About one check in four fails in the field, so the app says why ("wipe finger and lens", "shade the phone", "press lighter") and tries again. A bad capture never produces a number.

**Quality gates (design values):** reject a window if perfusion index is under 0.3%, beat-template correlation under 0.85, SNR under 5 dB, frames were dropped, or the torch dimmed mid-capture. No capture starts while the phone is thermally critical.

**As built, and the honest state.** These gates were set from the literature and have never been calibrated on real data, and they were not loosened to make a capture pass. On the phone, no red-channel capture has passed them yet (best perfusion 0.4 to 1.7%, correlation 0.63 to 0.79, SNR −4 to 0 dB). A real-recording analysis found the pulse present in the signal (strongest in green) but jumping between windows in the red-only view. The fix is `FingertipPos`: POS over red, green and blue, plus "must stay consistent across 8 s windows", plus agreement with one independent colour view. On four real captures it accepted only the table-resting capture (about 62 bpm) and rejected the other three. That 62 bpm is not yet confirmed against a counted pulse. Until a reference exists, treat the accepted rate as unconfirmed and do not claim a working pulse.

### Body vectors

| ID | Vector | Source | Method | Output |
| --- | --- | --- | --- | --- |
| P4 | Respiration rate | P2 pulse envelope, P3 low band, face | Respiratory-induced amplitude and frequency variation, 0.15 to 0.40 Hz | Breaths per minute (unvalidated) |
| P5 | Core-temperature trend | Gagge prior + ECTemp filter | Personalised sigmoid ECTemp Kalman filter, one step per minute on the median HR | Core trend + variance (internal, never shown) |
| P6 | Physiological Strain Index | P5 + HR | Moran PSI against personal resting baselines | PSI 0 to 10 |
| P7 | HR recovery (Site only) | P2 / P3 | 60 s and 120 s drop after vigorous work; not used after riding | Recovery score |
| P8 | Sweat and flush cues | Front camera | Skin-shine and redness vs baseline | Optional flag (seed only) |
| P9 | Excess HR | P2 / P3 + E6 | Measured HR minus HR expected for workload, posture and stop phase | Thermal + drift HR (bpm) |
| P10 | Idle-state HR drift | Repeated standard states | HR at the same state at the same time-since-stop vs the morning value | Drift (bpm) over the shift |
| P11 | HRV (PRV, RMSSD) | P2 full check, P3 | Time-domain RMSSD vs personal baseline; **never from under 60 s of clean pulse**; under heat, PRV reads high (vasodilation), so it is flagged heat-affected rather than counted as good recovery | Vagal-withdrawal score |
| P12 | PPG amplitude and perfusion | P2 | Pulse amplitude and perfusion index vs baseline | Peripheral-perfusion change |
| P13 | Sit-to-stand HR rise | P2 | Seated 30 s, stand, HR rise at 30 to 60 s vs baseline | Dehydration check |
| P14 | Forehead thermistor | Phone's own temperature sensors | Press phone to forehead; heat-transfer curve from the device thermistor; only when the phone is cool | Seed only, not on the demo path |
| P15 | Hydration deficit | PHS model + E8 + P13 | Model state, corrected by sit-to-stand and HR drift | % body mass, shown with a band |
| P16 | Acclimatisation state | Shift history | Builds over 10 to 14 days of heat work; decays about 2.3% per day off | Lowers expected HR and core prior |
| P17 | Perfusion collapse | P2 | Perfusion index high in heat, then a sudden fall across successive checks, counted only when contact quality is good (hard finger pressure also lowers perfusion) | Hypovolemic-strain cue (feeds a Danger anchor) |

### Measurement protocols that remove confounders

- **Stop transient:** when a rider stops, airflow stops, skin heats and HR jumps within seconds. Every reading carries time-since-stop, and HR is compared only at the same time-since-stop.
- **Posture:** dismounting adds a 10 to 20 bpm orthostatic rise for 30 to 60 s. The IMU keeps a time-since-posture-change clock, and readings in the first 30 s after a change are down-weighted.
- **Standard states:** P10 compares like with like: seated idle with seated idle, seated break with seated break.
- **HRR scope:** HR recovery is used only after vigorous site work, because riding is too low-intensity for a meaningful recovery curve.

### Personalisation

- **Onboarding (as built):** language, body size (age, sex, weight, height), heat experience (acclimatisation), an emergency number and what is shared, in about a minute. Clothing, helmet and HR-blunting medicines are in the profile model; a full cool-baseline capture is a separate step.
- **HRmax:** 208 − 0.7 × age (Tanaka).
- **Medicine flag:** HR-based channels are down-weighted; brain, exposure and symptom channels carry more weight.
- **Cool-morning baseline:** resting HR, RMSSD, PPG amplitude, sit-to-stand rise and idle-state HR, plus tremor and tapping baselines.
- **Daily 3-tap check-in:** slept well, ate, feel unwell. Each answer shifts the priors.
- **Start-of-shift:** CT₀ comes from a time-of-day curve (36.8 °C at 05:00 to 37.4 °C at 17:00). Starting variance is 0.02, or 0.2 if fingertip HR is above 100 or WBGT above 30 °C, and Q is inflated for the first 20 minutes.
- **Cautious defaults:** unknown acclimatisation counts as 0 days (earlier anchor), unknown resting HR is 70 bpm, location sharing is off.
- **Online learning:** k_T, k_D and the stop-transient template are designed to update from each worker's history; the live engine still uses literature priors.

### Quality gates

- **Innovation test:** each HR observation is checked against the filter's prediction (NIS above 3.84 is rejected).
- **Rate limit:** HR changes faster than physiologically possible between readings are rejected.
- **Cross-source agreement:** when two channels (P2 + P3, or P2 + P1) are available, they must agree before the reading counts at full weight.
- **Signal quality:** perfusion index for fingertip; SNR and kurtosis for camera; motion energy for SCG.
- **Stimulant flag:** recent caffeine or nicotine widens the observation noise.
- **Adaptive R:** a moving average of innovations; when it stays biased, R is inflated so humidity-driven circulatory drift is tracked as P9 / P10 instead of pushed into the core trend. Inflating R must never lower a shown level.
- **Fallback:** with no fresh HR, the physics prior keeps running and the uncertainty band widens. A stale reading holds its last anchor, it never clears one.

## Detection vectors: neurological and behavioural

Heat stroke is a brain emergency, so Thakaan watches directly for the brain slowing down. Cognition holds up until core temperature passes about 38.5 °C or body-mass loss passes 2%, and motor coordination degrades first, so the battery targets motor control, not simple reaction time. The 5-second tests of the first design could not catch lapses and are swamped by touchscreen latency (up to about 100 ms between phones); they were dropped.

| Order | Test | Time | Metric |
| --- | --- | --- | --- |
| 1 | Tremor hold (N9) | 10 to 15 s | Accelerometer 4 to 12 Hz band power; skipped or flagged within 60 s of lifting |
| 2 | Fitts tapping (N4) | 20 s | Throughput (bits/s) and spatial error rate between two shifting targets, from `MotionEvent` event time |
| 3 | Speech code (N3) | 5 s | Correct digits, articulation rate, pauses |
| 4 | Symptoms (N10) | 2 taps | Thirst, headache, dizziness, nausea, cramps, effort 1 to 10 |
| On flag | Brief PVT (N11) | 3 min | Lapses over 500 ms, false starts, mean 1/RT; only at a break, to confirm |

Passive and event vectors run alongside the battery:

| ID | Vector | Sensor | Method | Status |
| --- | --- | --- | --- | --- |
| N1 | Gait instability | Accelerometer + gyroscope | Stride-time variability, autocorrelation peak height, lateral sway vs a per-placement baseline | Built |
| N2 | Riding smoothness | Handlebar IMU | Approximate entropy of steering yaw, per-road-segment baselines | **Not built** (needs many rides on the same streets) |
| N3 | Speech check | Microphone | Fixed random 4-digit code; correctness, articulation rate, pauses. Jitter, shimmer and CPP only when quiet | Built (Vosk digit grammar) |
| N4 | Fitts tapping | Touchscreen | As above | Built, runs with real finger taps |
| N5 | Passive interaction drift | Touchscreen | Tap accuracy on app buttons, typing speed | **Not built** |
| N6 | Confusion markers | GNSS + app state | Wrong-turn rate, circling, long unexplained stops in sun | **Not built** |
| N7 | Collapse detection | Accelerometer + gyroscope | Fall, tip-over, or slow slump followed by stillness | Built |
| N8 | Eye and face fatigue | Front camera | Blink rate, eyelid closure, yawning | Built as display-only; off outdoors |
| N9 | Hand tremor | Accelerometer | 4 to 12 Hz band power, hold test and passive version | Built |
| N10 | Symptoms | Touch | As above | Built |
| N11 | Brief PVT-B | Touchscreen | As above | Built |

### Brain-detection logic

- **Own normal on own phone:** every N-score is a robust z-score (median and MAD) against the same worker, same phone, same placement (pocket vs hand gait) and same time-of-day bucket. With no baseline there is no judgement. Brain scores are never compared across phones.
- **The bucket filling:** change detection is a one-sided CUSUM on those z-scores, `C⁺ = max(0, C⁺ + x − μ₀ − k)`, with k = 0.5σ and an alarm at h = 4 to 5σ. One bad test does not alarm; five mildly slow ones do.
- **Independent families:** confirmation needs agreement across different kinds of evidence: motion (N1, N2, N7, N9 count as one), touch (N4, N5, N11), voice (N3), navigation (N6) and symptoms (N10). Three versions of the same signal do not count as three.
- **Context prior:** sleep loss, time of day (the post-lunch dip) and recent caffeine adjust the expected score, because they affect attention more than mild heat does. The prior can only down-weight a flag when exposure is low and a benign cause is known; an unknown answer is neutral.
- **Exposure-conditioned:** brain flags escalate only when the exposure prior is meaningfully high, so a tired cool morning does not trigger heat alerts.
- **Confirmation rule:** a past-threshold battery raises the level one step and schedules N11 at the next break; two families flagged with high exposure force at least Strained.
- **Speech check (as built):** a fixed digit code, so it works across Kannada, Tamil, Telugu, Hindi and English, but the offline recogniser is Vosk with a digit grammar (English-India and Hindi models), which has not yet been tried with a real voice. Whisper-tiny multilingual runs in a lab screen as an advisory second opinion; it is weak in Hindi and weaker in Kannada, and no transcript ever changes a level.
- **Squint correction (N8):** sun squint is separated from eyelid-closure fatigue using ambient light (E1) and eye-aperture shape. Because face checks do not work outdoors, N8 is off there.
- **Collapse (N7):** detects falls, bike tip-overs and slow slumps followed by stillness. Heat collapse is often a slump, not a fall. A spoken "are you OK, say haan" countdown always runs first; an on-screen "I'm OK" button ends the countdown or clears the alarm. A phone put down after being carried can still start a countdown, and the countdown plus the button are the safeguard.
- **Symptom vector (N10):** one-tap or voice symptoms, asked at every check and level change. Dizziness or confusion with high exposure forces at least Strained; a severe symptom forces Danger.

## Detection vectors: environmental and exposure

Exposure is the backbone of the score: it is always available, and it drives both the strain prior and the Chhaya shade map. Two riders on the same street can face very different heat: one under a tree, one on open tarmac.

| ID | Vector | Sensor | Method | Output |
| --- | --- | --- | --- | --- |
| E1 | Sun / shade / overcast state | Ambient-light sensor + colour-temperature sensor | Lux drop plus CCT jump (direct sun about 5,000 to 5,500 K; open shade about 7,500 to 10,000 K), tilt correction from gravity, self-shadow check (sun behind the rider, from heading vs solar azimuth) | State per second |
| E2 | Cumulative sun dose | E1 over time | Minutes and energy in direct sun, weighted by solar elevation | Sun dose for the shift |
| E3 | Device heat soak | Battery + thermal-zone temperatures, `getThermalHeadroom()` | Used **only** for device care and the E16 correction. The phone's body is never used as a globe thermometer (mass, internal heat, flat shape and a case make it unreliable) | Thermal state |
| E4 | Sky and surroundings | Rear camera (on demand) | SegFormer-B0 Cityscapes view of sky, tree and building share | Display-only cross-check |
| E5 | Surface and road type | Map | Road class from OSM | Heat-island penalty per segment |
| E6 | Activity and workload | IMU + GNSS speed | Riding, walking, stairs, lifting, idle; metabolic-equivalent estimate; rules first, a small CNN as a shadow model | Work intensity |
| E7 | Work / rest ledger | E6 + E1 | A real rest is shade plus still for over 5 minutes; a long red light in the sun is not a rest | Rest debt |
| E8 | Hydration log | One tap, voice ("pani piya" in hi, kn, ta, te, en phrases), water-stop arrival | Logs drinks; sweat loss from PHS | Hydration debt |
| E9 | Weather | Cached Open-Meteo hourly forecast | Interpolated offline for temperature, humidity and wind | Air temperature, humidity, wind |
| E10 | Acclimatisation | Shift history | Days worked in heat over the last 14 days | Acclimatisation factor |
| E11 | Time-of-day risk | Clock + solar position | Solar elevation, peak-heat window | Time risk multiplier |
| E12 | GNSS sky view | `GnssMeasurement`, 1 Hz, L1 + L5 | Per-satellite LOS probability (C/N0, ΔC/N0, elevation, Doppler residual, L1−L5 gap) from a small tree model; 10° sky bins; Bayesian update; cosine-weighted sky-view factor | Sky-view factor, sun direction blocked or clear |
| E13 | Shade type | E12 | Canopy (moderate drop, high scintillation) vs building or flyover (sharp drop) | Tree / structural / open |
| E14 | Body-shadow mask | E12 + heading | Learns the C/N0 loss behind the rider so the rider's body is not read as shade | Per-device mask |
| E15 | Solar irradiance | E1 | Lux ÷ 110 ≈ W/m²; above sensor saturation (about 65,000 lux) a clear-sky model by sun position | Shortwave load (W/m²) |
| E16 | Local air temperature | E9 + battery temperature | Forecast is the base; a battery-temperature transient model with self-heat subtraction nudges it, only when the phone is idle and uncased | Air temperature |
| E17 | Apparent wind | GNSS speed + forecast wind | Vehicle speed as airflow; near zero at stops, and that spike is real | Air speed |
| E18 | Heat stress for standards | E9 + E15 + E16 + E17 | **Liljegren WBGT** through the `lwbgt` C library (NDK, JNI); never the ACSM87 formula by day. UTCI is computed too, as a secondary display-only index | WBGT, UTCI |

- **Sun-direction check:** if satellites near the sun's azimuth and elevation are blocked, the rider is likely in the sun's shadow. This cross-checks the light sensor and the computed shadow layer.
- **Unknown is hot:** missing weather scores as the most extreme heat, so no real reading can be more pessimistic than missing data. A missing or invalid WBGT input returns null, which the risk engine treats as hot.
- **Weather coverage (as built):** the cache covers 8 to 13 October 2026 for the Bengaluru demo point.
- **Crowdsourcing:** E1 and E12 readings are map-matched to road segments and correct the computed shade layer on the rider's own phone (see Chhaya Route).

## Risk engine: Thakaan Strain Score

The Thakaan Strain Score (TSS, 0 to 100) fuses exposure, body, brain and symptoms into one number per minute, with a forecast of minutes until the next level. Think of a fuel gauge for the body: low is fine, high means act now.

**Four layers**

1. **Exposure prior.** WBGT, sun dose, rest and drink debt, time of day and acclimatisation give a baseline strain expectation for this worker right now. Always computed, so the score never goes blank.
2. **Body update.** Core trend, PSI, excess HR, idle drift, hydration and perfusion collapse shift the prior, weighted by how recent and clean the last HR reading was.
3. **Brain update.** CUSUM alarms by family. Two independent families force at least Strained.
4. **Symptoms.** Enter the fusion directly.

```latex
TSS = 100\cdot\sigma\big(w_E\,E + w_B\,B\,q + w_N\,N + w_S\,S + b\big)
```

E is the exposure prior, B body strain, q the freshness and quality weight of the last HR reading, N brain impairment, S symptoms, σ the logistic function. **As built:** all four weights are 3.0 and the bias is −3.0, fixed by hand. Weights are not fitted on the worker's history, and no isotonic or Platt calibration is applied yet, so the same number does not yet carry a proven equal risk across workers. Treat TSS as an ordered score, and the anchored level as the thing to trust.

**The shown level is the highest that the score or any anchor supports.**

| Level | TSS | Anchors that force it (examples) | Default action |
| --- | --- | --- | --- |
| Cool | 0 to 29 | None | Normal routing; passive logging |
| Warming | 30 to 54 | WBGT above the work-rest action limit; weather unknown; PSI above 3; resting HR above 90; several symptoms | Nudge to drink; Chhaya allows small detours |
| Strained | 55 to 79 | Core trend at the action point (38.5 °C, or 38.0 °C unacclimatised); PSI above 5; excess HR over the personal limit; hydration deficit above 2%; resting HR above 100; one brain family plus high exposure; dizzy or sick in heavy exposure | Water stop routed next; full check at next stop |
| Danger | 80 to 100 | Core trend very high (never waits for 39 °C); PSI above 7; two brain families flagged; perfusion collapse; severe symptom | Stop now: nearest shade, alert contact and supervisor, cooling coach |
| Collapse | N7 event | Fall or slump plus stillness after the countdown | Siren, bystander screen, SMS preview with location (no automatic SMS or 108 call is built) |

- **Stability (as built):** a level rises immediately but falls only after a dwell time (Strained 3 min, Warming 5 min) and only when the score is 3 points under the boundary. Anchors latch for 10 minutes. Danger never auto-clears: only a passed recovery check steps it down once, after which ordinary dwell rules apply.
- **Stale evidence holds:** body anchors come only from a fresh reading; a reading older than 15 minutes holds the last anchor level and widens the confidence band instead of dropping.
- **Chest pulse is raise-only:** the chest channel never counts as fresh heart evidence and cannot relax a held anchor; a resting chest pulse above 90 or 100 can only add a floor.
- **Forecast:** a linear trend projects TSS 15 and 30 minutes ahead and shows "Strained in about N min". The forecast never extrapolates a falling score downward. A small GRU runs as a shadow model and can only raise a warning; it is no better than the linear forecast and is not relied on.
- **Uncertainty:** each score carries a confidence band, shown as a soft halo around the gauge.
- **Explainability:** every level change records its top three drivers ("2 h without shade, resting pulse above 90, tapping slower") for the explainer to speak.
- **Feedback loop (design, not built):** an "alert was right / wrong" button as labels for personal tuning.

## Chhaya Route: strain-aware shade routing

Chhaya Route lets the rider's body set how much shade the route buys: the higher the strain, the more detour time the router will spend on shade and water. At Cool it gives the fastest route and no detour. Every route shows its price: "+2 min, 70% shade".

**Shade map (as built)**

1. **Computed shadows.** Sun position (date, time, lat/long) ray-cast against OSM building footprints and canopy polygons, precomputed on the laptop for each road segment and each 30 minutes of the day (`tools/chhaya/shade.py`). About 95% of OSM building heights are guessed (3 storeys × 3.2 m), so those segments are marked low-confidence and their sun fraction is pushed halfway to full sun. Trees and awnings are mostly not modelled.
2. **Satellite-height layer (opt-in).** A second table built from satellite building and tree heights (Open Buildings 2.5D Temporal 2023, Meta/WRI canopy height) on an H3 resolution-11 grid, 4 seasons × 30-minute slots, 4,159 cells. It is modelled, validated against no real light yet, and height error was measured only in the Global North, so it stays opt-in on the map ("Shade from: Satellite heights").
3. **Measured corrections.** The rider's own E1 and E12 readings, map-matched to segments, move a Bayesian shade estimate over the computed layer slowly. Single GPS points are never trusted; narrow streets make GPS jump sides.
4. **Heat-island and surface penalty.** A penalty on sunny arterials.

**Water and cooling stops**

- OSM drinking-water points, shops, tea stalls, fuel stations and rest points, shown as **"listed, quality unverified"** until riders confirm them. OSM cannot vouch for water quality.
- Rider tags by voice or tap ("pani yahan milta hai", "chhaya, baith sakte hain") pin a stop with type and hours (design; the flood report is the built tag).
- **Thakaan Pyaau network** (shops opt in with a QR sticker; a scan confirms the stop is live) is design only, not built.

**Routing engine (as built).** Our own Dijkstra over an OSM-derived graph, not GraphHopper or Valhalla: no JVM-heavy dependency, runs fully offline and returns in milliseconds.

```latex
C_e = t_e\,\big(1 + \lambda(TSS)\cdot S_e(h)\big) + \mu\,H_e + F_e
```

t is travel time, S the sun-exposure fraction at hour h, H the heat-island penalty, F the flood term (a very large cost, effectively closing a road). λ is exactly 0 below TSS 30 and rises to 6 by TSS 75, so at Cool the route **is** the fastest route; Warming and Strained are floored at 1.0 and 2.5 so shade preference never vanishes inside the budget. The router finds the largest share of λ whose route still fits the detour budget.

- **Detour budget (as built):** scales with the trip. Warming allows 10% of the fastest time, between 1 and 5 minutes; Strained allows 25%, between 3 and 10 minutes. Danger drops the destination and goes to the nearest cool seated stop (an exposed-seat penalty applies), or to the nearest shade if none is reachable.
- **Forced water stop:** at Strained the router inserts the best listed water stop whose total detour stays inside the budget, and shows the extra seconds.
- **Shade ETA:** every route shows extra time (in seconds under 90 s), shade share, the fastest route dashed for comparison, and how much of the route rests on guessed heights.
- **Navigation hand-off:** the map is its own screen. Turn-by-turn overlay on top of a delivery app is not built.
- **Not built:** shift planner, return-trip choice, walking-leg shade preference.

**Map layers (as built, one at a time, each with a legend in plain words).** Shade; Water; Floods; Phone signal; Traffic; Air. Credit: © OpenStreetMap contributors (ODbL).

- **Phone signal:** this phone's own radio level on streets it has passed (a street needs 3 samples). It differs by network and phone and is not a coverage map.
- **Traffic:** an assumed typical pattern by road type and hour, plus the rider's measured speed on streets they rode. It is not live traffic.
- **Air:** one modelled area value (CAMS through Open-Meteo, about an 11 km grid, India's CPCB scale) laid over the whole map. It cannot tell one street from another.
- **None of Signal, Traffic or Air changes a route or a level.** Flood reports and the strain level do.

## Interventions and alert ladder

Every alert names one action, says it in the worker's language, and shows what it costs in time.

| Level | Haptic | Voice (example, Hindi) | Screen | Other |
| --- | --- | --- | --- | --- |
| Warming | 1 soft pulse | "Paani pee lo, agle signal pe." (Drink water at the next signal.) | Amber ring on gauge | Water stop suggested |
| Strained | 2 strong pulses | "Shareer garam ho raha hai. 3 minute aage chhaya aur paani hai." | Orange banner with route | Chhaya Route switches on; full check at next stop |
| Danger | 3 long pulses, continuous until acknowledged | "Abhi ruk jao. Chhaya mein baitho, paani piyo." | Red takeover with nearest shade | Contact + supervisor alert; cooling coach starts |
| Collapse | Siren + flash | Design: loud bystander message, "Is vyakti ko garmi lagi hai. 108 ko call karein." (not in the code yet) | First-aid steps for bystanders | SMS **preview** with location; nothing is sent without a tap, and no automatic 108 call exists |

At Danger, the contact and supervisor alert is the level summary the console reads; automatic SMS to contacts is not built. Spoken lines exist for en-IN, hi-IN, kn-IN, ta-IN and te-IN, and all five offline voices are installed on the iQOO 15. How they sound has not been checked by a human ear, and **the language strings need native-speaker review** before any real deployment.

**Intervention features**

- **Cooling coach (built):** a guided 10-minute recovery (shade, drink, wet cloth on neck, fan), then a full check plus tremor and tapping. Danger clears only when recovery passes.
- **Smart snooze:** "I'm at a drop-off" snoozes non-danger alerts until the next stop, never at Danger.
- **Shift report (built):** time at each level, peaks, changes and provenance mix. Time with no readings is shown as its own line and never as Cool time. The copy button hands out levels only.
- **Proof of exposure (built):** a signed, hash-chained on-device log the worker can export (see Additional features, item 5).
- **Bystander mode:** on collapse the screen offers first-aid steps and the SMS preview. A lock-screen emergency card is design only.
- **Earnings view** (an early 10-minute rest versus an unplanned stop later): design only.

## Worker modes: Rider and Site

Thakaan detects where the phone is and switches modes, so the same app serves a rider on a handlebar and a labourer with a phone in his pocket. Mode detection uses the IMU vibration spectrum, mount orientation and GNSS speed to pick Rider (mounted), Pocket, In-hand or Desk; the worker can also pick a mode at sign-up.

| Feature | Rider Mode (handlebar) | Site Mode (pocket) |
| --- | --- | --- |
| Heart rate | P2 fingertip PPG at every hand-over and prompted stop; P10 idle-state drift | P3 chest SCG when seated and still; P2 + P13 sit-to-stand at every break; P7 recovery after heavy work |
| Brain vectors | N1 gait at drop-offs, N3 / N4 / N9 at stops | N1 gait (continuous), N3 / N4 / N9 at breaks |
| Exposure | E1 continuous (sensor faces the sky), E6 riding load, E17 apparent wind | E1 at breaks and when the phone is out, E6 lifting and climbing load |
| Interventions | Chhaya Route, water-stop insertion, voice prompts | Break scheduler (work/rest ratio by TSS), shade-spot finder |
| Stop detector | Speed ≈ 0 + idle vibration triggers a check prompt | Still + upright + break time |
| Thermal care | Capture blocked while thermal status is severe; torch dimming voids a window | Not needed |

**Honest state.** Activity, mode and stop detection are built with the five states Moving, Stopping, Stopped, Walking, Resting and two clocks (time since stop, time since posture change). A full device thermal governor that dims the screen and duty-cycles the NPU and GNSS is not built; only the capture guard is. **Site extras not built:** crew roll-call and the rotation advisor. **Built, not run on two phones:** buddy pairing over the crew mesh (a shared code; if one phone reaches Danger, the buddy's phone is alerted).

## Supervisor console and Office Kit

The supervisor sees crew risk levels and alerts, never raw heart rate or voice.

**Thakaan Console (as built):** a local web page (`console/index.html`) that anyone can open from a QR code. The phone's "Copy status" button puts a signed level summary on the clipboard; the supervisor pastes it into the console.

- Crew board: level, trend, minutes since last rest, coarse reasons, provenance chip. Crew members beyond the real phone are shown as SIMULATED.
- Map coloured by level. Position is opt-in and coarse.
- Hash-chain verifier in the browser (SHA-256 and ECDSA P-256 through WebCrypto), cross-checked against fixtures generated by the Kotlin code. It shows "Chain OK" or "Chain BROKEN".
- **Privacy by construction:** `ConsoleSafe` maps driver codes to category labels on the phone, so a driver text that quoted a number or symptom cannot leak. The console receives level, trend and coarse reasons only.

**Office Kit integration**

| Office Kit surface | Thakaan use | State |
| --- | --- | --- |
| Screen mirror + remote control | Demo and supervisor view of the phone on the laptop; drive it from the keyboard | Used during build; the loop (#3, #52) is not yet rehearsed by all three |
| Shared clipboard | The status paste flow above | Built; the console clipboard flow is not yet tested on the phone |
| File transfer | Heat reports, exposure logs and model files move phone to laptop and back | Used for model pushes |
| Notification sync | Danger and Collapse alerts appear on the laptop | Not built |
| Camera share | Phone camera as the console webcam | Not built |

**Crew mesh (no internet).** Built as Bluetooth LE extended advertising only; Wi-Fi Aware is not built. Flood reports hop phone to phone (limited to 3 hops, 20 an hour and 3 km, and they can only add closures). Buddy alerts use a shared pairing code. The relay rules are laptop-tested, but nothing has yet run between two phones, and a report heard from the mesh is anonymous and unverified.

## On-device AI and runtime stack

Small models run on the phone through LiteRT; the language model runs on the Adreno GPU through LiteRT-LM. A session that loads without throwing may still be on the CPU, so Diagnostics reads the delegated-ops count ("N/N ops on NPU") instead of trusting a successful load.

| Component | Model / method | Runtime | Compute unit (as built) |
| --- | --- | --- | --- |
| Fingertip PPG, SCG, activity, gait, tremor, CUSUM | Kotlin DSP | JVM / NDK | CPU |
| Core-temp filter | ECTemp EKF (sigmoid) + Gagge prior | Kotlin | CPU |
| WBGT | `lwbgt` (Liljegren) | NDK / JNI | CPU |
| Routing | Own Dijkstra with custom weights | Kotlin | CPU |
| Face detection + mesh | MediaPipe Face Landmarker | MediaPipe | Loads on the phone |
| Face pulse | POS + CHROM, EfficientPhys as second opinion | LiteRT | Loads; never seen a real face against a reference |
| Sky / shade view | SegFormer-B0 Cityscapes | LiteRT | Loads; never seen a real street |
| SCG denoiser, activity CNN, strain GRU | 1D-CNN / GRU trained on **simulated** data | LiteRT CPU interpreter | CPU; shadow models only |
| Line-of-sight classifier | XGBoost-style trees on simulated data | Kotlin | CPU; shadow model only |
| Digit code | Vosk offline grammar (en-IN, hi) | Vosk | CPU |
| Speech lab | Whisper-tiny multilingual | LiteRT | Loads and transcribes a self-test clip; advisory |
| Explainer + coach phrasing | Gemma 4 E2B | LiteRT-LM | GPU; loads on the phone in well under 45 s |
| Voice out | Android offline TextToSpeech (hi, kn, ta, te, en) | Android | CPU |

**Runtime architecture**

- **Sensor hub:** a foreground service owns all sensors and publishes timestamped streams, each stamped REAL, REPLAY or SIMULATED. OriginOS kills background work, so the app must be locked in Recents with battery set to unrestricted. The level engine runs in a process-wide scope so it survives the screen closing.
- **Inference seam:** every model sits behind one `InferenceEngine` interface with QNN, GPU and CPU back ends, and Diagnostics shows each model's back end.
- **LLM guardrails (as built):** the template line is always built first. A model line is accepted only if it states the engine's own minute figure, adds no other numbers, quotes no temperature or pulse, and contains no reassurance. Otherwise the template is shown. The level and action never come from model text. This guard was seen working on the phone: the first Gemma line was rejected for writing the minutes as a word, and the template was shown.
- **Thermal guard:** blocks fingertip capture when thermal status is severe or headroom is near 1.0.
- **Storage:** app-private storage on the phone (the OS encrypts it at rest). An application-level encrypted database (Room + SQLCipher) is not built.

## Data, privacy, consent

Health data stays on the phone; the only things that ever leave are anonymised shade readings (design), crew flood reports, and alerts the worker chooses to share.

| Data | Stored where | Leaves the phone? |
| --- | --- | --- |
| Camera frames, audio clips | RAM only, discarded after inference | Never |
| HR, HRV, core-temp trend, brain scores, symptoms | App-private storage on the phone | Never |
| TSS level + alerts | On device | Only to contacts / supervisor the worker approved |
| Light readings on road segments | On device | Design: anonymised, time-bucketed, snapped to segments. Not built as an upload |
| Flood reports | On device | Over the crew mesh, anonymous |
| Proof-of-exposure log | On device, hash-chained, signed with a hardware-backed Keystore key (StrongBox present) | Only when the worker exports it |

- **Consent:** onboarding asks what is shared. Separate per-feature consent screens (camera, voice, shade sharing, supervisor alerts), a consent log and a one-tap "erase my data" are designed for DPDP Act 2023 alignment but are **not built yet**.
- **No penalty use:** the console shows levels, never scores per minute or raw signals, so data cannot be used to rate or deactivate workers.
- **Permissions (as built):** no `INTERNET`; location, camera, microphone, vibrate, notifications and Bluetooth scan/advertise only. Dependencies that added phone-state and storage permissions had them removed in the manifest.
- **Framing:** a heat-strain warning and wellbeing tool, not a medical diagnosis. A person who holds the unlocked phone can see everything on it.

## UX flows and screens

The home screen is one big strain gauge a rider can read in half a second at a red light.

**Onboarding (about a minute, once; the cool baseline is a separate step)**

1. Language pick.
2. Body size (age, sex, weight, height), heat experience.
3. Emergency number and what is shared.
4. Daily 3-tap check-in (slept, ate, unwell), shown once per day.
5. Cool baseline: fingertip check, sit-to-stand, tremor, tapping, speech code, 30 s walk (partly built).

**Screens**

| Screen | Contents |
| --- | --- |
| Home / gauge | Level with colour, shape and words, confidence halo, forecast "Strained in about N min", sun dose, rest and water debt, air dose today, provenance chip, one big action, Replay 20x |
| Chhaya map | Route with shaded segments, water and shade pins, "+2 min, 70% shade" chip, layers (Shade, Water, Floods, Phone signal, Traffic, Air), flood report, OSM credit |
| Quick check | Fingertip with live waveform and perfusion ring, tremor hold, Fitts tapping, speech code, symptoms; about 95 s with speech |
| Alert takeover | Level colour, one instruction, route to shade, "I'm OK" / "I'm resting" |
| Cooling coach | 10-minute timer, steps, re-check at the end |
| Shift report | Time at each level, peaks, changes, gaps, provenance mix, levels-only copy |
| Diagnostics | Sensor health, thermal headroom, model back ends, ECTemp 7/7, Safety checks, Keystore self-test, voices, Models on this phone |
| Safety checks | Six live scenarios with PASS / FAIL and hidden guard switches |
| Labs | PPG lab, Chest lab, Speech lab, Face check and live face view (development and display-only) |

**Design rules**

- Colour plus shape plus words for every level (works in glare and for colour-blind users).
- Text at least 20 sp on the gauge; everything readable at arm's length in sunlight; high-contrast light theme for direct sun.
- Voice-first: every prompt can be answered by a spoken "haan" / "nahi".

## Demo script

The demo runs about 3 to 4 minutes. Everything on the path works with the phone in airplane mode, and "replay" is said aloud whenever the chip says REPLAY. This is the current script (`docs/DEMO_SCRIPT.md`); the first design's live-body beats (jumping jacks, a live pulse waveform on stage) depend on a fingertip pulse that has not yet passed its quality gates, so they are not on the path.

| Time | On stage | On the phone / projector |
| --- | --- | --- |
| 0:00 to 0:20 | Hook: a delivery rider, noon, 41 °C. Heat illness arrives without warning, and the phone is already in their hand. | Home, REAL chip |
| 0:20 to 1:20 | Tap Replay 20x. "Replay." | Gauge goes Cool, Warming, Strained; forecast line; REPLAY chip |
| 1:20 to 1:50 | The alert: one action, in the worker's language, with its time cost. Switch Hindi to Kannada. | Spoken line + haptic |
| 1:50 to 2:50 | Chhaya Route with airplane mode on: fastest at Cool; detour to shade plus a water stop at Strained; nearest cool seat at Danger | Route, "+N min, N% shade", shade is estimated |
| 2:50 to 3:50 | A teammate who has never seen it does the 90-second check: finger, hold still, tap, how do you feel | Quick check |
| 3:50 to 4:20 | Supervisor console: Copy status, paste, levels and coarse reasons only; change one byte in the log, "Chain BROKEN" | Console |
| 4:20 to 4:40 | Safety checks 6/6 PASS; flip one guard, its row turns red; ECTemp 7/7 | Diagnostics |
| Close | Limits: shade is computed, the level is an estimate, not a medical device, ECTemp needs a licence | Limitations slide |

Backups: if the camera misbehaves, skip the finger step and show the Safety tab. If the phone is unavailable, run the Office Kit mirror. A recorded backup video is still to do (#63).

**Demo prep checklist**

- [ ] Real 30 to 60 min ride recorded for the replay (#51; the demo currently replays a labelled recording and does not claim it is a real ride)
- [x] Shade tables and offline graph for the venue area bundled in the APK
- [ ] Warm up all models 2 minutes before the pitch
- [ ] Phone cool, case off, not charging
- [ ] Office Kit loop rehearsed by all three (#3)
- [ ] Backup video (#63)

## Pitch narrative and judge Q&A

The pitch argues three things: the problem is personal and invisible, the phone can sense it, and Thakaan acts before collapse rather than after. **Rule: every number spoken must be in the claim ledger in `docs/PITCH.md` with its source.** Pulse and strain accuracy, early-warning lead time and a rider's quote are not yet measured; say so instead of guessing.

**Story arc**

1. **Problem:** outdoor workers lose judgement to heat before they feel ill; city alerts and wearables don't reach them.
2. **Insight:** heart-rate trends reveal heat strain (the US Army's ECTemp approach), and the brain's slowdown shows in tremor and tapping. A phone can sense all of it.
3. **Product:** body + brain + sun fused into one strain score, on-device, in the worker's language.
4. **Differentiator:** Chhaya Route, a router whose detour budget is set by the rider's own body.
5. **Depth:** ECTemp Kalman filter with a Gagge prior, Liljegren WBGT, CUSUM brain battery, GNSS sky view, offline routing, guarded LLM phrasing.
6. **Honesty:** truth labels on every number, a safety invariant with guard switches, 7/7 reference vectors, a tamper-evident log.

**Likely judge questions**

| Question | Answer |
| --- | --- |
| Can you read heart rate on a moving bike? | No, and we don't try. We check at hand-overs and breaks; the heat-balance model carries the estimate between readings. |
| How do you know core temperature without a thermometer? | We estimate a trend (personalised ECTemp on a Gagge prior) and never show a number. Every action fires at or below 38.5 °C because the method loses sensitivity above about 39 °C, and an independent resting-HR rule backs it up. |
| Is the pulse working today? | Be exact: the camera holds a steady 30 fps and a new POS method accepts a stable capture, but no accuracy number against a reference exists yet. |
| What about darker skin tones for camera heart rate? | Face-based reading is optional and display-only. Fingertip PPG with the torch is the primary channel. |
| Won't riders ignore longer routes? | The detour is capped by strain and shown in time. At Cool there is no detour at all. |
| Isn't this just a weather alert? | Weather is one input. Two riders on the same street get different levels. |
| How do we know your numbers are real? | Every number has a REAL, REPLAY or SIMULATED chip; the filter passes 7/7 reference vectors; six safety checks run live and turn red when a guard is switched off. |
| What does the hash chain prove? | That a log entry was not edited after it was recorded. Not that the observation was true. |
| Is the shade real? | It is computed from map and satellite data, not measured, and the screen says so. |
| What about privacy and employers? | Raw health data never leaves the phone. Supervisors see levels and coarse reasons only. |
| Does it drain the battery or overheat the phone? | Sensing is event-driven, and a capture guard blocks checks on a hot phone. Battery cost has not been measured. |
| Who pays? | Not decided. Candidates: platforms (fewer heat incidents), insurers (cover backed by exposure logs), state labour boards. |
| Why on-device? | Construction sites and crowded roads lose signal, health data is sensitive, and alerts can't wait for a server. |

## Limitations (what Thakaan does not do)

This is the content of the limitations slide (`docs/LIMITATIONS_SLIDE.md`) and the longer `docs/LIMITATIONS.md`. Naming the limits ourselves scores better than a judge catching them.

- **Core-temperature estimates fail near the danger zone.** Field validations report high sensitivity below about 38.75 °C and almost every hot reading missed above 39 °C, which is exactly where heat stroke begins. So every action fires at or below 38.5 °C, and a heart-rate rule does not depend on the temperature estimate.
- **Heart checks are spot readings,** not continuous, and fingertip pulse needs a still finger and a cool phone. No fingertip capture has yet matched a reference.
- **Validation populations are narrow.** The underlying models were validated mostly on young, Western, often military or athletic men. No study covers South Asian workers, women, older workers or budget phones in motion.
- **Brain signals are personal drift signals,** not validated heat-illness detectors. They are supporting evidence, not a diagnosis.
- **Shade is computed, not measured.** About 95% of OSM building heights are guessed, trees and awnings are mostly unmodelled, and the satellite layer is modelled and unchecked against real light.
- **Learned models were trained on simulated data** (activity CNN, chest denoiser, strain GRU, line-of-sight trees). They run as shadow models and never decide a level.
- **Chest-pocket pulse** works only seated and still, and was tuned on synthetic data; real recordings so far come from one person.
- **Face pulse, breathing, variability and oxygen** come from skin colour and are unconfirmed against a reference; the oxygen figure is uncalibrated.
- **Air** is one modelled area value and ventilation from heart rate can be off by about 45% per estimate (middle half), averaging out over a shift.
- **Floods** show "reported flooded", never a depth; a photo freshness check cannot prove a road is flooded.
- **Not a medical device.** ECTemp is patent-covered (US 10,702,165), so commercial use needs a licence.
- **Language strings need native-speaker review** before real deployment.

## Build plan

Build in phases, each ending in something demoable, so the pitch is safe whenever time runs out. The ticket-level plan is `docs/Thakaan — Build Map v1.md` (65 tickets in strict dependency order) and the live status is `docs/TICKETS.md`. Priority: CORE first, SUPPORTING after the spine works end to end, SEED narrate-only; Phase 9 is cut first when time runs out.

| Phase | What gets built | Status |
| --- | --- | --- |
| 0 Skeleton | Kotlin + Compose app, sensor hub with provenance, inference seam, Diagnostics, recorder and replay | Built; seam and Diagnostics seen on the phone |
| 1 Fingertip PPG | Camera2 lock, pipeline, quality gates, quick and full checks, thermal guard | Built; steady 30 fps seen, no gate-passing capture yet |
| 2 Motion and context | Activity and mode, stop detector, collapse with countdown | Built; collapse countdown seen on the phone |
| 3 Environment and exposure | E1, irradiance and dose, weather cache, WBGT, rest and drink, sky view | Built; WBGT library seen on the phone; sun and shade outdoors not yet |
| 4 Body model | ECTemp 7/7, personalisation, Gagge prior, hydration, excess HR, sit-to-stand, onboarding, chest SCG, face | ECTemp 7/7 seen on the phone; rest built, not verified |
| 5 Brain battery | Tremor, Fitts, speech code, symptoms, CUSUM, gait, brief PVT | Built; Fitts seen with real taps; speech not tried with a real voice |
| 6 Risk engine and safety | TSS, alert ladder, property test, Safety checks tab, cooling coach, forecast, quick-check flow | Safety checks seen on the phone; property test green |
| 7 Chhaya Route | OSM graph, shade layers, strain-aware routing, map, light-map logging, recorded ride | Offline routing seen on the phone; real recorded ride not done |
| 8 Console and proof | Supervisor console, hash-chained log, provenance chips, airplane-mode proof | Keystore signing and tamper detection seen on the phone |
| 9 Optional | Gemma coach, air dose, flood reports and crew mesh | Gemma and air built; mesh not run between two phones |
| 10 Proof and demo | Metrics table, rehearsal, limitations slide, backup recording, pitch, rider feedback | Limitations drafted; metrics, rehearsal, backup, rider quote still to do |

## Build status as of 10 Oct 2026

Legend: **Phone** = seen working on the iQOO 15; **Built** = code and laptop tests only; **No** = not built.

| Area | State | Notes |
| --- | --- | --- |
| Offline operation | Phone | No `INTERNET` permission; routing works in airplane mode |
| Provenance REAL / REPLAY / SIMULATED | Phone | Replay 20x shows REPLAY on Home and Chhaya; not yet checked on every screen |
| ECTemp 7/7 vectors | Phone | Quadratic reference model matches at 1e-9; sigmoid is the personalised path |
| Personalised filter live in the engine | Built | Vectors, 12-minute gap and 160 bpm spike tests pass on the laptop; live switch-on not confirmed |
| Safety checks (6) with guard switches | Phone | 5/5 seen, guards 1 to 5 seen red; check 6 added after one guard turned nothing red, unit-tested, not re-seen |
| Safety property test | Built | 452 test cases pass on the laptop; each guard off makes the property fail |
| Keystore signing, tamper detection | Phone | Hardware-backed key, StrongBox present; changed byte and cut-off end both caught |
| Fingertip pulse | Built | Gates uncalibrated; no capture accepted by the red-only gates; POS method accepts a table capture, unconfirmed |
| Tremor, Fitts, symptoms, CUSUM, gait, PVT-B | Built | Fitts accepted with real taps; five baseline runs still to do |
| Speech code | Built | Vosk grammar loads; real voices untried |
| Collapse detection | Phone | Upright-to-flat still phone starts the countdown; "I'm OK" effect and cushion drop not reported |
| Sun / shade, GNSS sky view | Built | Indoors only so far; GNSS and colour-temperature sensor gave no data indoors |
| WBGT (Liljegren) | Phone | Library loads, plausible value |
| Risk engine, alerts, voices | Phone | Five voices installed; how they sound is unchecked |
| Chhaya routing, OSM layers | Phone | 38% and 49% shade on two test routes; shade is computed |
| Phone signal, Traffic, Air layers | Built | Laptop-tested, not yet seen on the phone |
| Flood reports, crew mesh | Built | Photo check and relay rules laptop-tested; mesh not run between two phones |
| Gemma explainer | Phone | Loads on GPU; wording guard seen rejecting a line |
| Whisper-tiny, Vosk, EfficientPhys, SegFormer, four shadow models | Phone | Load and produce output; accuracy unproven |
| Chest pulse | Built | Real chest recordings agree with a counted pulse of 70 in two runs (70.6, 73.2) at the lab tolerance of 6 beats; the continuous stream stays at 4 |
| Console and chain verifier | Built | Verifier cross-checked against Kotlin fixtures; console clipboard flow not tested on the phone |
| Shift report | Phone | Levels logged; survives closing the screen |
| Riding smoothness N2, passive touch drift N5, confusion N6 | No | |
| Probability calibration of TSS, fitted weights | No | |
| Encrypted database, per-feature consent, erase-my-data | No | |
| Thermal governor beyond capture guard | No | |
| Notification sync, camera share, Wi-Fi Aware mesh | No | |
| Pyaau QR network, rotation advisor, crew roll-call, shift planner | No | |
| Metrics table (#60), rehearsed demo (#61), backup video (#63), rider quote (#65) | No | Pulse and strain accuracy are unmeasured: say nothing |

## Glossary

| Term | Meaning |
| --- | --- |
| ApEn | Approximate entropy: how irregular a signal is; planned for steering yaw (N2, not built) |
| CCT | Correlated colour temperature, from the colour-temperature sensor |
| Chhaya Route | Thakaan's strain-aware shade and water routing |
| CUSUM | Cumulative-sum change detection: small repeated slowdowns add up like a filling bucket until it overflows |
| ECTemp | US Army Kalman-filter model that estimates core-temperature trend from heart rate (patent US 10,702,165) |
| EKF | Extended Kalman filter |
| Fitts tapping | Tapping between two targets to test motor coordination; scored as throughput in bits/s |
| HRV / PRV / RMSSD | Heart-rate variability / its pulse-derived version / its beat-to-beat measure; never from under 60 s of clean pulse |
| HTP / QNN | Qualcomm Hexagon tensor processor / Qualcomm neural network runtime |
| Independent families | Different kinds of brain evidence (motion, touch, voice, navigation, symptoms) that must agree before a flag counts |
| Perfusion index | How strong the pulse signal is in the fingertip; too hard a press kills it |
| POS | Plane-orthogonal-to-skin, a classic PPG / rPPG colour projection |
| PSI | Physiological Strain Index, 0 to 10 |
| Pyaau | A public water point; Thakaan's opt-in water-stop network (design only) |
| PVT-B | Brief psychomotor vigilance test: lapses over 500 ms, false starts, mean 1/RT |
| rPPG | Remote photoplethysmography: pulse from skin colour on camera |
| SCG | Seismocardiography: heartbeat from chest vibration |
| TSS | Thakaan Strain Score, 0 to 100 |
| C/N0 | GNSS carrier-to-noise density: satellite signal strength |
| Gagge two-node model | Core + skin heat-balance model of the body |
| H3 | Uber's hexagonal grid; used here for the baked satellite-height shade table (res 11) |
| LOS / NLOS | Satellite seen directly / only by reflection |
| NIS | Normalised innovation squared: outlier test for filter observations (gate 3.84) |
| PHS (ISO 7933) | Predicted Heat Strain model: sweat rate and core rise |
| UTCI / WBGT | Heat-stress indices; WBGT (Liljegren) drives anchors, UTCI is display-only |
| REAL / REPLAY / SIMULATED | Provenance labels: measured live, recorded then played back, or generated |
| Safety invariant | The rule that losing or degrading information may never lower the strain level |
| Guard switch | A hidden switch that disables one safety guard so its check must turn red |
| Hash chain | Each log entry stores a fingerprint of the previous one, so editing history is detectable |
| Test vector | A fixed input with a known correct output, used to check an implementation |
| Minute ventilation (V_E) | Litres of air a person breathes per minute |
| PM2.5 | Fine particles (2.5 micrometres or smaller) that reach deep into the lungs |
| ODbL | The open licence for OpenStreetMap data; requires a credit line |

## Additional features

These seven features were added after the first text of this bible. Each now has a defined shape and a build state.

| # | Addition | One-line purpose | State |
| --- | --- | --- | --- |
| 1 | Truth labels: REAL / REPLAY / SIMULATED | Every number on screen says where it came from | Built; seen on Home and Chhaya |
| 2 | Limitations slide | Say plainly what the models cannot do | Drafted, not yet answered cold by each teammate |
| 3 | Safety-invariant test and a Safety checks screen | Prove that lost or bad data never lowers the level | Built; seen on the phone |
| 4 | ECTemp test vectors | Prove the core-temperature filter is implemented correctly | Built; 7/7 seen on the phone |
| 5 | Hash-chained proof-of-exposure log | Make the exported heat log tamper-evident | Built; verified on the phone and in the browser |
| 6 | Offline map bundle from OpenStreetMap | Roads, water points and rest stops for the demo city | Built; bundled in the APK |
| 7 | Air and rain layers | Breathing dose, rain and waterlogging, in the same app | Built, laptop-tested; flood layer from rider reports only |

### 1. Truth labels: REAL, REPLAY, SIMULATED

**What it is.** Every number the app or the Console shows carries a small chip saying where it came from.

| Label | Meaning | Example in Thakaan |
| --- | --- | --- |
| REAL | Measured live, now, on this phone, or a real public data source | Live fingertip heart rate; a cached weather reading |
| REPLAY | A real recording played back later | The 20x replay of the recorded ride |
| SIMULATED | Generated for the demo, not measured | Any made-up colleagues on the crew board; any invented hotspot; demo level overrides on the map |

**How it works.** A `provenance` field is stamped on every reading at the sensor-hub layer. Anything computed from a REPLAY or SIMULATED input inherits that label, so a strain score driven by replayed heart rate is shown as REPLAY, never REAL. One chip appears on the gauge, the forecast, the sun dose, the Chhaya chips and each Console row. On the phone, a replay run reported REPLAY on every stream.

**Why.** No judge can claim something shown as live was staged, and the boundary between measured and replayed data is visible instead of hidden.

### 2. Limitations slide

See the "Limitations" section above. One slide shown before the close, and a ready answer for judge Q&A.

### 3. Safety-invariant test and a "Safety checks" screen

**The rule.** *Losing or degrading information may never lower the strain level.* This is rule 11, turned into an automated test and a screen.

**Part A: a property test.** Run the level calculation on randomly generated shifts, then randomly remove or corrupt inputs (HR missing, stale or noisy; GNSS lost; light sensor blocked; model unavailable). A degraded run is compared with a reference run whose body and brain evidence freezes at the moment of degradation, and the degraded run may never show a lower level. The test fails if any dropout produces a lower level. Each guard switched off makes the property fail, and it fails when a deliberate bug lowers a level.

**Part B: a "Safety checks" tab** on the Diagnostics screen that runs these scenarios on the phone and shows PASS or FAIL for each:

1. No fresh heart rate: level falls back to exposure and time, never to Cool by default.
2. Stale heart rate: confidence widens and the level does not drop.
3. GNSS lost: shade state is treated as unknown, which counts as sun.
4. Body score says Cool but two brain families are flagged: level is at least Strained.
5. The language model suggests a different level: the model's text is ignored; the engine's level stands.
6. Missing weather counts as hot: level is at least Warming with no weather data.

**Making the checks mean something.** Each scenario is shown to fail when its guard is switched off. This caught a real defect: the "missing weather" guard turned nothing red when flipped, so check 6 was added. A check that cannot fail proves nothing.

### 4. ECTemp test vectors

**What ECTemp is.** A published Kalman-filter method from US Army research (Buller et al., 2013) that estimates a person's core-temperature trend from heart rate alone, named in this bible as the core-temperature filter (P5).

**How it works.** `ectemp_vectors.json` holds seven reference cases that earlier Python and TypeScript implementations reproduce. The Kotlin implementation loads the same file, runs each case with personalisation off, and must match. Diagnostics shows "ECTemp vectors 7/7 pass", on the phone and on the laptop.

**Filter settings to use** (unless a test says otherwise):

- one filter step per minute, using the median heart rate of that minute;
- Q = 0.000484, R = 356.4544, NIS gate 3.84;
- starting variance 0.02, or 0.2 if the worker logs on already hot;
- action thresholds at or below 38.5 °C (38.0 °C for workers not yet acclimatised), never relying on an estimate above 39 °C.

**One thing tested explicitly.** The reference cases assume a reading every minute. Thakaan feeds readings mainly at stops, so the filter predicts through gaps and its variance grows, never shrinks. That behaviour has its own tests.

### 5. Hash-chained proof-of-exposure log

**What it is.** Each log entry (shift, time, sun dose, rests, peak level) stores a short fingerprint (SHA-256) of the entry before it, and the log is signed with an ECDSA P-256 key held in the Android Keystore (hardware-backed, StrongBox present on the iQOO 15). If anyone edits or deletes an earlier entry, every later fingerprint stops matching.

**How it works.** The Shift report export includes the chain and signature. The console verifies it in the browser and shows "Chain OK" or "Chain BROKEN". On the phone: a changed byte is caught (entry content was changed), a cut-off last entry is caught (expected 3 entries, found 2), and changed data fails the signature. The demo beat is to change one byte and show "Chain BROKEN".

**Honest caveat.** A valid chain shows that entries have not changed since they were recorded. It does not prove the original observation was true. Say this in the pitch.

### 6. Offline map bundle from OpenStreetMap

**What it is.** Chhaya Route needs roads, water points, shops and rest stops for the demo area, packed to run offline.

**How it works.** Scripts (`tools/chhaya/build_graph.py`) fetch roads and amenities from OpenStreetMap for the Bengaluru demo area and pack them into a compact graph that ships in the APK with the precomputed shade tables. Amenities pulled: roads, `amenity=drinking_water`, tea stalls and shops, fuel stations and rest points.

**Label limits honestly.** OpenStreetMap cannot certify water quality. Water points show as "listed, quality unverified" until riders confirm them.

**Licence.** OpenStreetMap data is © OpenStreetMap contributors (ODbL); the credit is shown wherever OSM data appears. Satellite-height and canopy sources carry their own CC BY credits (see `docs/ASSETS.md`); the SegFormer weights are non-commercial research use only.

### 7. Optional air and rain layers

These layers extend the app beyond heat. They must never delay or weaken the core strain loop. They do not change the strain level; they are shown as separate cards and map layers.

#### 7a. Breathing dose (air pollution)

**What it is.** The amount of polluted air a worker actually breathes, not the city headline number.

```latex
Dose_{PM2.5} = \sum C \times V_E \times \Delta t
```

C = PM2.5 concentration (µg/m³), V_E = minute ventilation (litres of air breathed per minute), Δt = time in each interval.

**How it works (as built).**

- **Concentration.** The phone has no PM2.5 sensor. The value is a modelled area value from Open-Meteo (Copernicus CAMS, about an 11 km grid), cached for the demo week, and labelled as such, not as a station reading and not the worker's own air. An OpenAQ key was not obtained.
- **Ventilation.** Estimated from heart rate with the Greenwald et al. (2019) pooled power model (471 people, 14,550 measurements). It is essentially unbiased on average, but single estimates can be off by roughly 45% (middle half), averaging down over a shift. It was validated with measured heart rate, not phone camera pulse. Between heart-rate checks, ventilation is a workload estimate from E6 and is labelled as an estimate.
- **Lung capacity.** A typical value and an unknown sex (middle value) are used until entered.
- **Output.** An "Air dose today" card on Home, shown to one decimal under 10 µg. The shift report shows levels only.

#### 7b. Rain and waterlogging

**What it is.** Warn a rider about a flooded road and keep the route off it.

**How it works (as built).**

- **Rider flood report.** One tap plus a fresh photo: "road flooded here". A photo freshness check rejects stale or blank photos, but it cannot prove a road is flooded. A report closes every road segment within 30 m (parallel lane, side road) and expires after 3 hours.
- **Crew mesh.** A report heard from a nearby phone closes roads like a local one, but is anonymous and unverified, so reports are limited to 3 hops, 20 an hour and 3 km, and can only add closures.
- **Rain-onset cue.** The iQOO 15 has no barometer, so the cue comes from a lux and colour-temperature drop plus the cached forecast. It is a local hint, not a measurement of rainfall.
- **Not built:** a modelled flood-prone layer (no elevation or drain data was fetched, by choice) and a known-hotspot list.
- **Honest limit.** Flood depth is not measured. The app shows "reported flooded", never a depth. An unverified report is shown as caution, not clear.

**Extended route cost.** The flood term is part of the live edge cost:

```latex
C_e = t_e\,\big(1 + \lambda(TSS)\cdot S_e(h)\big) + \mu\,H_e + F_e
```

F_e is very large (the road is effectively closed) for an edge with an active report until the report expires. At F_e = 0 the cost is the original formula. The air term (ν A_e) from the first design is not used: the air value is one number for the whole area, so it cannot rank streets, and the Air map layer is display-only.

### Glossary additions

| Term | Meaning |
| --- | --- |
| Shadow model | A learned model that runs beside the rule-based path, is shown on Diagnostics, and never decides a level |
| Crew mesh | Bluetooth LE extended advertising between nearby phones for flood reports and buddy alerts |
| Claim ledger | The list in `docs/PITCH.md` of every number the pitch may say, with its source and state |
