# Thakaan (थकान) — *Garmi se pehle, Thakaan.*

An on-device, offline Android app for the iQOO 15 that senses an outdoor worker's heat strain (body + brain + sun) with only the phone they already carry, and routes them to shade and water (**Chhaya Route**) before they collapse.

- Product intent: [`docs/Thakaan — Product Bible.md`](docs/Thakaan%20—%20Product%20Bible.md) · plain-words version: [`docs/Thakaan — Product Bible, Simplified.md`](docs/Thakaan%20—%20Product%20Bible%2C%20Simplified.md)
- How we build it: [`docs/Thakaan — Build Map v1.md`](docs/Thakaan%20—%20Build%20Map%20v1.md) · live checklist: [`docs/TICKETS.md`](docs/TICKETS.md)
- AI-assistant context and invariants: [`CLAUDE.md`](CLAUDE.md)

## Working with Claude Code

| Command | Does |
| --- | --- |
| `/status [A\|B\|C]` | Progress, unblocked tickets per owner, next checkpoint |
| `/ticket <n>` | Build ticket #n: check blockers → build → run its test → tick the tracker |
| `safety-invariant-reviewer` agent | Reviews changes against the fail-safe rules |

The code-review-graph MCP server is configured in `.mcp.json`; hooks in `.claude/settings.json` keep the graph updated on every edit. Build it once code exists: `code-review-graph build`.

Heat-strain warning and wellbeing aid, not a medical device. Map data © OpenStreetMap contributors (ODbL).
