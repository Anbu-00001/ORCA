# DEV B — handoff · agentic layer

**Branch:** `Dev-B` (on `upstream`) · **Merged into:** `main` · **Written:** 2026-08-27
**Companion to:** [`DEV_B.md`](DEV_B.md), which is the *task brief*. This file is what
actually happened, and what is still owed.

> **If you are an agent picking this repo up:** read §0 and §6 before you edit
> `orca/agentic.py`. Three of the invariants in §6 are load-bearing safety claims that
> tests assert but that are easy to break by accident while adding a feature. §2 is one
> unfinished task that a human deliberately deferred — do not silently "fix" it without
> reading why.

---

## 0. Thirty-second version

| | |
|---|---|
| **Landed** | R-45 (fallbacks log), R-49 (10 s wall-clock budget), and a `CANNOT ASSESS` branch in the composer that pre-dates the verdict itself |
| **Still owed** | One PRD edit — §2. Patch is committed at `docs/team/dev-b-prd-r45-r49.patch` |
| **Blocks others** | Nothing. The R-25 sweep (§5) is done and unblocks Dev A and Dev D |
| **Must not break** | §6 — three invariants with tests behind them |
| **Verify with** | `pytest -q --ignore=tests/test_mcp_server.py` → **239 passed, 1 skipped** |

Two commits:

```
4fe5ee0  fix: log the extraction fallback reason (R-45, closes N-4);
         bound the agentic layer to one wall-clock budget (R-49)
b0014a9  fix: an unassessable zone is never phrased as advice (R-39 shell side)
```

Files touched: `orca/agentic.py`, `tests/test_agentic.py`. Nothing else.
`policy.py`, `schema.py`, `planner.py`, `api.py` are untouched — verify with
`git diff --name-only main..Dev-B`.

---

## 1. What landed

### R-45 — both fallbacks now name their reason

The extraction fallback was `except AgenticUnavailable: pass`, while the composition
fallback 130 lines below logged properly. Both now log. **This was the last
`except…pass` in the repo, so it also closes N-4.**

Behaviour is unchanged: every field still keeps its deterministic default. Only the
silence is gone. If a test in `tests/test_agentic.py` changes result after you touch
this, you have changed more than the logging.

Why it matters, in one line from the code's own comment: *a server ran a full day with
the agentic layer off and looked identical to one with it on.*

### R-49 — one wall-clock budget for the whole layer

`REQUEST_TIMEOUT_S = 8.0` is **per call**, and `answer_question()` makes two sequential
calls. The layer could add ~16 s to one request — exactly the wifi-off stage condition.

```python
LAYER_BUDGET_S   = 10.0   # total network wait the layer may add to one /ask
MIN_CALL_BUDGET_S = 0.5   # below this, a call cannot finish; don't start it
```

**Enforced two ways, and both are necessary.** The brief only asked for the first:

