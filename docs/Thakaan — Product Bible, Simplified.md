# Thakaan — Product Bible, Simplified

Oct 10, 2026 · @Bhaskar

## How to read this document

Thakaan is a phone app that notices when an outdoor worker is getting dangerously hot, and steers them to shade and water before they collapse. This is the same product as Product Bible v3, told twice: once in everyday words, once with the real engineering, with a bridge between the two.

Every section has three parts:

- **In plain words:** no jargon, as if explaining to a friend who has never built an app.
- **Technical view:** the real methods, numbers and names from Product Bible v3.
- **How they tie together:** which plain idea is which technical part, and why the product needs both.

### The whole product in one story

**In plain words.** A delivery rider is out on a 38 °C afternoon. His phone cannot take his temperature, so it does what a good coach would. It guesses how hot his body is from his heartbeat (checked at stops) and from the weather, sun and effort. It checks whether his hands and head are still sharp with a 30-second test. It blends everything into one score and, when the score climbs, routes him through shade to water, speaking in his own language. All of it stays on his phone.

**Technical view.** A personalised ECTemp Kalman filter, driven by a Gagge heat-balance model, estimates a core-temperature trend from fingertip PPG heart rate. A tremor + Fitts-tapping battery with CUSUM change detection watches motor control. Light-sensor and GNSS readings give radiant load and a Liljegren WBGT. A calibrated logistic fusion turns these into the Thakaan Strain Score (TSS, 0 to 100), which sets the detour budget of an on-device router (Chhaya Route). Everything runs on the iQOO 15, offline.

**How they tie together.** The coach is the strain score. Its three senses are the body model, the brain check and the exposure reading, and its one action is the shade-and-water route. Section 13 is a decoder that lists every plain term next to its technical name.

## The big idea and the problem

Thakaan (थकान, "exhaustion") exists so that the worker never reaches exhaustion. Its tagline is *Garmi se pehle, Thakaan*: before the heat gets you.

**In plain words.** Heat stroke attacks your judgement before your body gives up. That is why the most exposed workers keep going until they collapse: they do not feel it coming. They are paid per task, so resting costs money. They carry a phone all day but cannot afford a heat-monitoring wearable. City heat alerts say "it is hot today" and nothing about *you*.

Delivery riders are a special case. Sitting on a moving scooter is light work, and the wind keeps you cool. Then you stop at a red light over hot tarmac, the wind vanishes, and your body heats within seconds. Tools built for soldiers and labourers miss this pattern.

Thakaan fills the gap with a personal heat signal and an action that fits the job: a shaded route, a water stop, a rest at the right moment.

**Technical view.** The platform is the iQOO 15 (Snapdragon 8 Elite Gen 5, Hexagon NPU, Adreno 840) running OriginOS 6 / Android 16, written in Kotlin, with no wearable. Riders are low-metabolic work (1.5 to 2.5 MET) with high forced airflow while moving, then a sudden airflow loss at every stop. The sub-product, Chhaya Route (छाया, "shade"), is strain-aware routing to shade and water.

| User | Where the phone lives | What they need |
| --- | --- | --- |
| Delivery rider | Handlebar mount, screen facing rider | Strain warnings, shaded routes, water stops, checks at hand-overs |
| Construction worker | Pocket, put down at breaks | Break check-ins, rest pacing, collapse alarm |
| Traffic police, vendors, farm workers | Pocket or hand | Exposure dose, rest prompts, collapse alarm |
| Site supervisor / fleet lead | Laptop + phone | Crew-level risk view without raw health data |

**How they tie together.** "Heat attacks judgement first" is why Thakaan has a brain check as well as a body model. "Wind vanishes at a stop" is why heart readings are compared only at the same time-since-stop. "Resting costs money" is why every alert shows its time cost. Each plain-language fact about riders turns into a specific engineering choice.

## The 12 rules Thakaan never breaks

**In plain words.** These are promises. If a new feature would break one, the whole team has to decide to allow it. **Technical view.** Each rule is a design invariant from Product Bible v3. **How they tie together.** The table shows each promise beside its engineering meaning.

