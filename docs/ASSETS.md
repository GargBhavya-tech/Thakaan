# Pre-fetched assets (ticket #4)

Never download at demo time: venue Wi-Fi with 100+ teams is unreliable. Large files are not committed (see `.gitignore`); stage them on the laptop and push phone-side files by Office Kit. Fill this table as each asset is fetched and verified (it must open on the laptop; log size and date).

| Asset | Needed by | On laptop | On phone | Size | Date | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| Bengaluru OSM extract (4 tiles + demo buildings/canopy; gitignored) | #46, #47 | [x] | [ ] | 57.3 MB | 2026-10-10 | © OpenStreetMap contributors (ODbL) |
| Open-Meteo hourly cache (temp, humidity, wind) for venue area | #18 | [x] | [ ] | <0.1 MB | 2026-10-10 | assets/weather/bengaluru_open-meteo.json, committed |
| `lwbgt` C source (Liljegren WBGT) | #19 | [x] | n/a | 0.4 MB | 2026-10-10 | builds via NDK 26.1 CMake (arm64) into libthakaan_wbgt.so |
| `ectemp_vectors.json` (7 reference cases) | #22 | [x] | [x] | 0.1 MB | 2026-10-10 | assets/ectemp_vectors.json and app/src/main/assets/ |
| Gemma 4 E2B `.litertlm` (~2 GB) | #57 | [x] | [ ] | 1915 MB | 2026-10-10 | assets/models/, gitignored; push to phone by Office Kit / adb |
| OpenAQ API key | #58 | [ ] | n/a | | | stretch; never commit |
| Chhaya graph + shade layer (generated) | #46, #47 | [x] | [x] | 1.0 MB | 2026-10-10 | tools/chhaya/build_graph.py + shade.py; copies in app/src/main/assets/chhaya |

OpenAQ key was not obtained (ticket #58 stays a roadmap line). Water points are listed, quality unverified.

