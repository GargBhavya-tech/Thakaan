# Decisions (ticket #1: scope and submission lock)

| # | Decision | Status | Answer | Date |
| --- | --- | --- | --- | --- |
| 1 | Name | LOCKED | **Thakaan**, sub-product **Chhaya Route**. Use it across form, deck and app. Remove every trace of the old idea (Chaukas) from forms and slides. | 2026-10-10 |
| 2 | Track | LOCKED | **HealthTech** ("heat illness is an occupational-health emergency"). Fallback: Open Innovation. | 2026-10-10 |
| 3 | Role split (A Body / B Brain + World / C Systems + Demo) | OPEN | Names not assigned yet. | |
| 4 | Demo teammate (cool baseline captured early) | OPEN | Not chosen yet. | |

Ticket #1 is not done until rows 3 and 4 have a written answer. Role ownership of tickets is in `CLAUDE.md`.

## 2026-10-10 Engineering decisions (written without the phone)
- ECTemp: published quadratic model reproduces the 7 reference vectors at 1e-9; sigmoid model is the personalised path; NIS gate 3.84; gaps are predict-only so variance grows.
- Safety property (#41): a degraded run is compared with a reference run whose body/brain evidence freezes at the moment of degradation; the degraded run may never show a lower level. Each guard switched off makes the property fail.
- WBGT: lwbgt (Liljegren) via JNI; the C code accepts RH 150% so Kotlin validates ranges; missing native lib or bad inputs return null, which the risk engine treats as hot. UTCI is a secondary index transpiled from pythermalcomfort, not used for anchors.
- Brain battery: robust z (median/MAD) vs same worker, device, placement and time-of-day bucket; no baseline means no judgement; context prior can only down-weight when exposure is low and a benign cause is known; unknown answers are neutral.
- Chhaya routing: own Dijkstra over an OSM-derived graph, not GraphHopper/Valhalla (no JVM-heavy dependency, runs offline in milliseconds). Edge cost = time x (1 + lambda(TSS) x sun) plus a heat-island penalty; lambda is 0 below TSS 30 and 3 by TSS 75. Detour budgets +60 s at Warming, +180 s at Strained; Danger goes to the nearest cool seated stop.
- Shade is computed by ray-casting in Python (tools/chhaya/shade.py), not measured. About 95% of building heights are guessed (3 storeys x 3.2 m); those segments are marked low-confidence and their sun fraction is pushed halfway to full sun. Measured corrections (ShadeBelief) overlay the computed layer.
- Console privacy: the console receives level, trend and coarse reasons only. `ConsoleSafe` maps driver codes to category labels on the phone, so a driver text quoting a number or symptom cannot leak. Position is opt-in and coarse.
- Audit chain: SHA-256 hash chain plus ECDSA P-256 signature, verified in the browser with WebCrypto and cross-checked against Kotlin-generated fixtures. A valid chain proves no edits after recording, not that the observations were true.
- WBGT vs UTCI: WBGT (Liljegren) drives anchors; UTCI is secondary and display-only.
- Fail-safe asymmetries: heat-balance prior cooling clamped to -0.002 C/min and warming to +0.05 C/min; the forecast never extrapolates a falling score downward; the adaptive filter is used only through max(plain, adaptive) for anchors.
- Profile defaults are cautious: unknown acclimatisation = 0 days (earlier anchor), unknown resting HR = 70, location sharing off.
- Explainer: template line is always built first; a model line is accepted only if it states the engine's own minute figure, adds no other numbers, quotes no temperature or pulse, and contains no reassurance. Otherwise the template is used. The level and action never come from model text.
