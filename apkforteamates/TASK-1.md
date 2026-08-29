# TASK 1 — Route planning, and the bug that makes multi-zone questions lie

**Difficulty: hardest.** Take this only if you are comfortable with
geometry, graph search and writing tests before code.
**Estimated: 2–3 focused days.** Two independent pieces; do 1B first, it
is smaller and it teaches you the codebase.

---

## Before you write a single line

Read these, in this order. They are short and they will save you a day:

1. `CLAUDE.md` — the five hard rules. Rule 1 (no synthetic data) and
   rule 5 (do not touch `policy.py` / `schema.py`) both bite in this task.
2. `orca/agents.py` lines 180–340 — the geofence agent. You are reusing
   its geometry, not rewriting it.
3. `orca/drift.py` — the newest module in the project and the closest
   model for what you are about to write: deterministic, cited,
   unit-tested, refuses rather than guesses.
4. `tests/test_drift.py` — how tests are written here. Expected values are
   hand-computed, never captured from a previous run.

**Run the suite before you change anything**, so you know it was green
when you started:

```bash
.venv/bin/python -m pytest -q          # expect 477 passed, 1 skipped
```

---

# PART 1B — Fix `_zone_by_substring` (do this first)

**Severity: High. This is a live safety bug and it is currently shipping.**

## What is wrong

`orca/planner.py:188`:

```python
def _zone_by_substring(query: str, zones: list[dict]) -> dict | None:
    query_lower = query.lower()
    for zone in zones:
        if zone["name"].lower() in query_lower:
            return zone          # <-- returns the FIRST match in ZONES order
    return None
```

It returns the first zone in `ZONES` **declaration order** that appears
anywhere in the query. It does not care where in the sentence the name
appeared, and it does not care that there were two.

## Why it matters

Ask: *"From Chennai down to Thoothukudi, is it safe?"*

`ZONES` lists Chennai first, so ORCA answers **for Chennai** and shows a
green `GO`. Thoothukudi — the destination, and frequently the roughest
zone on the coast — is silently dropped. The crew sees a green badge for
a voyage that ends somewhere the app never looked at.

That is a wrong safety answer delivered with full confidence. It is the
worst failure mode this project has.

## What to do

**Do not just sort by position in the string.** That swaps one arbitrary
rule for another. The honest fix is to stop pretending a multi-zone
question has a single-zone answer.

1. Add `_zones_by_substring(query, zones) -> list[dict]` returning **every**
   zone named, in the order they appear in the query.
2. Keep `_zone_by_substring` as a thin wrapper returning the first of
   those, so nothing else breaks while you work.
3. In `orca/planner.py` / `orca/agentic.py`, when more than one zone is
   named:
   - answer for **all** of them, and
   - lead with the **worst** verdict, not the first zone.
   `orca/policy.py` already ranks actions — read it, do not re-implement
   the ordering.
4. If the two zones disagree (`GO` at Chennai, `DO NOT GO` at
   Thoothukudi), the headline must be the `DO NOT GO`, and the answer must
   name both.

## Tests to write (`tests/test_planner.py`)

```
test_a_query_naming_two_zones_returns_both
test_the_worst_verdict_leads_when_two_zones_disagree
test_zone_order_in_the_answer_follows_the_query_not_ZONES
test_a_single_zone_query_behaves_exactly_as_before   # regression
```

Make the last one pass first, then break it deliberately to check it
actually catches the regression, then fix it.

## Definition of done

- `.venv/bin/python -m pytest -q` is green.
- You have run this by hand and pasted the output into your PR:
  ```bash
  curl -s localhost:8000/ask -H 'content-type: application/json' \
    -d '{"question":"From Chennai down to Thoothukudi, is it safe?","lat":13.1,"lon":80.3}' | jq .
  ```
- The answer names **both** zones and leads with the worse one.

---

# PART 1A — Route planning (SIH26176 capability #9)

**This is the last capability the problem statement asks for that ORCA
does not have.** Right now `/ask` replies *"ORCA has no route or
navigation planning."* A judge who has read the PS will ask about it.

Three independent model reviews (ChatGPT, Gemini, Grok — see
`docs/RESEARCH.md` §6) ranked this the highest-value missing feature. It
is the only recommendation that survived all three.

## What you are building

`GET /route?from_lat=&from_lon=&to=<zone>` returning a list of waypoints
plus, for **every** deviation, the reason for it.

