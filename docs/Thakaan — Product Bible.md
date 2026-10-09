# Thakaan — Product Bible

Oct 10, 2026 · @Ashish

## Vision

Thakaan turns the phone an outdoor worker already carries into a personal heat-strain guardian: it senses the body, the brain and the sun, and steers the worker to shade and water before heat takes them down. Everything runs on the iQOO 15, offline, with no wearable.

- **One-liner:** A phone that feels your heat strain and routes you to shade before you collapse.
- **Tagline:** *Garmi se pehle, Thakaan.* (Before the heat gets you.)
- **Name:** Thakaan (थकान) means exhaustion; the product exists so the worker never reaches it.
- **Sub-product:** Chhaya Route (छाया, shade), strain-aware routing to shade and water.
- **Platform:** iQOO 15 (Snapdragon 8 Elite Gen 5, Hexagon NPU, Adreno 840), OriginOS 6 / Android 16, Kotlin.

## Problem and users

Heat stroke attacks judgement before the body gives up, so the people most exposed keep working until they collapse. Those people are paid per task, carry a phone all day, and do not own a heat-strain wearable.

- **What fails today:** City-level heat alerts say nothing about one person. Wearables (chest straps, patches, smartwatches) cost too much and are uncomfortable in humid heat. Platform rest points exist, but workers skip them because rest costs earnings.
- **The gap Thakaan fills:** a personal, real-time heat-strain signal plus an action that fits the job: a shaded route, a water stop, a rest at the right moment.

| User | Where the phone lives | What they need |
| --- | --- | --- |
| Delivery rider (scooter / motorcycle) | Handlebar mount, screen facing rider | Strain warnings at stops, shaded routes, water stops on the way |
| Construction worker | Pocket, put down at breaks | Break-time check-ins, rest pacing, collapse alarm |
| Traffic police, street vendors, farm workers | Pocket or hand | Exposure dose, rest prompts, collapse alarm |
| Site supervisor / fleet lead | Laptop + phone | Crew-level risk view without raw health data |

## Product principles

These invariants hold for every feature; a change that breaks one needs a team decision.

1. **Phone only.** No wearable, no external sensor, no cloud dependency for the core loop.
2. **On-device, offline.** All sensing, models, risk scoring, routing and alerts run on the phone. Network is used only to sync the shade map and optional weather.
3. **Strain, not temperature.** The UI shows strain levels (Cool, Warming, Strained, Danger), never a body-temperature number.
4. **Sense when the body is still.** Camera and voice checks fire at stops, drop-offs and breaks; motion sensors run continuously.
5. **Prevention over detection.** The best alert is a shaded route or a rest taken early, not an alarm at collapse.
6. **The worker owns the data.** Raw physiology never leaves the phone; only alerts the worker chooses to share are sent.
7. **Speak the worker's language.** Every instruction is spoken in Kannada, Hindi, Tamil, Telugu or English, short and actionable.
8. **Earnings-aware.** Every intervention shows its time cost; detours are capped by current strain.
9. **Never block the job.** Navigation and the delivery flow always stay usable; Thakaan sits on top, not in the way.
10. **Fail safe.** If a sensor is unavailable, the score falls back to exposure and time, never to "all clear".

## System architecture

Five sensor groups feed three detection layers; one strain score drives every action, all on the phone.

&#91;embedded content: Thakaan architecture · sensors to actions\]

E1 light readings also flow into the shared shade map that Chhaya Route uses, so every rider's ride improves everyone's routes.

## Body model (physiological detection)

Thakaan simulates the worker's body heat balance every 30 seconds and corrects the simulation with every heart-rate reading. Fingertip PPG and chest SCG are the primary heart channels; the front camera is optional.

### Core idea: a physics prior corrected by heart rate

- **Process model (prediction between readings):** a Gagge two-node thermoregulation model steps core and skin temperature forward every 30 s. A simplified ISO 7933 Predicted Heat Strain (PHS) model gives sweat rate and water loss.
- **Model inputs:** metabolic rate from activity (E6; riding about 1.5 to 2.5 MET, walking and lifting from the Pandolf equation and MET tables), air temperature and humidity (E9), mean radiant temperature from solar load (E1, E3), air speed as apparent wind (GNSS speed + forecast wind), clothing and helmet insulation (onboarding + clothing factor).
- **Filter state:** core temperature, hydration deficit (% body mass), acclimatisation level, personal HR offset.
- **Observation model (correction at each reading):** measured HR is explained as workload HR plus a thermal term, a dehydration term, a posture term and a stop-transient term.

```latex
HR_{obs} = HR_{work}(M) + k_T\,(T_c - T_{c,0}) + k_D\,D + \Delta HR_{posture} + \Delta HR_{stop}(t) + b_{person} + \varepsilon
```

T\_c = core temperature, D = hydration deficit (% body mass), M = metabolic rate. Literature priors, refined per person: k\_T about 15 to 18 bpm per °C (range 10 to 25), k\_D about 4 to 5 bpm per 1% body-mass loss (range 3 to 8).

- **Hydration state:** PHS sweat loss minus logged drinks (E8) gives the running hydration deficit; each 1% deficit also adds about 0.15 to 0.20 °C to the core prior.

### Heart-rate channels

| ID | Channel | Sensor | Method | When | Outputs |
| --- | --- | --- | --- | --- | --- |
| P2 | Fingertip PPG (primary) | Rear camera + flash | Red-channel contact PPG, 30 to 60 s, peak detection, Viterbi tracking, perfusion-index gate | Delivery hand-over, breaks, "tap to check", prompted stops | HR, RMSSD, PPG amplitude, pulse shape, respiration |
| P3 | Chest SCG (Site primary) | Accelerometer + gyroscope, high rate | Band-pass, gyro-referenced adaptive cancelling, 1D-CNN denoiser on NPU, AO-peak detection | Phone in chest pocket, worker still | HR, beat intervals |
| P1 | Face rPPG (optional) | Front camera | Locked Camera2 pipeline (AE lock, 10 ms exposure multiples, AWB off, linear tone curve, NR / edge / stabilisation off, YUV at binned 720p, 30 fps), cheek + forehead mesh ROI, background illumination cancelling, IMU motion cancelling, POS + EfficientPhys (INT8, NPU), Viterbi tracking, SNR and kurtosis gates | Red lights, when the face is clear | HR, respiration |

### Body vectors