| # | The promise (plain) | What it means technically |
| --- | --- | --- |
| 1 | Only the phone you already own. | No wearable, no external sensor, no cloud dependency for the core loop. |
| 2 | Works with no internet. | Sensing, models, scoring, routing and alerts run on-device; network only syncs shade map, weather and optional layers. |
| 3 | Tell people how strained they are, not their temperature. | UI shows Cool, Warming, Strained, Danger; never a body-temperature number. |
| 4 | Check the heart only when the worker is still. | Heart checks at hand-overs, stops and breaks; motion sensors run continuously. |
| 5 | Prevent trouble; do not just sound an alarm. | The best alert is a shaded route or early rest, not a siren at collapse. |
| 6 | Act early, well before the dangerous zone. | Every core-temperature action fires at or below a 38.5 °C trend (38.0 °C before acclimatisation); nothing depends on an estimate above 39 °C. |
| 7 | Your health data is yours. | Raw physiology never leaves the phone; only alerts the worker chooses to share are sent. |
| 8 | Talk in the worker's language. | Short spoken instructions in Kannada, Hindi, Tamil, Telugu or English. |
| 9 | Be honest about what it costs you. | Every intervention shows its time cost; detours are capped by current strain. |
| 10 | Never get in the way of the job. | Navigation and delivery flow stay usable; Thakaan sits on top. |
| 11 | When unsure, assume the worse. | Losing information may never lower the level. Unknown shade counts as sun; stale heart rate widens uncertainty, never clears it. |
| 12 | Show where every number came from. | Each value carries a REAL, REPLAY or SIMULATED label that propagates to anything computed from it. |

**The thread through the rules.** Rules 3, 6 and 11 work as a team. The heart-based temperature estimate gets unreliable at the dangerous top end (above about 39 °C). So the app never shows a temperature (3), acts well below that zone (6), and treats any missing data as bad news (11). Rule 12 lets anyone check this on stage.

## How the system fits together

Thakaan has three detectors, one score and one set of actions, all inside the phone.

&#91;embedded content: system overview · 3 watchers, 1 score, 1 set of actions\]

The three watchers each report to the score. The shared shade map is filled by the weather reporter's readings and read back by the route.

**In plain words.** Think of three people watching the rider. A **nurse** watches the body: how fast the heart is going and how hot that suggests he is. A **teacher** watches the mind: are his hands steady, is he tapping accurately, is he making odd turns? A **weather reporter** watches the street: how much sun, shade and humidity is on him right now? All three report to a **coach**, who boils everything down to one score and decides what to say and where to send him.

**Technical view.** The phone's sensors (camera and torch, motion sensors, touchscreen and microphone, light sensor, satellite signals, cached weather) feed three detection layers: body (P vectors), brain and behaviour (N vectors) and exposure (E vectors). A calibrated fusion turns them into the Thakaan Strain Score, TSS. The score drives every action: alerts, routing, check prompts and supervisor messages. Light and satellite readings also flow into a shared shade map, so every ride improves everyone's routes.

| Plain role | Technical layer | IDs | Always available? |
| --- | --- | --- | --- |
| Weather reporter | Exposure prior | E1 to E18 | Yes, so the score never goes blank |
| Nurse | Body update | P1 to P17 | Only after a clean heart reading |
| Teacher | Brain update | N1 to N11 | Some signals all day, battery at pauses |
| Rider's own words | Symptoms | N10 | At every check |

**How they tie together.** The weather reporter is always there, so Thakaan can always give a score. The nurse and teacher sharpen it when fresh readings arrive. Their reports are combined by weight, and a weak or stale report counts for less. The coach never invents a level: the language model only phrases what the risk engine decided.

## The body: heart checks and the hidden temperature

This section answers one question: how hot is the worker's body, when the phone cannot take his temperature?

### Step 1: reading the pulse with a fingertip

**In plain words.** The worker rests a fingertip on the phone's camera, with the torch on. Each heartbeat pushes a little blood into the fingertip, which changes how much light passes through. The camera sees that flicker, and the app counts the beats. It takes about 70 seconds for a full check and about 30 for a quick one. Roughly one check in four fails in the field, so the app says why ("wipe finger and lens, shade the phone, press lighter") and tries again.

**Technical view.** This is fingertip photoplethysmography (PPG), the primary heart channel (P2).

| Setting | Value |
| --- | --- |
| Full check | 70 s: 10 s settling (discarded) + 60 s recording; gives HR, RMSSD, perfusion, waveform |
| Quick check | 25 to 30 s; HR only |
| Camera | Auto-exposure, white balance and focus off; fixed 30 fps; 640 × 480; centre 100 × 100 px region; torch at 50 to 70% |
| Signal | Red channel primary; cubic-spline resample, 0.5 to 4.0 Hz band-pass, Daubechies-4 wavelet denoise, derivative peak detection, Viterbi tracking |
| Rejection | Perfusion index under 0.3%; beat template correlation under 0.85; SNR under 5 dB |
| Guard | No capture while the phone is thermally critical |

**How they tie together.** "Rest gently" and the green ring are the perfusion-index check: too hard a press squeezes the blood out and the signal dies. "Wipe and shade" maps to the quality gates that reject noisy captures. The app rejects bad readings instead of guessing, which is rule 11 again.

### Step 2: guessing the hidden core temperature

