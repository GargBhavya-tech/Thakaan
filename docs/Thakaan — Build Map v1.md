# Thakaan — Build Map v1

Oct 10, 2026 · @Ashish

For anyone building Thakaan who has not read the full bible. Each ticket can be built and tested on its own before the next; the companion **Thakaan — Product Bible v3** has the deep "why".

## How to read this map

**Build model:** 3-person team, **48-hour Grand Finale**, iQOO Hackathon 2026, Bengaluru, 9 to 11 Oct. Build order targets **a demoable heart-check + strain gauge by Checkpoint A**, then iterates. Tickets are numbered in strict dependency order: `Blocked by` only ever points to a lower number.

### Priority tag

| Tag | Meaning |
| --- | --- |
| 🟥 **CORE** | The spine or a headline differentiator. If you build nothing else, build these. |
| 🟨 **SUPPORTING** | Real, shown as one cue, pane or mode. Build after the spine works end to end. |
| ⬜ **SEED / PITCH-ONLY** | Build a token version only, or narrate it on stage. |

### Light tag

| Tag | Meaning |
| --- | --- |
| ⚪ **PRE-BUILD** | Decide, source or rehearse before coding starts. Doing it on the clock wastes an hour. |
| 🟢 **GREEN** | Needs the laptop: big installs, NDK builds, model conversion, console polish. |
| 🔴 **RED-OK** | Phone is enough to build-test it (captures, field tests, tuning). Keep the Office Kit mirror running (that usage is scored). |

Confirm the finale's Red / Green Light rules at kickoff; if they differ from the city battle, re-tag.

### Checkpoints

| Label | Meaning | Time |
| --- | --- | --- |
| **CP-A** | First graded eval: fingertip heart check, strain gauge, one alert, Office Kit live | Fill from the organiser schedule |
| **CP-B** | Second graded eval: full body model, brain battery, Chhaya Route, safety checks | Fill from the organiser schedule |
| **FINAL** | Stage pitch: rehearsed demo, metrics table, backup video | Fill from the organiser schedule |
| **Stretch** | Only if everything above is green |  |

### Per-ticket fields

Each ticket has **Build** (what to make), **Watch out** (the mistake the bible already flagged), **Test** (how to verify it standalone) and **Done when** (the pass condition), plus Priority, Type, Light, Checkpoint and Blocked by.

**Golden rule:** don't start a ticket until its blockers pass their own Test. A ticket that "mostly works" is not done; the next one silently inherits its bugs.

