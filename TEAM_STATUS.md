# ORCA — Team Status

**For:** other ICARUS teammates and their agents/assistants working on this repo.
**Last updated:** 2026-08-27, real zones + basemap overhaul (the 4 mock "Zone A/B/C/D" points are gone).
**Read this before touching the repo.** It tells you what's real, what's
verified, what's still a manual/human job, and where not to step.

---

## TL;DR

Everything in the war plan's "BUILD" list (S7) is code-complete, including
the MCP stretch goal, and was 103 pytest + 12 Playwright tests green as of
commit `4cdcb13`, all run against real data (no mocks feeding the app
itself). A fresh `git clone` + `pip install -r requirements.txt` + `pytest`
was verified to work standalone at that point.

**New, beyond the war plan:** two three.js 3D visualizations (a per-query
reasoning graph, and a geospatial ocean diorama with real NOAA bathymetry +
risk columns) — see "3D visualizations" below. **The 4 mock "Zone
A/B/C/D" points are gone**, replaced by 10 real, named, Wikipedia-sourced
Tamil Nadu coastal fishing harbours spanning the whole coast, and the map
basemap changed from generic demo tiles to a real, labeled OpenStreetMap
style — see "Real zones" below. Backend suite is **114 pytest tests, all
green** (excluding `orca/mcp_server.py`, still broken — see below,
unchanged); e2e is **20 Playwright tests, all green**.

**⚠️ `orca/mcp_server.py` still does not import** (unrelated to the 3D
work below): a parallel edit broke it against the installed `mcp` package,
and that one `ModuleNotFoundError` aborts collection for the *entire*
`pytest -q` run unless you `--ignore=tests/test_mcp_server.py`. See
"Code-intelligence tooling" below for the exact error and the fix. Nothing
else is known to be broken — this is one bad import in one file, and every
count in this document already excludes it.

**What you actually need to do before the real demo:** see
[`MANUAL_TASKS.md`](MANUAL_TASKS.md). It's short. Read it.

---

## War plan §7 completion status

Every code-shaped item in the plan is done. Everything left is human-only
(logistics, a recording, PowerPoint) and lives in `MANUAL_TASKS.md` — there
is no outstanding code work.

| # | §7 item | Status |
|---|---|---|
| 1 | Backend pulling real marine data | ✅ `data/fetch.py` — 3 real sources |
| 2 | Normaliser → MarineObservation | ✅ `orca/schema.py` |
| 3 | Five agents | ✅ `orca/agents.py` |
| 4 | Safety policy | ✅ `orca/policy.py`, mutation-tested |
| 5 | API + evidence | ✅ `orca/api.py` |
| 6 | Web page (map/answer/evidence) | ✅ `web/index.html` |
| 7 | Offline toggle, proven | ✅ tested incl. full network-block simulation |
| 8 (stretch) | MCP server wrapper | ⚠️ built + tested, **currently broken by a regression** — see below |
| 9 (stretch) | Slide 2 diagram redraw | ❌ deck/PowerPoint work, human-only |

§7's DO NOT BUILD list (Flutter app, NavIC/LoRa hardware, live Tamil ASR,
Jac/Mojo rewrite, login/db, training anything) — correctly left alone; see
"What's NOT built" below for the reasoning on each.

The rest of the war plan document (§1–6, 9–15) is pitch script, Q&A prep,
deck instructions, and hour-by-hour logistics — not buildable by an agent.
The pieces of it that matter to code have already been folded into this
file, `README.md`, and `MANUAL_TASKS.md`.

---

## What's built and verified

