# ORCA — Team Status

**For:** other ICARUS teammates and their agents/assistants working on this repo.
**Last updated:** 2026-08-27, chatbot question types + conversation memory (orca/memory.py).
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
style — see "Real zones" below. `geofence_agent` now also checks real
distance to the actual India-Sri Lanka maritime boundary (IMBL), and the
UI ships a real WMO Douglas sea scale ruler plus a three-palette
(Day/Dusk/Night) design system inspired by IHO S-52 chart convention —
see "IMBL geofence + design system" below. The chatbot's query box is no
longer substring-matching theater: `orca/agentic.py` adds a real,
fail-closed agentic layer (Groq, free tier) that resolves free-text
queries onto real zones and phrases answers in the query's own language
(including real Tamil) — see "Agentic chatbot layer" below. Backend suite
is **207 pytest tests, all green** (excluding `orca/mcp_server.py`, still
broken — see below, unchanged; +1 more, `test_answer_question_live_end_to_end`,
when `GROQ_API_KEY` is set); e2e is **39 Playwright tests, all green**,
including a dedicated exceptional-cases sweep (`e2e/live.spec.js`'s
"exceptional / error paths" block + `e2e/agentic-exceptions.spec.js`) —
404/422 on the real endpoints, a real dead-port backend, a real 503, an
empty-query no-op, and a *real* invalid Groq key hit live against a
disposable second backend instance, not mocked.

The chatbot now answers four question *kinds*, not just one — see
"Chatbot question types + memory" below.

Two teammate-sourced research documents (`ORCA_AUTHENTICITY_UPGRADE.md`-
style data/feature plan, and a design-system spec) proposed a large body
of further work — real Indian data sources (INCOIS ERDDAP, IMD, MOSDAC),
an LLM planning/agentic layer, multi-turn conversation, vessel-class
thresholds, a field-condition mobile redesign, and more. **Only the
tractable, zero-new-architecture-risk slice of both is done** (see
below); the rest is a prioritized backlog, not started — see "What's
proposed but not built" near the end of this file. The LLM/agentic layer
specifically needs your explicit go-ahead before anyone builds it: it's a
new paid dependency and it's the first thing that would put a live
network call in `orca/api.py`'s request path, which every test in this
repo currently assumes never happens.

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

## IMBL geofence + design system (real Indian maritime data, real chart convention)

**Real India-Sri Lanka maritime boundary (IMBL) geofencing.** New
`MarineRegionsIMBLFetcher` in `data/fetch.py` pulls the real 4-segment
India-Sri Lanka treaty boundary line from Marine Regions (Flanders Marine
Institute / IOC-UNESCO — the standard worldwide reference for this),
cached to `data/cache/imbl/` (its own subdirectory, same
not-swept-by-`load_cached_observations()` reasoning as bathymetry).
`geofence_agent` in `orca/agents.py` now computes real distance from the
queried point to the nearest point on that boundary and escalates:
≤10km advisory (risk 0.3), ≤5km warning (risk 0.6), ≤2km urgent — hard
deny. **This is proximity-based, not a side-of-line crossing test** —
robustly determining which side of a multi-segment treaty line a point
falls on is a harder problem than this prototype takes on, and getting it
wrong could wrongly clear a boat that's actually crossed. Being this
close to an international boundary is, on its own, a legitimate reason to
stop — a documented, honest simplification, not a claim of exact crossing
detection. None of the 10 real `ZONES` themselves are anywhere near the
IMBL (closest is Rameswaram at ~23km) — this only engages for a
custom-coordinate query near the actual strait, which the UI already
supports (free-text lat/lon fields, not locked to the 10 named zones).

