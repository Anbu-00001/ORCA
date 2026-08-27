# ORCA — conventions

- **TDD, always**: tests exist before/alongside implementation for every
  module. Network-touching code (`data/fetch.py`) is tested against REAL
  captured fixtures in `tests/fixtures/real_*.json|csv`, never invented
  response shapes — capture a real response first (curl/WebFetch), then test
  the parser against it.
- **Validation pattern**: plain `@dataclass` + `__post_init__` that raises
  `ValueError` on invalid state (see `schema.py`, `policy.py`). This is the
  house style for invariant enforcement — not pydantic, not a separate
  validator layer, even though pydantic is a dependency (kept scoped to the
  FastAPI request boundary only).
- **Agents are plain functions**, `list[MarineObservation] -> Finding`, no
  classes/registry/framework. Adding a 6th agent means adding a 6th function
  with this exact signature, registered in `orca.planner.AGENTS`.
- **Missing-evidence pattern (replicate this for anything new)**: when an
  agent/fetcher lacks the data it needs, return/emit a neutral result
  (`suggests_go=False, risk_level=0.0, hard_deny=False`) with a `reason`
  string naming what's missing — never a fabricated conclusion, never silence.
  This is CLAUDE.md rule 1 applied at the Finding level, not just the raw-data
  level.
- **`policy.resolve()` is intentionally zone-agnostic** — it answers "should
  you go to the place these findings describe," nothing about comparing
  locations. Cross-zone comparison ("try an alternative") lives in
  `planner.build_recommendation()`. Do not add a zone/location parameter to
  `resolve()`; read its docstring before changing its signature.
- **Frontend test hooks**: every interactive/assertable element needs a
  `data-testid` — that's what `e2e/*.spec.js` targets, not CSS selectors or
  text content. `?mock=1` forces `web/mock_response.json` (isolated dev/test,
  no network); `?api=<url>` overrides the API base.
- **`demo/scenarios.json` is read-only output, never an input** — generated
  by querying the real running API. Nothing in `orca/api.py` or
  `orca/planner.py` may branch on strings from this file; doing so recreates
  the exact "hardcoded string" failure the project's design is built to avoid.
- **Commits**: small, one module/concern per commit, green before the next
  one starts. Messages state what was actually verified (which command, what
  output), not just what changed.