| ID | Vector | Source | Method | Output |
| --- | --- | --- | --- | --- |
| P4 | Respiration rate | P2 pulse envelope, P3 low band, mic breath envelope | Respiratory-induced amplitude and frequency variation, 0.15 to 0.40 Hz | Breaths per minute |
| P5 | Core-temperature trend | Physics prior + HR filter | EKF / UKF with Gagge process model and the observation model above | Core trend + uncertainty band (internal) |
| P6 | Physiological Strain Index | P5 + HR | Moran PSI against personal resting baselines | PSI 0 to 10 |
| P7 | HR recovery (Site only) | P2 / P3 | 60 s and 120 s drop after vigorous work (lifting, stairs); not used after riding | Recovery score |
| P8 | Sweat and flush cues (optional) | Front camera | Skin-shine and redness index vs baseline | Flush / heavy-sweat / dry-skin flag |
| P9 | Excess HR | P2 / P3 / P1 + E6 | Measured HR minus HR expected for current workload, posture and stop phase | Thermal + drift HR (bpm) |
| P10 | Idle-state HR drift | Repeated standard states | HR at the same state (seated idle at a stop, seated break) at the same time-since-stop, compared with the morning value | Drift (bpm) over the shift |
| P11 | HRV (RMSSD) | P2 (30 s minimum, 60 s preferred), P3 | Time-domain RMSSD vs personal baseline; windows under 30 s rejected | Vagal-withdrawal score |
| P12 | PPG amplitude and perfusion | P2 | Pulse amplitude and perfusion index vs baseline (cutaneous vasodilation) | Peripheral-perfusion change |
| P13 | Sit-to-stand HR rise | P2 | Seated 30 s, stand, HR rise at 30 to 60 s vs baseline; exaggerated rise flags low plasma volume | Dehydration check |
| P14 | Forehead thermistor | Phone's own temperature sensors | Press phone to forehead; heat-transfer curve from the device thermistor (FeverPhone method); only when the phone itself is cool | Independent skin / core temperature reading |
| P15 | Hydration deficit | PHS model + E8 + P13 | Model state, corrected by sit-to-stand and HR drift | % body mass |
| P16 | Acclimatisation state | Shift history | Builds over 10 to 14 days of heat work; HR adaptation decays about 2.3% per day off | Lowers expected HR and core prior |

### Measurement protocols that remove confounders

- **Stop transient:** when a rider stops, airflow stops, skin heats and HR jumps within seconds. Thakaan models this transient with a per-rider template and compares HR only at the same time-since-stop.
- **Posture:** dismounting adds a 10 to 20 bpm orthostatic rise for 30 to 60 s. The IMU tracks time since standing, and the posture term corrects for it; readings in the first 30 s after a posture change are down-weighted.
- **Standard states:** P10 compares like with like: seated idle with seated idle, seated break with seated break.
- **HRR scope:** HR recovery is used only after vigorous site work, because riding is too low-intensity for a meaningful recovery curve.

### Personalisation

- **Onboarding:** age, sex, weight, height, clothing type, helmet type, medicines that blunt HR (for example beta-blockers), caffeine and smoking habits.
- **HRmax:** 208 − 0.7 × age (Tanaka).
- **Medicine flag:** HR-based channels are down-weighted; brain and exposure channels carry more weight.
- **Cool-morning baseline:** resting HR, RMSSD, PPG amplitude, sit-to-stand rise and idle-state HR at the start of each shift.
- **Daily 3-tap check-in:** slept well, ate, feel unwell. Each answer shifts the priors.
- **Online learning:** k\_T, k\_D, the stop-transient template and the personal offset update from each worker's own history.

### Quality gates

- **Innovation test:** each HR observation is checked against the filter's prediction (normalised innovation squared); outliers are rejected.
- **Rate limit:** HR changes faster than physiologically possible between readings are rejected.
- **Cross-source agreement:** when two channels (P2 + P3, or P2 + P1) are available, they must agree before the reading counts at full weight.
- **Signal quality:** perfusion index for fingertip; SNR above −4 dB and kurtosis for camera; motion energy for SCG.
- **Stimulant flag:** recent caffeine or nicotine (check-in) widens the observation noise.
- **Fallback:** with no fresh HR, the physics prior keeps running and the uncertainty band widens.

## Detection vectors: neurological and behavioural

Heat stroke is a brain emergency, so Thakaan watches directly for the brain slowing down; these vectors cover the high-strain zone where heart-rate models flatten out.

| ID | Vector | Sensor | Method | When it runs | Output |
| --- | --- | --- | --- | --- | --- |
| N1 | Gait instability | Accelerometer + gyroscope | Step detection, stride-time variability, autocorrelation peak height, lateral sway dispersion vs personal baseline | Whenever the worker walks (drop-off, site) | Gait instability score |
| N2 | Riding smoothness | Handlebar IMU (gyro yaw, lateral accel) | Approximate entropy of steering yaw, correction magnitude, lane-weave and braking-jerk features with road-curvature removed | Continuously while riding | Riding control score |
| N3 | Speech check | Microphone | 5 s spoken code; articulation rate, pause ratio, slur index via Whisper-small alignment, jitter, shimmer, cepstral peak prominence vs baseline | At stops and breaks, on prompt or on escalation | Speech impairment score + dehydration voice cue |
| N4 | Reaction-time test | Touchscreen | 5 s tap-the-target game: reaction time, miss rate, tap scatter | At stops and breaks | Psychomotor score |
| N5 | Passive interaction drift | Touchscreen, app events | Tap accuracy on app buttons, typing speed and error rate, repeated or missed actions | All day, no prompt | Interaction drift score |
| N6 | Confusion markers | GNSS + app state | Wrong-turn rate vs active route, circling, long unexplained stops in sun, repeated delivery-step errors | All day | Confusion flag |
| N7 | Collapse detection | Accelerometer + gyroscope + GNSS | Free-fall / impact signature, then no movement; or bike tip-over signature | Continuously | Collapse event |
| N8 | Eye and face fatigue | Front camera | Blink rate, eyelid-closure ratio, gaze drift, yawning | With P1 when the face is clear | Facial fatigue score |
| N9 | Hand tremor | Accelerometer while phone is held | 4 to 12 Hz tremor band power | Whenever the phone is in hand | Tremor index |

- **Confirmation rule:** a high physiological score plus any brain vector (N1 to N6, N8, N9) escalates faster than either alone.
- **Baselines:** every N-score is personal, compared with the worker's own cool-morning values.

### Brain-detection logic

- **Exposure-conditioned:** brain flags escalate only when the exposure prior is meaningfully high, so a tired cool morning does not trigger heat alerts.
- **Same-time baselines:** each N-score is compared with the worker's own score at the same time of day on cool days. N2 also keeps per-road-segment baselines, since riders repeat streets.
- **Change detection:** CUSUM / SPRT on robust z-scores (median and MAD) instead of fixed thresholds.
- **Independent families:** confirmation needs agreement across sensor families: motion (N1, N2, N7, N9 count as one), voice (N3), touch (N4, N5), face (N8), navigation (N6).
- **Tap test:** 5 s micro-tests are pooled across the day; a 30 s test runs only when something is flagged.
- **Speech check:** a fixed digit code with forced alignment (not free transcription), so it works across Kannada, Tamil, Telugu, Hindi and English. Gated on SNR; jitter and shimmer are dropped when noise is high.
- **Squint correction (N8):** sun squint is separated from eyelid-closure fatigue using ambient light (E1) and eye-aperture shape.
- **Collapse (N7):** detects falls, bike tip-overs and slow slumps or sit-downs followed by stillness. A spoken "say haan" countdown runs before the siren.
- **Symptom vector (N10):** one-tap or voice symptoms: thirst, headache, dizziness, nausea, cramps, and a 1 to 10 effort rating. Asked at every check and at each level change.

