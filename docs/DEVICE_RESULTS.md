# Results from the first run on the iQOO 15 (I2501, Android 16), 2026-10-10

Verified on the phone:
- App installs and launches; onboarding runs through all five steps, rejects bad weight and a too-short phone number, and is skipped on relaunch.
- Live sensing: accelerometer and gyro 200 Hz, light 10 Hz, all REAL, foreground service running. GNSS and the colour-temperature sensor produced no data (GNSS: tested indoors only).
- Diagnostics: ECTemp 7/7 vectors pass on device (#22), `libthakaan_wbgt.so` loads and gives a plausible WBGT (#19), Safety checks 5/5 (#42), thermal headroom and status readable (#12 inputs).
- Camera2 capture (#8): opens, streams a steady 30 fps (mean 33.4 ms, one dropped frame in 743), manual exposure/ISO/torch applied.
- A capture with no finger is rejected with the right prompt; the quick-check flow handles retry and skip.

Found and fixed on the phone:
- Screens drew under the status bar and cutout (insets now handled in MainActivity).
- A fixed 8 ms exposure gave a nearly black image through a fingertip (red 13 of 255). Exposure is now re-aimed during the 10 s settle window to a red mean near 150, then frozen; torch is at full strength and the pixel patch is 200x200.
- The torch-dimmed check misread that deliberate exposure change as a dimming torch; it now looks only at the analysed window.

Not yet passing:
- No fingertip capture has passed the quality gates yet. Best values: perfusion 0.4 to 1.7% (gate 0.3), beat correlation 0.63 to 0.79 (gate 0.85), signal-to-noise -4 to 0 dB (gate 5), spectral and beat heart rates within 5 bpm in the last two runs (72/68 and 80/75). The gates were never calibrated on real data, and there is no reference heart rate yet, so they were NOT loosened.
- Next: capture 5+ clean attempts next to a pulse oximeter or watch, try the 60 s FULL check (more data, better SNR), then decide gate values from that data.
- Not tested yet: console clipboard flow, alert speech and voices (Google TTS is installed; which of hi/kn/ta/te voices exist is unchecked), Keystore signing, gait, GNSS outdoors, light in sun/shade/pocket.

Second run (same day, tested without a human where possible):
- Quick check end to end: the hand-hold (tremor) step ran and completed; the symptoms step recorded; the summary lists each step as done, unusable or skipped with its reason.
- Tapping step: adb-injected taps are rejected ("too few taps", then "timestamps unusable"). That is inconclusive, not a pass: injected taps are not finger input. It needs five real runs by hand (#33).
- Chhaya (#46-#49): map renders, route computes, new destination re-routes instantly with AIRPLANE MODE ON; shade note and OSM credit shown; 38% and 49% shade on two routes, 9 to 35% of each route on guessed heights.
- Safety tab (#42): 5/5 on device. Flipping each guard turned its check red (guards 1, 2, 3, 4 and 5 checked by hand). The "Missing weather counts as hot" guard turned NOTHING red: a check that cannot fail. Fixed by adding check 6; unit-tested (282 tests green), not yet re-seen on the phone.
- Found: a phone left still after being moved started the collapse countdown, then ALARM "help is being called" with no way to cancel (the text said "tap" but no button existed, and the alarm never cleared). Fixed: "I'm OK" button; it ends the countdown or clears the alarm. Not yet seen on the phone. The detector's "phone on a desk" suppression is still never switched on, so putting the phone down after carrying it can start a countdown; the countdown plus the button is the safeguard.

Hand-tested by the user on the iQOO 15 (same day):
- Fitts tapping step (#33): runs and is accepted with real finger taps (the adb-injected taps were the problem, not the code). Five baseline runs and the non-dominant-hand comparison are still to do.
- Collapse (#15): tilting the phone upright to flat and leaving it still starts the "Are you OK?" countdown. The first attempt did not, because the phone was held in the hand; the detector needs it nearly motionless, by design. The "I'm OK" button's effect, the alarm clearing, and a drop-onto-cushion test have not been reported yet.

Third run (checked by Claude on the phone, no human needed):
- Keystore (#54): key created, hardware backed true, StrongBox feature present; export verifies OK; one changed byte caught (BROKEN, entry content was changed); last entry cut off caught (BROKEN, expected 3 entries, found 2); changed data fails the signature. Shown in Diagnostics, "Device self-tests".
- Voices (#40): en-IN, hi-IN, kn-IN, ta-IN, te-IN all report "voice installed". Claude cannot hear them: wording and pronunciation still need a human ear.
- No network (invariant 1): `dumpsys package` shows no INTERNET permission. Found a dependency adding READ_PHONE_STATE and storage permissions; removed in the manifest.
- Replay (invariant 6): Replay 20x shows the REPLAY chip and every stream reports REPLAY / non-real.
- Daily check-in (#28): shown once, three taps, saved, not shown again after relaunch.
- Stream stability (#5), partial: sensing ran 08:18 to 08:29 (11 minutes) with the screen on; the gauge stayed REAL on every 2-minute sample and thermal status stayed 0. Per-stream rates were not sampled during the run, so the "no dropped stream for 10 minutes" criterion is only partly met. (The last sample showed the Chhaya screen: someone opened it during the run.)

Ticket sheet corrected (same day): #22 ticked [x] (its Done when, Diagnostics 7/7, was seen on the phone). #8 is [~]: steady 30 fps verified indoors, but no visible pulse trace yet and not tried in sun and shade. #23 is [~]: the vector, 12-minute gap and 160 bpm spike tests pass on the laptop; that the personalised filter is switched on in the live engine is not yet confirmed. #55 is [~]: REPLAY and REAL chips seen on Home and Chhaya during replay and live sensing; not yet checked on every screen.
