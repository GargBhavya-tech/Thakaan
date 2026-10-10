# Face sensing design (ticket 31 and what hangs off it)

Status (2026-10-10): pulse, breathing, fatigue, forehead thermal logic, frame guidance and the camera, tracker and screen are written. 49 face tests pass on the laptop with synthetic data. **Nothing here has been run on the phone yet.** The forehead thermal check has logic only: no screen and no confirmed temperature-sensor source. Everything here is **optional, off the critical path and never replaces the fingertip pulse**. The ticket says so ("never let it replace fingertip"), and the research below is why.

## What is in scope

| Piece | Bible id | What it gives | Where it lives |
| --- | --- | --- | --- |
| Face pulse and breathing from the front camera | P1 | HR, respiration, a confidence | `face/FaceRppg.kt` (POS), `face/FrontCameraCapture.kt` |
| Face detection and landmarks | (tool for P1, N8) | forehead and cheek regions, eye and mouth openness | `face/FaceLandmarks.kt` (MediaPipe Face Landmarker) |
| Face fatigue (blink rate, eyelid closure, yawns, gaze drift) | N8 | one FACE-family brain score | `face/FatigueSignals.kt`, feeds the existing `BrainFamily.FACE` |
| Forehead-press thermistor check | P14 | a coarse "forehead reads warm" flag, never a number shown | `face/ForeheadThermal.kt` |
| Face in frame and mount guidance | (stop detector, mount setup) | "move the phone left", "face found", the 15 s capture trigger | `face/FrameGuide.kt` |

## What the research says (and what we do about it)

1. **Face landmarks.** MediaPipe Face Landmarker (`com.google.mediapipe:tasks-vision`) runs on Android from a bundled `face_landmarker.task` model and returns 478 landmarks, blendshape scores (eye blink left/right, jaw open and others) and a head-pose matrix. Loading the model from `assets/` needs no network at run time. *Decision:* use the blendshape scores for blink and yawn (they are already normalised 0 to 1, so they are less fragile than raw eye-aspect-ratio thresholds) and the landmarks for the skin regions. The model file is **not committed** (rule: no model files in git); it is staged in `app/src/main/assets/models/` and listed in `docs/ASSETS.md`. *Verify on the phone with airplane mode on.*
2. **Pulse from a face (POS).** POS (plane-orthogonal-to-skin, Wang et al., IEEE TBME 2017) projects normalised R, G, B traces onto a plane orthogonal to the skin-tone direction, which cancels most intensity change from motion and light, and overlap-adds short windows. A published sliding-window study found error falls as the window grows until compute cost bites; the original paper's short window (about 1.6 s) is the commonly cited starting point, **to be confirmed against the paper**, so our window length is a parameter and is chosen by test on synthetic and real data. *Decision:* POS first, as the ticket says; a learned model (EfficientPhys) only if POS proves too weak and a legal model file is available.
3. **Skin tone and light.** Published audits find rPPG error is larger for darker skin (one cited result: a chrominance method went from about 5 bpm error on lighter skin to about 14 bpm on the darkest tones; this figure is second-hand from a 2025 audit and not checked against the original), very light skin can saturate sensors, and light and motion are the other failure modes. Direct sun on a face was not covered by any study found. *Decisions:* (a) face pulse is **optional and low weight**; (b) it is allowed to say "not reliable here" and say nothing; (c) a face pulse is only used when it agrees with the fingertip or when no fingertip reading exists, and then at reduced weight; (d) we never report accuracy numbers for it until measured on our own test people next to a reference.
4. **Blink, eyelid closure, yawn.** Literature uses eye aspect ratio (EAR) thresholds around 0.2 to 0.25, PERCLOS (share of time the eyes are closed; two definitions are in use: time fraction below a threshold, or time at 80% or more closed per minute) and mouth aspect ratio for yawns (sustained wide opening, not one frame). Papers agree fixed thresholds fail across people and recommend per-person calibration. *Decisions:* per-worker baseline (invariant: brain scores compare only the same worker on the same phone, same placement, same time-of-day bucket); we state the PERCLOS definition we use (fraction of frames with eyelid closure at or above 0.8 in a window); a yawn needs a sustained jaw opening for at least 1.5 s; one frame never counts.
5. **Forehead thermistor (FeverPhone).** The UW FeverPhone paper presses the touchscreen to the forehead for about 90 s and uses the phone's thermistor and the raw touchscreen capacitance; it reached about 0.41 F average error on 37 emergency-department patients with a model trained on water-bag and clinical data. We do **not** have that dataset or model. *Decisions:* (a) implement only the physical idea, a first-order heat-transfer fit to the temperature rise of the phone's own sensors during a press, and gate it hard (phone must start cool, the press must be steady, the curve must fit); (b) never show a body-temperature number (invariant 2): the only output is "forehead reads warm / normal / not usable", used as an independent flag that can raise concern and can never lower a level; (c) mark it experimental and off by default until it has been compared with a thermometer on real people.
6. **Face in frame as a stop trigger.** The Bible lets "speed near zero plus engine idle vibration plus face in frame" start a 15 s capture at a red light. *Decision:* face-in-frame is only a **permission to try**; it never creates a stop by itself and never starts when the phone is hot (existing `ThermalGuard`).