**In plain words.** Two guesses are made every minute and blended. **Guess one is from physics:** given the weather, sun, how hard he is working, what he is wearing and how long he has been out, how much would a body warm up this minute? **Guess two is from the heart:** a hotter body usually means a faster heartbeat, so a clean reading lets us check the first guess. If the two disagree, the app moves its estimate toward whichever it trusts more. A clean reading is trusted a lot; a noisy one barely counts. Between readings, only the physics guess runs, and the app admits it is getting less certain.

**Technical view.** A personalised ECTemp Kalman filter, one step per minute using the median HR of that minute. The prediction step is a Gagge two-node heat-balance model instead of a plain random walk.

```latex
\hat{CT}_t = CT_{t-1} + \Delta CT_{Gagge}(t), \qquad \hat{v}_t = v_{t-1} + Q_t
```

The observation model is a sigmoid mapping core temperature to expected heart rate, with the floor A learned from the worker's lowest sustained resting HR and the ceiling K set from their age (HRmax = 208 − 0.7 × age).

```latex
HR_{exp} = A + \frac{K - A}{\left(1 + 0.06\,e^{-0.89(\hat{CT}_t - 37.84)}\right)^{1/0.07}}
```

```latex
k_t = \frac{\hat{v}_t\,m_t}{m_t^2\,\hat{v}_t + R_t}, \quad CT_t = \hat{CT}_t + k_t\,(HR_t - HR_{exp}), \quad v_t = (1 - k_t m_t)\,\hat{v}_t
```

Here m is the slope of expected HR against core temperature and R = 356.4544 is the heart-rate noise (about 19 bpm SD). Gaps skip the update, so variance grows. A reading is rejected when its normalised innovation squared exceeds 3.84. The Kotlin filter must match seven reference cases in `ectemp_vectors.json`.

**How they tie together.** "Guess one" is the Gagge prior; "guess two" is the sigmoid HR model; "how much to trust each" is the Kalman gain k. "Admits it is less certain" is the variance v growing during gaps. "Ignoring a wild reading" is the NIS gate. Personalising A and K is how the same filter fits a 22-year-old and a 55-year-old.

### Step 3: water, sweat and the confusers

**In plain words.** Sweat loses water. As the body dries out, the heart works harder for the same job, which looks like extra heat. So the app tracks water: estimated sweat minus every drink logged ("pani piya"). It also knows what fools the heart: a sudden stop, standing up, caffeine, or heart-slowing medicine. It compares like with like (seated-idle against seated-idle, and the same number of seconds after a stop) so these do not trigger false alarms.

**Technical view.**

- The ISO 7933 PHS model gives sweat rate; PHS loss minus logged drinks (E8) is the hydration deficit (P15). Each 1% body-mass loss adds about 4 to 5 bpm and 0.15 to 0.20 °C.
- Drift priors of about 15 to 18 bpm per °C core rise split measured HR into thermal, dehydration and workload parts (P9 excess HR, P10 idle-state drift).
- Dismounting adds 10 to 20 bpm for 30 to 60 s, so readings in the first 30 s after a posture change are down-weighted.
- Sustained HR above 90 to 100 bpm at rest triggers a level rise even if the core trend looks normal (the independent HR rule).
- Beta-blocker users get HR channels down-weighted and brain, exposure and symptom channels up-weighted.

**How they tie together.** The hydration deficit and posture corrections exist because heart rate alone is a blunt tool. They separate "heart is fast because of heat" from "heart is fast because he just got off the bike", so the nurse does not cry wolf.

## The brain: noticing when thinking and coordination slip

**In plain words.** Heat dulls the mind and makes the hands clumsy, often before the worker feels ill. So Thakaan runs a short check of about 30 to 40 seconds at natural pauses, such as after handing over a delivery. He holds the phone still while it feels for shaking. He taps between two moving targets, which tests hand-eye coordination. He says a short digit code aloud. He taps how he feels (thirst, headache, dizziness). Through the day the phone also quietly notices how smoothly he rides, how he walks, whether he mis-taps, takes wrong turns, or falls.

The key trick is that every result is compared with **his own normal on his own phone**, never with other people. A single bad test means little. But small slowdowns that keep repeating add up, like a bucket filling one drop at a time, and eventually the bucket overflows and the alarm goes. A flag needs agreement from different kinds of evidence, such as shaky hands plus slow tapping plus a wrong code, not three versions of the same signal.

**Technical view.** Cognition holds up until core temperature passes about 38.5 °C or body-mass loss passes 2%, then motor coordination degrades first. So the battery targets motor control, not simple reaction time.

| Order | Test | Time | Metric |
| --- | --- | --- | --- |
| 1 | Tremor hold (N9) | 10 to 15 s | Accelerometer 4 to 12 Hz band power |
| 2 | Fitts tapping (N4) | 20 s | Throughput (bits/s) and spatial error rate |
| 3 | Speech code (N3) | 5 s | Correct digits, articulation rate and pauses |
| 4 | Symptoms (N10) | 2 taps | Thirst, headache, dizziness, nausea, cramps, effort 1 to 10 |
| On flag | Brief PVT (N11) | 3 min | Lapses over 500 ms, false starts, mean 1/RT |