1. **Skip** composition when the budget is already spent, and log the skip.
2. **Clamp** composition's timeout to whatever is left — `timeout=min(REQUEST_TIMEOUT_S,
   remaining)`.

Without the clamp there is no bound. Extraction can finish just *inside* the budget and
then hand composition a full fresh 8 s, which is the original 16 s worst case wearing a
check. `tests/test_agentic.py::test_composition_timeout_is_clamped_to_what_is_left_of_the_budget`
exists specifically so removing the clamp fails loudly.

### `CANNOT ASSESS` in the composer — ahead of the verdict existing

Not one of the four brief tasks. Added because the R-25 sweep (§5) found it, and a human
chose the wording. See §4 for the contract Dev D needs to honour.

---

## 2. THE ONE THING STILL OWED

`DEV_B.md` task 2, final bullet: **"State the resulting bound in the PRD's R-49 row. A
bound nobody wrote down isn't 'stated'."**

It is not done. The rows were written, then reverted when the repo owner said not to
touch `PRD.md` — `PRD.md` is shared across four branches and was a merge-conflict risk
mid-sprint. That was a deliberate call, not an oversight.

**R-49 is therefore enforced in code and locked by tests, but Open in the document that
defines it.** Apply after merge:

```bash
git apply docs/team/dev-b-prd-r45-r49.patch
```

If `PRD.md` has moved and the patch no longer applies, make these four edits by hand:

| PRD row | Change |
|---|---|
| **R-45** | `Partial` → **`Met`**. Drop the "*Currently the composition fallback logs; the extraction fallback does not*" clause. |
| **R-49** | `Open` → **`Met`**, and state the bound: *`LAYER_BUDGET_S` = 10 s of network wait per `/ask`, regardless of how many calls the layer makes. Enforced by skipping composition when the budget is spent AND clamping its timeout to the remainder — a check alone is not a bound.* |
| **N-4** | `Partial — see R-45` → **`Met`** |
| **§8** | Strike the two gap rows: *"The extraction fallback is silent"* and *"Agentic latency is unbounded at ~16 s worst case"* |

After that, the PRD's Open list drops from eight to six: R-36, R-37, R-38, R-39, R-54,
R-55.

---

## 3. Deviations from `DEV_B.md`

Both on the repo owner's instruction. Recorded so nobody reads the brief later and thinks
it was ignored.

| Brief says | Actual | Why |
|---|---|---|
| Branch `agentic`, push to `origin` | Branch **`Dev-B`**, pushed to **`upstream`** | The team standardised on `Dev-A`…`Dev-D` on `upstream` (`github.com/Dhev-1/Orca`). `origin` is a personal fork. |
| Four tasks | Four tasks **+ the `CANNOT ASSESS` composer branch** | The sweep surfaced it as a fail-open; the owner picked the wording rather than leaving a default in place. |
| `git add … PRD.md` implied by task 2 | `PRD.md` untouched | §2 above. |

---

## 4. FOR DEV D — the `CANNOT ASSESS` contract

The shell is **already correct** for your fourth verdict. You do not need to touch
`orca/agentic.py`. What you do need is to match two things exactly.

### 4.1 The action string

```python
CANNOT_ASSESS = "CANNOT ASSESS"   # orca/agentic.py
```

Exact match, including the space and the case. Anything else falls into the `else`
branch, which tells the composer *"Then tell them plainly what to do"* — i.e. it phrases
a zone ORCA knows nothing about as actionable advice. That is the §1.3 confident gap
arriving inside the answer text, and it is the specific defect this branch exists to
prevent.

### 4.2 `agent_findings[].observation_ids` must be present and honest

The composer names *which readings ORCA lacked*, and it derives that list from the
findings you produce:

```python
# an agent that cited no observation ids had nothing to look at
for finding in recommendation.get("agent_findings") or []:
    if not finding.get("observation_ids"):
        ...
```

- It is read off the findings on purpose, never re-derived from a hardcoded variable
  list — so it **cannot name a reading the planner did not actually find missing**.
- If `agent_findings` is absent or empty, it degrades gracefully: the answer still says
  ORCA does not know, it just names nothing. Not an error.
- Keep `observation_ids` populated for agents that *did* have data, or they will be
  reported as blind.

### 4.3 What the answer will say

Plainly that ORCA has no readings and does not know; **neither "go" nor "don't go"**; the
missing readings by name; and the alternative zone if one exists. That wording was a
human decision (R-39's "I don't know is a defensible answer"), not a default — change it
only with the same sign-off.

Tests: `tests/test_agentic.py`, the block headed *"R-39's fourth verdict, seen from the
shell"*. Includes a regression guard that `GO`, `DO NOT GO` and `SAFER ALTERNATIVE` are
unmoved.

---

## 5. FOR DEV A / DEV D — R-25 consumer sweep result

Read-only sweep, done. Full table: **https://claude.ai/code/artifact/2556fa1b-0164-48e7-90c0-656448e44ec4**

**Two consumers render an unrecognised verdict as safe green. Both block R-39.**

| Location | What it does with a value it doesn't recognise |
|---|---|
| `web/index.html` — `actionClass()` | Falls through to `return "action-go"`. An unassessable zone renders in the GO green, in the same badge. |
| `web/three-viz.js` — `ACTION_COLOR[action] \|\| COLOR_LOW` | `COLOR_LOW` **is** the GO green (`#0f6e5c`, `--accent`). The 3D core sphere renders safe too. **Not on the brief's checklist — found by widening the grep.** |

Fix both by adding an explicit `CANNOT ASSESS` branch/entry, so nothing reaches the
permissive default. Stronger fix if there is time: make the default *fail loud* — render
an unknown verdict in a colour that belongs to no verdict — so the next enum widening
cannot repeat this silently.

