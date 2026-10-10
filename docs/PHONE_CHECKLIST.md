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