Passive signals run all day: gait instability (N1), riding smoothness via steering-yaw approximate entropy (N2), touch drift (N5), confusion markers from GNSS and app state (N6), collapse detection (N7), and eye fatigue (N8, off outdoors).

Change detection is a one-sided CUSUM on robust z-scores, with k = 0.5σ and an alarm at h = 4 to 5σ:

```latex
C^{+} = \max(0,\; C^{+} + x - \mu_0 - k)
```

Confirmation needs independent families: motion (N1, N2, N7, N9 count as one), touch (N4, N5, N11), voice (N3), navigation (N6) and symptoms (N10). A past-threshold battery raises the level one step and triggers N11 at the next break; two families flagged with high exposure force at least Strained. Sleep loss, time of day (the post-lunch dip) and recent caffeine adjust the expected score, because they affect attention more strongly than mild heat does.

**How they tie together.** "His own normal on his own phone" exists because touchscreen latency differs by up to about 100 ms between phones. "The bucket filling" is the CUSUM sum C⁺. "Different kinds of evidence" is the independent-family rule, and it stops a single noisy sensor from raising a false alarm. "Hot day makes the same slowdown more serious" is the context prior, which links the brain layer back to the exposure layer.

## The environment: how much heat is landing on you

**In plain words.** Two riders on the same street can face very different heat. One is under a tree and one is on open tarmac. So the phone measures what is hitting the worker right now:

- **Sun or shade?** The light sensor sees how bright it is and how yellow or blue the light looks. Direct sun is brighter and yellower; shade is dimmer and bluer.
- **What is blocking the sky?** The phone listens to satellites. If many are blocked, there is a roof, a flyover, a building or a tree overhead. A tree blocks signals differently from a building, so the app can tell them apart.
- **How hot is the air?** From a forecast saved on the phone, nudged using the phone's own battery temperature when the phone is idle and uncased.
- **How much air is moving?** The bike's speed is a breeze. At a red light, the breeze is gone.

These combine into a single "heat stress" number, like a smarter "feels like" temperature. It is also the backbone of the score: it is always available, even with no heart reading.

**Technical view.**

| Plain idea | Vector | Method |
| --- | --- | --- |
| Sun or shade | E1 | Lux drop + correlated colour temperature jump (direct sun about 5,000 to 5,500 K; open shade about 7,500 to 10,000 K) |
| How strong is the sun | E15 | Lux ÷ 110 ≈ W/m²; above sensor saturation (about 65,000 lux) a clear-sky model by sun position |
| What blocks the sky | E12, E13 | Per-satellite line-of-sight probability from an on-device XGBoost classifier; 10° sky bins; canopy vs building by signal pattern |
| Local air temperature | E16 | Cached forecast + battery-temperature transient model with self-heat subtraction |
| Air movement | E17 | GNSS speed as airflow; near zero at stops |
| Heat-stress number | E18 | Liljegren WBGT via the `lwbgt` C library; the simple ACSM87 formula is never used by day |

The phone's own body temperature is never used as a globe thermometer: its mass, internal heat, flat shape and case make that unreliable. E3 (device heat soak) serves only device care and the E16 correction.

**How they tie together.** The light and satellite readings do double duty: they set the worker's heat load now (feeding the strain score and the heat-balance prior) and, once anonymised, they teach the shared shade map that Chhaya Route uses. "The breeze vanishes at a red light" is E17 going to zero, which is the same traffic-light spike that the heart checks account for.

## The strain score: one number, four levels

**In plain words.** Everything the nurse, teacher and weather reporter see is blended into one score from 0 to 100, updated every minute, with a forecast for 15 and 30 minutes ahead. Think of a fuel gauge for the body: low is fine, high means act now. The worker sees four named levels with a colour, a shape and a word, never a body-temperature number.

A few things keep the score honest. It can never go blank, because the weather part is always there. A level can also be forced up by a single alarming fact (an "anchor"), even if the blended number looks calm. Levels do not flicker up and down, and Danger only clears after the worker passes a recovery check. Each change records its top three reasons, such as "2 hours without shade, idle heart rate up 14 beats, tapping slower", so the app can say why in plain speech.

**Technical view.** TSS fuses four layers (exposure prior, body update, brain update, symptoms) through a logistic function, calibrated with isotonic or Platt scaling so the same number means the same risk across workers and days:

```latex
TSS = 100\cdot\sigma\big(w_E E + w_B B q + w_N N + w_S S + b\big)
```

E is the exposure prior, B body strain, q the freshness and quality of the last HR reading, N brain alarms, S symptoms. A small GRU on the NPU forecasts TSS 15 and 30 minutes ahead, and a confidence band shows as a halo around the gauge.

