# Phone checklist: everything written without the iQOO 15

All of this code exists and passes its laptop unit tests, but has **not** been run on the phone. Tickets marked `[~]` in `TICKETS.md` stay that way until the matching item below is done. Tick items here as you go; then flip the ticket.

Build and install: `gradlew :app:installDebug` (JAVA_HOME = Android Studio's `jbr`). The debug build now also compiles the native WBGT library for arm64.

## Settings by hand (once)
- [ ] OriginOS: battery **Unrestricted** for Thakaan, app **locked in Recents**, allow background activity and autostart.
- [ ] Location permission "Allow all the time"; notification permission.

## Outdoors, with sky view (closes #2 and #5)
- [ ] GNSS: confirm L5 / multi-constellation fixes in the Diagnostics screen, and that the stream stays REAL for 10 minutes.
- [ ] Sky view (#21): stand in the open, then under a tree and beside a tall building; open-sky ratio should drop.

## Body (A)
- [ ] #8 PPG capture: finger on rear lens, 30 s, torch on; check frame rate and the thermal guard.
- [ ] #9 to #11: resting HR within about 5 bpm of a reference (pulse oximeter or watch); a clearly bad capture (finger lifting) is rejected and retried; the full check gives RMSSD only after 60 s clean.
- [ ] #22: ECTemp 7/7 vectors PASS on device (Diagnostics).

## Motion (B)
- [ ] #13 activity: sit, walk, climb stairs, ride; label and placement (pocket / hand / mounted) look right.
- [ ] #14 stop detector: stop for 2 minutes; posture clock starts.
- [ ] #15 collapse: simulated fall onto a cushion starts the countdown; "haan/nahi" cancels; nothing fires while the phone is dropped on a desk from 30 cm.
- [ ] #32 tremor: hold at arm's length 12 s; repeat after 30 push-ups (power should rise); a test within 60 s of lifting is flagged.
- [ ] #33 Fitts: five baseline runs are stable; the non-dominant hand is clearly worse. Touch times come from MotionEvent event time.
- [ ] #37 gait: walk normally and with a deliberate wobble in the same placement.

## World (B)
- [ ] #16 light: phone in sun vs shade vs pocket vs blocked; E1 says Sun / Shade / Unknown correctly and Unknown counts as sun.
- [ ] #19 WBGT: open Diagnostics; Wbgt.NativeBackend should load `libthakaan_wbgt.so` (if it does not, WBGT shows missing and the engine treats it as hot).
- [ ] #46 to #49: airplane mode, route between two venue-area points returns in under a second; Cool route equals the fastest; Strained inserts a water stop.
- [ ] #50: walk one street twice; its shade estimate moves.

## Systems (C)
- [ ] #34 speech code: say a code correctly, slowly, and wrong, in noise (needs the recogniser wired in).
- [ ] #40 alerts: spoken line plays in Hindi, Kannada, Tamil, Telugu and English; haptic patterns feel different per level.
- [ ] #42 Safety checks tab: 5/5 PASS on device; flipping each hidden guard turns its check red.
- [ ] #54: StrongBox key creation; `hardwareBackedKey` is true in the export, or false (honest) if not; export, verify OK, change one byte, verify BROKEN.
- [ ] #6 NPU: Diagnostics shows "N/N ops on NPU" for the test model; Gemma loads on GPU (`assets/models/gemma-4-E2B-it-gpu.litertlm`, 1.9 GB, push by adb or Office Kit).

## Added 2026-10-10 (second batch)
- [ ] #8 camera key set: confirm `applied` (exposure, ISO, frame duration, torch level) in Diagnostics; frame intervals within 25-42 ms for over 98% of frames; no flattening of the pulse in 40 C sun.
- [ ] #12 thermal guard: with the phone hot, a capture is refused with the shade prompt; covering then dimming the torch voids the window.
- [ ] #45 quick check: a teammate who has never seen it finishes all steps unaided in under 90 s; a deliberately shaky finger is rejected with a prompt; skipping a step shows "skipped" in the summary.
- [ ] #28 onboarding: first launch shows setup; relaunch skips it; a bad weight/height/age is refused; leaving days-in-heat at 0 keeps the earlier alerts.
- [ ] #40 voices: install or confirm Kannada, Telugu and Tamil TTS voices; a missing one must show the English fallback note in Diagnostics.
- [ ] Alerts: rising to Warming, Strained and Danger speaks and vibrates once each; falling does not speak.
- [ ] #53 console: run `python -I tools/console/serve.py`, tap Copy status on the phone, paste into the host page; the level and coarse reasons show, no raw numbers; the QR opens the read-only view on a second device.
- [ ] #42 Safety tab: 5/5 PASS and each guard switch turns its row red.
- [ ] #56 airplane mode: enable airplane mode, then run a full replay, a route and a quick check; nothing fails, and no network permission is listed in app info.

## Face sensing (#31), all untested on the phone (written 2026-10-10, 335 laptop tests green)
- [ ] Diagnostics, Device self-tests, "Face model": loads with **airplane mode on** (the model is inside the APK; no network).
- [ ] Front camera: Face check opens, shows "Face found" when the face is centred, names the problem (closer, back, centre, cut off) otherwise.
- [ ] Camera: steady frame rate and AE/AWB lock working on this phone (the screen shows "camera settling" until the lock is applied). If the frame rate is uneven the pulse is rejected as "camera dropped frames": record how often.
- [ ] Rested baseline: tick "I feel rested", run once in good light, see "Saved as your normal". Then run three more normal checks: they should say "Compared with your normal" and nothing is flagged.
- [ ] Fatigue signals: blink normally (blinks about 10 to 25 a minute), then deliberately close your eyes for 1 s three times (long closures should count 3), then fake a long yawn (yawn should count 1). Talking must not count as a yawn.
- [ ] Squint: face a bright window; a squint reading must say "bright light is making you squint", not tiredness.
- [ ] Face pulse next to a watch or pulse oximeter: five attempts. Record face HR vs reference. Until this is done, NO accuracy claim about the face pulse. Try darker and lighter skin among testers, and indoors vs shade; direct sun is expected to fail.
- [ ] Forehead thermal check: not wired to a screen yet (logic only). Needs a screen and a temperature sensor source on this phone first.

## Models, air dose, flood reports, chest vibration (added 2026-10-10, 390+ laptop tests green)
- [ ] #4 assets: run `python tools/verify_assets.py --adb <path to adb>` with the phone attached: no PROBLEM lines (Gemma and both Vosk folders must show "on the phone"; the APK must contain the PM2.5 cache). Then tick #4.
- [ ] Models: Diagnostics > Models on this phone: Small models (6 rows), Gemma, Speech all green (done once on 2026-10-10 except the two newest rows).
- [ ] #34 speech step: Quick check step 4 shows a 4-digit number; say it in Hindi and in English; then say one digit wrong, then slowly with a slur: the last two must be recorded as different.
- [ ] #58 air dose: start sensing, ride or walk 10 minutes: Home shows "Air dose today: N micrograms (area average X, modelled; mostly estimated from workload)". After a fingertip check the text should say "from heart rate" for the next minutes. The value must be labelled modelled, never "your air".
- [ ] #59 flood report: Chhaya screen > "Report flood here (takes a photo)" with the camera uncovered: "Reported flooded", a red line on the map and a changed route. With the lens covered it must refuse ("too dark"). Restart the app: the red line is still there; after 3 hours it is gone.
- [ ] #59 rain cue: covering the light sensor outdoors in daylight with a damp forecast hour should show "Rain may be starting" on Home (a cue, not a forecast).
- [ ] #30 chest lab: Diagnostics > Chest lab: sit, phone flat on the chest, 45 s. Compare "Chest pulse" with a fingertip or watch at the same time: within a few beats is a pass. Walk during a second recording: it must say "not still enough". Save the numbers file for tuning (adb pull files/scg_lab_*.csv).
- [ ] Face live view: record a minute (Record button) and count pulse and breaths by hand; compare with the pulse, breathing and variability cards.
- [ ] Shade look-up (Diagnostics): outdoors under open sky and under a tree: the overhead summary should differ.
