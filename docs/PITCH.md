# Thakaan pitch (ticket 64): two formats, one claim ledger

Rule: every number spoken must be in the ledger below with its source. If it is not in the ledger, do not say it.
Status of this file: **drafted, not rehearsed.** Ticket 64 is done only after a dry run for someone who has not seen the project, and after ticket 60 (`docs/METRICS.md`) has every row measured. Updated 2026-10-11 with the metrics table; the fingertip pulse and shade accuracy figures are the team's own reported checks, not logged data.

## Claim ledger (what we can say, and what backs it)

| Claim | Number / fact | Source | State |
| --- | --- | --- | --- |
| Works with no network | The app declares no INTERNET permission; Chhaya re-routes with airplane mode on | `dumpsys package` on the iQOO 15; Chhaya run in airplane mode | verified on phone |
| Body model matches the published one | ECTemp 7 of 7 reference vectors pass, on the phone and on the laptop | Diagnostics screen; `ectemp_vectors.json` | verified on phone |
| Cannot be talked into a lower level | Safety checks 6 of 6 pass; turning off a guard turns its row red (guards 1 to 5 seen red on the phone, check 6 unit-tested) | Safety tab; 450+ unit tests, 0 failures | mostly verified |
| Signed log catches editing | Phone Keystore key is hardware backed (StrongBox present); a changed byte and a cut-off end are both caught | Diagnostics, Keystore signing | verified on phone |
| Every alert can be spoken in the worker's language | Voices installed on the phone for en, hi, kn, ta, te | Diagnostics, Voices | availability verified; **how they sound is not** |
| Replay is never passed off as live | Replay shows a REPLAY chip and every stream is flagged non-real | Replay run on the phone | verified on phone |
| Map routes on the phone | At Cool the route is the fastest. At Strained it costs about 30 to 50 s (6 to 11%) extra for +2 to +5 points of shade (150 random trips, two seeds, OSM and satellite layers) | `docs/METRICS.md` row 9, `RouteMetricsTest` | development-time; shade is modelled; say "a minute or less for a little more shade", not "much shadier" |
| Shade accuracy | More than 90% accurate, in the team's own outdoor check | `docs/METRICS.md` row 8 | **reported, no log kept**: say "in our own check", never "validated" |
| Pulse from the fingertip | About 95% accurate against a reference device, in the team's own check; camera holds a steady 30 fps | `docs/METRICS.md` row 1 | **reported, no paired readings logged, development-time**: say "about 95% in our own check against a reference", not "validated", and not for exercise unless row 2 is filled |
| Chest pulse | 70.6 and 73.2 against a counted 70 (two recordings, one person) | `docs/METRICS.md` row 11 | development-time, tuned on the same two; say "early result" |
| Strain accuracy, early-warning lead time | not measured | ticket 60 | **say nothing** |
| Extra map layers | Traffic is an assumed typical pattern, Air is one modelled area value, Phone signal is this phone's own radio | `docs/LIMITATIONS.md` | Traffic and Air seen on the phone; Signal untested (no SIM); say "estimated" every time |
| A rider wants this | TBD | ticket 65 | **no rider quote yet: state the caveat** |

Always say out loud: shade is computed from map data, not measured; the level is an estimate, never a body temperature; this is a heat-strain wellbeing tool, not a medical device; ECTemp needs a licence for commercial use.

## Format 1: table walkaround (for the evaluators, about 5 minutes, hands on)

Lead with Chhaya Route, then the body model. Let them touch the phone.

1. **Hand them the phone on Home.** "This is a rider's phone. Level, one action, no body-temperature number." Point at the REAL chip. Tap Replay 20x and say "replay" aloud while the chip says REPLAY.
2. **Chhaya Route (the thing they remember).** Open Chhaya, turn on airplane mode in front of them, tap a destination. "Offline. The route and the shade are computed on the phone." At Strained it detours to shade and adds a water stop with the seconds shown.
3. **The body model.** Diagnostics: ECTemp 7 of 7. "We reproduce the published equations exactly before we personalise."
4. **Honesty discipline.** Safety tab 6 of 6; flip a guard, the row turns red. "A check that cannot fail proves nothing." Keystore signing: change one byte, the log is flagged.
5. **Let them do the Quick check** (about 80 s for the general check; it depends on the kind of work set in Settings, and a delivery partner's includes the 40 s face check, so about 2 minutes). Tremor, tapping, how do you feel.
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

## The two panels (built 2026-10-11; seen on the phone with the simulated ride, not yet with a person's face on a bike)

- **Delivery partner demo (Ride tab):** at the restaurant, set the drop point, run the pickup check (face, pulse, how you feel), get Fit to ride or Rest 5 minutes and drink water, then start the ride with Simulated ride on: turn-by-turn on top, map and score side by side, the camera on only while riding steadily. Say "simulated" aloud while the chip says SIMULATED.
- **Construction, police or security, other gig:** the Today tab shows the score, the chance of reaching Strained in the next 20 minutes with one action, and when the next check is due.
- Say the chance is a model with fixed settings, not a measurement, and that the riding camera has not been tried on a moving bike.
- If the check reminder is part of the demo, trigger it once beforehand: on one run it did not appear on the first sensing start and the cause is unknown.

## Do not say
- That rider monitoring while riding has been tested on a bike, with a helmet, or at night.
- That the 20-minute chance is validated: it is a model with design constants.
- That the check reminder always appears, or that the drowsiness limits were tuned on riders.
- Any pulse or accuracy figure not in the ledger as verified.
- "Detects heat stroke" (it flags strain and recommends an action).
- That Gemma decides anything: it is wired in and runs on the phone's GPU, but it only re-words a line the risk engine already chose, a wording check can reject it, and the template is the fallback.
- That the 95% pulse or the 90% shade figure is validated, or a held-out result: no held-out session exists.
- That Strained routes are much shadier: the measured gain is a few points of shade for under a minute.
- That the recorded ride is real (replay is labelled replay).

## Before rehearsal
- [~] Ticket 60 numbers filled into the TBD rows (done except rows 2, 4, 5, 10, and no held-out session).
- [ ] Ticket 65: one real rider quote, or say "we have not yet tested this with riders".
- [ ] Listen to one alert in each language and note which sound wrong.
- [ ] Dry run of each format for someone new; time them.