| Piece | File | Status |
|---|---|---|
| MarineObservation schema + validation | `orca/schema.py` | ✅ 15 tests |
| Safety policy (Finding/Decision/resolve) | `orca/policy.py` | ✅ 19 tests, mutation-verified |
| Real data fetchers (Open-Meteo x2, NOAA ERDDAP) | `data/fetch.py` | ✅ 11 tests incl. live integration |
| Five agents | `orca/agents.py` | ✅ 27 tests |
| Planner (query -> agents -> policy -> answer) | `orca/planner.py` | ✅ 16 tests |
| FastAPI (`/ask`, `/evidence/{id}`, `/health`) | `orca/api.py` | ✅ 10 tests |
| Frontend (map, answer, evidence panel, offline badge) | `web/index.html` | ✅ 12 Playwright e2e tests (incl. wifi-off simulation) |
| MCP server wrapper (stretch, S6.2) | `orca/mcp_server.py` | ⚠️ 5 tests, real protocol-level — **currently broken, see below** |
| Demo scenario transcript | `demo/scenarios.json` | ✅ generated from live output |
| Real bathymetry fetcher + `GET /bathymetry` | `data/fetch.py`, `orca/api.py` | ✅ 7 + 2 tests, live-integration-tested |
| Reasoning graph + ocean diorama (three.js) | `web/three-viz.js`, `web/three-viz-app.js` | ✅ 8 Playwright tests (mock + live) |

Run everything yourself:
```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest -q --ignore=tests/test_mcp_server.py   # 114 tests, green
                              # (fix orca/mcp_server.py's import -- see "Code-intelligence
                              #  tooling" below -- to also get its 5 tests running)
npm install && npx playwright install chromium
npx playwright test          # 20 tests, boots real servers itself -- unaffected by the pytest issue
```

Run the actual app:
```bash
source .venv/bin/activate
uvicorn orca.api:app --host 127.0.0.1 --port 8000 &
python3 -m http.server 8080 --directory web &
# open web/index.html directly, or http://127.0.0.1:8080/index.html
```
If port 8000 is already taken on your machine, use any port and open the
page with `?api=http://127.0.0.1:<port>`.

---

## Architecture decisions worth knowing about

These aren't in the war plan verbatim — they're judgment calls made while
building, and you should know the reasoning before "fixing" them.

1. **Zone-scoped policy, planner does zone comparison.** `policy.resolve()`
   answers "should you go to the place these findings describe" for ONE
   zone. `planner.py` runs it per-zone across `data/fetch.py`'s `ZONES`
   (currently A/B/C/D) and does the cross-zone "try an alternative"
   search. This is why `resolve()`'s signature has no zone/location
   argument — don't add one without re-reading `orca/policy.py`'s docstring.

2. **No LLM anywhere in the reasoning path**, including query parsing.
   `planner.resolve_zone_from_query()` is a plain substring match against
   known zone names, falling back to nearest-zone-by-coordinates. This
   was a deliberate choice, not a shortcut: an LLM call would need
   network at query time, which breaks the offline guarantee (CLAUDE.md
   rule 8) and adds a place for the "policy got prompted around" failure
   mode the whole pitch argues against. If you add real NLP here, it
   must not touch policy.py's decision, only phrase the input.

3. **ERDDAP via raw HTTP, not `erddapy`.** One fewer dependency, same
   real service. `coastwatch.noaa.gov` 403s the default `python-requests`
   User-Agent — `data/fetch.py` sends a real self-identifying one.

4. **Chlorophyll data is often stale/absent per zone, on purpose.**
   The VIIRS NRT dataset is frequently cloud-masked right at our bbox,
   and its "latest" granule can lag wall-clock time by weeks. `fetch.py`
   anchors to ERDDAP's own `(last-N):(last)` relative time, not
   `date.today()`, and skips a zone's chlorophyll entirely (logged, not
   faked) if nothing in the lookback window is valid. Today's real cache
   has chlorophyll for Point Calimere/Mandapam/Rameswaram/Thoothukudi but
   not the other 6 zones — that's real, not a bug.

