# Limitations slide and judge Q&A (ticket #62, draft for the team to rehearse)

Naming the limits yourself scores better than a judge finding them. This is drafted from what the phone and the laptop have actually shown (see `DEVICE_RESULTS.md` and `LIMITATIONS.md`); every line is something we can defend today. Each team member should be able to say each answer below in one sentence without reading it.

## The slide (shown before the close)

**What Thakaan cannot do yet**

1. **It does not measure body temperature.** It estimates strain from heart rate and heat exposure. Estimates like this lose sensitivity above about 39 degrees, so every action fires at 38.5 or earlier, and a separate resting-heart-rate rule backs it up.
2. **Heart checks are spot readings.** The fingertip and face pulse need a still finger or face and light; bad captures are rejected, not guessed. We have not yet compared our pulse against a pulse oximeter over many people.
3. **The studies behind the models are narrow.** Mostly young, Western, often athletic men. Nobody has validated them on South Asian workers, women, older workers or budget phones in motion. Neither have we.
4. **Brain signals are drift signals, not a diagnosis.** Tapping, tremor, gait and speech are compared with the same worker's own baseline. Nobody has shown they detect heat illness.
5. **Shade is computed, not measured.** Ray-cast from building heights and tree canopy, with low-confidence streets treated as sunny. Not yet compared with real light outdoors.
6. **Some of it is estimated or experimental.** Oxygen from the face camera is uncalibrated. Air dose uses a modelled area value, not the worker's own air. Chest pulse is tuned on two recordings of one person.
7. **Not a medical device.** It is a heat-strain warning and wellbeing aid. The core-temperature method (ECTemp) is patent-covered (US 10,702,165), so commercial use needs a licence. Two other components have licence limits to clear before commercial use.
8. **Hindi, Kannada, Tamil and Telugu wording needs native-speaker review.**

**What you can rely on:** losing or degrading information never lowers the level. A property test and the Safety checks tab on the phone prove it, and a switch turns each check red to show it can fail.

## Judge Q&A, one clean sentence each

| Challenge | Answer |
| --- | --- |
| Heart rate on a moving bike? | We do not take it while moving: the fingertip and face checks run at stops and breaks, and the phone refuses a reading when the finger or head moves. |
| Core temperature? | We never show a temperature; we show Cool, Warming, Strained or Danger, and act at 38.5 or earlier because estimates lose sensitivity near 39. |
| Dark skin? | Colour-based pulse is harder on dark skin and in bright light, so it is rejected when unsure; we have not yet tested across skin tones and will not claim accuracy until we have. |
| Your 5-second tests? | We replaced them with a longer tremor hold and a tapping task compared against the same worker's own baseline, and they support a level, they never decide one alone. |
| Longer routes in the heat? | The route gives up extra time only in proportion to the trip and the strain level: Cool takes the fastest, Strained accepts a longer shaded route, and the screen states the extra time. |
| Weather alert? | Weather comes from a cached forecast and the phone's light sensor, so it works offline; rain onset is a cue, not a forecast. |
| "Are your numbers real?" | Every reading is stamped REAL, REPLAY or SIMULATED at the source; the demo ride is a replay and says so on screen; and the hash-chained log proves entries were not edited, not that the observation was true. |
| Privacy? | Heart rate, brain scores and symptoms never leave the phone, camera frames and audio stay in memory, the app has no internet permission, and the supervisor sees levels only. |
| Who pays? | **Team to decide before the pitch.** Candidate answers: the employer or platform that is liable for heat illness pays for a per-worker licence; or an insurer or the state. Do not state a price we have not tested. |
| Is this validated? | Not yet: the safety logic is tested on the laptop and on the phone, but no study compares our estimates with a reference on real workers, and we say so. |
| What if the phone dies or loses signal? | Everything runs offline; a lost GPS counts as sun and a missing heart rate holds or raises the level, never lowers it. |
| Can anyone fake a flood report? | The crew mesh is anonymous, so yes; that is why reports can only add closures, are rate limited, and show as "reported", never as a depth. |

## For the team

- Rehearse cold: ask each person one row at random; the answer must fit in one sentence.
- Two limits in the older `LIMITATIONS.md` are out of date: Gemma is wired in (it can only re-word, never change a level) and a shift-report screen now exists.
- Row "Who pays" is the one answer we have not decided. Do not improvise it on stage.
