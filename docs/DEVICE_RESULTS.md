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
- Not tested yet: tremor, tapping and symptom steps, Chhaya map, console clipboard flow, alert speech and voices, Keystore signing, airplane-mode run.
