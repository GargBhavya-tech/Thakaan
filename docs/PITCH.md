# Thakaan pitch (ticket 64): two formats, one claim ledger

Rule: every number spoken must be in the ledger below with its source. If it is not in the ledger, do not say it.
Status of this file: **drafted, not rehearsed.** Ticket 64 is done only after a dry run for someone who has not seen the project, and after ticket 60 (metrics) supplies the accuracy numbers marked TBD.

## Claim ledger (what we can say, and what backs it)

| Claim | Number / fact | Source | State |
| --- | --- | --- | --- |
| Works with no network | The app declares no INTERNET permission; Chhaya re-routes with airplane mode on | `dumpsys package` on the iQOO 15; Chhaya run in airplane mode | verified on phone |
| Body model matches the published one | ECTemp 7 of 7 reference vectors pass, on the phone and on the laptop | Diagnostics screen; `ectemp_vectors.json` | verified on phone |
| Cannot be talked into a lower level | Safety checks 6 of 6 pass; turning off a guard turns its row red (guards 1 to 5 seen red on the phone, check 6 unit-tested) | Safety tab; 286 unit tests, 0 failures | mostly verified |
| Signed log catches editing | Phone Keystore key is hardware backed (StrongBox present); a changed byte and a cut-off end are both caught | Diagnostics, Keystore signing | verified on phone |
| Every alert can be spoken in the worker's language | Voices installed on the phone for en, hi, kn, ta, te | Diagnostics, Voices | availability verified; **how they sound is not** |
| Replay is never passed off as live | Replay shows a REPLAY chip and every stream is flagged non-real | Replay run on the phone | verified on phone |
| Map routes on the phone | Fastest route 2.7 to 3.0 km; shade 38% and 49% on two test routes | Chhaya screen | verified on phone; shade is estimated |
| Pulse from the fingertip | Camera holds a steady 30 fps; **no capture has passed the quality gates yet** | `docs/DEVICE_RESULTS.md` | **do not claim a working pulse** |
| Pulse / strain accuracy, early-warning lead time | TBD | ticket 60 | **not measured: say nothing** |
| A rider wants this | TBD | ticket 65 | **no rider quote yet: state the caveat** |

Always say out loud: shade is computed from map data, not measured; the level is an estimate, never a body temperature; this is a heat-strain wellbeing tool, not a medical device; ECTemp needs a licence for commercial use.

## Format 1: table walkaround (for the evaluators, about 5 minutes, hands on)

Lead with Chhaya Route, then the body model. Let them touch the phone.

1. **Hand them the phone on Home.** "This is a rider's phone. Level, one action, no body-temperature number." Point at the REAL chip. Tap Replay 20x and say "replay" aloud while the chip says REPLAY.
2. **Chhaya Route (the thing they remember).** Open Chhaya, turn on airplane mode in front of them, tap a destination. "Offline. The route and the shade are computed on the phone." At Strained it detours to shade and adds a water stop with the seconds shown.
3. **The body model.** Diagnostics: ECTemp 7 of 7. "We reproduce the published equations exactly before we personalise."
4. **Honesty discipline.** Safety tab 6 of 6; flip a guard, the row turns red. "A check that cannot fail proves nothing." Keystore signing: change one byte, the log is flagged.
5. **Let them do the 90-second check** (tremor, tapping, how do you feel). Skip the finger step unless the pulse has been calibrated by then.
6. **Close with limits** (first three bullets of `docs/LIMITATIONS.md`).

## Format 2: stage narrative (timed, 3 minutes)

| Time | Beat | Say / show |
| --- | --- | --- |
| 0:00 | The problem | A delivery rider, noon, 41 C. Heat illness arrives without a warning, and the phone is already in their hand. |
| 0:20 | What Thakaan is | One phone, no wearable, no network. It senses body, brain and sun and turns them into one level. |
| 0:40 | Replay the shift | Replay at 20x. Cool, Warming, Strained. Say "replay" while the chip says REPLAY. |
| 1:10 | The alert | One action, spoken in the worker's language, with its time cost. Switch Hindi to Kannada. |
| 1:30 | Chhaya Route | Airplane mode on. Same trip at Cool: fastest. At Strained: shade and a water stop. At Danger: nearest cool seat. |
| 2:10 | Why believe it | ECTemp 7 of 7; Safety 6 of 6 with a guard flipped red; signed log, one byte changed, flagged. Provenance chips on everything. |
| 2:40 | The limits | Shade is estimated; the level is an estimate; not a medical device. Pulse accuracy: say what ticket 60 measured, or say it is not measured yet. |
| 2:55 | Ask | Rider feedback and a licence conversation for ECTemp. |

## Do not say
- Any pulse or accuracy figure not in the ledger as verified.
- "Detects heat stroke" (it flags strain and recommends an action).
- That Gemma writes the alerts (it is not wired in; templates are used, and a model could only re-word them).
- That the recorded ride is real (replay is labelled replay).

## Before rehearsal
- [ ] Ticket 60 numbers filled into the TBD rows.
- [ ] Ticket 65: one real rider quote, or say "we have not yet tested this with riders".
- [ ] Listen to one alert in each language and note which sound wrong.
- [ ] Dry run of each format for someone new; time them.