## Detection vectors: environmental and exposure

Exposure is the backbone of the score: it is always available, and it drives both the strain prior and the Chhaya shade map.

| ID | Vector | Sensor | Method | Output |
| --- | --- | --- | --- | --- |
| E1 | Sun / shade / overcast state | Ambient-light sensor + colour-temperature sensor | Lux level + spectral (CCT) signature, mount-angle correction from gravity vector, rolling-window classifier | State per second, solar-load estimate |
| E2 | Cumulative sun dose | E1 over time | Minutes in direct sun, weighted by time of day and solar elevation | Sun dose for the shift |
| E3 | Device heat soak | Battery + thermal-zone temperatures, `getThermalHeadroom()` | Subtract the phone's own compute heat model; residual tracks ambient and radiant load | Ambient heat proxy |
| E4 | Sky and surroundings | Rear camera (on demand) | Sky-view fraction, tree / building / open-road segmentation on NPU | Shade context, open-sky flag |
| E5 | Surface and road type | Camera + GNSS + map | Asphalt vs concrete vs canopy-covered road from map and segmentation | Radiant-heat factor per segment |
| E6 | Activity and workload | IMU + GNSS speed | Riding, walking, climbing stairs, lifting, idle; metabolic-equivalent estimate | Work intensity over time |
| E7 | Work / rest ledger | E6 + app state | Time since last real rest (shade + still), rest length, rest location | Rest debt |
| E8 | Hydration log | Voice ("pani piya"), one-tap button, water-stop arrivals | Logs drinks; estimates sweat loss from workload, sun dose and heat proxy | Hydration debt |
| E9 | Weather and heat-island layer | Cached forecast + satellite land-surface temperature tiles | Synced when online, cached for the day | Air temperature, humidity, heat-island map |
| E10 | Acclimatisation | Shift history on device | Days worked in heat over the last 14 days | Acclimatisation factor |
| E11 | Time-of-day risk | Clock + solar position | Solar elevation, peak-heat window (12:00 to 16:00) | Time risk multiplier |

- **Crowdsourcing:** E1 readings are tagged to map-matched road segments and contribute to the shared shade map (see Chhaya Route).

### Added exposure vectors

| ID | Vector | Sensor | Method | Output |
| --- | --- | --- | --- | --- |
| E12 | GNSS sky view | GnssStatus + GnssMeasurement (full tracking, 1 Hz, L1 + L5) | Per-satellite LOS probability from an on-device XGBoost classifier (C/N0, ΔC/N0, elevation, Doppler residual, L1−L5 C/N0 gap); satellites binned into 10° sky cells; Bayesian update over a 5 to 10 s window; cosine-weighted sky-view factor | Sky-view factor 0 to 1, sun-direction blocked or clear |
| E13 | Shade type | E12 | C/N0 drop + variance: canopy (moderate drop, high scintillation) vs building or flyover (sharp drop) vs open sky | Tree shade / structural shade / open |
| E14 | Body-shadow mask | E12 + heading | Learns the C/N0 loss behind the rider by relative azimuth and compensates, so the rider's body is not read as shade | Per-device gain mask |
| E15 | Solar irradiance | E1 | Lux converted to approximate W/m² after a saturation check; fused with E12 sun-direction state | Shortwave load (W/m²) |
| E16 | Black-globe proxy | E3 | Phone heat-up rate in sun, after subtracting its own compute heat | Radiant heat estimate, mean radiant temperature |
| E17 | Apparent wind | GNSS speed + forecast wind | Vehicle speed as airflow; drops to near zero at stops | Air speed for the heat model |
| E18 | Heat index for standards | E9 + E15 + E16 + E17 | WBGT and UTCI computed from local inputs | WBGT, UTCI, tied to published thresholds |

- **Sun-direction check:** if satellites near the sun's azimuth and elevation are blocked, the rider is likely in the sun's shadow. This cross-checks the light sensor and the computed shadow layer (the shadow-matching principle).

## Risk engine: Thakaan Strain Score

The Thakaan Strain Score (TSS, 0 to 100) fuses exposure, body and brain into one number per minute, plus a forecast of minutes until the next level.

**Three layers**

1. **Exposure prior.** E2, E3, E6, E7, E8, E9, E10, E11 give a baseline strain expectation for this worker right now. Always computed, so the score never goes blank.
2. **Body update.** P5 and P6 (core-temp trend and PSI) shift the prior up or down, weighted by how recent and how clean the last heart-rate reading was. P7 and P8 add modifiers.
3. **Brain override.** N1 to N9 raise the score directly; any two brain vectors above threshold force at least Strained.

```latex
TSS = 100\cdot\sigma\big(w_E\,E + w_B\,B\,q + w_N\,N + b\big)
```

E = exposure prior, B = body strain (PSI-based), q = freshness-and-quality weight of the last HR reading, N = brain impairment, σ = logistic. Weights are fitted on the device to the worker's history and capped by safety floors.

| Level | TSS | Meaning | Default action |
| --- | --- | --- | --- |
| Cool | 0 to 29 | Normal | Normal routing; passive logging |
| Warming | 30 to 54 | Strain building | Nudge to drink; Chhaya Route allows small detours |
| Strained | 55 to 79 | High strain | Water stop routed next; voice + tap check at next stop |
| Danger | 80 to 100 | Brain or body red flags | Stop now: route to nearest shade, alert contact, supervisor ping |
| Collapse | N7 event | Fall + no movement | Siren, SMS + call to contact and 108 with location |

- **Forecast:** a small temporal model (GRU on NPU) projects TSS 15 and 30 minutes ahead from the current trend and the upcoming route's sun exposure.
- **Uncertainty:** each score carries a confidence band; the UI shows it as a soft halo around the gauge.
- **Explainability:** every level change records its top three drivers ("2 h without shade, HR recovery slow, tap test slower") for the LLM to speak.

### Decision-layer upgrades

- **Anchored levels:** each level is tied to physiological and environmental anchors, not only TSS cut-offs. For example: Warming = core trend rising or PSI above 3 or WBGT above the work-rest action limit; Strained = PSI above 5, excess HR above the personal threshold, or hydration deficit above 2%; Danger = PSI above 7, two brain families flagged, or a severe symptom.
- **Calibrated score:** TSS is calibrated (isotonic or Platt scaling) so the same number means the same risk across workers and days.
- **Stability:** hysteresis and minimum dwell times stop levels from flapping; Danger never auto-clears without a passed recovery check.
- **Symptom weight:** N10 symptoms enter the fusion directly; dizziness or confusion with high exposure forces at least Strained.
- **Feedback loop:** an "alert was right / wrong" button after each alert, and voluntary rests, act as labels for personal tuning.

