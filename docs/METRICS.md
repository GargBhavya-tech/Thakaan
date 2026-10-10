# Metrics table (ticket #60)

Every number has a label. **Development-time** means we looked at it while building, so it may be tuned to. **Held-out** means a session nobody tuned against. **There is no held-out session yet**, so nothing below is held-out, and the pitch must not call any of it validation. "NOT MEASURED" rows are waiting for a reference or a person (see "What I need from you" at the bottom). Reference devices (oximeter, watch) are for development only and are never part of the product.

| # | Metric | Value | Label | How it was measured |
| --- | --- | --- | --- | --- |
| 1 | Fingertip heart-rate accuracy vs a reference | about 95% accurate (reported by the team on 2026-10-11) | development-time, REPORTED: no paired readings were logged, so the number cannot be reproduced or checked yet | checked by hand against a reference device; reference, number of readings, rest vs exercise and the meaning of 95% (within 5% of the reference?) are not recorded |
| 2 | Fingertip heart-rate error vs a reference, after exercise | NOT SEPARATED: the report above does not say whether it includes post-exercise readings | n/a | needs its own paired readings |
| 3 | Fingertip capture rejection rate | 3 of 4 real captures rejected (75%) | development-time, n = 4 | PPG lab, one person, hand-held vs on a table (`DEVICE_RESULTS.md`, fourth run). Too few to quote as a rate |
| 4 | Quick-check duration | 95 s with the speech step, 82 s without | design value, NOT stopwatch-timed | sum of the step lengths in `QuickCheckPlan` (fingertip step is 35 s: 10 s settle + 25 s measured) |
| 5 | Full-check duration | 60 s of clean pulse for HRV | design value, NOT stopwatch-timed | rule: no HRV from less than 60 s |
| 6 | ECTemp reference vectors passed | 7 of 7, with personalisation off | development-time (fixed vectors, deterministic) | laptop unit test and on the phone (Diagnostics), seen 2026-10-10 |
| 7 | Safety checks passed | 5 of 5 on the phone; check 6 added later (unit-tested, not yet seen on the phone); property test passes on the laptop | development-time | Safety checks tab, guards 1 to 5 turned their row red by hand |
| 8 | Shade accuracy vs real light | more than 90% accurate (reported by the team on 2026-10-11) | development-time, REPORTED: no log kept, so not reproducible yet | checked by hand outdoors; which layer (OSM or satellite), how many places, what was compared with (eyes or the light sensor) and the meaning of 90% are not recorded |
| 9 | Route extra time vs shade gained, per level | see the route table below | development-time, modelled shade | `RouteMetricsTest` on the real Bengaluru graph, 150 random pairs 1.5 to 4 km apart, two seeds |
| 10 | Time from collapse to alarm | at least 23 s after the impact (8 s of stillness, then a 15 s countdown that "haan" or the I'm-OK button cancels) | design value; countdown start and alarm both seen on the phone, NOT stopwatch-timed | constants in `CollapseRules` and `CountdownMachine` |
| 11 | Chest pulse vs a counted pulse | 70.6 and 73.2 against a counted 70 (errors 0.6 and 3.2 beats) | development-time, n = 2, one person; the lab tolerance was set from these same two recordings | Chest lab, 2026-10-10 |
| 12 | Offline run | route computed with airplane mode on; app holds no INTERNET permission (`dumpsys package`); route takes 1 to 14 ms on the laptop, instant on the phone | development-time | `DEVICE_RESULTS.md` second and third runs; `RouteMetricsTest` timings |

## Route table (row 9)

Median over 150 random walking trips per run, afternoon 16:15 (hottest slot in the table), OSM shade layer. "Changed" is the share of trips where the strain-aware route is not the fastest one. Shade gain is in percentage points of route length compared with the fastest route.

| Level | Seed 1: changed / extra time / shade gain | Seed 2: changed / extra time / shade gain |
| --- | --- | --- |
| Cool | 0% / none / 0 | 0% / none / 0 |
| Warming | 65% / +4 s (1.0%) / +2.5 | 64% / +3 s (0.7%) / +2.0 |
| Strained | 86% / +30 s (6.7%) / +4.8 | 90% / +51 s (11.5%) / +2.4 |

The satellite-height layer and the 11:00 and 14:00 slots are in `app/build/metrics_routes.txt` after running `RouteMetricsTest`; they give the same picture: Strained costs about 25 to 50 s (6 to 11%) for a median gain of 1 to 3 points of shade, mean about 3 to 4.5 points.

**What this says, plainly:**
- Cool always takes the fastest route, as designed.
- The shade gain is **small**: +2 to +5 points of route length at Strained (OSM layer: the fastest route is only about 18% shaded at 16:15, the strain-aware one about 21 to 23%; satellite layer: about 34% rising to about 37%). There is shade to find, so this is not a shortage of shade: the detour budget (a share of the trip time) is what limits the gain, because a shadier street usually means a longer walk. Do not claim that Thakaan finds much shadier routes; claim that it never costs time at Cool, and at Strained trades under a minute for a few points of shade. If a bigger difference is wanted, the budget is the knob, and the cost is more walking in the heat.
- The two seeds agree on direction and on Cool and Warming, but Strained extra time differs by about a factor of 1.7 (30 s vs 51 s median) because 150 trips is a small sample. That is the margin the "reproduces" test allows; quote a range, not a point.
- Shade is modelled, not measured, and has not been compared with real light.

## What I need from you

1. **Rows 1 and 2:** to turn the reported 95% into a number that reproduces, I need the paired readings: for each, the fingertip heart rate, the reference heart rate, and whether it was at rest or after exercise. Even 10 pairs typed from memory or from photos is enough to compute the error and the share within 5%.
2. **Row 8:** which shade layer you checked, roughly how many streets or points, and how you judged "accurate" (by eye, or with the phone's light sensor). If you can, one 15-minute walk with sun or shade written down every 2 minutes lets me compute it from the app's own log.
3. **Rows 4, 5, 10:** with a stopwatch, time 3 quick checks start to summary, and 3 collapse tests (lay the phone down still after a tilt) from the tilt to the alarm.
4. **The held-out session:** decide which one recording or walk nobody touches until this table is final (the C owner). Without it every row stays development-time.