## Safety and privacy rules for this feature (same as the rest of the app)

- Camera frames are RAM-only. Only per-frame colour means, blendshape scores and a few landmark coordinates are kept, and only for the duration of the check. No frame, face crop or landmark set is stored, logged or sent. The app has no INTERNET permission.
- Nothing from the face ever lowers a level. Missing face, bad light or no pulse means "no information", which holds or raises, never lowers.
- No emotion, identity, age, gender or attractiveness inference. The model's blendshapes are used only for eyelid and jaw openness.
- The brain-battery rule applies: face fatigue is compared only with the same worker, same phone, same placement, same time-of-day bucket; no baseline means no judgement.
- Face fatigue alone cannot raise a level above what the engine's existing rule for "one brain family flagged" does (two families are needed to force Strained).

## Build order and how each part is tested

1. **FaceRppg (POS) and respiration**: pure Kotlin. Tested on the laptop with synthetic skin-colour signals (known HR, added motion, flicker, noise, a dark-skin low-amplitude case): HR within 3 bpm when clean, rejects pure noise, never reports a pulse from a flat trace.
2. **FatigueSignals**: pure Kotlin on blendshape time series. Tested with synthetic blink, long-closure, yawn and head-turn series, including threshold personalisation.
3. **ForeheadThermal**: pure Kotlin. Tested with synthetic first-order curves, noisy curves, warm start, wobble.
4. **FrameGuide**: pure Kotlin on a face box. Tested on positions and sizes.
5. **Android layer** (device-only, written, then tested on the iQOO 15): front camera capture with locked exposure, MediaPipe wrapper, Face check screen, mount guidance, Diagnostics rows.
6. **On the phone**: front camera manual-control support, frame rate, whether the model runs offline in airplane mode, live ROI colour trace, and a first comparison with the fingertip reading.

## What we must not claim

- Any accuracy for face pulse, respiration or forehead temperature before it has been compared with a reference on our own people.
- That it works on all skin tones or in sun.
- That the forehead check measures body temperature. It is a coarse experimental flag.

## Sources
- Google AI Edge, Face Landmarker guide for Android: https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android
- Wang, den Brinker, Stuijk, de Haan, "Algorithmic Principles of Remote PPG", IEEE TBME 2017, doi 10.1109/TBME.2016.2609282
- Effects of window length on rPPG accuracy and cost (CinC 2020 / 2024 studies): https://www.cinc.org/2020/Program/accepted/153.html
- Breda, Springston, Mariakakis, Patel, "FeverPhone", UW UbiComp Lab: https://ubicomplab.cs.washington.edu/publications/feverphone
- Personalised EAR/MAR thresholds for drowsiness: https://arxiv.org/pdf/2604.22479
- rPPG skin-tone bias audit (npj Digital Medicine, 2025) and BMVC 2024 PhysFlow: https://bmva-archive.org.uk/bmvc/2024/papers/Paper_136/paper.pdf
