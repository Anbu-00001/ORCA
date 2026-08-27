# ORCA — pre-demo work split

**For:** ICARUS teammates and their agents. **Window:** 6–12 hours.
**Written:** 2026-08-27, after an audit of Part 1's open items against the code.

**Find your own document — it stands alone. You should not need to read this file to start.**

| Who | Document | Owns | Kill time |
|---|---|---|---|
| **Dev D** (merger) | [`docs/team/DEV_D.md`](docs/team/DEV_D.md) | `orca/planner.py`, `orca/api.py`, `API_CONTRACT.md`, `PRD.md` | merge T+5:00 |
| **Dev A** | [`docs/team/DEV_A.md`](docs/team/DEV_A.md) | `web/index.html`, `e2e/*.spec.js` | T+4:30 |
| **Dev B** | [`docs/team/DEV_B.md`](docs/team/DEV_B.md) | `orca/agentic.py` + the R-25 consumer sweep | T+3:30 |
| **Dev C** | [`docs/team/DEV_C.md`](docs/team/DEV_C.md) | `orca/mcp_server.py`, `requirements.txt`, all gates | T+3:00, gates to the end |

File ownership is **exclusive**. If a task would take you into someone else's file, it
stops being your task and becomes a message to them. Dev B's consumer sweep is the one
cross-cutting job, and it is **read-only** — it produces a table, not a diff.

`orca/policy.py` and `orca/schema.py` are **frozen** (N-5). Nobody edits them — Dev C
touches `policy.py` for sixty seconds during G-4 and reverts it.

Baseline before any of this: **226 passed, 1 skipped**
(`--ignore=tests/test_mcp_server.py`; the skip is the live-Groq test, not a failure).

---

## 1. Why this sprint exists

Two fail-opens. Both make the system say *go* when it should not.

### R-59 (new) — danger with no opportunity resolves to GO

`orca/policy.py:64` gates rule 2 on `opportunity` **and** `danger`. With hazards present
but nothing suggesting go, execution falls through to rule 3 and returns `GO`, reason
*"No hazards found; conditions acceptable"*.

**This is not R-39.** Every agent here has evidence, so R-39a's guard passes and the bug
survives untouched. You could ship R-39/39a/39b in full and this remains.

Live on today's cache at **Kanyakumari** (wind risk 0.67, wave 0.60) and **Colachel**
(0.63 / 0.57) — both above the project's own 0.6 threshold, both rendering as an
affirmative *"Go to Kanyakumari."* with the contradicting readings sitting in the same
response's `evidence` list.

The trigger is inverted, which is what makes it dangerous: `suggests_go` goes false when
water is cold or chlorophyll is cloud-masked — so **the worse the fishing looks, the more
likely the safety override is skipped entirely.**

```bash
.venv/bin/python -c "
from orca.planner import build_recommendation
r = build_recommendation('fishing at Kanyakumari', 8.0883, 77.5385)
print(r.action, '|', r.reason)"

# GO | No hazards found; conditions acceptable
```

### R-39 — no evidence at all resolves to GO

Already specified (R-39 / 39a / 39b) and already decided — Open Decision 8, resolved: a
fourth action, `CANNOT ASSESS`, produced in the planner so `policy.py` stays frozen.
Nothing to re-litigate. It needs building, **after** the frontend can survive the new
enum value.

---

## 2. The merge order is not negotiable

`web/index.html:587` defaults every unrecognised `action` to the **green GO badge**:

```js
function actionClass(action) {
  if (action === "DO NOT GO")         return "action-do-not-go";
  if (action === "SAFER ALTERNATIVE") return "action-safer-alternative";
  return "action-go";   // ← every unrecognised value lands here
}
```

Ship R-39 before Dev A's fix and a zone ORCA *cannot assess* renders as a confident
green **GO** — strictly worse than the bug it fixes. The PRD's amended R-25 predicted
this failure in the abstract; it is literally true of this repo today.

| When | What |
|---|---|
| **T+0:00** | Dev D freezes the contract, **alone**. Nobody cuts a branch until it's pushed. ~45 min, and it is the only thing between four people and four merge conflicts. |
| **T+0:45** | Three branches cut in parallel. Every person owns a **different file**. |
| **GATE** | **Dev A's enum fix merges before or with Dev D's R-39. Never after.** |
| **T+3:00** | Dev C kill time — the `mcp` pin makes `pytest` run clean for everyone. Gates keep running. |
| **T+3:30 / T+4:30** | Dev B, then Dev A. Dev B's consumer sweep lands earlier — Dev A and Dev D are both waiting on it. |
| **T+5:00** | Dev D merges everything, full suite, real cache. G-13 and G-14 run for the first time. |
| **T+6:00** | Dev C refreshes the cache, then hands off so the presenter can rehearse. |

---

## 3. The cut line

Decide which column you're in by **T+3:00**, not at T+5:30.

| Must land (the fail-opens) | Should land (cheap + honest) | If the window holds |
|---|---|---|
| R-59 unopposed danger — *Dev D* | R-45 one line, closes N-4 — *Dev B* | R-38 severity field |
| R-39 `CANNOT ASSESS` — *Dev D* | R-54 closes N-6, N-7 — *Dev D* | R-40 blind-agent disclosure |
| R-25 frontend default — *Dev A* | R-55 mark the mock — *Dev A* | R-49 latency budget — *Dev B* |
| R-60 bounded reroute — *Dev D* | R-25 consumer sweep — *Dev B* | R-33 reading age — *Dev A* |
| G-13 / G-14 regressions — *Dev C* | G-1 pin · G-4 / G-7 / G-8 — *Dev C* | |

---

## 4. Deferred — and said out loud if asked

Three things stay broken tonight. Each is a deliberate call, and the presenter should be
able to answer for all three without hedging.

**R-36 — `geofence_agent` takes its position from `observations[0]`.**
Blocked on Open Decision 9 (unresolved), and it breaks R-5's uniform
`list[MarineObservation] → Finding` signature — which would force a change to
`run_agents()`, in Dev D's file, colliding with the P0 work.
**It also buys nothing on stage.** The agent cannot fire at any of the ten zones
regardless: nearest approach to the IMBL is **22.7 km at Rameswaram** against a 10 km
advisory band, and no zone falls inside the Krusadai MPA box. Both boundaries are real
and correctly implemented; neither is currently reachable. If a judge asks to see a
geofence deny, the honest answer is that you can't show one from a covered zone — say so.

**R-37 — `policy.py` still names the first danger, not the worst.**
`policy.py` stays frozen, so `danger[0]` and `hard_denials[0]` stay as they are. The
planner path added today names the worst hazard correctly, so both fail-opens are fixed
without touching the frozen file. R-37 stays **Open** in the PRD — don't quietly mark it Met.

**The 2.5 m hard-deny has never fired on live data.**
Maximum wave height in the current cache is **1.62 m**. The headline safety rule — the
Douglas degree 4/5 boundary the UI draws as its "ORCA stops here" line — cannot be
demonstrated from real readings. The flip test in `tests/test_planner.py` is what proves
it works end to end. Rehearse showing *that*, not a live query hoping for weather.

---

## 5. One note on staffing

The war plan's original call (§1.3) was that teammates *aren't* a build dependency —
deck, QA, rehearsal, logistics. That was right when there was nothing but a build list.
It's less right now that there's a concrete defect list with clean file boundaries. But
the rule it protected still holds, and this plan is built entirely around it:
**one repo, one merger, and nobody in `planner.py` but Dev D.**
