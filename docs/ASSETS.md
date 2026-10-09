# Pre-fetched assets (ticket #4)

Never download at demo time: venue Wi-Fi with 100+ teams is unreliable. Large files are not committed (see `.gitignore`); stage them on the laptop and push phone-side files by Office Kit. Fill this table as each asset is fetched and verified (it must open on the laptop; log size and date).

| Asset | Needed by | On laptop | On phone | Size | Date | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| Bengaluru OSM extract | #46, #47 | [ ] | [ ] | | | © OpenStreetMap contributors (ODbL) |
| Open-Meteo hourly cache (temp, humidity, wind) for venue area | #18 | [ ] | [ ] | | | |
| `lwbgt` C source (Liljegren WBGT) | #19 | [ ] | n/a | | | compile with NDK |
| `ectemp_vectors.json` (7 reference cases) | #22 | [ ] | [ ] | | | team already has it from earlier Python/TS ports |
| Gemma 4 E2B `.litertlm` (~2 GB) | #57 | [ ] | [ ] | | | stretch |
| OpenAQ API key | #58 | [ ] | n/a | | | stretch; never commit |
