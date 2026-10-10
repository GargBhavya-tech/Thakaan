# Thakaan: what it does not do (read before you trust it)

Thakaan is a heat-strain wellbeing tool, not a medical device. It does not diagnose heat illness. ECTemp is patent-covered (US 10,702,165), so commercial use needs a licence.

## What is estimated, not measured
- **Shade is computed, not measured.** It is ray-cast from OpenStreetMap buildings. About 95% of building heights are guessed (3 storeys), so roughly half the street segments are marked low-confidence and treated as more sunny. Trees and awnings are not modelled. Measured corrections from riders only move the estimate slowly.
- **Core temperature is an estimate.** It is a filtered inference from heart rate (and a heat-balance prior), never shown as a number. The worker sees Cool, Warming, Strained or Danger.
- **Fingertip pulse** needs a still finger and a cool phone. Bad captures are rejected, not guessed.
- **WBGT** comes from a cached forecast plus the phone's light sensor, not a globe thermometer. The weather cache covers only 8 to 13 October 2026.

## What is not built or not proven yet
- Verified on the iQOO 15: Keystore signing and tamper detection, ECTemp 7/7, the native WBGT library, offline routing, replay labelling, the Safety checks, five installed voices. **Not yet verified on the phone:** a fingertip pulse that passes the quality gates (none has yet), how the spoken alerts sound, GNSS outdoors and the light sensor in sun and shade. See `docs/DEVICE_RESULTS.md`.
- The language model (Gemma) is not wired in. The template phrasing is used and the model can only re-word it, never change a level.
- The speech-code test has no recogniser yet. The real recorded ride is not captured yet; the demo uses replay and says so (the REPLAY chip).
- H3 cells were skipped; street segments act as cells.

## What the audit log proves
The hash chain and signature prove an entry was not edited after it was recorded. They do not prove the observation was true.

## Privacy limits
Pulse, brain scores and symptoms stay on the phone. The supervisor console sees the level, a trend, minutes since rest and coarse reasons. Position is shared only if the worker turns it on. A person who holds the unlocked phone can see everything on it.

## Safety behaviour you can rely on
Losing or degrading information never lowers the level: missing heart rate, lost location, a blocked light sensor or an unavailable model hold or raise it. This is checked by a property test and by the Safety checks tab.

## Added 2026-10-10 (models, air dose, floods, chest)
- Air dose uses a modelled PM2.5 value for the area (Open-Meteo / Copernicus CAMS), not a station reading and not the worker's own air; no OpenAQ key was obtained. Ventilation from heart rate uses the Greenwald 2019 pooled model, whose single estimates can be off by about 45% (middle half), averaging out over a shift; between heart-rate checks it is a workload estimate. Typical lung capacity and an unknown sex (middle value) are used until entered. There is no shift-report screen yet, so the dose appears on Home only.
- Flood reports: a photo freshness check stops stale or blank reports but cannot prove a road is flooded, so reports show as "reported flooded", never a depth. The BLE / Wi-Fi Aware crew mesh and buddy pairing are NOT built (roadmap line): reports stay on the phone that made them. Rain onset is a cue from light and the cached forecast, not a forecast.
- Chest vibration heart rate works only seated and still and has not been compared with a fingertip yet. Its learned denoiser was trained on simulated data. The continuous version (ScgStream, run by the shift engine) was tuned and tested on synthetic recordings only; the thresholds (motion 0.15 m/s2, tilt 4 deg, 30 s settle, 8% method agreement) are untuned on real people. Alone it can only raise concern (a sustained resting rate above 90 starts the high-rest-HR clock); it never counts as fresh heart evidence and a low chest rate never clears that clock. Breathing rate from the same recording is a by-product, unvalidated.
- The strain GRU, activity CNN, chest denoiser and line-of-sight trees were trained on simulated data (tools/models/train_models.py), run as shadow models and never decide a level; the GRU is no better than the linear forecast and is not used.
- Face pulse, breathing, heart variability and the oxygen estimate come from skin colour and are unconfirmed against a reference; the oxygen figure is uncalibrated unless the worker enters an oximeter reading.
