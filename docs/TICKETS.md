# Thakaan — Ticket Tracker

Generated from `docs/Thakaan — Build Map v1.md` (65 tickets). Tick a box only when the ticket's **Done when** passes its own **Test**. Golden rule: don't start a ticket until its blockers are ticked.

Owner: A = Body, B = Brain + World, C = Systems + Demo, all = shared. Priority: 🟥 CORE · 🟨 SUPPORTING · ⬜ SEED. Light: ⚪ PRE-BUILD · 🟢 GREEN · 🔴 RED-OK.

**Status note:** `[~]` = code written and laptop unit tests green, but the device-only part (camera, GNSS, light sensor, speech, haptics, NPU, human-in-the-loop) is UNTESTED on the phone. #41 is `[x]` because its Test runs on the laptop (150+ tests, guard-off-must-fail proofs). Tickets 9-11, 13-21, 24-27, 29, 32-40, 42, 46-48, 50, 54 are in this state; see docs/PHONE_CHECKLIST.md when it exists.


## Phase 0: Pre-build decisions and foundations

| Done | # | Ticket | Owner | Priority | Light | Checkpoint | Blocked by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [~] | 1 | Scope and submission lock (name + track locked; roles, demo teammate open: see DECISIONS.md) | all | 🟥 CORE | ⚪ PRE-BUILD | before coding | — |
| [~] | 2 | Loaner smoke test (10 of 11 items measured, GNSS L5 needs outdoor re-run: see SMOKE_TEST.md) | all | 🟥 CORE | 🔴 RED-OK | first hour | — |
| [ ] | 3 | Office Kit loop rehearsed | all | 🟥 CORE | ⚪ PRE-BUILD | CP-A | — |
| [ ] | 4 | Assets pre-fetched | all | 🟥 CORE | 🟢 GREEN | first Green window | — |
| [~] | 5 | App skeleton, sensor hub and provenance (10-min run OK for IMU + light, all REAL; GNSS stream needs outdoor check; OriginOS lock/battery settings by hand) | C | 🟥 CORE | 🔴 RED-OK | CP-A | #2 |
| [x] | 6 | Inference seam and Diagnostics screen | C | 🟥 CORE | 🟢 GREEN | CP-A | #5 |
| [~] | 7 | Stream recorder and replay harness (unit tests + phone replay verified; 5-min walking recording still to do) | C | 🟥 CORE | 🔴 RED-OK | CP-A | #5 |

## Phase 1: Fingertip PPG (the heart spine)

| Done | # | Ticket | Owner | Priority | Light | Checkpoint | Blocked by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [ ] | 8 | Camera2 lock and torch capture | A | 🟥 CORE | 🔴 RED-OK | CP-A | #5 |
| [~] | 9 | PPG signal pipeline to heart rate | A | 🟥 CORE | 🔴 RED-OK | CP-A | #8 |
| [~] | 10 | Signal-quality gates and retry UX | A | 🟥 CORE | 🔴 RED-OK | CP-A | #9 |
| [~] | 11 | Quick and full checks | A | 🟥 CORE | 🔴 RED-OK | CP-A | #10 |
| [~] | 12 | Thermal guard for captures | A | 🟨 SUPPORTING | 🔴 RED-OK | CP-B | #11 |

## Phase 2: Motion and context

| Done | # | Ticket | Owner | Priority | Light | Checkpoint | Blocked by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [~] | 13 | Activity, workload and mode detection | B | 🟥 CORE | 🔴 RED-OK | CP-A | #5, #7 |
| [~] | 14 | Stop detector and posture clock | B | 🟥 CORE | 🔴 RED-OK | CP-A | #13 |
| [~] | 15 | Collapse detection with countdown | B | 🟥 CORE | 🔴 RED-OK | CP-A | #14 |

## Phase 3: Environment and exposure