| Level | TSS | Example anchors that force it | Default action |
| --- | --- | --- | --- |
| Cool | 0 to 29 | None | Normal routing, passive logging |
| Warming | 30 to 54 | Core trend rising; PSI above 3; hydration deficit above 1% | Drink nudge; small Chhaya detours allowed |
| Strained | 55 to 79 | Core trend at 38.0 °C (unacclimatised) or 38.5 °C; PSI above 5; hydration deficit above 2%; one brain family + high exposure | Water stop routed next; full check at next stop |
| Danger | 80 to 100 | PSI above 7; two brain families flagged; perfusion collapse (P17); confusion or severe symptom | Stop now: nearest shade, contact + supervisor alert, cooling coach |
| Collapse | N7 event | Fall or slump + stillness after the countdown | Siren, SMS + call to contact and 108 |

**How they tie together.** The weights are the nurse, teacher and reporter being trusted by different amounts. The freshness term q is how a stale heart reading counts for less. The anchors are rule 6 (act early) written as code: they fire at or below 38.5 °C and never wait for an estimate above 39 °C. The top-three-drivers record is what lets the language model speak a reason without being allowed to change the level.

## Chhaya Route: a map app that cares about shade

**In plain words.** Normal map apps find the fastest road. Chhaya Route finds the fastest road *for how your body is doing right now*. When you are cool, it gives you the fastest route and no detour. As your strain rises, it will spend a little more time to put you in shade, and to pass a water stop. At Warming it will add about a minute, at Strained about three, and at Danger it simply sends you to the nearest shade. Every route shows the price: "+2 min, 70% shade".

To know where shade is, the app uses four sources. It calculates where buildings and trees cast shadows from the sun's position. It learns from other riders' light and satellite readings along each road. It marks hot zones from satellite temperature maps. It knows the road surface and width. Water points come from OpenStreetMap and from riders saying "pani yahan milta hai". They are shown as "listed, quality unverified" until riders confirm them. Shops can join a "Thakaan Pyaau" network with a QR sticker.

**Technical view.**

- **Shade map:** five layers per road segment per hour: computed shadows (ray-cast against OSM building footprints and canopy polygons, every 30 min), crowdsourced light (E1), GNSS sky view (E12 to E14), heat-island layer and surface layer.
- **Aggregation:** traces map-matched with an HMM, binned on an H3 grid (resolution 11 to 12, about 10 to 25 m) in 1-hour slots, each cell holding a Bayesian shade estimate weighted by device reliability and satellite count.
- **Engine:** an offline graph (GraphHopper or Valhalla on Android) with a custom per-edge cost:

```latex
C_e = t_e\,\big(1 + \lambda(TSS)\cdot S_e(h)\big) + \mu\,H_e + \nu\,A_e + F_e
```

t is travel time, S the sun-exposure fraction at hour h, H the heat-island penalty, and λ rises with TSS (about 0 at Cool). A and F are the optional air and flood terms. Detour budgets are +1 min at Warming and +3 min at Strained; Danger routes to the nearest cool, seated stop. Map credit: © OpenStreetMap contributors (ODbL).

**How they tie together.** The strain score is the dial and λ is the wire connecting it to the map. At Cool, λ is about zero, so the cost is just travel time and you get the fastest route. As strain climbs, λ grows and sunny segments become "expensive", so the router happily takes a shadier, slightly longer road. The "+2 min, 70% shade" label is simply the route's extra time and its average S\_e, shown to the rider.

## Alerts, work modes and the supervisor view

### What the phone does at each level

**In plain words.** Every alert names exactly one action, says it in the worker's language, and shows what it costs in time. The alerts get firmer as the danger grows. If the worker collapses and does not respond to "say haan" (yes), the phone sounds a siren, tells bystanders what to do, and calls for help. A 10-minute "cooling coach" guides recovery after a Danger alert, and the level only clears when the worker passes a recovery check.

**Technical view.**

| Level | Haptic | Voice example (Hindi) | Screen | Other |
| --- | --- | --- | --- | --- |
| Warming | 2 soft pulses | "Paani pee lo, agle signal pe." | Amber ring | Water stop suggested |
| Strained | 3 strong pulses, repeat in 2 min | "Shareer garam ho raha hai. 3 minute aage chhaya aur paani hai." | Orange banner with route | Chhaya Route on; full check at next stop |
| Danger | Continuous until acknowledged | "Abhi ruk jao. Chhaya mein baitho, paani piyo." | Red takeover with nearest shade | Contact + supervisor alert; cooling coach |
| Collapse | Siren + flash after "say haan" countdown | "Is vyakti ko garmi lagi hai. 108 ko call karein." | Bystander first-aid steps | SMS with location; auto-call 108 |