Not a fuel optimiser. Not turn-by-turn. A path that avoids the things
that get boats seized, wrecked or fined, and that explains itself.

## What it must avoid, and where the data already is

| Hazard | Source, already cached | How |
|---|---|---|
| India–Sri Lanka IMBL | `data/cache/imbl/imbl_boundary.json` | never come within `agents.IMBL_URGENT_KM` (2.0 km) |
| Gulf of Mannar Marine National Park | `PROHIBITED_ZONE`, `orca/agents.py:52` | never enter the polygon |
| Shallow water / grounding | `data/cache/bathymetry/bathymetry_grid.json` — 4,760 points, `{lat, lon, elevation_m}` from NOAA ETOPO 2022 | stay in water deeper than a stated draft |
| Zones under `DO NOT GO` | `build_recommendation()` | do not route through one |
| Live IMD warnings | `orca/alerts.py` — new, already built | do not route through a `covering` polygon |

**Every one of these already exists.** You are not fetching anything. If
you find yourself adding a network call, stop — that is rule 8.

## Suggested approach

Keep it boring (rule 7).

1. **Build a grid.** The bathymetry cache is already a lat/lon grid. Use
   its points as nodes. Mark a node blocked if: it is land
   (`elevation_m >= 0`), shallower than the draft, inside the MPA, or
   within `IMBL_URGENT_KM` of the boundary.
2. **Search it.** A\* or Dijkstra with great-circle distance as the cost
   and 8-way neighbours. `_haversine_km` is in `orca/agents.py:217` —
   **import it, do not copy it**.
3. **Simplify.** Collapse collinear runs so the output is a handful of
   waypoints, not 200 grid cells.
4. **Explain.** For each waypoint, one sentence saying why the path
   turned there. A route with no reasons is not an ORCA feature.

**Optional, and only if 1–4 are done and tested:** current-aware cost.
`/bundle` now exposes `ocean_current_velocity_kmh` and
`ocean_current_direction_deg` per zone under `drift_inputs`.

> ⚠️ **The convention trap.** Open-Meteo gives **wind** as the direction it
> blows **FROM** and **current** as the direction it flows **TOWARD**.
> These are opposite. Getting one backwards is a silent 180° error that
> nothing will catch for you. `orca/drift.py` documents and tests both —
> read it before you touch a direction.

## Legal framing — put this on the screen, not in a comment

Formal route guidance and hazard clearance are restricted to certified
ECDIS under IMO rules. If ORCA hands out a track and a boat hits an
uncharted shoal off Pamban, the wording matters.

- Present output as an **advisory vector**, never a binding instruction.
- State that pilotage remains the master's responsibility.
- `orca/drift.py` and `DriftScreen` already carry this framing. Copy their
  wording rather than inventing new wording.

## Wiring it up (only when the endpoint actually works)

1. `orca/route.py` — new module, pure Python, **no LLM**, no network.
2. `GET /route` in `orca/api.py`.
3. Remove `"route"` from `_UNSUPPORTED_TERMS` in `orca/extract.py:170`.
4. Remove the matching entry from `_UNSUPPORTED_NOTES` in
   `orca/agentic.py:342`.

Steps 3 and 4 are **last**. Removing them early makes ORCA claim a
capability it does not have.

## Tests (`tests/test_route.py`) — write these first

```
test_a_route_never_passes_within_IMBL_URGENT_KM_of_the_boundary
test_a_route_never_enters_the_marine_national_park
test_a_route_never_crosses_land
test_a_route_never_enters_water_shallower_than_the_stated_draft
test_every_waypoint_carries_a_reason
test_an_impossible_route_is_refused_with_a_reason_not_an_empty_list
test_a_straight_open_water_route_is_actually_straight
```

That last one matters: if your grid search adds zigzags in open water
with nothing to avoid, the cost function is wrong.

## Definition of done

- All tests green, plus the existing 477.
- You have **run it** and pasted real output:
  ```bash
  curl -s "localhost:8000/route?from_lat=9.28&from_lon=79.31&to=Rameswaram" | jq .
  ```
  (That start point is in Palk Bay, close to the IMBL — if your route does
  not visibly bend away from the line there, it is not working.)
- The waypoints plotted on a map do not cross the boundary. Check this by
  eye, not only by test.

## Do not touch

`orca/policy.py`, `orca/schema.py`, `orca/agents.py` (import from it,
do not edit it), `orca/alerts.py`, `orca/drift.py`.