## Chhaya Route: strain-aware shade routing

Chhaya Route lets the rider's body set how much shade the route buys: the higher the strain, the more detour time the router will spend on shade and water.

**Shade map: four layers, fused per road segment per hour**

1. **Computed shadows.** Sun position (date, time, lat/long) ray-cast against building footprints and heights (OSM, open building-height datasets) and tree-canopy polygons, precomputed per segment for every 30 minutes of the day.
2. **Crowdsourced light map (the Thakaan layer).** Every rider's E1 readings, map-matched to segments with an HMM map matcher, aggregated by hour. Bayesian update corrects the computed layer for flyovers, awnings, parked trucks and seasonal canopy.
3. **Heat-island layer.** Satellite land-surface temperature tiles mark hot zones (open asphalt, no canopy).
4. **Surface layer.** Road surface and width from map + E5.

**Fifth layer: GNSS sky view.** Every rider's E12 to E14 readings add a radio view of the sky to each segment, separating tree shade from building shade. Over time, thousands of satellite rays also validate the 3D building model used for computed shadows (3D-mapping-aided GNSS).

**Aggregation:** traces are map-matched to road centrelines, then binned on an H3 grid (resolution 11 to 12, about 10 to 25 m cells) in 1-hour slots. Each cell keeps a Bayesian shade estimate, weighted by device reliability and satellite count.

**Water and cooling stops**

- OSM drinking-water points, shops, tea stalls, fuel stations and platform rest points.
- Rider tags by voice: "pani yahan milta hai" (water here) or "chhaya, baith sakte hain" (shade, can sit) pins a stop with type and hours.
- **Thakaan Pyaau network:** shops opt in with a QR sticker; a scan logs a visit, confirms the stop is live, and ranks it.

**Routing engine (on device)**

- Offline graph (GraphHopper or Valhalla on Android) for the rider's city, with custom per-edge weights.
- Edge cost:

```latex
C_e = t_e\,\big(1 + \lambda(TSS)\cdot S_e(h)\big) + \mu\,H_e
```

t = travel time, S = sun-exposure fraction at hour h, H = heat-island penalty, λ rises with TSS. At Cool, λ ≈ 0 (fastest route).

- **Detour budget:** the router caps extra time by level (for example Warming +1 min, Strained +3 min, Danger: nearest shade regardless).
- **Forced water stop:** at Strained, the route inserts the best water stop within the budget.
- **Rest-point routing:** at Danger, the destination becomes the nearest cool, seated stop.

**Extra routing features**

- **Shift planner:** before a shift, a forecast of the safest hours and zones based on the day's heat and the worker's past strain.
- **Shade ETA:** every route shows "+2 min, 70% shade" so the cost is always visible.
- **Return-trip choice:** after a drop-off, pick the shadier way back when there is no time pressure.
- **Walking legs:** drop-off walks (building to door) also prefer the shaded side.
- **Navigation hand-off:** works as its own map or as a turn-by-turn overlay on top of the delivery app.

## Interventions and alert ladder

Every alert names one action, says it in the worker's language, and shows what it costs in time.

| Level | Haptic | Voice (example, Hindi) | Screen | Other |
| --- | --- | --- | --- | --- |
| Warming | 2 soft pulses | "Paani pee lo, agle signal pe." (Drink water at the next signal.) | Amber ring on gauge | Water stop suggested |
| Strained | 3 strong pulses, repeat in 2 min | "Shareer garam ho raha hai. 3 minute aage chhaya aur paani hai." (Your body is heating up; shade and water 3 minutes ahead.) | Orange full-width banner with route | Chhaya Route switches on; speech + tap check at next stop |
| Danger | Continuous pattern until acknowledged | "Abhi ruk jao. Chhaya mein baitho, paani piyo." (Stop now. Sit in shade, drink water.) | Red takeover with nearest shade | Contact + supervisor alert; cooling coach starts |
| Collapse | Siren + flash | Loud bystander message: "Is vyakti ko garmi lagi hai. 108 ko call karein." (This person has heat illness; call 108.) | First-aid steps for bystanders | SMS with location to contact and supervisor; auto-call 108 after countdown |

**Intervention features**

- **Cooling coach:** guided 10-minute recovery: shade, drink, wet cloth on neck, fan; re-check HR and tap test at the end; clears the level only when recovery passes.
- **Bystander mode:** on collapse, the lock screen shows the worker's emergency contact, blood group (if entered) and heat first-aid steps in the local language.
- **Smart snooze:** "I'm at a drop-off" snoozes non-danger alerts until the next stop, never at Danger.
- **Earnings view:** shows what an early 10-minute rest costs versus an unplanned stop later in the shift.
- **Daily heat report:** end-of-shift summary: sun dose, rests taken, water logged, peak strain, spoken in the worker's language.
- **Proof of exposure:** a signed, on-device log of heat dose per shift the worker can export to back a heat-bonus, insurance or welfare claim.

## Worker modes: Rider and Site

Thakaan detects where the phone is and switches modes, so the same app serves a rider on a handlebar and a labourer with a phone in his pocket.

**Mode detection:** IMU vibration spectrum (engine and road bands), mount orientation, GNSS speed and proximity sensor pick Rider (mounted), Pocket, In-hand or Desk. The worker can also pick a mode once at sign-up.

| Feature | Rider Mode (handlebar) | Site Mode (pocket) |
| --- | --- | --- |
| Heart rate | P2 fingertip PPG at every hand-over and prompted stop; P1 face rPPG optional at red lights; P10 idle-state drift | P3 chest SCG when still; P2 fingertip + P13 sit-to-stand at every break; P7 recovery after heavy work |
| Brain vectors | N2 riding smoothness (continuous), N1 gait at drop-offs, N3 / N4 at stops, N8 at stops | N1 gait (continuous), N3 / N4 at breaks, N9 tremor |
| Exposure | E1 continuous (sensor faces the sky), E6 riding load | E1 at breaks and when phone is out, E6 lifting / climbing load |
| Interventions | Chhaya Route, water-stop insertion, voice prompts through phone or helmet earphone | Break scheduler (work/rest ratio by TSS), shade-spot finder on site |
| Stop detector | Speed ≈ 0 + engine-idle vibration + face in frame triggers a 15 s capture burst | Still + upright + break time |
| Mount setup | Guided mount-angle calibration so the face is in frame | Chest-pocket placement guide for SCG |
| Thermal care | Device heat-soak guard: dims, duty-cycles NPU, suggests a mount sunshade | Not needed |

**Site Mode extras**

- **Crew roll-call:** one tap at break start; each worker gets a fingertip check and a tap test in under 30 seconds.
- **Rotation advisor:** suggests who swaps to a shaded task next, from crew TSS.
- **Buddy pairing:** two workers paired; if one hits Danger, the buddy's phone vibrates and shows where they are.