Smart snooze ("I'm at a drop-off") pauses non-danger alerts until the next stop, never at Danger. Collapse detection (N7) uses IMU and GNSS to spot falls, bike tip-overs and slow slumps followed by stillness.

**How they tie together.** Each row is a level from the strain score. Rule 8 is the voice column, rule 9 is the "cost in time" on screen, and rule 5 is why the quieter, earlier levels do the real work and Collapse is the last resort.

### Two kinds of worker, one app

**In plain words.** A rider has the phone on the handlebar. A construction worker keeps it in a pocket and puts it down at breaks. Thakaan works out which situation it is in and changes how it behaves. A rider gets quick checks at hand-overs, a route and a water stop. A site worker gets break check-ins, rest scheduling, and a buddy system where a teammate's phone vibrates if someone is in trouble.

**Technical view.** Mode detection uses the IMU vibration spectrum, mount orientation and GNSS speed to pick Rider, Pocket, In-hand or Desk. Rider Mode uses P2 quick checks at hand-overs, N2 riding smoothness, E1 continuous light and E17 apparent wind, with a heat-soak guard that dims the screen and duty-cycles the NPU and GNSS. Site Mode uses chest SCG (P3) when still, full P2 + sit-to-stand (P13) at breaks, N1 gait, a break scheduler and a rotation advisor.

**How they tie together.** The two modes use the same score but different sensors, because the body is in a different place and a different kind of work is happening. A phone in a pocket cannot see the sky and a phone on a handlebar cannot feel a chest heartbeat, so each mode picks the signals that actually work.

### The supervisor's view

**In plain words.** A supervisor can see each person's level (Cool, Warming, Strained, Danger), when they last rested and where they are, but never their heart rate, voice or scores. If mobile data fails on site, phones pass messages to each other.

**Technical view.** The Thakaan Console runs on a laptop over vivo Office Kit (screen mirror, shared clipboard, file transfer, notification sync, camera share) plus a BLE / Wi-Fi Aware crew mesh. It shows a live crew board with provenance chips, a map, an alert feed, and a shift report with a hash-chain verifier.

**How they tie together.** This is rule 7 in practice: privacy is built into what the console is allowed to receive, so it cannot leak what it never gets.

## Trust: proving the numbers are real and the data is safe

**In plain words.** A health app that cannot show its working is just a guess in nice colours. Thakaan makes four checkable promises.

1. **Every number wears a label** saying where it came from: measured live, a real recording played back, or made up for the demo.
2. **Losing information never makes things look safer.** If the heart reading is missing or the satellite signal drops, the level stays the same or goes up, never down.
3. **The core calculation is tested against known answers**, like checking a calculator against a printed answer book.
4. **The shift log is tamper-evident.** Change one entry and the chain visibly breaks.

For privacy, the raw health data (heart, brain scores, symptoms) stays on the phone. Camera frames and audio are discarded right after use. Only anonymised shade readings, public water or shade tags, and alerts the worker chose to share leave the phone.

**Technical view.**

| Feature | How it works |
| --- | --- |
| Truth labels | A `provenance` field (REAL, REPLAY or SIMULATED) set at the sensor hub on every reading. Anything computed from REPLAY or SIMULATED inherits that label. |
| Safety invariant, Part A | A property test generates random shifts, then randomly drops or corrupts inputs. It fails if any degraded run gives a lower level than the full-data run. |
| Safety invariant, Part B | Five live PASS / FAIL checks on the Diagnostics tab (no HR falls back to exposure, stale HR widens the band, GNSS lost means sun, two brain families means at least Strained, LLM cannot override the level). A hidden switch disables each guard in turn to confirm its check turns red. |
| ECTemp test vectors | `ectemp_vectors.json` holds seven reference cases the Kotlin filter must match; Diagnostics shows "ECTemp vectors 7/7 pass". |
| Hash-chained log | Each shift entry stores the hash of the previous entry and is signed with a StrongBox key; the Console shows "Chain OK" or "Chain BROKEN". |

Privacy is organised by data type:

| Data | Stored | Leaves the phone? |
| --- | --- | --- |
| Camera frames, audio | RAM only, discarded | Never |
| HR, PRV, core trend, brain scores, symptoms | Encrypted on device | Never |
| TSS level + alerts | On device | Only to contacts or supervisor the worker approved |
| Light and sky readings | On device, then batched | Yes: anonymised, time-bucketed, snapped to H3 cells |
| Proof-of-exposure log | On device, hash-chained, signed | Only when exported |

Separate consents exist for heart checks, voice checks, shade sharing and supervisor alerts. The product follows DPDP Act 2023 principles (purpose limitation, one-tap erase, on-device consent log) and is framed as a wellbeing tool, not a medical device.

