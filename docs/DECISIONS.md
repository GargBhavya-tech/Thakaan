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