**Rider Mode extras**

- **Red-light check:** the 15 s face capture runs silently at signals; nothing to press.
- **Drop-off walk check:** gait is scored on every walk from bike to door.
- **Helmet audio:** voice prompts route to Bluetooth earphones when paired.

## Supervisor console and Office Kit

The supervisor sees crew risk levels and alerts, never raw heart rate or voice; the laptop console runs over vivo Office Kit and a local mesh.

**Thakaan Console (laptop)**

- Live crew board: name, level (Cool to Danger), trend arrow, minutes since last rest, sun dose.
- Map of riders or site zones coloured by level, with shade and water stops overlaid.
- Alert feed: Danger and Collapse events with location and the three spoken drivers.
- Shift heat report: crew sun dose, rests taken, alerts, export to PDF.
- City shade map viewer: the crowdsourced Chhaya layer by hour, for planners and NGOs.

**Office Kit integration**

| Office Kit surface | Thakaan use |
| --- | --- |
| Screen mirror + remote control | Demo and supervisor view of the phone app on the laptop; drive it from the keyboard |
| Shared clipboard | Clipboard protocol: the console writes a sealed request ("crew status"), the phone answers with a signed level summary |
| File transfer | Daily heat reports, proof-of-exposure logs, shade-map tiles and model files move phone to laptop and back |
| Notification sync | Danger and Collapse alerts appear on the laptop instantly |
| Camera share | Phone camera as the console webcam for the cooling-coach check-in |

**Crew mesh (no internet)**

- Phones on one site form a BLE / Wi-Fi Aware mesh; levels and alerts hop phone to phone to the supervisor's phone.
- Buddy alerts and collapse SMS fall back to the mesh when mobile data is down.

## On-device AI and runtime stack

Small vision, signal and audio models run on the Hexagon NPU through QNN; the language model runs on the Adreno GPU through LiteRT-LM.

| Component | Model / method | Runtime | Compute unit |
| --- | --- | --- | --- |
| Face detection + mesh | MediaPipe Face Landmarker | MediaPipe / LiteRT | NPU / GPU |
| rPPG | PhysFormer / EfficientPhys-class model, POS fallback | LiteRT + QNN delegate | NPU |
| SCG denoiser | 1D-CNN | LiteRT + QNN | NPU |
| Activity + mode classifier | 1D-CNN on IMU windows | LiteRT + QNN | NPU |
| Gait + riding features | ApEn, stride variability, sway (Kotlin DSP) | JVM / NDK | CPU |
| Speech check | Whisper-small (alignment, rate), DSP for jitter / shimmer / CPP | ORT + QNN EP | NPU |
| Sky / shade segmentation | Lightweight segmentation (YOLO-seg class) | LiteRT + QNN | NPU |
| Core-temp filter | ECTemp EKF (sigmoid model) | Kotlin | CPU |
| TSS fusion + forecast | Logistic fusion + small GRU | LiteRT | NPU |
| Explainer + coach | Gemma 4 E2B | LiteRT-LM | GPU |
| Voice out | Android TextToSpeech offline voices (hi, kn, ta, te, en) | Android | CPU |
| Voice in (tags, "pani piya") | Android on-device recognizer / Whisper-small | Android / ORT-QNN | NPU |
| Routing | GraphHopper / Valhalla offline graph with custom weights | NDK | CPU |
| Map matching | HMM map matcher | Kotlin | CPU |

**Runtime architecture**

- **Sensor hub:** a foreground service (types: location, camera, microphone, health) owns all sensors and publishes timestamped streams.
- **Stop scheduler:** an event-driven state machine (Moving, Stopping, Stopped, Walking, Resting) that wakes the camera, NPU and mic only in the right states.
- **Inference engine seam:** every model sits behind one `InferenceEngine` interface with QNN, GPU and CPU back ends; a Diagnostics screen shows "N/N ops on NPU" for each.
- **LLM guardrails:** the LLM only phrases what the risk engine decided; its instruction comes from a fixed action set and it cannot change the level.
- **Thermal governor:** reads thermal headroom, lowers frame rates and duty cycles, and pauses non-critical models first.
- **Storage:** encrypted on-device time-series store (Room + SQLCipher) for baselines, shift logs and the shade-map tiles.

### Added components

| Component | Model / method | Runtime | Compute unit |
| --- | --- | --- | --- |
| Heat-balance prior | Gagge two-node model + simplified ISO 7933 PHS | Kotlin | CPU |
| Body filter | EKF / UKF over core temp, hydration, acclimatisation, offset | Kotlin | CPU |
| Fingertip PPG | Red-channel DSP, Viterbi tracker, perfusion index | Kotlin / NDK | CPU |
| Face rPPG (optional) | POS + EfficientPhys INT8, locked Camera2 pipeline | LiteRT + QNN | NPU |
| LOS / NLOS classifier | XGBoost on GNSS features | Small tree model in Kotlin | CPU |
| Sky-view estimator | 10° sky bins, Bayesian update | Kotlin | CPU |
| WBGT / UTCI | Standard equations from local inputs | Kotlin | CPU |
| Change detection | CUSUM / SPRT on robust z-scores | Kotlin | CPU |
| Speech code check | Forced alignment on a fixed digit code | ORT + QNN | NPU |
| Score calibration | Isotonic / Platt scaling | Kotlin | CPU |

## Data, privacy, consent

Health data stays on the phone; the only things that ever leave are anonymised shade readings and alerts the worker chooses to share.

| Data | Stored where | Leaves the phone? |
| --- | --- | --- |
| Camera frames, audio clips | RAM only, discarded after inference | Never |
| HR, HRV, core-temp trend, brain scores | Encrypted on device | Never |
| TSS level + alerts | On device | Only to contacts / supervisor the worker approved |
| Light readings on road segments | On device, then batched | Yes, anonymised, no identity, time-bucketed, location snapped to segments |
| Water / shade tags | On device, then synced | Yes, as public map points |
| Proof-of-exposure log | On device, signed with StrongBox key | Only when the worker exports it |

- **Consent screens:** separate, plain-language consents for camera checks, voice checks, shade sharing and supervisor alerts; each can be switched off.
- **No penalty use:** the console shows levels, never scores per minute or raw signals, so data cannot be used to rate or deactivate workers.
- **DPDP Act 2023:** purpose-limited processing, deletion on request (one tap "erase my data"), consent logs on the device.
- **Framing:** a heat-strain warning and wellbeing tool, not a medical diagnosis.

## UX flows and screens

The home screen is one big strain gauge a rider can read in half a second at a red light.

**Onboarding (5 minutes, once)**

1. Language pick (voice-first), mode pick (Rider / Site).
2. Consents, one per feature.
3. Emergency contact, supervisor (optional), blood group (optional).
4. Mount or pocket calibration with on-screen guide.
5. Cool baseline: 60 s fingertip HR, 5 s speech code, tap test, 30 s walk.

**Screens**