**How they tie together.** Each promise maps to a rule: the labels are rule 12, the invariant is rule 11, the privacy table is rule 7. One honest limit: a valid hash chain proves entries were not changed after recording, not that the original observation was true. The bible says to state this plainly.

## Under the hood: the phone, the optional extras and the screens

**In plain words.** Everything runs on the phone's own chips, with no internet needed. The main processor does the maths for the body and brain checks. A dedicated AI chip handles the small learning models, and the graphics chip runs the small language model that phrases advice and coaches recovery. One safety rule applies to that language model: it may only *say* what the risk engine decided, from a fixed list of actions. It cannot change the level. A "thermal governor" watches the phone's own heat and quietly switches off non-essential work first, because a hot phone cannot take a good pulse reading.

Two extras are off by default and never change the strain level. A **breathing-dose card** adds up the dust and smoke you breathed in during the shift. A **rain and flood layer** lets riders report flooded roads with one tap and a photo, which the router treats as closed until the report expires.

The home screen is one big gauge that can be read in half a second at a red light, with colour, shape and words for each level. Every prompt can be answered with "haan" or "nahi".

**Technical view.**

| Job | Method | Runs on |
| --- | --- | --- |
| Heat-balance prior, ECTemp filter, fingertip PPG, DSP, CUSUM, routing, WBGT | Kotlin / NDK (Gagge, EKF, wavelets, GraphHopper or Valhalla, `lwbgt`) | CPU |
| Face rPPG, SCG denoiser, activity classifier, speech code | LiteRT / ORT + QNN | Hexagon NPU |
| TSS fusion + 15 and 30 min forecast (small GRU) | LiteRT | NPU |
| Explainer + cooling coach | Gemma 4 E2B via LiteRT-LM | Adreno GPU |
| Voice out and tags in | Android offline TTS (hi, kn, ta, te, en) + on-device recogniser | CPU |

Supporting parts: a foreground **sensor hub** stamps provenance on every reading; a **stop scheduler** state machine (Moving, Stopping, Stopped, Walking, Resting) wakes the torch, camera, NPU and mic only when needed; an `InferenceEngine` seam gives QNN, GPU and CPU back ends (Diagnostics shows "N/N ops on NPU"); storage is Room + SQLCipher.

The optional layers use the formula below, with ventilation estimated from heart rate by the Greenwald et al. (2019) pooled power model (about 20 to 25% error per minute, averaging out over a shift). Flood depth is never measured: the app shows "reported flooded", never centimetres.

```latex
Dose_{PM2.5} = \sum C \times V_E \times \Delta t
```

**How they tie together.** Rule 1 and 2 (phone only, offline) are why the table is split between CPU, NPU and GPU instead of calling a server. The LLM guardrail is rule 11 applied to the coach, and the thermal governor is rule 11 applied to the hardware. The optional layers extend the same machinery, such as the route cost (the A and F terms), without changing the strain score.

## The demo, the honest limits and the build plan

### The 3-minute demo

**In plain words.** The pitch shows the idea working live, with no cheating. A teammate checks their pulse on stage, does jumping jacks and the score moves. A recorded ride at 20 times speed shows the shade map filling in and the route changing. A shaky, slow, wrong-answer brain check triggers Danger. A teammate slumps, the countdown runs, and the collapse alarm sounds. Then the phone goes into airplane mode to prove it still works. Every number on screen carries its label, so the audience knows what is live and what is replayed.

**Technical view.** The demo runs from 0:00 to 3:00: hook, live fingertip check (REAL), jumping jacks, REPLAY ride with Chhaya reroute and Hindi voice, brain-battery CUSUM crossing, collapse countdown, airplane mode with "ECTemp vectors 7/7" and all Safety checks PASS, then close on the Console with "Chain OK".

**How they tie together.** Each beat proves one rule: the live check is rule 4, the replay label is rule 12, the airplane-mode moment is rules 1 and 2, the safety checks are rule 11.

### What Thakaan honestly cannot do

**In plain words.** It is not a thermometer and not a medical device. It checks the heart only at stops, not on a moving bike. The science behind it was mostly tested on young, fit Western or military men, not on South Asian workers, women or older people, so it needs local testing. It gets less accurate above about 39 °C, which is why it acts well below that.

**Technical view.**

- HR-based core estimates lose sensitivity above about 39 °C, so every action fires at or below 38.5 °C and an independent HR rule backs it up.
- Heart checks are spot readings, not continuous.
- Underlying models were validated mostly on young Western, military or athletic men.
- Brain signals are personal drift signals; none is validated alone as a heat-illness detector.
- Language strings need native-speaker review.
- ECTemp in a monitoring system is covered by US Patent 10,702,165 (USARIEM); a commercial version needs a licence through the Defense Health Agency.

