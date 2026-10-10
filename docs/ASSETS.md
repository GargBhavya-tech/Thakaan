# Pre-fetched assets (ticket #4)

Never download at demo time: venue Wi-Fi with 100+ teams is unreliable. Large files are not committed (see `.gitignore`); stage them on the laptop and push phone-side files by Office Kit. Fill this table as each asset is fetched and verified (it must open on the laptop; log size and date).

| Asset | Needed by | On laptop | On phone | Size | Date | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| Bengaluru OSM extract (4 tiles + demo buildings/canopy; gitignored) | #46, #47 | [x] | [ ] | 57.3 MB | 2026-10-10 | © OpenStreetMap contributors (ODbL) |
| Open-Meteo hourly cache (temp, humidity, wind) for venue area | #18 | [x] | [ ] | <0.1 MB | 2026-10-10 | assets/weather/bengaluru_open-meteo.json, committed |
| `lwbgt` C source (Liljegren WBGT) | #19 | [x] | n/a | 0.4 MB | 2026-10-10 | builds via NDK 26.1 CMake (arm64) into libthakaan_wbgt.so |
| `ectemp_vectors.json` (7 reference cases) | #22 | [x] | [x] | 0.1 MB | 2026-10-10 | assets/ectemp_vectors.json and app/src/main/assets/ |
| Gemma 4 E2B `.litertlm` (~2 GB) | #57 | [x] | [x] pushed 2026-10-10 | 1915 MB | 2026-10-10 | assets/models/, gitignored; push to phone by Office Kit / adb |
| MediaPipe `face_landmarker.task` (face detector, 478 landmarks, blendshapes) | #31 | [x] | [x] (inside the APK) | 3.76 MB | 2026-10-10 | app/src/main/assets/models/, gitignored; from storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/; library com.google.mediapipe:tasks-vision:0.10.21 from Google Maven |
| Vosk `vosk-model-small-en-in-0.4` and `vosk-model-small-hi-0.22` (digit recogniser) | #34 | [x] | [x] pushed 2026-10-10 | 55 MB + 79 MB unpacked | 2026-10-10 | from alphacephei.com/vosk/models, Apache-2.0; assets/models/vosk/ (gitignored); `tools/push_models.sh`; library com.alphacephei:vosk-android:0.3.75 |
| EfficientPhys `efficientphys.tflite` (learned face pulse, 72x72) | #31 | [x] | [x] (inside the APK) | 8.7 MB | 2026-10-10 | pretrained UBFC-rPPG checkpoint from ubicomplab/rPPG-Toolbox (Responsible-AI licence, trained on public face datasets: clear before commercial use); converted by `tools/models/convert_efficientphys.py`, matches PyTorch to 2e-6 |
| SegFormer-B0 Cityscapes `segformer_b0_cityscapes.tflite` (sky / tree / building view) | #47 cross-check | [x] | [x] (inside the APK) | 15 MB | 2026-10-10 | nvidia/segformer-b0-finetuned-cityscapes-512-1024 (NVIDIA Source Code License: non-commercial research use only); converted by `tools/models/convert_segformer.py`. Replaces the YOLO-seg idea: YOLO/DeepLab classes have no sky, tree or building |
| Four small models trained on SIMULATED data: `activity_cnn.tflite`, `scg_denoiser.tflite`, `strain_gru.tflite`, `los_xgboost.json` | #7, #30, #44, #36 | [x] | [x] (inside the APK) | 0.2 MB | 2026-10-10 | `tools/models/train_models.py` (fixed seeds); gitignored; shadow models, never deciding a level |
| Whisper-small TFLite | #34 | [ ] | [ ] | | | not fetched: no ready-made TFLite of Whisper-small exists (only tiny/base from argmaxinc/whisperkit-litert, ~290 MB for base); Vosk is used instead |
| Open-Meteo hourly PM2.5 cache (Bengaluru, 7 days; modelled CAMS value, CC BY 4.0) | #58 | [x] | [x] (inside the APK) | 4 KB | 2026-10-10 | assets/airquality/, replaces the OpenAQ key which was not obtained; verified by `tools/verify_assets.py` (docs/ASSET_LOG.md) |
| OpenAQ API key | #58 | [ ] | n/a | | | stretch; never commit |
| Chhaya graph + shade layer (generated) | #46, #47 | [x] | [x] | 1.0 MB | 2026-10-10 | tools/chhaya/build_graph.py + shade.py; copies in app/src/main/assets/chhaya |

OpenAQ key was not obtained (ticket #58 stays a roadmap line). Water points are listed, quality unverified.