**Douglas sea scale.** `WAVE_HARD_DENY_M = 2.5` in `orca/agents.py` isn't
an arbitrary cutoff — it's the real WMO/Douglas scale boundary between
degree 4 "Moderate" and degree 5 "Rough" (the same vocabulary IMD's own
Coastal Bulletin uses). `web/index.html` now renders a real Douglas ruler
(0-6, correct WMO band boundaries) with a marker on the latest
`wave_height_m` evidence and a visible line at the real 2.5m deny
threshold — see `#douglas-ruler`. **Caught a real bug before shipping
this:** the first draft had the bands off by one (2.5m landing between
"Slight"/"Moderate" instead of the real "Moderate"/"Rough" — exactly the
detail that makes this feature meaningful) — caught by checking a known
input's rendered band name against the WMO table, not by trusting the
first draft. If you touch `DOUGLAS_SCALE` in `web/index.html`, re-check
it the same way.

**Three-palette design system (Day/Dusk/Night).** IHO S-52 requires
certified marine chart displays to carry three calibrated colour tables
— Day, Dusk, and Night — because bright UI light at night destroys a
watchkeeper's dark adaptation. `web/index.html` ships the same three,
switchable via visible header buttons (not buried in settings),
persisted to `localStorage`, applied via a `data-palette` attribute set
*before* first paint (a tiny inline `<script>` at the very top of
`<head>`, to avoid a flash of the wrong palette). Colours are drawn from
real navigation-light/chart convention, not invented: starboard green for
GO, port red for DO NOT GO, chart magenta for SAFER ALTERNATIVE and
geofence warnings. The existing CSS custom property *names*
(`--accent`, `--danger`, `--amber-border`, etc.) were kept rather than
mass-renamed to the newer semantic names a design document proposed
(`--go`/`--stop`/`--caution`) — same visual/conceptual result, far lower
risk than a mechanical rename across every rule in the stylesheet.

**Typography.** Archivo (requested with `wdth` axis, NOT a separate
"Archivo Condensed" family — verify this before touching the Google
Fonts URL, see SCRATCH.md) for instrument-style labels; Public Sans for
body text; IBM Plex Mono for anything that's transmissible safety-message
data (coordinates, evidence values) — nothing else uses monospace, that's
a deliberate rule, not a leftover default; Hind Madurai / Noto Sans Tamil
UI for Tamil text (`[lang="ta"]`, line-height 1.8). All 4 families
verified as real, resolvable Google Fonts families before use.

**Verified, not built:** INCOIS's own ERDDAP (the single best real-data
upgrade available) and IMD's marine/cyclone/lightning API are both
blocked by real, specific infrastructure issues, not skipped casually —
see MANUAL_TASKS.md items 10-11 and SCRATCH.md for exactly what was
checked. Marine Regions and INCOIS's PFZ text advisory are confirmed
live and auth-free but only the IMBL boundary has been wired into the app
so far — ingesting the PFZ advisory itself as a cross-check source is
real, valuable, unstarted work.

---

## Agentic chatbot layer (orca/agentic.py)

Direct feedback: the chatbot was "MOCK BASED ON THE LOCATION WE TOUCH" —
`resolve_zone_from_query()` was pure substring matching on the 10 zone
names, and clicking a map marker just wrote the zone's own name into the
query box, so of course it "worked." See SCRATCH.md's "chatbot layer
research" entry (2026-08-27) for the research this was built from —
Anthropic's own "Building effective agents" guidance, and current
safety-critical-agent literature converging on the same shape.

