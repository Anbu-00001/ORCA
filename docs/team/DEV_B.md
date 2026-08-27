# DEV B — agentic layer

**Branch:** `agentic` · **Kill time:** T+3:30

**You own, exclusively:**
`orca/agentic.py` · `tests/test_agentic.py`

You also produce the **R-25 consumer sweep** (task 4) — read-only, no files edited.

**You are fully isolated from the safety cascade.** `agentic.py` never imports
`orca/policy.py`, and by design it never decides `action`, `risk_level` or `hard_deny` —
it resolves zones and phrases answers. Nothing you do here can change a verdict. That is
why this track can run in parallel with Dev D's safety work with no coordination.

Tasks 1–3 are small and independent of everyone else. **Task 4, the consumer sweep,
is not** — Dev A and Dev D are both blocked on it, so do it first or early.

---

## Tasks

### 1. [P1 — one line] R-45 — the one silent fallback

`orca/agentic.py:642`:

```python
except AgenticUnavailable:
    pass  # every field keeps its deterministic default
```

The composition fallback 130 lines below it, at `:772`, does this properly — and its
comment explains exactly why:

> *Falling back is correct; falling back QUIETLY is what let a server run all day with
> the agentic layer off while looking exactly like one that had it on.*

This is the one place the module doesn't follow the lesson its own comment states. Copy
the pattern up: log a warning naming the reason, keep the deterministic default.

**This closes N-4** — the last `except…pass` in the repo, and one of the three
non-functional requirements currently sitting at Partial.

Keep the existing behaviour identical. The fallback must still be silent in *effect* —
every field keeps its deterministic default — it just stops being silent in the log.

### 2. [P2] R-49 — one wall-clock budget for the whole layer

`REQUEST_TIMEOUT_S = 8.0` at `:66` is **per call**, and `answer_question()` makes two
sequential calls: extraction, then composition. Worst case the layer adds **~16 s** to a
single request.

That worst case is precisely the stage condition: a key present, the network
unreachable, wifi off, a judge watching.

The requirement is that the added time be **bounded and stated**:

- Add a single budget constant for the whole layer.
- Stamp a start time at the top of `answer_question()`.
- Before the composition call, check whether the budget is already spent. If it is, skip
  it and fall back — **logged, per task 1** — rather than starting a second 8 s wait.
- State the resulting bound in the PRD's R-49 row. A bound nobody wrote down isn't
  "stated".

### 3. [P2] Test the bound

Assert the layer cannot add more than the budget. Without a test, R-49 is a comment.

The existing suite already has the patterns you need — `tests/test_agentic.py` is 896
lines and covers the fail-closed paths (no key, network error, timeout, malformed JSON,
schema violation) thoroughly. Follow its style rather than inventing one.

### 4. [P0 — Dev A and Dev D are waiting on this] The R-25 consumer sweep

This one is outside your file and it is **read-only** — you produce a table, not a diff.
Do it early; Dev A needs it for the e2e work and Dev D needs it before widening the
enum.

The backend is adding a fourth `action` value, `CANNOT ASSESS`. The amended R-25 says a
new value in an existing enum is **not** additive: a client switching on `action` lands
in its default branch, and if that default is "proceed", the widening has built a
fail-open. It requires every known consumer to be checked first. **Nobody has done it.**

```bash
grep -rn "SAFER ALTERNATIVE\|DO NOT GO" \
  --include=*.py --include=*.js --include=*.html \
  --include=*.json --include=*.md .
```

For each hit, two columns:

- **How it's used** — a fact you read off the line: *compares* (`===`, `==`, `!==`) ·
  *matches* (a regex) · *prints* · *documents*.
- **What it does with a value it doesn't recognise** — the verdict. This is the real
  work, and it's the question that already found the worst defect in the audit:
  `web/index.html:587` falls through to `return "action-go"`, so an unknown verdict
  renders green.

Check your table catches these; if any is missing, the grep was too narrow:
`web/index.html:587` · `e2e/live.spec.js` 29, 66, 119, 176, 189 and 90, 131 ·
`e2e/mock.spec.js:15` · `orca/mcp_server.py` · `demo/scenarios.json` · `README.md` ·
`API_CONTRACT.md`

Post the table to the team. Anything that defaults to "proceed" is a blocker on the
Dev D's R-39, not a nice-to-have.

---

## Done when

```bash
.venv/bin/python -m pytest -q tests/test_agentic.py
```

And prove the fallback is actually audible now — this is the whole point of task 1:

```bash
GROQ_API_KEY=deliberately-invalid .venv/bin/python -m uvicorn orca.api:app
# then POST to /ask and watch the log:
# → a WARNING naming the reason, on BOTH paths, not just composition
```

If you run the whole suite, note that `tests/test_mcp_server.py` currently aborts
collection — that's the known G-1 bug and Dev C is fixing it. Until their pin lands:

```bash
.venv/bin/python -m pytest -q --ignore=tests/test_mcp_server.py
# baseline: 226 passed, 1 skipped
```

The one skip is the live-Groq test, which correctly skips without a real key.

---

## Do not

- Touch `orca/policy.py` or `orca/schema.py` — frozen (N-5).
- Touch `orca/planner.py` or `orca/api.py` — Dev D is rewriting the zone loop in
  `planner.py` and will conflict with you.
- Let anything you add reach a prompt from conversation history. R-53: history reaches
  **only** the extraction step, never composition. R-42: the composer receives no
  history at all and has no schema field for `action`, `risk_level` or `hard_deny`.
  Both are load-bearing claims — keep them true.