| Screen | Contents |
| --- | --- |
| Home / gauge | TSS ring coloured by level with confidence halo, forecast "Strained in \~20 min", sun dose, rest debt, water debt, one big action button |
| Chhaya map | Route with shaded segments highlighted, water / shade pins, "+2 min, 70% shade" chip |
| Quick check | Fingertip or face capture with live pulse waveform, then speech code, then tap game (under 25 s total) |
| Alert takeover | Level colour, one instruction, route to shade, "I'm resting" button |
| Cooling coach | 10-minute timer, steps, re-check at the end |
| Shift report | Sun dose, peak level, rests, water, spoken summary, export proof-of-exposure |
| Diagnostics | Sensor health, "N/N ops on NPU" per model, thermal headroom, last HR quality |
| Settings | Language, contacts, consents, erase data |

**Design rules**

- Colour plus shape plus words for every level (works in glare and for colour-blind users).
- Text at least 20 sp on the gauge; everything readable at arm's length in sunlight.
- Voice-first: every screen can be answered by a spoken "haan" / "nahi" (yes / no).
- Dark-on-light high-contrast theme for direct sun.

## Demo script

The 3-minute demo runs live on stage for body and brain checks, and uses a clearly labelled 20x replay of a real recorded ride for the shift story.

| Time | On stage | On the projector (Office Kit mirror) |
| --- | --- | --- |
| 0:00 to 0:20 | Hook: "Heat stroke shuts down your judgement before your body. Riders don't feel it coming." | Title + Bengaluru heat photo |
| 0:20 to 0:50 | Teammate sits, phone on a stand: face check, then fingertip check | Live pulse waveform, HR, "N/N ops on NPU" |
| 0:50 to 1:20 | Teammate does 60 s jumping jacks; check again | HR jumps, gauge moves Cool to Warming |
| 1:20 to 2:00 | Replay: recorded ride at 20x, labelled "Replay, 20x" | Shade map fills in, sun dose climbs, TSS rises to Strained, Chhaya Route reroutes with a water stop, Hindi voice prompt |
| 2:00 to 2:25 | Teammate wobbles a walk, slurs the speech code, misses taps | Brain vectors light up, level jumps to Danger, red takeover |
| 2:25 to 2:40 | Teammate lies down still | Collapse alarm, bystander screen, SMS preview |
| 2:40 to 2:50 | Airplane mode on, repeat one check | Still works offline |
| 2:50 to 3:00 | Close: "Every rider already has the sensor. Thakaan gives it a conscience." | Supervisor console + shade map |

**Demo prep checklist**

- [ ] Record a real 30-60 min ride with light, GNSS and stop logs for the replay
- [ ] Pre-load shade tiles and offline graph for the venue area
- [ ] Warm up all models 2 minutes before the pitch
- [ ] Phone stand, bright lamp for the sun/shade switch
- [ ] Phone cool, case off, not charging before the pitch
- [ ] Backup video of the full demo

## Pitch narrative and judge Q&A

The pitch argues three things: the problem is personal and invisible, the phone can sense it, and Thakaan acts before collapse rather than after.

**Story arc**