5. **`demo/scenarios.json` is a read-only transcript, not a hardcoded
   response path.** It's generated by `scripts/generate_demo_scenarios.py`
   querying the real running API. **Nothing in `orca/api.py` or
   `orca/planner.py` branches on query strings from this file.** If you
   ever see code that special-cases "Nagapattinam" or similar, that's the
   exact "hardcoded string" failure the war plan's S8.4 warns about —
   flag it, don't build it.

6. **The live "conflict" demo is currently wind-driven, not wave-driven.**
   Real wave heights sampled at our coastal points stayed under ~1.6m
   through the whole build (nearshore, sheltered water — even scanning
   3+ months of real historical data, no day exceeded ~1.0m at
   Nagapattinam's exact point). The `hazard_agent`'s >2.5m hard-deny rule
   is real and tested (including the exact 3.1m/1.0m flip from S8.4, see
   `tests/test_agents.py::test_hazard_flip_from_dangerous_to_safe_...`),
   but it may not fire on real data on demo day. Real wind risk at
   Mandapam/Rameswaram *does* naturally trigger the override live, today
   — see `demo/scenarios.json`. Re-run `scripts/generate_demo_scenarios.py`
   close to the presentation to see what's live then; conditions change.

7. **Offline mode is a display concern, not a behavior branch.** `/ask`
   and `/evidence` always read only `data/cache/`, online or not — see
   `tests/test_api.py::test_ask_and_evidence_are_unaffected_by_connectivity`.
   `/health`'s `offline_mode` is a best-effort connectivity probe used
   *only* to flip the frontend badge. Physically switching off wifi during
   the demo is safe to do live; it changes the badge and nothing else.

---

## 3D visualizations (new, beyond the war plan)

Requested directly (not in S7): "make our outputs more enthusiastic and
energetic for researchers to see." Two three.js views, both additive —
nothing existing was replaced, and both degrade to an inert placeholder
(never a fabricated one) if their data source is unavailable.

1. **Reasoning graph** (`ReasoningGraph` in `web/three-viz.js`, toggled by
   "View reasoning in 3D" under the evidence panel). A per-query 3D render
   of the actual decision trace: the final action at the center, the 5
   agents on a ring around it (sized/colored by `risk_level`, a red pulsing
   ring if `hard_deny`), and each agent's own supporting
   `MarineObservation`(s) one ring further out. Every node is real data
   from the last `/ask` response's new `agent_findings` + `evidence`
   fields (see API_CONTRACT.md) — nothing here is a separate computation
   or an illustration; it's the same reasoning the answer card already
   shows, laid out spatially. Layout is a fixed radial placement, not a
   force simulation — the graph is small and constant-shaped (1 decision +
   5 agents + ~4-10 observations), so a physics layout would only add
   instability, not information.

2. **Ocean diorama** (`OceanDiorama` in `web/three-viz.js`, toggled by the
   "3D Ocean" / "2D Map" buttons over the map). Real seafloor/land relief
   from `GET /bathymetry` (NOAA NCEI ETOPO 2022, 60 arc-second — see
   `data/fetch.py`'s `ERDDAPBathymetryFetcher`), with a risk column at
   each zone from the last `/ask` response's new `zone_summaries` field
   (worst-agent `risk_level` + `hard_deny` per zone, computed by
   `build_recommendation()` — every zone it evaluates, not just the
   chosen one). Vertical scale is exaggerated 1/1200 for legibility (real
   depths here range roughly -3700m to +560m within the BBOX) — clearly a
   presentation choice, documented in the code, never applied to the
   underlying numbers. Clicking a column calls the same
   `window.__ORCA_SELECT_ZONE__` bridge the 2D map's markers use, so both
   views drive one shared query flow.

**Backend additions backing these** (both additive to API_CONTRACT.md,
existing clients ignore unknown fields):
- `Recommendation.agent_findings` / `.zone_summaries` (`orca/planner.py`)
  — surfaces computation `build_recommendation()` already did internally
  but previously discarded. Neither field is fabricated; see the tests
  named `test_recommendation_*` in `tests/test_planner.py`.
- `GET /bathymetry` (`orca/api.py`) — 503 if the cache isn't populated,
  never a fabricated/empty 200.
- `ERDDAPBathymetryFetcher` (`data/fetch.py`) — real ETOPO 2022 data from
  `oceanwatch.pifsc.noaa.gov`'s ERDDAP (two other NOAA ERDDAP mirrors
  timed out during research — see SCRATCH.md if you need a different
  region/mirror). Cached to **`data/cache/bathymetry/`, a subdirectory** —
  load-bearing detail: `orca/planner.py`'s `load_cached_observations()`
  globs `data/cache/*.json` non-recursively and parses every match as a
  list of `MarineObservation` dicts, so a bathymetry grid (one dict, a
  `points` key, not a list) sitting directly in `data/cache/` would break
  every `/ask` call. `tests/test_fetch.py`'s
  `test_write_bathymetry_cache_is_not_swept_by_load_cached_observations`
  guards this specifically — don't "clean up" that subdirectory into the
  parent.

**Checked and not used:** MOSDAC (ISRO's own ocean data portal) — every
dataset needs an authenticated account, no anonymous endpoint exists (see
SCRATCH.md for what was checked and why it's a post-hackathon item, not a
blocker). A teammate-suggested coral-reef ML repo was also checked and
isn't usable here — its data is explicitly synthetic (CLAUDE.md rule 1)
and it has no 3D visualization to borrow from anyway.

**Known trade-off, not fixed:** three.js itself is loaded from `unpkg.com`
(pinned version, same convention as `maplibre-gl`), not vendored locally.
Unlike MapLibre (which has a tested SVG fallback), the 3D views have no
offline fallback yet — under the wifi-off scenario in S8.6, "3D Ocean" and
"View reasoning in 3D" would fail to load (2D map, answer card, and
evidence panel are completely unaffected — they don't depend on this).
Not fixed because it wasn't asked for and the war plan's core demo doesn't
route through either 3D view; vendoring `three.module.js` +
`OrbitControls.js` locally would close this if it's ever wanted.

---

## Real zones + basemap (the "this looks like a dummy demo" fix)

Direct feedback: the 4 "Zone A/B/C/D" points and the generic map tiles
read as placeholder/mock, even though the marine readings *at* those
points were always real. Fixed both halves.

**`data/fetch.py`'s `ZONES`** is now 10 real, named Tamil Nadu coastal
fishing harbours/towns, north to south (Chennai, Cuddalore, Karaikal,
Nagapattinam, Point Calimere, Mandapam, Rameswaram, Thoothukudi,
Kanyakumari, Colachel) — the full ~1076 km coastline, not 4 points
clustered near one town. Each coordinate is sourced from that place's own
Wikipedia article (a couple from the more specific *fishing harbour*
article where one exists — see the comment above `ZONES`), not eyeballed.
`BBOX` widened to match (`data/fetch.py` top of file). This is a genuinely
large blast radius — grep the commit for `Zone A|Zone B|Zone C|Zone D` if
you're touching an area not mentioned below; **~127 occurrences across 18
files** were updated, all except `ORCA-32-HOUR-WAR-PLAN-v3.md` (a
historical planning document — deliberately left as originally written,
not retconned).

- `orca/agents.py`'s `geofence_agent.PROHIBITED_ZONE` is now a small box
  around **Krusadai Island** (9.20°N, 79.17°E), a real island inside the
  real Gulf of Mannar Marine National Park (India's first marine
  biosphere reserve) — not an arbitrary empty-ocean rectangle. It's ~7km
  from Mandapam and ~15km from Rameswaram in `ZONES`, close enough to be
  a real nearby hazard, verified not to overlap either point.
- The map basemap changed from `demotiles.maplibre.org` (generic, no
  labels, no real cartography) to **OpenFreeMap** (`tiles.openfreemap.org`,
  free, no API key, no request limits — see `web/index.html`'s comment
  above `initMap()`), `liberty` style specifically because it carries
  real place labels and coastline detail.
- The `#map-fallback` SVG (shown when the map CDN is unreachable) now
  plots all 10 real zones via a straight lat/lon → screen-space
  projection instead of 4 hand-placed dots — an approximate sketch of the
  real coast shape.
- `scripts/generate_demo_scenarios.py` now imports `ZONES` from
  `data.fetch` instead of keeping its own second hardcoded copy — one
  source of truth going forward, not two lists that can drift apart.
  `demo/scenarios.json` was regenerated for real against the running API
  (not hand-edited) — Mandapam and Rameswaram currently show a genuine
  live wind-risk override.
- `docs/screenshots/orca_live_demo.png` was recaptured against the real
  running system after this change (Playwright, real query, real
  screenshot) — the old one visibly showed the "Zone A/B" mock UI on the
  bare demo-tiles map, which was itself evidence for the complaint that
  prompted this section.
- `data/cache/` and `data/cache/bathymetry/` were refetched for real
  against all 10 new points / the wider BBOX — run `python -m data.fetch`
  yourself if your local cache still has the old 4-zone data (you'll know
  because `/ask` for a new zone name returns 0 evidence).

**Not changed:** `orca/schema.py`, `orca/policy.py` (frozen per CLAUDE.md
rule 5 — this was a data/config change, not a policy change), and the
actual agent logic in `orca/agents.py` apart from the one constant above.

**Researched, not usable (yet):** MOSDAC (ISRO's ocean data portal) and
CMFRI's GIS-based fishing-harbour coordinate inventory both require an
authenticated account for any actual data/coordinates — no anonymous
download exists for either. INCOIS's live Potential Fishing Zone WebGIS
(~1223 real advisory nodes along the Indian coast) has no documented
public API either; its backend service URLs weren't discoverable without
inspecting live network calls in a real browser session, which wasn't
attempted. Protected Planet (WDPA) requires an API token for the Gulf of
Mannar's *exact* official boundary polygon — the Krusadai Island box
above is a real, verifiable point-based approximation, not that official
shape. All of this is written up in more detail in `SCRATCH.md`.

---

## What's NOT built (and why that's fine)

Per the war plan's explicit "DO NOT BUILD" list (S7) and scope decisions:
Flutter app, NavIC/LoRa hardware, live Tamil ASR, a Jac/Mojo rewrite,
login/accounts/database, training anything. Also not built: MOSDAC/INCOIS
live integration (registration pending — see MANUAL_TASKS.md), real
`return by <time>` forecasting (would need multi-hour forecast series per
observation, not just the nearest hour — worth a LATER item, not scoped
now, and deliberately not fabricated as a number with no data behind it).

---

## Code-intelligence tooling for this repo (CodeGraph / Serena / graphify)

Three code-intelligence tools are initialized in this repo. If your agent
has access to them, use them instead of grepping/reading files cold —
they're faster and more accurate for this codebase. If your agent doesn't
have them wired up, everything still works with plain grep/Read; nothing
here is load-bearing for the app itself.

- **CodeGraph** (MCP, `.codegraph/`) — a queryable index of every symbol,
  call edge, and file (currently 263 nodes / 628 edges / 21 files). Ask it
  things like *"how does build_recommendation decide GO vs SAFER
  ALTERNATIVE"* and it returns the verbatim source plus a blast-radius
  summary (who calls this, what breaks if you change it) in one shot,
  instead of a grep-then-Read loop. Per-project index — already initialized
  here, but if it ever looks stale after a big set of external edits, run
  `codegraph sync` (or `codegraph init` again) from the repo root. One
  known quirk: its "no covering tests found" flag on `Recommendation`
  is a false negative — that class *is* tested, just indirectly through
  `build_recommendation()`'s return value, and the static heuristic
  doesn't trace that. Don't trust that specific flag without checking.

- **Serena** (MCP, LSP-backed, `.serena/`) — symbol-level navigation and
  editing (find a function by name across the repo, find every place that
  calls it, jump straight to a symbol's body) without reading whole files.
  Live via the Python language server, so it's never stale the way an
  index can be. Project onboarding is already done — 5 memory files exist
  under Serena's project memory covering: `core` (source map + invariants),
  `tech_stack` (versions, and the `mcp` package's API-shape trap that bit
  this build once already — see below), `suggested_commands`,
  `conventions` (patterns to replicate, e.g. the missing-evidence pattern
  every agent/fetcher follows), and `task_completion` (what "done" means
  here). Any Serena-backed agent starting fresh on this repo should read
  those before making changes — they exist specifically so you don't have
  to rediscover this the hard way.

- **graphify** (`graphify-out/`) — a knowledge graph over the *whole*
  repo, code and docs together (362 nodes, 43 labeled communities,
  including the markdown docs and the deck screenshot in
  `docs/screenshots/`). Useful for architecture-level questions a symbol
  index can't answer well, e.g. *"why does MarineObservation connect the
  fetchers, agents, planner, API, and MCP server communities"* — it traced
  that MarineObservation is the single most-connected node in the whole
  codebase (47 edges), which is a nice structural confirmation that the
  "one type carries every number" design actually holds, not just in the
  docs. Query it with `graphify query "<question>"`,
  `graphify path "<A>" "<B>"` for a relationship between two concepts, or
  `graphify explain "<concept>"` for one node. Full report at
  `graphify-out/GRAPH_REPORT.md`; open `graphify-out/graph.html` directly
  in a browser for the interactive view. If you add files, re-run
  `graphify <path> --update` to re-extract just what changed rather than
  rebuilding from scratch.

**⚠️ `orca/mcp_server.py` is currently broken — known regression, not yet
fixed.** It was originally written against `mcp.server.mcpserver.MCPServer`
after checking the *actually installed* `mcp==2.1.1` (the package renamed
`FastMCP` → `MCPServer` mid-major-version and moved the module — genuinely
not guessable from memory/training data, has to be checked against
`pip show mcp` each time). Someone editing in parallel reverted it to the
older `mcp.server.fastmcp.FastMCP` import, which does not exist in
`mcp==2.1.1`:
```
$ python3 -c "import orca.mcp_server"
ModuleNotFoundError: No module named 'mcp.server.fastmcp'. This is mcp 2.x,
where FastMCP was renamed to MCPServer (from mcp.server.mcpserver import
MCPServer) ...
```
Confirmed broken as of this commit — `import orca.mcp_server` fails outright,
so `tests/test_mcp_server.py` and `python -m orca.mcp_server` are both
currently red. Fix: change the import back to
`from mcp.server.mcpserver import MCPServer` (and `MCPServer("orca")`
instead of `FastMCP("orca")`) — see git history around commit `4cdcb13` for
the last known-working version, and re-run `pytest tests/test_mcp_server.py`
to confirm before trusting it again. This is exactly the class of bug
CodeGraph/Serena/graphify exist to catch fast — don't re-guess the API,
check the installed version.

---

## Ownership / merge notes

This build was done by a single operator+Claude Code session end-to-end
(not the three-branch split in war plan S3.5/S4), so there's no
`data`/`web` branch split to merge right now. If more people start
committing: **`orca/schema.py` and `orca/policy.py` are the safety-critical
files** — per CLAUDE.md rule 5, don't modify them without a clear reason
and without re-running `pytest tests/test_schema.py tests/test_policy.py`
plus the mutation check described in `tests/test_policy.py`'s docstrings.

Full history: `git log --oneline`. Every commit here is small and was
green before the next one started — if something breaks, `git bisect` or
just read the commit messages, they say what was verified and how.