**What it is:** a fixed-code-path *workflow* (not an open-ended
autonomous agent — deliberately, per the research above and this
project's "boring beats clever" rule), calling the Groq API
(`openai/gpt-oss-20b` for zone extraction, `openai/gpt-oss-120b` for
composition — both strict-JSON-schema-capable, verified against Groq's
docs before use). Three things it can never do, by construction, not
convention:
- It never imports `orca/policy.py` (CLAUDE.md rule 4) — it only ever
  sees an already-decided `Recommendation`.
- It can only pick a zone from the real, fixed `ZONES` enum — never an
  invented place (`extract_query_intent`'s strict schema, plus a
  server-side re-check in case strict mode itself ever fails).
- It can only cite `evidence[].id`s that are actually in the real
  evidence list — any id the model names that isn't real is silently
  dropped (citation hallucination is a documented failure mode even
  under schema constraints; see the arxiv citation in SCRATCH.md).

**Zero-risk-first design:** a cheap, deterministic substring match
against the real zone list always runs first and always wins when it
finds something — the LLM is only consulted for what substring matching
genuinely cannot do (a landmark description, a query not in English).

**Fails closed, always:** `GROQ_API_KEY` unset, or any failure at any
stage (timeout, network error, malformed response) → `/ask` reproduces
today's fully offline, deterministic output byte-for-byte. This is now
CLAUDE.md rule 8's documented exception (see that file) — `orca/agentic.py`
is the second and only other file allowed to touch the network, and the
guarantee above is what makes that safe. Proven, not just claimed: every
failure mode (unconfigured, `ConnectionError`, `Timeout`, malformed JSON,
non-200 status, a hallucinated zone name, a hallucinated citation) has
its own test in `tests/test_agentic.py`.

**Verified live**, not just mocked — real Groq calls, real answers:
- English, zone named exactly: substring match resolves it, zero LLM
  calls for zone resolution, composed phrasing: *"Yes, you can head out
  to Kanyakumari; the wave height is 1.4 m, which is fine for fishing."*
- English, **no zone name anywhere in the query** ("the southernmost tip
  of India") — plain substring matching could never resolve this; the
  LLM correctly identified Kanyakumari, constrained to the real zone
  list. Covered by a live Playwright test
  (`e2e/live.spec.js`, skips itself without a real key).
- **Real Tamil query** (`நாகப்பட்டினத்தில் இருந்து மீன்பிடிக்க போகலாமா?`),
  wave height rigged above the 2.5 m hard-deny line: language correctly
  detected as `ta`, decision correctly DO NOT GO, and the composed Tamil
  answer states the exact real numbers (3.1 m / 2.5 m), not a
  paraphrase — grounding held under a full script switch, not just
  English rephrasing.

**Security note, fixed during this build:** a Groq key was briefly
pasted as a comment into `web/three-viz.js` (frontend JS — served to the
browser as-is, no build step, fully visible via view-source). Removed
before it was ever committed (`git log` confirms); moved to a git-ignored
`.env` (see `.env.example`), which only the backend process reads via
`os.environ`. A key must never live in `web/*.js`.

**New response fields** (additive — see `API_CONTRACT.md`):
`agentic_used`, `detected_language`, `cited_evidence_ids`. Frontend shows
a small "AI-enhanced" badge (`#agentic-badge`) only when `agentic_used`
is genuinely true, and sets `answer-text`'s `lang` attribute from the
real detected language (reuses the existing `[lang="ta"]` Tamil font
rule, not a separate style).

**Setup (optional):** `cp .env.example .env`, fill in a free key from
console.groq.com/keys, `source .env`. See README's "2b" step.

**Exceptional cases, swept headless (2026-08-27):** every `raise
HTTPException`/frontend `catch` in the codebase was grepped, then tested
headless (Playwright) where it added real value beyond the existing
pytest coverage:
- `GET /evidence/{id}` for a real nonexistent id → 404, not a silent 200.
- `POST /ask` with a missing field / wrong field type → 422, not a 500
  or a fabricated answer.
- Frontend against a real dead port (connection refused, not simulated)
  → "ERROR" text, ask button re-enables, zero uncaught page errors.
- Frontend against a real 503 (`page.route`-intercepted, same technique
  the existing wifi-off test already uses) → same graceful handling.
- Empty query / cleared coordinates → verified as a true no-op (zero
  `/ask` requests fire), not a broken or hanging request.
- **The one live gap that mattered most:** a genuinely invalid/revoked
  `GROQ_API_KEY` hit for real. `e2e/agentic-exceptions.spec.js` spins up
  a disposable second backend on its own port with a deliberately bad
  key, and proves live (real 401 from the real Groq API) that both an
  LLM-needing query (falls back to nearest-zone, badge honestly hidden)
  and a substring-matching query (completely unaffected, zero-risk path
  never touches the network) still answer correctly — this is the
  single most likely real failure on demo day (key typo, revoked,
  rate-limited), now actually proven, not just argued for.

**Deliberately not tested headless:** "cache directory missing" for
`/ask` (503, zero observations) and `/bathymetry` (503) — both already
covered safely with `tmp_path` isolation at the pytest level
(`test_bathymetry_missing_cache_returns_503_not_empty_200`,
`test_build_recommendation_raises_on_zero_observations_everywhere`).
Headless E2E has no safe way to simulate "the real cache is absent"
without deleting/moving the actual `data/cache/` this repo ships with —
not worth the risk for coverage that already exists at the right layer.

---

## Chatbot question types + memory (orca/memory.py)

The agentic layer above answered exactly one intent, however phrased:
"is it safe at X". Four honest limits were identified and closed:

**1. Bare-number questions.** `answer_kind: "data_lookup"` — "what's the
wave height at Chennai?" now leads with the real reading (value, unit,
and an `id` that resolves through `/evidence/{id}` like every other
number) instead of only a verdict. **The safety floor:** a narrow
question can never bury a `DO NOT GO` — the composer is explicitly
instructed to state the danger regardless. The mirror-image bug is also
covered: a `data_lookup` that is *also* a DO NOT GO with no named zone
used to drop the number entirely (both `chosen_zone` and `resolved_zone`
are None there); it now falls back to `zone_summaries[0]`, the primary
zone actually evaluated.

**2. "What about tomorrow?"** Turned out to need no new data source at
all — `data/fetch.py` was already requesting `forecast_days=2` and
discarding 47 of 48 hourly points. `fetch_tomorrow()` reads one day ahead
into `data/cache/forecast/` (own subdirectory, so
`load_cached_observations()` cannot sweep it into the safety-critical
set). A "tomorrow" verdict runs the **identical** deterministic policy on
those observations — a forecast verdict is a real verdict — and they
carry an honestly lower `confidence` (0.75 vs 0.9), because a day-ahead
forecast genuinely is less certain. If the forecast cache is empty, the
answer says so rather than passing today's conditions off as tomorrow's.

**3. Out-of-coverage places.** `zone_match` now records how the answered
zone was reached — `exact` / `inferred` / `remembered` / `fallback`. On
`fallback` (nothing matched; nearest-by-coordinates) the response carries
a `coverage_note` and the UI renders it: *"You didn't name a place ORCA
covers, so this is for Nagapattinam, the nearest…"* — instead of silently
answering about somewhere else, which was the actual dishonesty.

**4. Off-topic questions.** `answer_kind: "off_topic"` declines politely
and the UI suppresses the GO/DO NOT GO badge, evidence panel and Douglas
ruler — none of which were asked about. Defaults to *on*-topic when the
signal is malformed: wrongly refusing a real fisherman's real question is
the worse failure (the abstention literature's over-abstention problem).

### The memory layer — and why it cannot hallucinate mid-conversation

`orca/memory.py` exists as its own module because of one rule:
**nothing the user typed is ever stored or replayed.** A turn is reduced
on ingest to `{zone_name, variable, time_frame}`, each re-validated
against the real closed sets (`ZONES`, the real observation variables,
`now`/`tomorrow`), capped at 3, and frozen. That makes the two documented
failure modes of multi-turn chat structurally impossible rather than
merely unlikely:

- **Hallucination compounding.** The naive pattern — concatenate the raw
  transcript into every later prompt — is what makes models drift,
  contradict themselves and reinforce their own earlier mistakes. Here a
  bad turn can leave behind only a zone that really exists and a variable
  that really exists, so there is no wrong *text* to carry forward, and
  every answer is re-derived from live cached observations.
- **Prompt injection through history.** An injected instruction would
  have to survive being reduced to an enum value, and fails validation
  instead. This is architectural prevention — the literature is clear
  that delimiters and role markers do not hold.

The other half of the guarantee is in `orca/agentic.py`: **history reaches
only the extraction step, never composition.** The composer sees one
thing — the decision just computed from real cached data — so it cannot
repeat or compound an earlier answer, because it has never seen one. Both
halves are asserted against the real outbound payloads in tests, not just
documented. The browser mirrors the same shape (`web/index.html`'s
`rememberTurn()`), and the server re-validates regardless: the client is
a convenience, not a trust boundary.

**Malformed history never fails a request.** `AskRequest.history` is typed
`Any`, not `list | None`, deliberately — a narrower annotation had
Pydantic reject a non-list with 422 *before* `sanitize()` ran, which
contradicted the documented guarantee. Caught by an e2e test (the
Python-level tests call `answer_question()` directly and bypass the HTTP
boundary entirely). A fisherman's safety answer must not be lost to a
malformed optional field.

### Hardcode audit (done alongside)

- Composition was sending the **entire** recommendation dict to the model
  — ~3,200 tokens — which exhausted Groq's free 8,000 TPM budget after
  about two questions and made composition silently fall back to template
  text. `_composition_context()` sends only what can actually appear in
  an answer: **~470 tokens, an 85% cut.** Also a grounding win.
- `("en","ta","other")` and `("verdict","data_lookup")` were each written
  twice (JSON schema + re-validation) — hoisted to `LANGUAGES`/`INTENTS`,
  matching what `memory.py` already did for its own enums.
- Three constants necessarily exist in both Python and JS (ZONES,
  `WAVE_HARD_DENY_M`, the history cap) because `web/` has no build step.
  `tests/test_frontend_constants.py` parses the real values out of
  `web/index.html` and asserts they match — drift fails at CI time
  instead of misleading someone on stage. Chosen over a `/zones` endpoint
  because the page must keep rendering with the API down.

---

## What's proposed but not built (two teammate research docs)

Two large research documents were dropped into this session: a
data/feature authenticity plan and a design-system spec. Both are
genuinely good — real endpoints, real domain research, not filler — and
both propose far more than one pass can build. What's real above is the
tractable slice; everything below is a prioritized backlog, not started:

- ~~LLM/agentic planning layer~~ — **built**, see "Agentic chatbot layer"
  above. What's still open from the original idea: this only covers
  zone resolution + phrasing (query → decision), not a multi-step
  *planning* agent proposing routes/timing for the policy engine to
  check — that's a materially bigger scope and still unbuilt.
- ~~Multi-turn conversation / session memory~~ — **built**, see "Chatbot
  question types + memory" above. Still open from the original idea:
  memory is a 3-turn structured cap with no persistence across page
  reloads, and there is no notion of a named user or a saved session.
- Vessel-class-dependent hazard thresholds (the design doc calls the
  current single global `WAVE_HARD_DENY_M` "a correctness bug, not a
  feature request" — a fair point, real future work, not attempted here).
- Route planning / return-window advisory, multi-source
  cross-validation/divergence reporting, historical cyclone back-test.
- Field-condition mobile redesign (giant verdict card, press-and-hold
  voice input, confidence-decay bar using the already-real
  `confidence`/`freshness_min` fields), a real landing page, shore mode,
  nearest-safe-landing-centre routing, departure ticket, catch log, peer
  proximity, haptic feedback.
- INCOIS PFZ advisory ingestion as an official cross-check source (data
  confirmed reachable, not yet wired in — see above).

None of this is silently dropped — it's listed here specifically so the
next person (human or agent) doesn't have to re-derive the plan from
scratch or wonder whether it was rejected.

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