1. **Problem:** outdoor workers lose judgement to heat before they feel ill; city alerts and wearables don't reach them.
2. **Insight:** heart rate trends reveal heat strain (the US Army's ECTemp approach), and the brain's slowdown shows in gait, speech and taps. A phone can sense all three.
3. **Product:** body + brain + sun fused into one strain score, on-device, in the worker's language.
4. **Differentiator:** Chhaya Route, the first router whose detour budget is set by the rider's own body.
5. **Depth:** NPU rPPG, SCG, Kalman core-temp filter, brain biomarkers, crowdsourced shade map, offline routing, LLM coach.
6. **Impact:** riders, construction crews, traffic police; proof-of-exposure for heat bonuses and insurance.

**Likely judge questions**

| Question | Answer |
| --- | --- |
| Can you read heart rate on a moving bike? | We don't try. Thakaan measures at the moments riders are naturally still: every red light and drop-off. Between readings, the Kalman model carries the estimate forward. |
| How do you know core temperature without a thermometer? | We estimate a trend with the ECTemp Kalman filter from heart rate, the method validated by the US Army. We never show a temperature number; we show strain levels. |
| What about darker skin tones for camera heart rate? | Red-channel weighting, POS projection, bright-light capture, and fingertip PPG as a skin-tone-independent fallback. |
| Won't riders ignore longer routes? | The detour is capped by strain and shown in minutes. At Cool there is no detour at all. |
| Isn't this just a weather alert? | Weather is one of 25+ vectors. The score is personal: two riders on the same street get different levels. |
| What about privacy and employers? | Raw health data never leaves the phone. Supervisors see levels only. |
| Does it drain the battery or overheat the phone? | Event-driven sensing: camera and NPU wake only at stops, a few seconds at a time; a thermal governor manages the rest. |
| Who pays? | Platforms (fewer heat incidents, welfare compliance), insurers (parametric heat cover backed by personal exposure logs), and state labour boards. |
| Why on-device? | Construction sites and crowded roads lose signal, health data is sensitive, and alerts can't wait for a server. |

## Build map for the finale

Build in five phases, each ending in something demoable, so the pitch is safe whenever time runs out. Hours are counted from the start of the build.

**Phase 0: Skeleton (hours 0 to 3)**

- [ ] Kotlin + Compose app, foreground sensor-hub service, mode picker
- [ ] `InferenceEngine` seam with QNN / GPU / CPU back ends and the Diagnostics screen
- [ ] Home gauge with mock TSS

**Phase 1: Body (hours 3 to 12)**

- [ ] P2 fingertip PPG with live waveform, RMSSD, perfusion, sit-to-stand (P13)
- [ ] Gagge / PHS heat-balance prior + body filter (P5, P9, P10, P15); P1 face rPPG last, optional
- [ ] ECTemp EKF + PSI + recovery rate
- [ ] Quick-check screen

**Phase 2: Brain + exposure (hours 12 to 22)**

- [ ] N3 speech check, N4 tap test, N1 gait, N7 collapse detection
- [ ] E1 sun/shade classifier, E2 sun dose, E6 activity, E7 rest ledger, E8 water log
- [ ] Stop-detector state machine
- [ ] TSS fusion + levels + alert ladder with haptics and TTS

**Phase 3: Chhaya Route (hours 22 to 34)**

- [ ] Offline graph for the demo area, computed shadow layer, water / shade pins
- [ ] Strain-aware edge cost + detour budget + forced water stop
- [ ] Light-map logging and segment aggregation
- [ ] Ride recorder + 20x replay mode

**Phase 4: Coach, console, polish (hours 34 to 44)**

- [ ] Gemma 4 E2B explainer + cooling coach, multilingual voice
- [ ] Supervisor console on laptop via Office Kit clipboard + notifications
- [ ] Shift report + proof-of-exposure export
- [ ] N2 riding smoothness, N8, N9, P3, P8 if time allows

**Phase 5: Demo hardening (hours 44 to 48)**

- [ ] Rehearse the script three times, record the backup video
- [ ] Thermal and warm-up routine, offline test
- [ ] Slides: problem, insight, architecture, Chhaya, impact

## Glossary

| Term | Meaning |
| --- | --- |
| ApEn | Approximate entropy: how irregular a signal is; used on steering yaw |
| CCT | Correlated colour temperature, from the colour-temperature sensor |
| Chhaya Route | Thakaan's strain-aware shade and water routing |
| ECTemp | US Army Kalman-filter model that estimates core temperature from heart rate |
| EKF | Extended Kalman filter |
| HRV / RMSSD | Heart-rate variability / its beat-to-beat measure |
| HTP / QNN | Qualcomm Hexagon tensor processor / Qualcomm neural network runtime |
| POS | Plane-orthogonal-to-skin, a classic rPPG projection |
| PSI | Physiological Strain Index, 0 to 10 |
| Pyaau | A public water point; Thakaan's opt-in water-stop network |
| rPPG | Remote photoplethysmography: pulse from skin colour on camera |
| SCG | Seismocardiography: heartbeat from chest vibration |
| TSS | Thakaan Strain Score, 0 to 100 |

| Term | Meaning |
| --- | --- |
| C/N0 | GNSS carrier-to-noise density: satellite signal strength |
| CUSUM / SPRT | Sequential change-detection tests |
| Gagge two-node model | Core + skin heat-balance model of the body |
| H3 | Uber's hexagonal grid used to aggregate the shade map |
| LOS / NLOS | Satellite seen directly / only by reflection |
| NIS | Normalised innovation squared: outlier test for filter observations |
| PHS (ISO 7933) | Predicted Heat Strain model: sweat rate and core rise |
| UTCI / WBGT | Standard heat-stress indices |

## Additional features

This section was added after the first version of this bible. Nothing above it was changed or removed. It describes seven added features and how each one works.

### The seven additions at a glance

| # | Addition | One-line purpose | Touches in this bible | Suggested phase |
| --- | --- | --- | --- | --- |
| 1 | Truth labels: REAL / REPLAY / SIMULATED | Every number on screen says where it came from | Home gauge, Console, demo script | Phase 0, polish in Phase 4 |
| 2 | Limitations slide | Say plainly what the models cannot do | Pitch narrative, judge Q&A | Phase 5 |
| 3 | Safety-invariant test + Safety checks screen | Prove that lost or bad data never lowers the level | Product principle 10, Diagnostics screen | Phase 2, shown in Phase 5 |
| 4 | ECTemp test vectors | Prove the core-temperature filter is implemented correctly | P5 core-temperature filter, Diagnostics screen | Phase 1 |
| 5 | Hash-chained proof-of-exposure log | Make the exported heat log tamper-evident | Proof of exposure, Shift report | Phase 4 |
| 6 | Offline map bundle from OpenStreetMap | Roads, water points and rest stops for the demo city | Chhaya Route | Phase 3 |
| 7 | Optional air and rain layers | Breathing dose, rain and waterlogging, in the same app | Home screen, Chhaya Route edge cost | Stretch (after Phase 4) |

Items 1 to 5 are small and high value; 6 is already implied by the offline graph of Chhaya Route; 7 is optional and must never delay the core loop.

### 1. Truth labels: REAL, REPLAY, SIMULATED

**What it is.** Every number the app or the Console shows carries a small chip saying where it came from.

| Label | Meaning | Example in Thakaan |
| --- | --- | --- |
| REAL | Measured live, now, on this phone, or a real public data source | Live fingertip heart rate; a cached weather reading |
| REPLAY | A real recording played back later | The 20x replay of the recorded ride |
| SIMULATED | Generated for the demo, not measured | Any made-up colleagues on the crew board; any invented hotspot |

**How it works.** Add a `provenance` field (REAL, REPLAY or SIMULATED) to every reading at the sensor-hub layer. Anything computed from a REPLAY or SIMULATED input inherits that label, so a strain score driven by replayed heart rate is shown as REPLAY, never REAL. The UI renders one chip on the gauge, the forecast, the sun dose, the Chhaya ETA chip and each Console row.

**Why.** The demo script already labels the 20x replay. Making the rule universal means no judge can claim something shown as live was staged, and it makes the boundary between measured and replayed data visible instead of hidden.

### 2. Limitations slide

**What it is.** One slide, shown before the close, and a ready answer for judge Q&A, listing what Thakaan cannot do today.

- **Core-temperature estimates fail near the danger zone.** Field validations of the heart-rate method (ECTemp) report high sensitivity below about 38.75 °C and almost every hot reading missed above 39 °C, which is exactly where heat stroke begins. So every action fires at or below 38.5 °C, and there is a heart-rate rule that does not depend on the temperature estimate. Explain the levels this way.
- **Camera pulse fails in bright sun and is a spot reading,** not continuous. This is why Thakaan measures at stops and breaks.
- **Validation populations are narrow.** The underlying models were validated mostly on young, Western, often military or athletic men. No study covers South Asian workers, women, older workers, or budget phones in motion.
- **Brain-slowdown signals are not yet validated as heat-illness indicators.** Gait, riding smoothness, speech, tap and tremor vectors (N1 to N10) are personal-baseline drift signals; nobody has shown them to detect heat illness. They are presented as supporting evidence, not as a diagnosis.
- **Not a medical device.** Thakaan is a heat-strain warning and wellbeing aid.
- **Language strings need native-speaker review** before any real deployment.

**Why.** Technical judges ask "validated against what?". An honest list scores better than an overclaim, and it matches the existing framing in this bible ("a heat-strain warning and wellbeing tool, not a medical diagnosis").

### 3. Safety-invariant test and a "Safety checks" screen

**The rule.** *Losing or degrading information may never lower the strain level.* This is already Product principle 10 ("Fail safe"). This addition turns it into an automated test and a screen.

**Part A: a property test.** Run the level calculation on randomly generated shifts, then randomly remove or corrupt inputs (heart rate missing, stale, or noisy; GNSS lost; light sensor blocked; model unavailable). The test fails if any dropout produces a lower level than the same shift with full data.

**Part B: a "Safety checks" tab** on the Diagnostics screen that runs these five scenarios on the phone and shows PASS or FAIL for each:

1. No fresh heart rate: level falls back to exposure and time, never to Cool by default.
2. Stale heart rate (older than a set age): confidence halo widens and the level does not drop.
3. GNSS lost: shade state is treated as unknown, which is treated as sun, not shade.
4. Body score says Cool but two brain vectors from different sensor families are flagged: level is at least Strained (the existing brain-override rule).
5. The language model suggests a different level: the model's text is ignored; the risk engine's level stands (the existing LLM guardrail).

**Making the checks mean something.** Each scenario must also be shown to fail when the guard it tests is switched off, so a passing check cannot be an empty check. Add a hidden switch that disables each guard and confirm the matching scenario goes red.

### 4. ECTemp test vectors

**What ECTemp is.** ECTemp is a published Kalman-filter method, from US Army research (Buller et al., 2013), that estimates a person's core-temperature trend from heart rate alone. It is already named in this bible as the core-temperature filter (P5, and the runtime table's "ECTemp EKF").