**16 further sites hard-fail** (loud, safe) and must be widened in the same change:
`tests/test_api.py:17,97` · `tests/test_mcp_server.py:17,49` ·
`e2e/live.spec.js:227,246` and the seven `toHaveText(/GO|DO NOT GO|SAFER ALTERNATIVE/)`
regexes · `e2e/agentic-exceptions.spec.js:64,81` · `e2e/mock.spec.js:15`.

**The grep in `DEV_B.md` is too narrow.** It searches only `SAFER ALTERNATIVE` and
`DO NOT GO`, so it cannot see a consumer branching on `GO` alone — which is why
`e2e/live.spec.js:131` was on the checklist but not in the results. Use:

```bash
grep -rn "SAFER ALTERNATIVE\|DO NOT GO\|=== *['\"]GO['\"]\|== *['\"]GO['\"]" \
  --include=*.py --include=*.js --include=*.html --include=*.json --include=*.md \
  --exclude-dir=node_modules --exclude-dir=test-results .
```

One thing that is already right: `orca/planner.py:268` reads
`d.action == "GO" and d.chosen is not None`, so a `CANNOT ASSESS` zone can never be
offered as a safer alternative. **R-39b holds by construction.**

---

## 6. INVARIANTS — do not break these

Each has a test. Each is a claim the PRD makes in public.

1. **`orca/agentic.py` never imports `orca/policy.py`.** Structural, not conventional.
   The shell may change which zone was resolved, which of four answer shapes is composed,
   and the wording. It may never change `action`, `risk_level`, `hard_deny`, or any
   number. (P3, P9)

2. **Composition receives no conversation history — ever.** (R-42, R-53)
   `compose_grounded_answer()` has no `history` parameter and no reference to one in its
   body. History reaches *only* extraction, and only as validated enum values from
   `orca/memory.py`. This is what makes hallucination-compounding and history-borne
   prompt injection structurally impossible rather than merely unlikely. If you add a
   parameter to the composer, check it cannot carry user text.

3. **The composer's output schema has exactly two fields** — `answer_text` and
   `cited_evidence_ids`. There is no field through which it could alter a verdict even if
   it tried. Do not add one.

4. **Every failure path logs its reason** (R-45, N-4). A typed `except X: pass` is still
   a swallowed exception. `grep -rn -A1 "except .*:" orca/ data/` must show no bare
   `pass` body.

5. **The layer's total added latency stays under `LAYER_BUDGET_S`** (R-49). Both the skip
   *and* the clamp are required; removing either restores the 16 s worst case.

---

## 7. Verify after merge

```bash
pytest -q tests/test_agentic.py                      # 61 passed, 1 skipped
pytest -q --ignore=tests/test_mcp_server.py          # 239 passed, 1 skipped
npx playwright test                                  # 38 passed, 1 skipped
```

`tests/test_mcp_server.py` aborts collection — known G-1 bug, Dev C's fix. The one skip
is the live-Groq test, which correctly skips without a real key.

**Prove the fallbacks are audible** — the whole point of R-45:

```bash
GROQ_API_KEY=deliberately-invalid python -m uvicorn orca.api:app --port 8000
curl -s localhost:8000/ask -H 'Content-Type: application/json' \
  -d '{"query":"can I fish at Nagapattinam","lat":10.7672,"lon":79.8449}'
```

Expected — a WARNING on **both** paths, and a correct deterministic answer:

```
WARNING orca.api:      ORCA agentic layer: ON (GROQ_API_KEY found)
WARNING orca.agentic:  Agentic extraction unavailable, using deterministic defaults: 401 ...
WARNING orca.agentic:  Agentic composition unavailable, using deterministic text: 401 ...
```

If you see only one WARNING, R-45 has regressed.

---

## 8. Known sharp edges

- **Groq free tier will 429 under demo load.** Composition then falls back to
  deterministic template text — correct fail-closed behaviour, now visibly logged, but
  the answers get noticeably blunter. Budget the question count on stage, or pay for a
  tier.
- **`LAYER_BUDGET_S = 10.0` is a chosen number, not a measured one.** It is a defensible
  ceiling, not an SLA. Raising it above `2 × REQUEST_TIMEOUT_S` fails
  `test_the_budget_is_smaller_than_two_full_call_timeouts` on purpose.
- **The 3D fail-open (§5) is not in `web/`'s test coverage.** Playwright asserts the
  badge text, never the sphere colour. Fixing `ACTION_COLOR` without adding a test leaves
  the same hole open for the next enum change.
