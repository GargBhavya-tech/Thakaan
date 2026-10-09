---
description: Show build progress, what is unblocked now, and the next demoable checkpoint
argument-hint: [owner A|B|C]
---

Report Thakaan build status from `docs/TICKETS.md` (optional owner filter: $ARGUMENTS).

1. Count `[x]` done, `[~]` written-but-untested-on-device, and `[ ]` open, overall and per phase.
2. List **unblocked** tickets (every `Blocked by` ticket is `[x]`), 🟥 CORE first, grouped by owner. For each owner show one 🔴 RED-OK and one 🟢 GREEN ticket if available (scheduling rule: nobody idles on a light change).
3. Name the earliest checkpoint (CP-A, CP-B, FINAL) with open tickets and the tickets still blocking it.
4. Flag any ticket ticked `[x]` whose blocker is not `[x]` (golden-rule violation).
5. Keep it under 25 lines. No prose beyond the lists.
