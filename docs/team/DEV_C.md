# DEV C — test suite & gates

**Branch:** `mcp` · **Kill time:** T+3:00 for the pin; gates run to the end.

**You own, exclusively:**
`orca/mcp_server.py` · `requirements.txt` · `tests/test_mcp_server.py`

You also **run** (do not edit) `data/fetch.py` at T+6:00, and you write results into
`TEAM_STATUS.md`.

---

## Start here

```bash
cd ~/cloon/or/ORCA
git fetch origin
git checkout -b mcp origin/main        # AFTER Dev D pushes the frozen contract

python3 -m venv .venv                  # once, if you don't have one
.venv/bin/pip install -r requirements.txt
```

Sanity check before you change anything — this is the baseline every other number in
this doc is compared against:

```bash
.venv/bin/python -m pytest -q --ignore=tests/test_mcp_server.py
# 226 passed, 1 skipped
```

---

## Task 1 unblocks everyone's test run — do it first

### [P1] G-1 — root cause found

`requirements.txt` pins `mcp==2.1.1`. In mcp 2.x, `FastMCP` was renamed `MCPServer`.
`orca/mcp_server.py:14` still imports the v1 name:

```
orca/mcp_server.py:14: from mcp.server.fastmcp import FastMCP
E   ModuleNotFoundError: No module named 'mcp.server.fastmcp'.
    This is mcp 2.x, where FastMCP was renamed to MCPServer
```

That single error **aborts collection for the entire suite** — which is why every
`pytest` invocation in this repo currently carries `--ignore=tests/test_mcp_server.py`,
and why G-1 has never been green.

**Take the pin, not the migration.** `mcp<2` resolves clean (verified). Migrating a
stretch-goal file with hours left is unforced risk, and the war plan is explicit that a
branch not green by its kill time is abandoned, not rescued.

```diff
  # requirements.txt
- mcp==2.1.1
+ mcp<2
```

```bash
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m pytest -q     # ← no --ignore. G-1 green, first time.
```

Tell the team the moment this lands — you re-run G-7 without the ignore flag, and the
Dev D's done-when depends on it.

While you're in the file: `mcp_server.py` is a consumer of the `action` value. Confirm
it passes the value through untouched (it returns `build_recommendation(...).to_dict()`)
and tell Dev B, who is filling in the verdict column of the R-25 consumer sweep.

---

## Task 2 — the gates nobody has actually run

### [P1] G-4, G-7 and G-8 — run them, record real output

All three are marked *"not re-run this session"*, and G-7 is worse than that: **the repo
had no `.venv` at all**, and neither `pytest` nor `fastapi` was importable until one was
created today. G-7 is unproven, not merely unrepeated — and it's the gate standing behind
*"it will work on the presentation laptop."*

**G-7 — does a fresh clone come up clean?**

```bash
cd "$(mktemp -d)"                       # scratch dir, nothing to delete
git clone /home/delta/cloon/or/ORCA orca-g7
cd orca-g7
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m pytest -q --ignore=tests/test_mcp_server.py
```

Expected, exactly: **`226 passed, 1 skipped`** — the skip is the live-Groq test, which
correctly skips because a fresh clone has no `.env`. Anything else is a finding.

Three things worth knowing: **don't run `data/fetch.py`** (the cache is committed, all 10
files, so the clone already has it — and a pointless live fetch risks rate-limiting the
sources you need at T+6:00); the `--ignore` flag is only needed until your own pin lands,
then **re-run without it** and expect all 11 files to collect; and clone from the **local
path**, since `origin` is SSH and a local clone tests the same property with no
credentials.

**G-4 — is rule 2 actually load-bearing?**
Delete policy rule 2, confirm a test fails, `git checkout` immediately.

You are touching the frozen file for sixty seconds. Do it **on your own branch**, and do
not let the deletion survive into a commit. The test that should fail is
`tests/test_policy.py:84` (`test_rule_2_is_load_bearing_do_not_delete`) — if it passes
with rule 2 deleted, that is a serious finding and Dev D needs to know immediately.

**G-8 — does the offline claim hold?**
Wifi **physically** off — not a proxy, not a firewall rule. `/ask` still answers, and
the badge flips to offline.

Note Dev D is removing `_is_reachable()` from `/ask` (R-54), so run this **after**
their merge if you can; the badge should then be driven by `/health`.

Paste **actual output** into `TEAM_STATUS.md`, not a summary. The project's definition
of done is having run it.

---

## Task 3 — the cache refresh (T+6:00)

MANUAL_TASKS §1. The cache was fetched 2026-08-26; on stage the page will honestly show
a large `freshness_min`, which is correct behaviour and a bad look.

```bash
.venv/bin/python data/fetch.py
.venv/bin/python scripts/generate_demo_scenarios.py --base-url http://127.0.0.1:<port>
```

This is the last mechanical step before the demo and the one with the least room to
recover, which is why it sits with someone who has spent the day in the test suite.

**Report which zone shows the live override conflict.** It moves with the weather, and
today it is *not* the Nagapattinam/Karaikal pair the README and `API_CONTRACT.md` use as
their illustrative example. The presenter needs the real answer.

After the refresh, re-run Dev D's zone sweep — fresh weather can move zones across
the 0.6 threshold, and the demo should be rehearsed against what's actually there:

```bash
.venv/bin/python -c "
from data.fetch import ZONES
from orca.planner import build_recommendation as b
for z in ZONES:
    r = b(f\"fishing at {z['name']}\", z['lat'], z['lon'])
    print(f\"{z['name']:15} {r.action:16} {r.reason[:45]}\")"
```

---

## Task 4 — the two regressions (after Dev D merges, T+5:00)

Without these, both fail-opens come back the next time someone refactors.

- **G-13** — a coordinate with no cached readings returns `CANNOT ASSESS`, never `GO`.
- **G-14** (new) — a zone with a finding ≥ 0.6 and no opportunity never returns `GO`.

G-14 is the regression for the bug that is live right now: Kanyakumari and Colachel both
return `GO` with reason *"No hazards found; conditions acceptable"* while carrying wind
and wave findings above the threshold in their own evidence lists.

---

## Done when

```bash
.venv/bin/python -m pytest -q     # collects all 11 test files, green
```

Baseline for comparison, before your pin: **226 passed, 1 skipped** with
`--ignore=tests/test_mcp_server.py`. The one skip is the live-Groq test, which correctly
skips without a real key — it is not a failure.

`TEAM_STATUS.md` carries real pasted output for G-1, G-4, G-7 and G-8.

---

## Ship it

Push the pin **as soon as it's green** — three other people are running `pytest` with a
workaround flag until it lands.

```bash
git add requirements.txt
git commit -m "fix: pin mcp<2 so the suite collects (G-1)"
git push -u origin mcp
```

Then tell everyone they can drop `--ignore=tests/test_mcp_server.py`.

Gate results are **not** a code commit — paste the real terminal output into
`TEAM_STATUS.md` and push that separately, or hand it to Dev D if the file is contested.

**A warning specific to your G-4 run.** `git add .` is fine in this repo generally —
`.gitignore` covers `.venv/` and the caches. But it stages *modifications to tracked
files*, not just new ones, and for sixty seconds during G-4 one of those modifications is
the deletion of the project's headline safety rule.

So in that window only: name your files explicitly, and `git checkout orca/policy.py` the
moment the test has failed. Then confirm you're clean before committing anything:

```bash
git status --short          # orca/policy.py must NOT appear
```