**How they tie together.** Every limit has a built-in answer. Weak accuracy near 39 °C is answered by acting at 38.5 °C. Spot-only heart checks are answered by the heat-balance model carrying the estimate between readings. Weak single brain signals are answered by needing two independent families.

### Likely judge questions, in plain words

| Question | Short answer |
| --- | --- |
| Can you read heart rate on a moving bike? | No, and we do not try. We check at hand-overs and breaks; the heat-balance model carries the estimate in between. |
| How do you know core temperature? | We estimate a trend (ECTemp, personalised) on top of a heat-balance model. We never show a number, and we act at 38.5 °C. |
| Isn't camera heart rate bad on dark skin? | Face-based reading is, so it is optional. Fingertip PPG with the torch shows no skin-tone bias in published validation. |
| Isn't this just a weather alert? | Weather is one of about 45 vectors. Two riders on the same street get different levels. |
| How do we know your numbers are real? | Every number has a REAL, REPLAY or SIMULATED chip; the filter passes 7/7 reference vectors; safety checks run live. |
| Who pays? | Platforms, insurers (heat cover backed by exposure logs) and state labour boards. |

### Build plan: five phases

Each phase ends with something demoable, so the pitch is safe whenever time runs out. Hours count from the start of the build.

| Phase | Hours | What gets built |
| --- | --- | --- |
| 0: Skeleton | 0 to 3 | Kotlin + Compose app, sensor hub with provenance, `InferenceEngine` seam, mock gauge |
| 1: Body | 3 to 13 | Fingertip PPG, ECTemp passing 7/7, Gagge / PHS prior, hydration, onboarding baseline |
| 2: Brain + exposure | 13 to 23 | Tremor, Fitts, speech code, CUSUM; sun/shade, WBGT; TSS, alerts, safety property test |
| 3: Chhaya Route | 23 to 34 | Bengaluru OSM bundle, strain-aware edge cost, GNSS sky view, ride replay |
| 4: Coach + console | 34 to 44 | Gemma coach, multilingual voice, Console over Office Kit, hash-chained export |
| 5: Hardening | 44 to 48 | Safety checks tab, limitations slide, three rehearsals, backup video |

**How they tie together.** The plan builds the body first because everything else leans on it, and leaves the stretch features (breathing dose, flood reports, face rPPG) for last because the core loop must work without them.

## Jargon decoder: plain meaning beside the technical term

Use this when someone says a technical word and you need the everyday meaning, or the other way round.

| Plain meaning | Technical term | Where it appears |
| --- | --- | --- |
| One 0 to 100 danger gauge for the body | TSS (Thakaan Strain Score) | Strain score |
| Pulse read with a fingertip on the camera | PPG (photoplethysmography), P2 | Body |
| How strong the pulse signal is in the fingertip | Perfusion index (PI) | Body |
| Beat-to-beat wobble in the pulse | PRV / RMSSD | Body |
| The blend of a physics guess and a heart guess | ECTemp Kalman filter (EKF, UKF upgrade) | Body |
| How much to trust a new reading | Kalman gain | Body |
| Test that rejects a wildly off reading | NIS gate (normalised innovation squared) | Body |
| Physics guess of how the body heats | Gagge two-node model | Body |
| Estimate of sweat and water lost | PHS (ISO 7933) | Body |
| Body-strain score from heart and temperature | PSI (Physiological Strain Index) | Strain score |
| Heart racing more than the effort explains | Excess HR (P9), idle-state drift (P10) | Body |
| Adding up small repeated slowdowns | CUSUM | Brain |
| Tapping between two targets to test coordination | Fitts tapping | Brain |
| A longer alertness test, only to confirm | PVT-B | Brain |
| Different kinds of evidence that must agree | Independent families | Brain |
| "Feels like" heat-stress number | WBGT (Liljegren model), UTCI | Environment |
| Sun or shade sensing from light colour | CCT (correlated colour temperature) | Environment |
| Reading which satellites are blocked | GNSS sky view, C/N0, LOS / NLOS | Environment |
| Map grid used to pool shade readings | H3 | Chhaya Route |
| Matching GPS dots to real roads | Map matching (HMM) | Chhaya Route |
| Cost of a road for the router | Edge cost C\_e, λ(TSS) | Chhaya Route |
| Every number says live, replayed or invented | REAL / REPLAY / SIMULATED | Trust |
| Losing data never lowers the level | Safety invariant | Trust |
| Known inputs with known right answers | Test vectors | Trust |
| Log that shows if anyone tampered with it | Hash chain | Trust |
| The phone's AI chip | Hexagon NPU, QNN | Under the hood |
| Small language model that phrases advice | Gemma 4 E2B on LiteRT-LM | Under the hood |
| Fine dust you breathe in | PM2.5, breathing dose | Optional layers |

Source for everything above: Thakaan Product Bible v3, 10 October 2026.