**How it works.** A test vector is a fixed input with a known correct output. The team already has a file of seven reference ECTemp cases, `ectemp_vectors.json`, that earlier Python and TypeScript implementations reproduce. The Kotlin implementation in Thakaan loads the same file, runs each case, and must match the expected outputs. The Diagnostics screen shows "ECTemp vectors 7/7 pass".

**Filter settings to use** (unless a test says otherwise):

- one filter step per minute, using the median heart rate of that minute;
- starting variance 0.02, or 0.2 if the worker logs on already hot (for example fingertip heart rate above 100 at sign-in, or WBGT above 30 °C);
- action thresholds at or below 38.5 °C (38.0 °C for workers not yet acclimatised), never relying on an estimate above 39 °C.

**One thing to test explicitly.** The reference cases assume a heart-rate reading every minute. Thakaan will feed the filter readings mainly at stops. Inflate the filter's uncertainty between readings and test that behaviour rather than assuming it works.

**Why.** It turns "we implemented the published algorithm" into a checkable claim, which raises technical-depth credibility cheaply.

### 5. Hash-chained proof-of-exposure log

**What it is.** The existing proof-of-exposure log is signed with a StrongBox key. Add a hash chain: each log entry (shift, time, sun dose, rests, peak level) stores a short fingerprint (hash) of the entry before it. If anyone edits or deletes an earlier entry, every later fingerprint stops matching.

**How it works.** The Shift report export includes the chain and a small verifier. After the export moves to the laptop through Office Kit file transfer, the Console shows "Chain OK" or "Chain BROKEN". An optional demo beat: change one byte in the exported file and show "Chain BROKEN".

**Honest caveat.** A valid chain shows that entries have not changed since they were recorded. It does not prove the original observation was true. Say this in the pitch.

### 6. Offline map bundle from OpenStreetMap

**What it is.** Chhaya Route needs roads, water points, shops and rest stops for the demo area, packed to run offline.

**How it works.** Scripts fetch roads and amenities from OpenStreetMap for the Bengaluru demo area and pack them into a compact bundle that feeds the routing graph. The team already has fetch scripts of this kind to reuse. Pull at least: roads, `amenity=drinking_water`, tea stalls and shops, fuel stations, and any platform rest points.

**Label limits honestly.** OpenStreetMap cannot certify water quality. Show water points as "listed, quality unverified" until riders confirm them with the voice tags already described under Water and cooling stops.

**Licence.** OpenStreetMap data is © OpenStreetMap contributors (ODbL); show the credit in the app.

### 7. Optional air and rain layers

These two layers extend the app beyond heat. They are optional and must never delay or weaken the core strain loop. Both are off by default, and they do not change the strain level unless the team decides so later; they are shown as separate cards.

#### 7a. Breathing dose (air pollution)

**What it is.** The amount of polluted air a worker actually breathes, not the city headline number.

```latex
Dose_{PM2.5} = \sum C \times V_E \times \Delta t
```

C = PM2.5 concentration (µg/m³) from the nearest public monitoring station, V_E = minute ventilation (litres of air breathed per minute), Δt = time in each interval.

**How it works.**

- **Concentration.** The phone has no PM2.5 sensor. Use the nearest station reading from the public OpenAQ service (free key required), cached when online like the weather. Label it as the station value, not the worker's own air.
- **Ventilation.** Estimate minute ventilation from heart rate with a published pooled-data power model (Greenwald et al., 2019, built from 471 people and 14,550 measurements). It is essentially unbiased on average, but any single minute can be off by roughly 20 to 25 percent; errors average down over a shift. It was validated with measured heart rate, not phone camera pulse. Thakaan has heart rate only at stops, so between stops it estimates ventilation from the activity and workload vector E6 and labels the result as an estimate.
- **Lung capacity.** Use a typical value and label it as such until Indian reference equations are entered.
- **Output.** An "Air dose today" card on Home, and a line in the Shift report.

#### 7b. Rain and waterlogging

**What it is.** Warn a rider about a flooded road and keep the route off it.

**How it works.**

- **Rider flood report.** One tap plus a photo: "road flooded here". A photo-authenticity check runs before a report counts (for example, rejecting photos that were not just taken).
- **Known hotspots.** A list of known waterlogging spots for the demo area, marked UNVERIFIED unless taken from an official source.
- **Rain-onset cue.** The barometer pressure trend of the phone plus the cached forecast can flag likely rain. This is a local hint only, not a measurement of rainfall.
- **Honest limit.** Flood depth is not measured, because no depth-labelled photo set is available. Show "reported flooded", never a depth in centimetres. An unverified report is shown as "caution", not "clear" (uncertainty resolves toward the worker's safety).

**Extended route cost.** When these layers are on, the existing Chhaya edge cost gains two optional terms:

```latex
C_e = t_e\,\big(1 + \lambda(TSS)\cdot S_e(h)\big) + \mu\,H_e + \nu\,A_e + F_e
```

A_e = air-exposure term for the road (small weight ν, from the nearest station reading), F_e = flood term, which is very large (the road is effectively closed) for an edge with an active report until the report expires. At ν = 0 and F_e = 0 the cost is exactly the original formula.

### Build map additions

The phases above are unchanged; these tasks slot into them.

| Phase | Add |
| --- | --- |
| 0 Skeleton | `provenance` field on every reading (item 1) |
| 1 Body | Load `ectemp_vectors.json` and pass all 7 cases; show on Diagnostics (item 4) |
| 2 Brain + exposure | Safety-invariant property test (item 3, Part A) |
| 3 Chhaya Route | Bengaluru OpenStreetMap bundle (item 6) |
| 4 Coach, console, polish | Hash chain on exports and Console verifier (item 5); provenance chips everywhere (item 1) |
| 5 Demo hardening | Safety checks tab (item 3, Part B); limitations slide (item 2) |
| Stretch | Breathing dose (7a); flood report and extended route cost (7b) |

### Glossary additions

| Term | Meaning |
| --- | --- |
| REAL / REPLAY / SIMULATED | Provenance labels: measured live, recorded then played back, or generated |
| Safety invariant | The rule that losing or degrading information may never lower the strain level |
| Hash chain | Each log entry stores a fingerprint of the previous one, so editing history is detectable |
| Test vector | A fixed input with a known correct output, used to check an implementation |
| Minute ventilation (V_E) | Litres of air a person breathes per minute |
| PM2.5 | Fine particles (2.5 micrometres or smaller) that reach deep into the lungs |
| OpenAQ | A public service that publishes air-quality station readings |
| ODbL | The open licence for OpenStreetMap data; requires a credit line |
