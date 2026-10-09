# Thakaan — project guide for Claude

Thakaan (थकान) is an on-device, offline Android app (Kotlin + Compose) for the iQOO 15 that turns an outdoor worker's phone into a personal heat-strain guardian: it senses body, brain and sun, fuses them into one Strain Score (TSS), and routes the worker to shade and water (Chhaya Route) before heat takes them down. Built by a 3-person team for the 48-hour iQOO Hackathon 2026 Grand Finale (Bengaluru, 9 to 11 Oct).

## Source of truth (read these, do not guess)

| File | Use it for |
| --- | --- |
| `docs/Thakaan — Product Bible.md` | Intent, vectors (P/N/E ids), formulas, UX, demo, privacy. Source of truth on **why**. |
| `docs/Thakaan — Product Bible, Simplified.md` | Same product, plain words + technical view + decoder. Reflects Bible v3 numbers. |
| `docs/Thakaan — Build Map v1.md` | 65 tickets (#1 to #65) with Build / Watch out / Test / Done when. Source of truth on **execution order**. |
| `docs/TICKETS.md` | Live checklist of all tickets (status, owner, blockers). Update it as tickets land. |

When bible and build map disagree: bible wins on intent, build map wins on execution. Where the original Bible and the Simplified Bible disagree on a number (the Simplified one and the Build Map reflect v3: tremor + Fitts tapping instead of 5 s tests, Liljegren WBGT via `lwbgt`, sigmoid ECTemp, PRV windows of 60 s), prefer the **Build Map / v3**.

## Non-negotiable invariants (every change is checked against these)

1. **Phone only, on-device, offline.** No wearable, no cloud in the core loop. No network call may exist on the demo path (fonts, tiles, analytics included). Network only syncs shade map / weather / optional layers.
2. **Strain, not temperature.** UI shows Cool / Warming / Strained / Danger. Never display a body-temperature number.
3. **Fail safe: losing or degrading information may NEVER lower the level.** Missing/stale HR, lost GNSS (unknown shade = sun), blocked light sensor, model unavailable → level holds or rises. If the safety property test (#41) fails, fix the engine, never the test.
4. **Act early.** Core-temperature anchors fire at ≤ 38.5 °C (38.0 °C before acclimatisation). No action ever waits for an estimate above 39 °C. An independent HR rule (sustained rest HR > 90 to 100 bpm) backs it up.
5. **LLM never changes a level.** Gemma only phrases what the risk engine decided, from a fixed action set. Template lines are the fallback.
6. **Provenance on every reading:** `REAL | REPLAY | SIMULATED`, stamped at the sensor hub, inherited by anything computed from it. Replay must never show as REAL.
7. **Worker owns the data.** Raw physiology (HR, HRV, core trend, brain scores, symptoms) never leaves the phone. Camera frames and audio are RAM-only. Supervisor console sees levels, never raw HR/voice.
8. **Never block the job.** Navigation/delivery flow stays usable; Thakaan sits on top.
9. **Every alert names one action, speaks it in the worker's language (hi/kn/ta/te/en), and shows its time cost.**
10. **Heat-strain wellbeing tool, not a medical device.** State limits honestly; ECTemp is patent-covered (US 10,702,165) so commercial use needs a licence.

## Things the docs flagged as traps

- Never use the phone body temperature as a globe thermometer (dropped in v3). Never use the ACSM87 WBGT formula by day; use Liljegren (`lwbgt` via NDK/JNI).
- Match published ECTemp equations exactly (Q = 0.000484, R = 356.4544, NIS gate 3.84) and pass all 7 cases in `ectemp_vectors.json` **with personalisation off** before personalising. Reference vectors assume a reading every minute; Thakaan gets them at stops, so variance must grow (never shrink) across gaps and that needs its own tests.
- Never compute RMSSD from < 60 s of clean PPG. Compare HR only at the same state and time-since-stop; down-weight the first 30 s after a posture change.
- Brain scores are compared only to the same worker on the same phone, same placement (pocket vs hand gait), same time of day. Touch timestamps come from `MotionEvent` event time, not UI callbacks.
- A model session that loads without throwing may still be on CPU; read the delegated-ops count ("N/N ops on NPU").
- OriginOS kills background work: foreground service, battery unrestricted, app locked in Recents.
- A check that cannot fail proves nothing: every safety check needs a hidden guard switch that turns it red.
- Hash chain proves entries were not edited after recording, not that the observation was true. Say so.

## Team and ownership (Build Map "Roles")

- **A, Body:** PPG, ECTemp, heat-balance prior, body vectors: #8–#12, #22–#31
- **B, Brain + World:** motion, environment, brain battery, Chhaya Route: #13–#21, #32–#38, #46–#51
- **C, Systems + Demo:** skeleton, risk engine, safety checks, Office Kit, console, metrics, demo: #5–#7, #39–#45, #52–#56, #60–#65. C also guards the held-out session for #60.

## Working rules

- **Golden rule:** do not start a ticket until its blockers pass their own Test. "Mostly works" is not done. Tickets are in strict dependency order (`Blocked by` only points lower).
- Work one ticket at a time with `/ticket <n>`; it loads the ticket, checks blockers, builds, runs the ticket's Test, and ticks `docs/TICKETS.md`.
- Priority: 🟥 CORE first (the spine), 🟨 SUPPORTING after the spine works end to end, ⬜ SEED token or narrate only. Phase 9 is cut first when time runs out.
- Light tags: ⚪ PRE-BUILD (decide/source before coding), 🟢 GREEN (needs laptop: NDK, model conversion, console), 🔴 RED-OK (phone-only; keep Office Kit mirror running).
- Everything is demoable at each phase boundary. Prefer deterministic parts (routing) in the live demo; use REPLAY for the shift story.
- Keep the plain ECTemp path runnable behind a switch when adding the Gagge prior (#24).

## Planned code layout (create as tickets need it)

```
app/                    Kotlin + Compose Android app (iQOO 15, Android 16)
  sensorhub/            foreground service, provenance stamping, recorder/replay  (#5, #7)
  inference/            InferenceEngine seam: QNN / GPU / CPU back ends          (#6)
  ppg/ body/ brain/     fingertip PPG, ECTemp + Gagge/PHS, tremor/Fitts/speech/CUSUM
  exposure/             E1/E15/WBGT/GNSS sky view, rest + drink ledgers
  risk/                 TSS, anchored levels, alert ladder, safety invariant     (#39–#42)
  chhaya/               graph, shade layers, strain-aware cost, map screen       (#46–#51)
  diagnostics/          NPU op counts, ECTemp 7/7, Safety checks tab
console/                local web view supervisor console + chain verifier       (#53, #54)
assets/                 ectemp_vectors.json, OSM extract, weather cache, lwbgt, model files (#4)
tools/                  asset fetchers, shadow precompute, ticket tooling
docs/                   bibles, build map, TICKETS.md
```

## Conventions

- Kotlin, coroutines/Flow for streams, Compose UI. Heavy DSP in Kotlin/NDK on CPU; small models on NPU via LiteRT + QNN; LLM on GPU via LiteRT-LM.
- UI: colour + shape + words for each level, ≥ 20 sp on the gauge, high-contrast light theme for sun, every prompt answerable by spoken "haan"/"nahi".
- Credit: "© OpenStreetMap contributors" wherever OSM data is shown. Water points are "listed, quality unverified" until riders confirm.
- Tests: property test for the safety invariant (#41) runs on the laptop in CI; ECTemp vectors run on-device and on the laptop.
- Do not commit model files, OSM extracts, or API keys (see `.gitignore`); stage them via Office Kit and list them in `docs/ASSETS.md` (#4).

<!-- code-review-graph MCP tools -->
## MCP Tools: code-review-graph

**IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph MCP tools BEFORE using Grep/Glob/Read to explore
the codebase.** The graph is faster, cheaper (fewer tokens), and gives
you structural context (callers, dependents, test coverage) that file
scanning cannot.

### When to use graph tools FIRST

- **Exploring code**: `semantic_search_nodes` or `query_graph` instead of Grep
- **Understanding impact**: `get_impact_radius` instead of manually tracing imports
- **Code review**: `detect_changes` + `get_review_context` instead of reading entire files
- **Finding relationships**: `query_graph` with callers_of/callees_of/imports_of/tests_for
- **Architecture questions**: `get_architecture_overview` + `list_communities`

Fall back to Grep/Glob/Read **only** when the graph doesn't cover what you need.

### Key Tools

| Tool | Use when |
| ------ | ---------- |
| `detect_changes` | Reviewing code changes — gives risk-scored analysis |
| `get_review_context` | Need source snippets for review — token-efficient |
| `get_impact_radius` | Understanding blast radius of a change |
| `get_affected_flows` | Finding which execution paths are impacted |
| `query_graph` | Tracing callers, callees, imports, tests, dependencies |
| `semantic_search_nodes` | Finding functions/classes by name or keyword |
| `get_architecture_overview` | Understanding high-level codebase structure |
| `refactor_tool` | Planning renames, finding dead code |

### Workflow

1. The graph auto-updates on file changes (via hooks).
2. Use `detect_changes` for code review.
3. Use `get_affected_flows` to understand impact.
4. Use `query_graph` pattern="tests_for" to check coverage.
