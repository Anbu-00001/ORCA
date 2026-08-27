# DEV D — critical path, sole merger

**Branch:** `main` (you are the only person who merges to it)
**Merge window:** T+5:00 · **Contract freeze due:** T+0:45

**You own, exclusively:**
`orca/planner.py` · `orca/api.py` · `API_CONTRACT.md` · `PRD.md` ·
`tests/test_planner.py` · `tests/test_api.py`

Nobody else touches these. `orca/policy.py` and `orca/schema.py` stay **frozen** (N-5) —
decided, not up for revisiting. Everything below is planner-side.

---

## Why this sprint exists

Two fail-opens. Both make the system say *go* when it should not.

### R-59 (new) — danger with no opportunity resolves to GO

`orca/policy.py:64` gates rule 2 on `opportunity` **and** `danger`. With hazards present
but nothing suggesting go, execution falls through to rule 3 and returns `GO`, reason
*"No hazards found; conditions acceptable"*.

**This is not R-39.** Every agent here has evidence, so R-39a's guard passes and the bug
survives untouched. You could ship R-39/39a/39b in full and this remains.

Live on today's cache at **Kanyakumari** (wind risk 0.67, wave 0.60) and **Colachel**
(0.63 / 0.57) — both above the project's own 0.6 threshold, both rendering as an
affirmative *"Go to Kanyakumari."* with the contradicting readings in the same
response's `evidence` list.

The trigger is inverted: `suggests_go` goes false when water is cold or chlorophyll is
cloud-masked, so **the worse the fishing looks, the more likely the safety override is
skipped entirely.**

```bash
.venv/bin/python -c "
from orca.planner import build_recommendation
r = build_recommendation('fishing at Kanyakumari', 8.0883, 77.5385)
print(r.action, '|', r.reason)"

# GO | No hazards found; conditions acceptable
```

### R-39 — no evidence at all resolves to GO

Already specified and already decided (Open Decision 8, resolved): a fourth action,
`CANNOT ASSESS`, produced in the planner so `policy.py` stays frozen. Nothing to
re-litigate — it needs building, **after** Dev A can survive the new enum value.

---

## The one hard sequencing rule

`web/index.html:587` defaults every unrecognised `action` to the **green GO badge**:

```js
function actionClass(action) {
  if (action === "DO NOT GO")         return "action-do-not-go";
  if (action === "SAFER ALTERNATIVE") return "action-safer-alternative";
  return "action-go";   // ← every unrecognised value lands here
}
```

**Dev A's fix merges before or with your R-39. Never after.** Ship R-39 first and the
demo shows a confident GO on a zone ORCA cannot assess — strictly worse than the bug
you set out to fix. The PRD's amended R-25 predicted this in the abstract; it is
literally true of this repo today.

---

## Your tasks

### 1. Freeze the contract and push — before anyone cuts a branch

Three people are waiting on this. Budget 45 minutes; it is the only thing standing
between them and three merge conflicts.

Into **`API_CONTRACT.md`**:
- The fourth `action` value, `CANNOT ASSESS`.
- The R-25 sentence, in words: *"A client that does not recognise this value MUST treat
  it as non-permissive — never as GO."*
- Two additive fields: `severity` (R-38) and `blind_agents` (R-40). Contract them now
  even if you cut the implementation later — Dev A renders nothing if they're absent.

Into **`PRD.md`** §5.3:
- **R-59** — a zone with a finding ≥ `RISK_OVERRIDE_THRESHOLD` and no opportunity must
  never resolve to `GO`.
- **R-60** — the alternative search is bounded by distance.
- Gate **G-14** — the regression for R-59.

§16.2 is explicit: requirements change here first, then in code.

### 2. [P0] Both verdict guards — one commit

R-59 and R-39 modify the same lines of the zone loop. Done separately, the second
rewrites the first.

One planner-side helper, applied once where `zone_results` is built, so the primary
decision, the alternative search and `zone_summaries` all agree. The two conditions are
disjoint — a zone with no observations has no danger to find — so order between them is
for clarity, not correctness.

