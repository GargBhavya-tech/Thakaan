---
description: Work one Thakaan build-map ticket end to end (blockers → build → test → tick)
argument-hint: <ticket number, e.g. 22>
---

Work Build Map ticket **#$ARGUMENTS** following the golden rule.

1. **Load it.** Read ticket #$ARGUMENTS in `docs/Thakaan — Build Map v1.md` (Build / Watch out / Test / Done when) and its row in `docs/TICKETS.md`. For intent, look up the matching vectors (P/N/E ids, formulas) in `docs/Thakaan — Product Bible.md`.
2. **Check blockers.** Every ticket in `Blocked by` must be ticked `[x]` in `docs/TICKETS.md`. If any is not, stop and say which one; do not build on a blocker that "mostly works".
3. **Check the invariants** in `CLAUDE.md` that this ticket touches (fail-safe, provenance, no temperature display, no network on demo path, LLM never changes a level). State which apply in one line.
4. **Use the graph first.** Call `get_minimal_context(task=...)`, then `semantic_search_nodes` / `query_graph` before reading files. Reuse existing seams (`InferenceEngine`, sensor hub, provenance) instead of adding parallel ones.
5. **Build** only what the ticket's *Build* says. Respect every *Watch out*. Prefer the smallest change that meets *Done when*.
6. **Test.** Run the ticket's *Test*. Anything needing the physical iQOO 15 (camera, GNSS, light sensor, NPU) cannot be verified from here: write the code and a step-by-step phone checklist, mark the ticket `[~]` in `docs/TICKETS.md`, and say plainly that it is untested on device. Never tick `[x]` on unverified work.
7. **Tick** `docs/TICKETS.md` (`[x]`) only when *Done when* is demonstrably met, then run `detect_changes` and report risk, what you tested, and what you could not test.
8. **Next.** Name the next unblocked ticket for the same owner (A Body / B Brain+World / C Systems+Demo).