**Scheduling rule:** always keep one Red-ready and one Green-ready ticket queued per person, so a light change never leaves anyone idle. Do the 🟢 asset and NDK tickets (#4, #19) in the first Green window; many tickets depend on them.

### Roles

| Person | Owns | Tickets |
| --- | --- | --- |
| **A: Body** | Fingertip PPG, ECTemp filter, heat-balance prior, body vectors | #8 to #12, #22 to #31 |
| **B: Brain + World** | Motion, environment, brain battery, Chhaya Route | #13 to #21, #32 to #38, #46 to #51 |
| **C: Systems + Demo** | Skeleton, risk engine, safety checks, Office Kit, console, metrics, demo | #5 to #7, #39 to #45, #52 to #56, #60 to #65 |

C also owns the held-out integrity of the metrics (#60).

## Phase 0: Pre-build decisions and foundations

### #1: Scope and submission lock

**Priority:** 🟥 CORE · **Type:** Discuss · **Light:** ⚪ PRE-BUILD · **Checkpoint:** before coding · **Blocked by:** —

**Build:** Settle in writing: name **Thakaan** (sub-product Chhaya Route) across form, deck and app; track (HealthTech, "heat illness is an occupational-health emergency"; Open Innovation as fallback); the role split above; the demo teammate whose baseline will be captured.

**Watch out:** Thakaan replaced Chaukas mid-finale. Remove every trace of the old idea from forms and slides.

**Test:** each item has one written answer, no "decide later".

**Done when:** name, track, roles and demo teammate are fixed.

### #2: Loaner smoke test

**Priority:** 🟥 CORE · **Type:** Research · **Light:** 🔴 RED-OK · **Checkpoint:** first hour · **Blocked by:** —

**Build:** On the iQOO 15, confirm: SoC (`ro.soc.model` SM8850); `SensorManager.getSensorList(TYPE_ALL)` shows the colour-temperature and flicker sensors and their max range; torch strength levels (`getTorchStrengthLevel`); GNSS raw measurements with full tracking and L5 carriers; `getThermalHeadroom()` returns numbers; offline TTS voices hi-IN and kn-IN; accelerometer max sample rate.

**Watch out:** E1, E12, #8 and #19 all assume these exist. If the CCT sensor is not exposed, E1 falls back to lux + camera white balance; if L5 is missing, drop the L1−L5 feature.

**Test:** a one-page checklist with yes / no and the measured value for each item.

**Done when:** every assumption the bible makes about the phone is confirmed or has a named fallback.

### #3: Office Kit loop rehearsed

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** ⚪ PRE-BUILD · **Checkpoint:** CP-A · **Blocked by:** —

**Build:** Practise screen mirror, remote control, clipboard and file transfer between the loaner and the laptop.

**Watch out:** Office Kit usage is scored from device telemetry; it's also how 2 GB model files and APKs move during Red Light.

**Test:** mirror, push a file both ways and drive the phone from the laptop in under a minute, cold.

**Done when:** every team member runs the loop without hesitation.

### #4: Assets pre-fetched

**Priority:** 🟥 CORE · **Type:** Research · **Light:** 🟢 GREEN · **Checkpoint:** first Green window · **Blocked by:** —

**Build:** Download and stage on the laptop: Bengaluru OSM extract; Open-Meteo hourly cache for the venue area (temperature, humidity, wind); the lwbgt C source; `ectemp_vectors.json`; Gemma 4 E2B `.litertlm`; OpenAQ key (stretch). Push the phone-side files by Office Kit.

**Watch out:** never download at demo time. Venue Wi-Fi with 100+ teams is unreliable.

**Test:** every file opens on the laptop and its size and date are logged.

**Done when:** all assets are on the laptop and the phone, with a written list.

### #5: App skeleton, sensor hub and provenance

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #2

**Build:** Kotlin + Compose app with a foreground sensor-hub service (location, camera, microphone, health types) that timestamps every reading and stamps a `provenance` field (REAL / REPLAY / SIMULATED). Mode picker (Rider / Site). Home gauge with a mock TSS and provenance chip.

**Watch out:** OriginOS kills background work. Lock the app in Recents, set battery to unrestricted, and keep the demo in the foreground.

**Test:** run 10 minutes with the screen on; confirm IMU, light and GNSS streams keep flowing and every reading carries REAL.

**Done when:** the hub streams all sensors with timestamps and provenance, on-device, no laptop.

### #6: Inference seam and Diagnostics screen

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🟢 GREEN · **Checkpoint:** CP-A · **Blocked by:** #5

**Build:** One `InferenceEngine` interface with QNN, GPU and CPU back ends; manifest entries for `libcdsprpc.so`, legacy jniLibs packaging and `ADSP_LIBRARY_PATH`. A Diagnostics screen showing sensor health, thermal headroom, "N/N ops on NPU" and empty slots for "ECTemp vectors" and "Safety checks".

**Watch out:** a session that creates without throwing may still be on the CPU; read the delegated-ops count.

**Test:** load one tiny model; Diagnostics shows its NPU op count and back end.

**Done when:** any model can be swapped behind the seam and its compute unit is visible.

### #7: Stream recorder and replay harness

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #5

**Build:** Record all hub streams to a file; replay them into the hub at 1x or 20x, stamped REPLAY.

**Watch out:** this is how every later ticket gets tested without riding, and how the demo shows a full shift. Replayed data must never show as REAL.

**Test:** record 5 minutes of walking, replay at 20x; downstream readings show REPLAY and match the recording.

**Done when:** any session can be recorded and replayed at speed with correct labels.

## Phase 1: Fingertip PPG (the heart spine)

> Every body vector depends on clean heart readings. Build and test this against a reference (a teammate's pulse oximeter or smartwatch, used for development only) before anything downstream.

### #8: Camera2 lock and torch capture

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #5

**Build:** Rear camera at 640 × 480, fixed 30 fps; AE, AWB and AF off; fixed exposure; low fixed ISO; stabilisation and noise reduction off; torch on at 50 to 70% strength. Average the red (and green) channel over the centre 100 × 100 px each frame, with the hardware timestamp.

**Watch out:** auto-exposure flattens the pulse into a straight line; a variable frame rate breaks HRV. Without the torch, sunlight leaking round the finger swamps the signal.

**Test:** finger on lens indoors and in direct sun; plot the raw red trace on Diagnostics and confirm a visible pulse in both.

**Done when:** a clean, evenly timed red-channel trace streams at 30 fps under sun and shade.

### #9: PPG signal pipeline to heart rate

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #8

**Build:** Cubic-spline resample to a uniform grid; zero-phase Butterworth band-pass 0.5 to 4.0 Hz; Daubechies-4 wavelet denoise; first-derivative peak detection with a 300 ms refractory period; Viterbi tracking across windows; live waveform on screen.

**Watch out:** the dicrotic notch double-counts without the refractory period; per-window FFT peaks jump between harmonics without tracking.

**Test:** 10 resting and 10 post-exercise captures vs the reference device; log the error of each.

**Done when:** resting HR is within about 3 bpm of the reference on most captures, and the waveform renders live.

### #10: Signal-quality gates and retry UX

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #9

**Build:** Reject a window if perfusion index is under 0.3%, beat template correlation under 0.85, SNR under 5 dB, or frames dropped. A perfusion ring turns green only in range. On rejection, say why: "wipe finger and lens", "shade the phone", "press lighter".

**Watch out:** expect 20 to 30% rejections in the field. A silent failure or a wrong number is worse than a retry prompt.

**Test:** press hard, press with a sweaty finger, wiggle; each must be rejected with the right prompt, and a good capture must pass.

**Done when:** bad captures never produce a number, and each failure tells the user what to do.

### #11: Quick and full checks

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #10

**Build:** Quick check: 10 s settle (discarded) + 15 to 20 s → HR. Full check: 10 s settle + 60 s → HR, RMSSD, perfusion index, pulse amplitude, respiration from the pulse envelope. Store every result with time, posture state and provenance.

**Watch out:** never compute RMSSD from under 60 s of clean data. Under heat, pulse-rate variability reads high (vasodilation), so flag RMSSD as heat-affected rather than as good recovery.

**Test:** run both checks back to back at rest; HR agrees within 3 bpm; RMSSD appears only for the full check.

**Done when:** both checks return stored, labelled results in under 30 s and 70 s.

### #12: Thermal guard for captures

**Priority:** 🟨 SUPPORTING · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #11

**Build:** Block captures when thermal status is SEVERE or headroom is near 1.0; detect torch dimming (a DC step) mid-capture and void the window.

**Watch out:** in 40 °C sun the OS can dim the torch or drop frames mid-capture, silently corrupting beat intervals.

**Test:** heat the phone (sun + load), attempt a capture; confirm it is blocked with a "cool the phone" prompt.

**Done when:** no capture completes under throttling conditions.

## Phase 2: Motion and context

### #13: Activity, workload and mode detection

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #5, #7

**Build:** Classify riding, walking, stairs, lifting and idle from IMU + GNSS speed (rules first, a 1D-CNN on the NPU later); map each to a metabolic rate (riding 1.5 to 2.5 MET, walking and loads via Pandolf / MET tables). Detect phone placement: mounted, pocket, in-hand, desk.

**Watch out:** the body model's excess-HR split (#26) is only as good as this workload estimate. Keep the classes few and stable.

**Test:** record riding, walking, stairs and sitting with #7; replay and check the label timeline matches what happened.

**Done when:** activity, metabolic rate and placement are published every second.

### #14: Stop detector and posture clock

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #13

**Build:** A state machine (Moving, Stopping, Stopped, Walking, Resting) with two clocks: time since stop and time since posture change (mount / dismount, sit / stand). It wakes the torch prompt, camera and mic only in the right states.

**Watch out:** stopping removes airflow and spikes HR within seconds; dismounting adds 10 to 20 bpm for 30 to 60 s. Every HR reading must carry both clocks or it can't be compared.

**Test:** replay a ride with stops and a drop-off walk; confirm state changes land within 2 s and the clocks reset correctly.

**Done when:** every reading is stamped with state, time-since-stop and time-since-posture-change.

### #15: Collapse detection with countdown

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #14

**Build:** Detect falls, bike tip-overs and slow slumps or sit-downs followed by stillness. Start a spoken "say haan" countdown (voice or tap cancels); on timeout, siren, bystander screen and an SMS preview with location.

**Watch out:** heat collapse is often a slump, not a fall. Without the countdown, sitting down on a kerb triggers a siren.

**Test:** fall onto a mat, slump slowly to the floor, and sit down normally; the first two trigger the countdown, the third does not; "haan" cancels.

**Done when:** slumps and falls reach the alarm, normal sitting never does, and the countdown always runs first.

## Phase 3: Environment and exposure

### #16: Sun / shade state (E1)

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #2, #5

**Build:** Classify sun / shade / overcast each second from lux plus colour temperature (direct sun about 5,000 to 5,500 K; open shade about 7,500 to 10,000 K), with tilt from the gravity vector and a self-shadow check (sun behind the rider, from GNSS heading vs solar azimuth).

**Watch out:** the light sensor faces the rider; with a low sun behind them, it reads "shade" while the rider bakes. If #2 shows no CCT sensor, fall back to lux + camera white balance.

**Test:** walk sun → building shade → tree shade → sun with the phone mounted at its real angle; compare the label timeline with a hand log.

**Done when:** the label matches the hand log on most transitions, including a sun-behind case.

### #17: Irradiance and sun dose (E15, E2)

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #16

**Build:** Lux ÷ 110 ≈ W/m²; above sensor saturation (check the max range from #2, often about 65,000 lux) switch to a clear-sky model from sun position; cosine tilt correction; shade attenuation when E1 says shade. Integrate minutes and energy in direct sun as the shift sun dose.

**Watch out:** never use the phone body's temperature as a radiant reading; that idea was dropped in v3.

**Test:** hold the phone in noon sun; confirm the saturation fallback kicks in and W/m² stays plausible.

**Done when:** a continuous W/m² stream and a running sun dose exist, with saturation handled.

### #18: Weather cache and local air temperature (E9, E16)

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #4, #5

**Build:** Load the cached Open-Meteo hourly grid (and KSNDMC stations if bridged) and interpolate temperature, humidity and wind offline. Add a local correction from a battery-temperature transient model with self-heat subtraction (screen, charging, headroom slope), used only when the phone is idle and uncased.

**Watch out:** a mounted phone in a case reads hot. The forecast is the base; the phone only nudges it.

**Test:** airplane mode: values still return for the venue coordinates; compare the local estimate with a cheap room thermometer.

**Done when:** air temperature and humidity are available offline every minute, labelled REAL (cached source).

### #19: Apparent wind and Liljegren WBGT (E17, E18)

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🟢 GREEN · **Checkpoint:** CP-A · **Blocked by:** #4, #17, #18

**Build:** Compile the lwbgt C library with the NDK and call it via JNI with air temperature, humidity, W/m² and air speed = GNSS speed + forecast wind. Also compute UTCI.

**Watch out:** never fall back to the simple ACSM87 formula by day; it understates risk in strong sun. Air speed drops to near zero at stops; that spike is real.

**Test:** run the published lwbgt reference inputs and match the outputs; replay a ride and see WBGT jump at every stop.

**Done when:** WBGT and UTCI update every minute from live inputs and match the reference values.

### #20: Rest ledger and drink log (E7, E8)

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #14, #16

**Build:** A real rest = shade + still for over 5 minutes; track time since last rest and rest length. Drinks logged by one tap, by voice ("pani piya") or on water-stop arrival.

**Watch out:** a long red light in the sun is not a rest.

**Test:** replay a session with a shaded break and a sunny stop; only the break counts. Say "pani piya" offline; a drink is logged.

**Done when:** rest debt and drinks are tracked and visible on Home.

### #21: GNSS sky view (E12, E13, E14)

**Priority:** 🟨 SUPPORTING · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #2, #16

**Build:** GnssMeasurement with full tracking at 1 Hz; per-satellite line-of-sight features (C/N0, ΔC/N0, elevation, Doppler residual, L1−L5 gap); start with C/N0 + elevation rules, then a small XGBoost; 10° sky bins with a Bayesian update; sky-view factor and "sun direction blocked". Learn the rider's body-shadow mask by relative azimuth.

**Watch out:** full tracking costs power and heat; duty-cycle it. The rider's body blocks satellites behind them; without the mask that reads as shade.

**Test:** ride or walk under a flyover, a tree avenue and open road; sky-view drops in the first two, and canopy shows more scintillation than the flyover.

**Done when:** sky view and sun-blocked flags agree with E1 on most segments.

## Phase 4: Body model

> The defensible technical depth. Build the filter against the reference vectors first, then feed it real fingertip readings.

### #22: ECTemp core filter and test vectors

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #4, #6

**Build:** Kotlin sigmoid ECTemp EKF: one step per minute on the median HR of that minute; Q = 0.000484; R = 356.4544; sigmoid observation model and its Jacobian; prediction-only when no reading. Load `ectemp_vectors.json` and show "ECTemp vectors N/7" on Diagnostics.

**Watch out:** match the published equations exactly before personalising anything; a small sign error in the Jacobian still "looks right" on one test.

**Test:** run all seven reference cases.

**Done when:** Diagnostics shows 7/7 pass.

### #23: Personalisation, gating and start-of-shift

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #22, #11

**Build:** Personal asymptotes: K from HRmax (208 − 0.7 × age), A from the lowest sustained 5-minute rest HR. NIS gate: reject a reading when (HR − HR\_exp)² / (m² v̂ + R) > 3.84. Start-of-shift CT₀ from a time-of-day curve (36.8 °C at 05:00 to 37.4 °C at 17:00); starting variance 0.02, or 0.2 if fingertip HR > 100 or WBGT > 30 °C; Q inflated for the first 20 minutes.

**Watch out:** the reference vectors assume a reading every minute; Thakaan gets readings at stops. Add your own tests for multi-minute gaps (variance must grow, never shrink).

**Test:** the 7 vectors still pass with personalisation off; a synthetic 12-minute gap grows variance linearly; an injected 160 bpm spike at rest is rejected.

**Done when:** personalised filter, gate and start rules are on, with gap tests passing.

### #24: Gagge / PHS heat-balance prior

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #13, #17, #18, #19, #22

**Build:** A Gagge two-node model stepping core and skin temperature each minute from metabolic rate (#13), air temperature and humidity (#18), radiant load (#17), air speed (#19) and clothing insulation; add its core increment to the ECTemp prediction step. A simplified ISO 7933 PHS gives sweat rate.

**Watch out:** this is the biggest single accuracy gain and the biggest bug risk. Keep it isolated behind a switch so the plain ECTemp path still runs.

**Test:** replay a hot ride vs a cool one with no HR readings; the predicted trend rises faster in the hot replay and stays flat when resting in shade.

**Done when:** the prior moves the core trend sensibly between readings and can be toggled off.

### #25: Hydration deficit (P15)

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #20, #24

**Build:** PHS sweat loss minus logged drinks → % body-mass deficit (needs body weight from onboarding). Feed it into the observation model (+4 to 5 bpm per 1%) and the core prior (+0.15 to 0.20 °C per 1%).

**Watch out:** a missed drink log inflates the deficit; show it as a range and nudge "did you drink?" before escalating on hydration alone.

**Test:** replay 3 hours of sun with no drinks, then log two drinks; the deficit climbs, then drops.

**Done when:** a hydration deficit with a band is shown and feeds the filter.

### #26: Excess HR and idle-state drift (P9, P10)

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #14, #23, #24

**Build:** Excess HR = measured HR minus HR expected for workload, posture and time-since-stop. Idle drift = HR in the same state at the same time-since-stop vs the morning value. Learn a per-rider stop-transient template.

**Watch out:** compare only like with like; a reading 10 s after dismounting against a seated morning baseline invents 15 bpm of "drift".

**Test:** take quick checks at the same stop offset morning and afternoon; drift shows only the real difference, and a post-dismount reading is down-weighted.

**Done when:** both values update after every accepted reading and appear in the risk drivers.

### #27: Sit-to-stand and perfusion collapse (P13, P17)

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #11, #14

**Build:** Sit-to-stand: seated 30 s, stand, HR rise at 30 to 60 s vs baseline. Perfusion collapse: perfusion index high in heat, then a sudden fall across successive checks → hypovolemic-strain cue.

**Watch out:** perfusion also falls with hard finger pressure; only count a collapse when contact quality (#10) is good.

**Test:** do the sit-to-stand at baseline and after 10 minutes of stair climbing; the rise is larger the second time.

**Done when:** both vectors produce stored values with quality flags.

### #28: Onboarding and cool baseline

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #11, #27

**Build:** Onboarding flow: language, mode, consents, profile (age, sex, weight, height, clothing, helmet, HR-blunting medicines, caffeine, smoking), contacts. Cool baseline: full fingertip check, sit-to-stand, tremor, tapping and speech slots (filled by #32 to #34), 30 s walk. Daily 3-tap check-in (slept, ate, unwell).

**Watch out:** capture the demo teammate's baseline early and cool, not after a night of building.

**Test:** a new user completes onboarding in about 7 minutes; every baseline field is filled.

**Done when:** profile and baseline feed every personalised model.

### #29: Adaptive R and drift handling

**Priority:** 🟨 SUPPORTING · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #26

**Build:** A moving average of innovations; when it stays biased, inflate R so humidity-driven circulatory drift is tracked as P9 / P10 rather than pushed into the core trend. UKF as an optional upgrade.

**Watch out:** inflating R must never lower the shown level (safety invariant, #41).

**Test:** a synthetic +12 bpm sustained offset raises R and stops the core trend from running away.

**Done when:** sustained drift no longer inflates the core trend, and the 7 vectors still pass with it off.

### #30: Chest SCG (P3)

**Priority:** 🟨 SUPPORTING · **Type:** Prototype · **Light:** 🟢 GREEN · **Checkpoint:** Stretch · **Blocked by:** #6, #13

**Build:** High-rate accelerometer + gyro in a chest pocket; band-pass, gyro-referenced adaptive cancelling, 1D-CNN denoiser on the NPU, AO-peak detection; only when the worker is still.

**Watch out:** useless while walking or lifting; gate hard on stillness.

**Test:** seated with the phone in a chest pocket, compare with a fingertip check.

**Done when:** still-state HR roughly matches fingertip, or the ticket is cut.

### #31: Face rPPG and forehead thermistor (P1, P14)

**Priority:** ⬜ SEED / PITCH-ONLY · **Type:** Prototype · **Light:** 🟢 GREEN · **Checkpoint:** Stretch · **Blocked by:** #6, #8

**Build:** Only if everything else is green: front-camera rPPG (locked Camera2, POS first) at red lights; forehead-press thermistor check (90 s, screen off, phone cool).

**Watch out:** face rPPG fails in sun and on darker skin; never let it replace fingertip.

**Done when:** either it runs as an optional extra, or it's a roadmap line.

## Phase 5: Brain battery

> 5-second tests were dropped in v3: they can't catch lapses and are swamped by touchscreen latency. Every score here is compared only with the same worker on the same phone.

### #32: Tremor hold (N9)

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #13

**Build:** A 10 to 15 s "hold the phone still at arm's length" test; accelerometer power spectral density in the 4 to 12 Hz band. Also a passive version whenever the phone is in hand.

**Watch out:** heavy lifting in the previous minute raises local muscle tremor; skip or flag tests within 60 s of a lifting label from #13.

**Test:** baseline, then repeat after 30 push-ups; band power rises clearly.

**Done when:** tremor power is stored per test with a lifting-confound flag.

### #33: Fitts tapping (N4)

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #5

**Build:** 20 s of rapid alternating taps between two shifting targets; record hardware touch timestamps (MotionEvent event time, not UI callbacks); compute throughput (bits/s) and spatial error rate.

**Watch out:** this is the motor-coordination test that dehydration hits first. Never compare scores across different phones.

**Test:** 5 runs at baseline give a stable throughput; a run with the non-dominant hand is clearly worse.

**Done when:** throughput and error rate are stored per test with a device ID.

### #34: Speech code (N3)

**Priority:** 🟨 SUPPORTING · **Type:** Prototype · **Light:** 🟢 GREEN · **Checkpoint:** CP-B · **Blocked by:** #6

**Build:** The app shows a random 4-digit code; the worker says it; forced alignment checks correctness, articulation rate and pauses. Gate on SNR; jitter, shimmer and CPP only when quiet.

**Watch out:** voice-quality biomarkers don't survive traffic noise or noise suppression. The value here is catching confusion (wrong digits) and slurring.

**Test:** say the code correctly, then slowly with a slur, then with wrong digits, in a noisy room; the last two flag.

**Done when:** correct / incorrect and rate are reliable in venue noise in Kannada, Hindi and English.

### #35: Symptom tap (N10)

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #5

**Build:** Icon taps or voice for thirst, headache, dizziness, nausea, cramps, plus a 1 to 10 effort rating. Asked at every check and every level change.

**Watch out:** keep it to 2 taps; big icons, readable in sun.

**Test:** a teammate completes it in under 5 s with one hand.

**Done when:** symptoms are stored and available to the risk engine.

### #36: CUSUM engine and context prior

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #28, #32, #33

**Build:** Per worker and device: robust z-scores (median, MAD) against the cool baseline at the same time of day; an upper CUSUM per test, k = 0.5σ, alarm at h = 4 to 5σ. Escalation weight depends on exposure (#19, #17), time of day, sleep answer and caffeine.

**Watch out:** sleep loss slows people far more than mild heat does. Without the context prior a tired cool morning looks like heat strain.

**Test:** feed a synthetic series of slightly slower tests; one bad test does not alarm, five mildly slow ones do.

**Done when:** each brain test feeds a CUSUM whose alarm state reaches the risk engine.

### #37: Gait instability (N1)

**Priority:** 🟨 SUPPORTING · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #13, #36

**Build:** On every walk: stride-time variability, autocorrelation peak height, lateral sway; baseline per phone placement (pocket, hand).

**Watch out:** pocket and hand gait look completely different; never mix baselines.

**Test:** walk normally, then with a deliberate wobble; the wobble scores clearly higher in the same placement.

**Done when:** gait scores feed the CUSUM per placement.

### #38: Riding smoothness and brief PVT (N2, N11)

**Priority:** 🟨 SUPPORTING · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** Stretch · **Blocked by:** #36

**Build:** N2: steering-yaw approximate entropy and correction size with per-road-segment baselines. N11: a 3-minute PVT-B (lapses over 500 ms, false starts, mean 1/RT), triggered only at a break to confirm a flag.

**Watch out:** N2 needs many rides on the same streets to mean anything; demo it as a replay only.

**Done when:** PVT-B runs on demand and N2 logs per segment, or both are roadmap lines.

## Phase 6: Risk engine, alerts and safety

### #39: Thakaan Strain Score and anchored levels

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A (v1), CP-B (full) · **Blocked by:** #11, #19, #20, #35

**Build:** v1 for CP-A: exposure prior (WBGT, sun dose, rest debt, time of day) + the independent HR rule (sustained rest HR above 90 to 100 bpm) + symptoms → TSS 0 to 100 and levels. Full version for CP-B: add the core trend, PSI, excess HR, idle drift, hydration, perfusion collapse and CUSUM alarms by family as #23 to #27 and #36 land; level = the highest that any anchor or the score supports; hysteresis and dwell times; top-3 drivers recorded for every change.

**Watch out:** no action may wait for a core estimate above 39 °C; core anchors fire at 38.5 °C (38.0 °C before acclimatisation).

**Test:** replay a cool morning ride (stays Cool) and a hot afternoon ride with no drinks (climbs to Strained); levels don't flap at boundaries.

**Done when:** a live level with drivers updates every minute from real inputs.

### #40: Alert ladder

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #15, #39

**Build:** Per level: haptic pattern, offline TTS line in the chosen language, screen (amber ring, orange banner, red takeover), and actions (contact + supervisor alert at Danger, collapse flow from #15). Smart snooze, never at Danger.

**Watch out:** every alert names one action and its time cost; no generic "stay safe".

**Test:** force each level from Diagnostics; the right haptic, voice line and screen fire in Hindi and Kannada.

**Done when:** all four levels and the collapse flow fire correctly, offline.

### #41: Safety-invariant property test

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🟢 GREEN · **Checkpoint:** CP-B · **Blocked by:** #39

**Build:** A unit test that generates random shifts, then drops or corrupts inputs (HR missing, stale, noisy; GNSS lost; light blocked; model off) and fails if any degraded run shows a lower level than the full-data run.

**Watch out:** this is principle 11 made executable. If it fails, fix the engine, never the test.

**Test:** run a few thousand random cases in CI on the laptop.

**Done when:** the property test passes, and fails when a deliberate bug lowers a level.

### #42: Safety checks tab with guard switches

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** FINAL · **Blocked by:** #6, #41

**Build:** On Diagnostics, five on-phone scenarios with PASS / FAIL: no fresh HR; stale HR; GNSS lost (unknown shade = sun); body Cool but two brain families flagged (at least Strained); LLM proposes another level (ignored). A hidden switch disables each guard and its scenario must go red.

**Watch out:** a check that can't fail proves nothing; the guard switches are what make this credible on stage.

**Test:** all five PASS; flip each guard off in turn and its scenario FAILs.

**Done when:** the tab shows 5/5 PASS live, and each guard switch proves its check.

### #43: Cooling coach

**Priority:** 🟨 SUPPORTING · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #11, #40

**Build:** A 10-minute guided recovery (shade, drink, wet cloth on neck, fan), then a full check plus tremor and tapping; the level clears only when recovery passes.

**Test:** trigger Danger, run the coach with a recovered teammate; the level steps down only after the re-check.

**Done when:** Danger can't clear without a passed recovery check.

### #44: Strain forecast

**Priority:** 🟨 SUPPORTING · **Type:** Prototype · **Light:** 🟢 GREEN · **Checkpoint:** Stretch · **Blocked by:** #39

**Build:** Project TSS 15 and 30 minutes ahead from the trend and the planned route's sun exposure (linear extrapolation first, a small GRU later); show "Strained in about 20 min".

**Done when:** the forecast shows on Home with a band, or it's a roadmap line.

### #45: Quick-check flow

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #11, #32, #33, #35, #39

**Build:** One screen chaining fingertip (live waveform + perfusion ring), tremor hold, Fitts tapping, speech code (when #34 lands) and symptoms, in about a minute; launched from Home, at hand-overs, and on escalation.

**Watch out:** this is the screen judges will touch; big targets, voice prompts, and every failure says what to do.

**Test:** a teammate who has never seen it completes the flow unaided in under 90 s.

**Done when:** the full flow runs end to end and updates the level.

## Phase 7: Chhaya Route

> The headline differentiator: the first router whose detour budget is set by the rider's own body. Routing is deterministic, so it is the safest part of the live demo.

### #46: OSM bundle and offline graph

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🟢 GREEN · **Checkpoint:** CP-B · **Blocked by:** #4

**Build:** Pack the Bengaluru demo area from OSM: roads, `amenity=drinking_water`, tea stalls, shops, fuel stations, rest points. Build an offline GraphHopper or Valhalla graph on Android with custom per-edge weights. Show "© OpenStreetMap contributors".

**Watch out:** OSM can't vouch for water quality; label water points "listed, quality unverified".

**Test:** airplane mode, route between two venue-area points; a sensible route returns in under a second.

**Done when:** offline routing works for the demo area with amenities loaded.

### #47: Computed shadow layer

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🟢 GREEN · **Checkpoint:** CP-B · **Blocked by:** #46

**Build:** Per segment, every 30 minutes of the day: sun position ray-cast against building footprints and heights and tree-canopy polygons → shade fraction. Precompute on the laptop, ship as tiles.

**Watch out:** missing building heights default to a floor count guess; mark those segments low-confidence rather than fully sunny or shaded.

**Test:** pick three streets you know (tree avenue, tall-building street, open road) at 13:00; shade fractions rank correctly.

**Done when:** every demo-area segment has an hourly shade fraction.

### #48: Strain-aware routing

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #39, #47

**Build:** Edge cost = travel time × (1 + λ(TSS) × sun fraction) + heat-island penalty. Detour budget by level (Warming +1 min, Strained +3 min, Danger: nearest shade). At Strained, insert the best water stop within budget; at Danger, route to the nearest cool seated stop.

**Watch out:** at Cool, λ ≈ 0 and the route must equal the fastest route. Never cost a rider time they didn't need to spend.

**Test:** same origin and destination at Cool, Strained and Danger; three different routes, extra time within budget, water stop inserted at Strained.

**Done when:** the route changes with the level and respects the budget.

### #49: Chhaya map screen

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #48

**Build:** Map with shaded segments highlighted, water / shade pins, a "+2 min, 70% shade" chip, turn-by-turn with voice, provenance chip.

**Test:** a teammate reads the extra time and shade share at a glance in sunlight.

**Done when:** the route, its cost and its stops are readable in one glance.

### #50: Light-map logging and aggregation

**Priority:** 🟨 SUPPORTING · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #16, #21, #46

**Build:** Map-match E1 (and E12 / E13) readings to segments with an HMM matcher; bin on H3 (resolution 11 to 12) by hour; Bayesian update of the segment shade estimate over the computed layer.

**Watch out:** GPS in narrow streets jumps sides; aggregate over time windows and segments, never trust single points.

**Test:** ride or walk one street twice; its cell's shade estimate moves toward what you measured.

**Done when:** measured readings visibly correct the computed layer.

### #51: Recorded ride and 20x replay

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #7, #49

**Build:** Record a real 30 to 60 minute scooter ride (light, GNSS, IMU, stops, a couple of quick checks). Replay it at 20x through the full app: shade map fills in, sun dose and WBGT climb, the level rises, Chhaya reroutes with a water stop. Everything shows REPLAY.

**Watch out:** this is the demo's shift story. Record it in real afternoon heat, early; a rainy-day recording won't climb.

**Test:** replay end to end twice; the same level changes happen at the same points.

**Done when:** a 2-minute replay reliably tells the shift story with REPLAY labels.

## Phase 8: Systems, Office Kit and console

### #52: Office Kit live during Red Light

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-A · **Blocked by:** #3, #5

**Build:** Keep the screen mirror running through every Red Light build and test session; move APKs, logs and recordings by Office Kit file transfer.

**Watch out:** Office Kit usage is measured from telemetry, not pitched. Idle-but-mirrored beats not mirrored.

**Test:** the mirror stays up through a full build session.

**Done when:** mirroring is a habit in every Red window.

### #53: Supervisor console

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🟢 GREEN · **Checkpoint:** CP-B · **Blocked by:** #39, #52

**Build:** A laptop console fed over the Office Kit clipboard protocol (sealed "crew status" request, signed level summary back) and notification sync: crew board (level, trend, minutes since rest, sun dose, provenance chip), alert feed with drivers, a map coloured by level. Build it as a local web view from the start so judges can open it by QR code too.

**Watch out:** the console shows levels, never raw HR or voice. Demo crew members beyond the real phone must show SIMULATED.

**Test:** trigger Danger on the phone; the console row turns red within seconds with its drivers; scan the QR on a second phone and see the same.

**Done when:** the console mirrors live levels and alerts, with correct provenance per row.

### #54: Hash-chained exposure log and verifier

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** FINAL · **Blocked by:** #39, #53

**Build:** Each shift entry (time, sun dose, rests, peak level) stores the hash of the previous entry and is signed with a StrongBox key. Export via Office Kit; the console shows "Chain OK" or "Chain BROKEN".

**Watch out:** a valid chain proves nothing was edited later, not that the observation was true. Say that on stage.

**Test:** export, verify OK; change one byte; verify BROKEN.

**Done when:** tampering is detected every time.

### #55: Provenance chips everywhere

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** FINAL · **Blocked by:** #5, #49, #53

**Build:** One REAL / REPLAY / SIMULATED chip on the gauge, forecast, sun dose, Chhaya ETA and every console row; labels propagate to anything computed from them.

**Test:** during a 20x replay, every number shows REPLAY; after replay ends and a live check runs, the gauge shows REAL.

**Done when:** no screen shows a number without its correct label.

### #56: Airplane-mode proof

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #40, #48

**Build:** A clean on-stage flow: airplane mode on, quick check, level update, Chhaya route, alert, all still working.

**Watch out:** any hidden network call (fonts, map tiles, analytics) breaks this live; test with the SIM out too.

**Test:** run the full demo path with airplane mode on.

**Done when:** the demo is identical with no network.

## Phase 9: Optional and cut-first

### #57: Gemma coach and explainer

**Priority:** 🟨 SUPPORTING · **Type:** Prototype · **Light:** 🟢 GREEN · **Checkpoint:** Stretch · **Blocked by:** #4, #6, #39

**Build:** Gemma 4 E2B on the GPU (LiteRT-LM) phrases the level's top three drivers and the shift report in the worker's language, from a fixed action set.

**Watch out:** the LLM never changes a level (Safety check 5). Fixed template lines are the fallback if it's slow or hot.

**Done when:** spoken explanations come from the LLM or the templates, never from neither.

### #58: Breathing-dose card

**Priority:** ⬜ SEED / PITCH-ONLY · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** Stretch · **Blocked by:** #11, #13, #18

**Build:** Dose = station PM2.5 (OpenAQ, cached) × ventilation from HR (Greenwald model) or workload between readings × time; an "Air dose today" card, labelled as the station value.

**Done when:** the card shows a labelled dose, or it's a roadmap line.

### #59: Flood reports and crew mesh

**Priority:** ⬜ SEED / PITCH-ONLY · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** Stretch · **Blocked by:** #48, #53

**Build:** One-tap flood report with a fresh photo that closes the edge (F term) until it expires; rain-onset cue from forecast + a lux / CCT drop (no barometer on the iQOO 15). BLE / Wi-Fi Aware crew mesh with buddy pairing.

**Done when:** either a report reroutes a ride, or both are roadmap lines.

## Phase 10: Proof and demo (do not skip)

### #60: Metrics table

**Priority:** 🟥 CORE · **Type:** Research · **Light:** 🔴 RED-OK · **Checkpoint:** CP-B · **Blocked by:** #11, #39, #48

**Build:** Measure and fill: fingertip HR error vs a reference device (rest and post-exercise), capture rejection rate, quick and full check durations, ECTemp vectors passed, safety checks passed, sun / shade label agreement with a hand log, route extra time vs shade gained at each level, time from collapse to alarm, offline run result. Label each number **held-out** (a session nobody tuned against) or **development-time**.

**Watch out:** the reference device is for development only, never part of the product. One owner (C) keeps one held-out session untouched until this ticket runs; unowned, it gets contaminated by accident.

**Test:** re-run two measurements and confirm they reproduce within a sane margin.

**Done when:** every row has a real, reproducible number with its held-out or development label.

### #61: Demo built and rehearsed

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** FINAL · **Blocked by:** #42, #45, #51, #56

**Build:** The 3-minute run from the bible: fingertip check (REAL), jumping jacks and re-check, 20x ride replay with Chhaya reroute (REPLAY), shaky tremor + slow tapping + wrong code + "dizzy" → Danger, slump → countdown → collapse alarm, airplane mode + Diagnostics (7/7, 5/5 PASS), console with Chain OK.

**Watch out:** capture the demo teammate's cool baseline the same day, early; keep the phone cool, case off, not charging.

**Test:** two full runs end to end under venue lighting; log any manual intervention.

**Done when:** two consecutive clean runs.

### #62: Limitations slide and challenge answers

**Priority:** 🟥 CORE · **Type:** Discuss · **Light:** ⚪ PRE-BUILD · **Checkpoint:** CP-A · **Blocked by:** —

**Build:** The limitations slide (core estimates lose sensitivity above about 39 °C so actions fire at 38.5 °C; heart checks are spot readings; validation populations are narrow; brain signals are drift signals, not diagnoses; not a medical device; ECTemp patent and licensing). Rehearse the judge Q&A cold: moving-bike HR, core temperature, dark skin, 5-second tests, longer routes, weather alert, "are your numbers real?", privacy, who pays.

**Watch out:** naming the limits yourself scores better than a judge catching them.

**Done when:** every team member answers each challenge in one clean sentence.

### #63: Backup recording

**Priority:** 🟥 CORE · **Type:** Prototype · **Light:** 🔴 RED-OK · **Checkpoint:** FINAL · **Blocked by:** #61

**Build:** Record one full successful demo run, including airplane mode and the console, as a fallback.

**Test:** play it start to finish; every beat of the script is visible and the chips are readable.

**Done when:** a usable backup video is on the laptop and the phone.

### #64: Pitch narrative, both formats

**Priority:** 🟥 CORE · **Type:** Discuss · **Light:** 🔴 RED-OK · **Checkpoint:** FINAL · **Blocked by:** #60, #61, #62

**Build:** A hands-on table walkaround for the evals and a timed stage narrative. Both lead with Chhaya Route and the body model, cite #60's numbers, and show the verification features (provenance chips, 7/7, 5/5, Chain OK) as the honesty discipline.

**Watch out:** every spoken number must match the metrics table.

**Test:** dry-run each format once for someone who hasn't seen the project.

**Done when:** both formats are rehearsed and every claim traces to a number or a stated design decision.

### #65: Real-worker feedback

**Priority:** 🟨 SUPPORTING · **Type:** Discuss · **Light:** ⚪ PRE-BUILD · **Checkpoint:** FINAL · **Blocked by:** —

**Build:** One or two short conversations with delivery riders (venue deliveries count): would they take a 2-minute shaded detour, where do they find water, have they felt heat sickness on shift? Quote them on stage.

**Done when:** at least one real rider quote is in the pitch, or the caveat is stated.

## Future work (out of scope for the finale)

1. **Field validation against core-temperature pills** with South Asian riders and construction workers, women and older workers.
2. **A body-model dataset** from consenting riders to fit k\_T, k\_D and the stop transient properly.
3. **City-wide Chhaya map** from thousands of riders, with GNSS-validated building models.
4. **Platform integration** so shaded routes and water stops appear inside delivery apps.
5. **Parametric heat insurance** backed by hash-chained personal exposure logs.
6. **Commercial ECTemp licence** through the Defense Health Agency before any product release.
7. **Native-speaker review** of every spoken line, and a full co-design cycle with workers' unions.

*This build map is a companion to Thakaan — Product Bible v3. The bible explains why; this map says what to build, in what order, how to test each piece, which light window and checkpoint it belongs to, and what is future work. When they disagree, the bible is the source of truth on intent; follow this map for execution.*