```python
# shape only — NOT yet run. policy.py untouched (N-5).
def _zone_verdict(decision, findings):
    # R-39: nothing to reason from
    if not any(f.observations for f in findings):
        return Decision(action="CANNOT ASSESS", ...)

    # R-59: danger that never reached rule 2
    if decision.action == "GO":
        danger = [f for f in findings if f.risk_level >= RISK_OVERRIDE_THRESHOLD]
        if danger:
            worst = max(danger, key=lambda f: f.risk_level)
            return Decision(
                action="SAFER ALTERNATIVE",
                reason=worst.reason,
                chosen=None,
                overridden=[],   # nothing was sacrificed — R-11
                explanation=f"Hazard with no competing opportunity: {worst.reason}")
    return decision
```

**Note the `max()`.** R-37 stays open in `policy.py` by your decision — but there's no
reason to introduce a *second* `danger[0]` on a path you're writing today.

**Existing tests to check you haven't contradicted:** `tests/test_policy.py:126`
(`test_danger_without_opportunity_does_not_trigger_rule_2`) asserts `resolve()` alone
does not return `SAFER ALTERNATIVE`. That stays true — your correction is one layer up,
in the planner. It should stay green untouched. If it goes red, you've edited the frozen
file by accident.

### 3. [P0] R-60 — bound the alternative search

Without a cap, Kanyakumari reroutes to **Chennai, 636 km**. Today's search already sends
Thoothukudi 569 km. Skip candidates beyond `MAX_ALTERNATIVE_KM` and the existing no-swap
text takes over: *"conditions are borderline… no clearly safer nearby zone found;
proceed with caution or wait."*

**Reuse `orca.agents._haversine_km`.** It exists and is tested — do not write a second
distance function.

Pick the cap deliberately and put the reasoning in the PRD row, the way
`WAVE_HARD_DENY_M` carries its Douglas citation. Anything defensible as one boat's
divert range is fine; what isn't defensible is an unexplained round number.

For reference, today's distances from the two affected zones to the nearest zone that
currently resolves to a genuine GO:

| From | Nearest GO-capable zone | Distance |
|---|---|---|
| Kanyakumari | Nagapattinam | 391 km |
| Colachel | Nagapattinam | 404 km |

There is no sensible alternative on the south coast today. Any cap under ~390 km
produces the no-swap message for both, which is the honest answer.

### 4. [P1] R-54 — probe out of the request path

`api.py:128`. `_is_reachable()` is a live socket connect inside `/ask`, and DNS
resolution is not bounded by its 0.75 s timeout. Closes **N-6 and N-7** together.

Confirm with Dev A that the offline badge still populates from `/health` before you
drop it.

### 5. [cut] R-38 severity · R-40 blind agents

Additive fields, already contracted in step 1. If the window closes, the contract still
stands and Dev A renders nothing — no breakage. **This is the honest place to stop.**

---

## Done when

```bash
.venv/bin/python -m pytest -q          # no --ignore, once Dev C lands the pin

.venv/bin/python -c "
from data.fetch import ZONES
from orca.planner import build_recommendation as b
for z in ZONES:
    r = b(f\"fishing at {z['name']}\", z['lat'], z['lon'])
    print(f\"{z['name']:15} {r.action:16} {r.reason[:45]}\")"

# Kanyakumari and Colachel must no longer read GO.
# No zone may say 'No hazards found' with a finding >= 0.6.

git status --short && git log origin/main..HEAD --oneline
# ^ must be empty. Dev C's G-7 clones from the local path, so nothing
#   else in this plan would catch an unpushed commit.
```

Baseline before you start: **226 passed, 1 skipped**
(`--ignore=tests/test_mcp_server.py`; the skip is the live-Groq test).

---

## What you are deliberately not fixing

Be ready to say all three out loud.

**R-36** — `geofence_agent` takes its position from `observations[0]`. Blocked on Open
Decision 9 (unresolved), breaks R-5's uniform signature, and would force a
`run_agents()` change that collides with your P0 work. It also buys nothing on stage:
the agent cannot fire at any of the ten zones regardless — nearest approach to the IMBL
is **22.7 km at Rameswaram** against a 10 km advisory band, and no zone falls inside the
Krusadai MPA box. If a judge asks to see a geofence deny, the honest answer is that you
can't show one from a covered zone.

**R-37** — `policy.py` still names the first danger, not the worst. Stays **Open** in
the PRD; don't quietly mark it Met.

**The 2.5 m hard-deny has never fired on live data.** Max wave in the cache is 1.62 m.
The flip test in `tests/test_planner.py` is what proves it works end to end — rehearse
showing that, not a live query hoping for weather.