| Done | # | Ticket | Owner | Priority | Light | Checkpoint | Blocked by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [~] | 16 | Sun / shade state (E1) | B | 🟥 CORE | 🔴 RED-OK | CP-A | #2, #5 |
| [~] | 17 | Irradiance and sun dose (E15, E2) | B | 🟥 CORE | 🔴 RED-OK | CP-A | #16 |
| [~] | 18 | Weather cache and local air temperature (E9, E16) | B | 🟥 CORE | 🔴 RED-OK | CP-A | #4, #5 |
| [~] | 19 | Apparent wind and Liljegren WBGT (E17, E18) | B | 🟥 CORE | 🟢 GREEN | CP-A | #4, #17, #18 |
| [~] | 20 | Rest ledger and drink log (E7, E8) | B | 🟥 CORE | 🔴 RED-OK | CP-A | #14, #16 |
| [~] | 21 | GNSS sky view (E12, E13, E14) | B | 🟨 SUPPORTING | 🔴 RED-OK | CP-B | #2, #16 |

## Phase 4: Body model

| Done | # | Ticket | Owner | Priority | Light | Checkpoint | Blocked by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [ ] | 22 | ECTemp core filter and test vectors | A | 🟥 CORE | 🔴 RED-OK | CP-A | #4, #6 |
| [ ] | 23 | Personalisation, gating and start-of-shift | A | 🟥 CORE | 🔴 RED-OK | CP-B | #22, #11 |
| [~] | 24 | Gagge / PHS heat-balance prior | A | 🟥 CORE | 🔴 RED-OK | CP-B | #13, #17, #18, #19, #22 |
| [~] | 25 | Hydration deficit (P15) | A | 🟥 CORE | 🔴 RED-OK | CP-B | #20, #24 |
| [~] | 26 | Excess HR and idle-state drift (P9, P10) | A | 🟥 CORE | 🔴 RED-OK | CP-B | #14, #23, #24 |
| [~] | 27 | Sit-to-stand and perfusion collapse (P13, P17) | A | 🟥 CORE | 🔴 RED-OK | CP-B | #11, #14 |
| [~] | 28 | Onboarding and cool baseline | A | 🟥 CORE | 🔴 RED-OK | CP-B | #11, #27 |
| [~] | 29 | Adaptive R and drift handling | A | 🟨 SUPPORTING | 🔴 RED-OK | CP-B | #26 |
| [ ] | 30 | Chest SCG (P3) | A | 🟨 SUPPORTING | 🟢 GREEN | Stretch | #6, #13 |
| [ ] | 31 | Face rPPG and forehead thermistor (P1, P14) | A | ⬜ SEED / PITCH-ONLY | 🟢 GREEN | Stretch | #6, #8 |

## Phase 5: Brain battery

| Done | # | Ticket | Owner | Priority | Light | Checkpoint | Blocked by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [~] | 32 | Tremor hold (N9) | B | 🟥 CORE | 🔴 RED-OK | CP-A | #13 |
| [~] | 33 | Fitts tapping (N4) | B | 🟥 CORE | 🔴 RED-OK | CP-A | #5 |
| [~] | 34 | Speech code (N3) | B | 🟨 SUPPORTING | 🟢 GREEN | CP-B | #6 |
| [~] | 35 | Symptom tap (N10) | B | 🟥 CORE | 🔴 RED-OK | CP-A | #5 |
| [~] | 36 | CUSUM engine and context prior | B | 🟥 CORE | 🔴 RED-OK | CP-B | #28, #32, #33 |
| [~] | 37 | Gait instability (N1) | B | 🟨 SUPPORTING | 🔴 RED-OK | CP-B | #13, #36 |
| [~] | 38 | Riding smoothness and brief PVT (N2, N11) | B | 🟨 SUPPORTING | 🔴 RED-OK | Stretch | #36 |

## Phase 6: Risk engine, alerts and safety

| Done | # | Ticket | Owner | Priority | Light | Checkpoint | Blocked by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [~] | 39 | Thakaan Strain Score and anchored levels | C | 🟥 CORE | 🔴 RED-OK | CP-A (v1), CP-B (full) | #11, #19, #20, #35 |
| [~] | 40 | Alert ladder | C | 🟥 CORE | 🔴 RED-OK | CP-A | #15, #39 |
| [x] | 41 | Safety-invariant property test | C | 🟥 CORE | 🟢 GREEN | CP-B | #39 |
| [~] | 42 | Safety checks tab with guard switches | C | 🟥 CORE | 🔴 RED-OK | FINAL | #6, #41 |
| [~] | 43 | Cooling coach | C | 🟨 SUPPORTING | 🔴 RED-OK | CP-B | #11, #40 |
| [~] | 44 | Strain forecast | C | 🟨 SUPPORTING | 🟢 GREEN | Stretch | #39 |
| [~] | 45 | Quick-check flow | C | 🟥 CORE | 🔴 RED-OK | CP-A | #11, #32, #33, #35, #39 |

## Phase 7: Chhaya Route

| Done | # | Ticket | Owner | Priority | Light | Checkpoint | Blocked by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [~] | 46 | OSM bundle and offline graph | B | 🟥 CORE | 🟢 GREEN | CP-B | #4 |
| [~] | 47 | Computed shadow layer | B | 🟥 CORE | 🟢 GREEN | CP-B | #46 |
| [~] | 48 | Strain-aware routing | B | 🟥 CORE | 🔴 RED-OK | CP-B | #39, #47 |
| [~] | 49 | Chhaya map screen | B | 🟥 CORE | 🔴 RED-OK | CP-B | #48 |
| [~] | 50 | Light-map logging and aggregation | B | 🟨 SUPPORTING | 🔴 RED-OK | CP-B | #16, #21, #46 |
| [ ] | 51 | Recorded ride and 20x replay | B | 🟥 CORE | 🔴 RED-OK | CP-B | #7, #49 |

## Phase 8: Systems, Office Kit and console

| Done | # | Ticket | Owner | Priority | Light | Checkpoint | Blocked by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [ ] | 52 | Office Kit live during Red Light | C | 🟥 CORE | 🔴 RED-OK | CP-A | #3, #5 |
| [~] | 53 | Supervisor console | C | 🟥 CORE | 🟢 GREEN | CP-B | #39, #52 |
| [~] | 54 | Hash-chained exposure log and verifier | C | 🟥 CORE | 🔴 RED-OK | FINAL | #39, #53 |
| [ ] | 55 | Provenance chips everywhere | C | 🟥 CORE | 🔴 RED-OK | FINAL | #5, #49, #53 |
| [~] | 56 | Airplane-mode proof | C | 🟥 CORE | 🔴 RED-OK | CP-B | #40, #48 |

## Phase 9: Optional and cut-first

| Done | # | Ticket | Owner | Priority | Light | Checkpoint | Blocked by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [~] | 57 | Gemma coach and explainer | all | 🟨 SUPPORTING | 🟢 GREEN | Stretch | #4, #6, #39 |
| [ ] | 58 | Breathing-dose card | all | ⬜ SEED / PITCH-ONLY | 🔴 RED-OK | Stretch | #11, #13, #18 |
| [ ] | 59 | Flood reports and crew mesh | all | ⬜ SEED / PITCH-ONLY | 🔴 RED-OK | Stretch | #48, #53 |

## Phase 10: Proof and demo (do not skip)

| Done | # | Ticket | Owner | Priority | Light | Checkpoint | Blocked by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [ ] | 60 | Metrics table | C | 🟥 CORE | 🔴 RED-OK | CP-B | #11, #39, #48 |
| [ ] | 61 | Demo built and rehearsed | C | 🟥 CORE | 🔴 RED-OK | FINAL | #42, #45, #51, #56 |
| [ ] | 62 | Limitations slide and challenge answers | C | 🟥 CORE | ⚪ PRE-BUILD | CP-A | — |
| [ ] | 63 | Backup recording | C | 🟥 CORE | 🔴 RED-OK | FINAL | #61 |
| [ ] | 64 | Pitch narrative, both formats | C | 🟥 CORE | 🔴 RED-OK | FINAL | #60, #61, #62 |
| [ ] | 65 | Real-worker feedback | C | 🟨 SUPPORTING | ⚪ PRE-BUILD | FINAL | — |






