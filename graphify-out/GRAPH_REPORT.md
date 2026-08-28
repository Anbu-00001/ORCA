# Graph Report - ORCA  (2026-08-28)

## Corpus Check
- 76 files · ~189,684 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 949 nodes · 1805 edges · 89 communities (53 shown, 36 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 68 edges (avg confidence: 0.76)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `df75f865`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- fetch.py
- generate_demo_scenarios.py
- test_agents.py
- test_planner.py
- test_policy.py
- test_mcp_server.py
- test_memory.py
- Prior Art Table (S12)
- MarineObservation
- ThreeVizBase
- test_api.py
- package.json
- MOSDAC / INCOIS Registration
- live.spec.js
- mock.spec.js
- Driving Claude Code — Core Discipline
- Three Things That Make ORCA Not-Another-App
- playwright.config.js
- Screen Recording + Screenshots Fallback
- copernicusmarine Library
- Deck — Remaining Fixes (S14)
- DGLL Lighthouses
- Failure Modes and Fallbacks (S13)
- sparkgeo/geo-mcp-servers Registry
- NIOT OMNI Buoys
- NOAA Marine MCP Server
- open-meteo-mcp Server
- OpenDrift
- Open-Meteo Marine API
- PydanticAI at LLM Boundary
- qgis-mcp Server
- Role: Presenter
- Role: QA / Runner
- Scope: STRETCH List (S7)
- Tech Stack Verdicts (Jac, Mojo, Zig, JAX)
- Executive Summary
- httpx==0.28.1
- pydantic==2.13.4
- requests==2.34.2
- uvicorn[standard]==0.52.4
- Memory Maintenance
- ORCA — core
- ORCA — suggested commands
- ORCA — tech stack
- conventions.md
- task_completion.md
- test_agentic.py
- agentic-exceptions.spec.js
- datetime
- web/index.html
- Repository Structure
- test_fetch.py
- MarineRegionsIMBLFetcher
- build_docx.py
- api.py
- load_cached_observations
- test_frontend_constants.py
- PART 2 — ROADMAP
- test_real_bathymetry_fetch_integration
- mcp_server.py
- SimulationR — research and verdicts on the ORCA simulation layer
- answer_question
- _obs
- compose_grounded_answer
- _capture_composition
- three-viz-app.js
- _FakeClock
- three-viz.js
- DEV A — frontend
- OceanDiorama
- DEV B — handoff · agentic layer
- FreeFlyController
- DEV B — agentic layer
- DEV D — critical path, sole merger
- schema.py
- DEV C — test suite & gates
- agentic.py
- ._buildTerrainMesh
- ORCA — pre-demo work split
- Your tasks
- verdict-qualifiers.spec.js
- test_tomorrow_is_the_same_clock_hour_not_midnight
- 4. FOR DEV D — the `CANNOT ASSESS` contract
- conftest.py
- test_answer_question_live_end_to_end

## God Nodes (most connected - your core abstractions)
1. `MarineObservation` - 60 edges
2. `answer_question()` - 50 edges
3. `build_recommendation()` - 42 edges
4. `_obs()` - 31 edges
5. `_obs()` - 26 edges
6. `OceanDiorama` - 25 edges
7. `sanitize()` - 24 edges
8. `_intent()` - 23 edges
9. `_clean_go_observations()` - 23 edges
10. `OpenMeteoMarineFetcher` - 22 edges

## Surprising Connections (you probably didn't know these)
- `ORCA Marine Advisory Dashboard Screenshot` --conceptually_related_to--> `MarineObservation`  [AMBIGUOUS]
  docs/screenshots/orca_live_demo.png → orca/schema.py
- `OpenMeteoMarineFetcher` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py
- `OpenMeteoForecastFetcher` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py
- `ERDDAPChlorophyllFetcher` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py
- `ERDDAPBathymetryFetcher` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Deterministic, LLM-Free Safety Override Pattern** — claude_policy_no_llm, orca_policy, readme_deterministic_safety_policy, orca_32_hour_war_plan_v3_safety_policy_code, team_status_no_llm_in_reasoning_path [INFERRED 0.85]
- **MarineObservation Provenance Traceability Pattern** — claude_marineobservation_requirement, orca_schema, api_contract_post_ask, api_contract_get_evidence, readme_data_traceability_schema, web_index_renderevidence [INFERRED 0.85]
- **Offline-First Cache Design Pattern** — claude_offline_only, orca_32_hour_war_plan_v3_offline_mode, team_status_offline_display_only, orca_api, web_index_refreshhealth [INFERRED 0.85]

## Communities (89 total, 36 thin omitted)

### Community 0 - "fetch.py"
Cohesion: 0.13
Nodes (20): main(), Path, Real marine data fetchers for the Nagapattinam/Chennai coast, Bay of Bengal…, Wind's equivalent of OpenMeteoMarineFetcher.fetch_tomorrow() -- same reasoning,…, Writes to its own subdirectory (default data/cache/bathymetry/), never…, Writes to its own subdirectory (data/cache/imbl/), for the same reason…, _slug(), write_bathymetry_cache() (+12 more)

### Community 1 - "generate_demo_scenarios.py"
Cohesion: 0.20
Nodes (13): demo/scenarios.json, Backup the Deck, Refresh Data Cache Before Demo, 5-Minute Demo Script (S9), Final Checklist T-2 (S15), Safety Policy resolve() Function (S8.4), Scenario Example: Safety Override in Action, Wave-Height Flip End-to-End Verification (+5 more)

### Community 2 - "test_agents.py"
Cohesion: 0.08
Nodes (52): _distance_to_imbl_km(), eo_satellite_agent(), _find(), geofence_agent(), _haversine_km(), hazard_agent(), _load_imbl_segments(), ocean_state_agent() (+44 more)

### Community 3 - "test_planner.py"
Cohesion: 0.06
Nodes (75): Find the one real observation a data_lookup question asked for. Returns None…, _resolve_lookup(), build_recommendation(), _collect_evidence(), observation_id(), observations_for_zone(), query -> agents over cached evidence -> policy.resolve() -> structured answer.…, The zero-risk, zero-network first-pass zone match: does a known zone's name… (+67 more)

### Community 4 - "test_policy.py"
Cohesion: 0.07
Nodes (49): Boring, Readable Code Rule, Definition of Done, Freeze schema.py and policy.py Rule, No New Dependencies Rule, No Swallowed Exceptions Rule, No Synthetic Data Rule, No Network Access at Demo Rule, policy.py No-LLM Guarantee (+41 more)

### Community 5 - "test_mcp_server.py"
Cohesion: 0.13
Nodes (14): ask_marine_advisory(), _ask_marine_advisory_tool(), get_evidence(), _get_evidence_tool(), Get a fishing-safety recommendation for a location on the Nagapattinam/Chennai…, Look up a single marine observation by id, with full provenance., Protocol adapter for :func:`ask_marine_advisory`. MCPServer 2.x dispatches…, Protocol adapter for :func:`get_evidence`; reads committed cache only. (+6 more)

### Community 6 - "test_memory.py"
Cohesion: 0.13
Nodes (33): ConversationTurn, last_zone(), Conversation memory for ORCA's chatbot layer. The single design rule here, and…, The most recent real zone the conversation was about, if any. Used to resolve a…, The exact, minimal structure handed to the extraction model. Plain dicts of…, One prior turn, reduced to validated facts. Frozen: once sanitized, a turn…, Reduce whatever the client sent to at most MAX_TURNS validated turns. Anything…, sanitize() (+25 more)

### Community 7 - "Prior Art Table (S12)"
Cohesion: 0.11
Nodes (21): Rehearsal Task, Sleep Task, Venue Logistics, DGLL NAVTEX, Fisher Friend (FFMA), GEMINI / DAT-SG, Hour-by-Hour Schedule (S10), INCOIS SAMUDRA (+13 more)

### Community 8 - "MarineObservation"
Cohesion: 0.24
Nodes (15): ORCA Marine Advisory Dashboard Screenshot, MarineObservation, pytest==9.1.1, _base_kwargs(), parametrize, Tests for orca/schema.py — written before the implementation. MarineObservation…, test_confidence_boundary_values_are_allowed(), test_confidence_out_of_range_raises() (+7 more)

### Community 9 - "ThreeVizBase"
Cohesion: 0.24
Nodes (4): attachInteraction(), ensurePositioned(), riskColor(), ThreeVizBase

### Community 10 - "test_api.py"
Cohesion: 0.10
Nodes (10): parametrize, Tests for orca/api.py — written before the implementation. These run against…, The actual §8.6 guarantee: /ask and /evidence read only from data/cache/ and…, Sanity: the endpoint is actually consulting real per-zone data, not returning…, An absent bathymetry cache is an honest 503, never a fabricated or silently-…, orca/memory.py promises a malformed or hostile history degrades to "no memory",…, test_ask_and_evidence_are_unaffected_by_connectivity(), test_ask_different_zones_can_produce_different_actions() (+2 more)

### Community 11 - "package.json"
Cohesion: 0.20
Nodes (9): description, devDependencies, @playwright/test, name, private, scripts, test:e2e, version (+1 more)

### Community 12 - "MOSDAC / INCOIS Registration"
Cohesion: 0.50
Nodes (4): MOSDAC / INCOIS Registration, Data Source Decision (S8.2), Scope: DO NOT BUILD List (S7), What's NOT Built

### Community 43 - "Memory Maintenance"
Cohesion: 0.33
Nodes (5): Add/update threshold, Discovery Model, Maintenance Actions, Memory Maintenance, Style

### Community 44 - "ORCA — core"
Cohesion: 0.40
Nodes (4): Invariants, More, ORCA — core, Source map

### Community 49 - "test_agentic.py"
Cohesion: 0.13
Nodes (24): extract_query_intent(), is_configured(), Turn one free-text question into validated, structured facts, under a strict…, _FakeResponse, _groq_response(), Tests for orca/agentic.py — written before most of the implementation existed,…, Mirrors tests/test_fetch.py's mocking style: a stand-in for requests.Response…, A Groq chat-completions response shaped exactly like the real API (verified… (+16 more)

### Community 51 - "datetime"
Cohesion: 0.12
Nodes (16): OpenMeteoForecastFetcher, OpenMeteoMarineFetcher, Read Open-Meteo's `current` block -- the model's own nowcast for this instant,…, Same live forecast fetch() already makes (forecast_days=2 asks for ~48 hourly…, Wind speed/gusts and precipitation, per point, near-term forecast., Wind/rain equivalent of OpenMeteoMarineFetcher._parse_point() -- same `current`…, Wave height/period/direction and SST, per point, as of now., datetime (+8 more)

### Community 52 - "web/index.html"
Cohesion: 0.20
Nodes (12): GET /health Endpoint, POST /ask Endpoint, Record Real Tamil Audio Sample, Frontend + Tamil Decision (S8.5), Role: Operator 3 — Frontend, Prompt 4 — Frontend, refreshHealth(), renderEvidence() (+4 more)

### Community 53 - "Repository Structure"
Cohesion: 0.31
Nodes (8): ORCA Tech Stack, Test on the Actual Presentation Laptop, Repo Layout (S8.1), Scope: BUILD List (S7), Repository Structure, fastapi==0.141.1, ORCA Build Log, Team Status TL;DR

### Community 54 - "test_fetch.py"
Cohesion: 0.12
Nodes (16): _box_around(), ERDDAPBathymetryFetcher, ERDDAPChlorophyllFetcher, NOAA CoastWatch ERDDAP VIIRS daily chlorophyll-a, per point. Satellite ocean…, Return (most_recent_valid_date, mean_value, n_pixels) or None if every pixel in…, NOAA NCEI ETOPO 2022 (60 arc-second) global relief -- real seafloor elevation…, date, Tests for data/fetch.py — written before the implementation. CLAUDE.md rule 1… (+8 more)

### Community 55 - "MarineRegionsIMBLFetcher"
Cohesion: 0.22
Nodes (8): fetch_all(), MarineRegionsIMBLFetcher, The real India-Sri Lanka maritime boundary (IMBL), from Marine Regions…, Run every fetcher. Returns (results_by_source, errors_by_source). A source that…, If Open-Meteo Marine is down, ERDDAP/Forecast results still come back, and the…, test_fetch_all_continues_when_one_source_fails_and_never_fabricates(), test_imbl_fetcher_build_url_uses_cql_filter(), test_imbl_fetcher_parses_real_fixture_into_segments()

### Community 56 - "build_docx.py"
Cohesion: 0.44
Nodes (11): add_bullet(), add_callout(), add_code_block(), add_heading_1(), add_heading_2(), add_heading_3(), add_p(), build_document() (+3 more)

### Community 57 - "api.py"
Cohesion: 0.16
Nodes (15): BaseModel, Prompt 3 — Agents + API, ask(), AskRequest, bathymetry(), get_evidence(), health(), _is_reachable() (+7 more)

### Community 58 - "load_cached_observations"
Cohesion: 0.21
Nodes (12): load_cached_observations(), load_forecast_observations(), _load_observations_from(), Path, Tomorrow's forecast only -- orca/agentic.py's data_lookup path is the only…, The regression this guards: forecast/*.json must never be counted as part of…, test_forecast_cache_directory_is_invisible_to_load_cached_observations(), test_load_forecast_observations_reads_back_what_was_written() (+4 more)

### Community 59 - "test_frontend_constants.py"
Cohesion: 0.22
Nodes (9): html(), _js_number(), fixture, Drift guards for constants that necessarily exist twice. web/ is plain HTML/JS…, The no-network fallback map is hand-placed SVG; its labels must still name the…, The browser may send fewer turns than the server keeps, never more -- anything…, test_frontend_history_cap_does_not_exceed_the_backend_cap(), test_frontend_svg_fallback_covers_exactly_the_real_zones() (+1 more)

### Community 60 - "PART 2 — ROADMAP"
Cohesion: 0.04
Nodes (44): 0. Read this first, 10. Phase 1 — Harden (immediately post-selection), 10B.1 The P1 boundary, 10B.2 Environment state, 10B.3 Sandbox controls, 10B.4 Why this is worth building, 10B. Phase 1B — Environment sandbox, 11. Phase 2 — Real Indian sources (+36 more)

### Community 61 - "test_real_bathymetry_fetch_integration"
Cohesion: 0.29
Nodes (7): integration, requires_bathymetry_network, requires_marineregions_network, requires_network, test_real_bathymetry_fetch_integration(), test_real_imbl_fetch_integration(), test_real_openmeteo_marine_fetch_integration()

### Community 62 - "mcp_server.py"
Cohesion: 0.25
Nodes (7): Expose ORCA as an MCP Server (S6.2), weather-mcp Server, ORCA as an MCP server (war plan S6.2, stretch goal -- core was green first).…, Quick Start Guide, System Architecture Diagram, mcp==2.1.1, What's Built and Verified Table

### Community 63 - "SimulationR — research and verdicts on the ORCA simulation layer"
Cohesion: 0.07
Nodes (28): 0. The verdict, up front, 1. THE LIVE DEFECT — read this first, 2. THE SECOND DEFECT — R-37, confirmed and quantified, 3.1 The null-perturbation test, 3.2 Why it fails: this is a swell coast, not a wind-sea coast, 3.3 The error points the wrong way, 3.4 The structural argument, independent of any formula, 3.5 What to allow, encode, and refuse (+20 more)

### Community 64 - "answer_question"
Cohesion: 0.13
Nodes (22): answer_question(), _post(), The only function in this file that makes a network call. Returns the parsed…, The single entry point orca/api.py's /ask handler calls instead of…, Task 1 / R-45, asserted rather than eyeballed in a terminal: the extraction…, The zero-risk-first guarantee, stated as the property that actually matters.…, The safety floor: a narrow question must not be allowed to bury a DO NOT GO.…, Gap #3: nothing matched, so build_recommendation fell back to the nearest zone… (+14 more)

### Community 65 - "_obs"
Cohesion: 0.18
Nodes (22): _intent(), _obs(), A full, schema-shaped extraction response, overridable per test., Route extraction vs composition by which schema was requested., Asking for tomorrow's chlorophyll has no honest answer -- chlorophyll is a…, what about tomorrow?' -- no zone in the query, none the model can infer. The…, A DO NOT GO has no chosen_zone (there is nowhere to send them), and an unnamed…, A bare follow-up ("and what about tomorrow?") can be classified data_lookup… (+14 more)

### Community 66 - "compose_grounded_answer"
Cohesion: 0.16
Nodes (19): _blind_agent_readings(), compose_grounded_answer(), Which readings ORCA did not have, from the findings themselves. An agent that…, Rephrase an already-decided Recommendation for a human, in their language. The…, _capture_prompt(), The half of the answer that makes it useful rather than merely honest -- and…, If an agent cited observations it was not blind, whatever else is true. Re-…, The standing 'never claim ORCA lacks data' instruction is true only when there… (+11 more)

### Community 67 - "_capture_composition"
Cohesion: 0.16
Nodes (17): _capture_composition(), parametrize, Run answer_question and hand back both the result and the exact system prompt…, The regression that started all of this. ZONE_A is the calmest zone AND the…, risk_level is a policy output, not a MarineObservation. Handed the float, the…, An ordinary one-zone question must leave the model unable to rank anything --…, ORCA holds two days. Answering a day-after-tomorrow question for today is fine;…, Which zone has the worst waves?" deliberately names no zone. The fallback note… (+9 more)

### Community 68 - "three-viz-app.js"
Cohesion: 0.18
Nodes (10): DOUGLAS_BANDS, douglasName(), ensureOceanDiorama(), ensureReasoningGraph(), params, renderSandbox(), seedSandbox(), wireReasoningToggle() (+2 more)

### Community 69 - "_FakeClock"
Cohesion: 0.18
Nodes (12): _FakeClock, The budget must not quietly degrade the normal path -- when there is plenty…, Returns each queued tick in turn, then repeats the last one., Run the layer against a controlled clock, capturing every outbound call so we…, A slow extraction must not be followed by a fresh full-length composition wait…, R-45's discipline applies to this path too: falling back is correct, falling…, The half that makes the budget a bound rather than a suggestion. Checking the…, test_a_fast_extraction_still_gets_the_full_per_call_timeout() (+4 more)

### Community 70 - "three-viz.js"
Cohesion: 0.15
Nodes (11): ACTION_COLOR, AGENT_SHORT_NAMES, buildElevationGrid(), COLOR_HIGH, COLOR_LOW, COLOR_MID, deepWaterWavelengthM(), DEPTH_CONTOURS_M (+3 more)

### Community 71 - "DEV A — frontend"
Cohesion: 0.15
Nodes (13): 1. [P0 — gates the backend] Make the unknown verdict non-permissive, 2. [P1] R-55 — mark the mock render, 3. [P2] R-33 — reading age, and one false claim, 4. [P1] Widen the e2e action assertions, 5. [cut] Render `severity` and `blind_agents`, DEV A — frontend, Done when, Notes (+5 more)

### Community 73 - "DEV B — handoff · agentic layer"
Cohesion: 0.17
Nodes (12): 0. Thirty-second version, 1. What landed, 2. THE ONE THING STILL OWED, 3. Deviations from `DEV_B.md`, 5. FOR DEV A / DEV D — R-25 consumer sweep result, 6. INVARIANTS — do not break these, 7. Verify after merge, 8. Known sharp edges (+4 more)

### Community 75 - "DEV B — agentic layer"
Cohesion: 0.18
Nodes (11): 1. [P1 — one line] R-45 — the one silent fallback, 2. [P2] R-49 — one wall-clock budget for the whole layer, 3. [P2] Test the bound, 4. [P0 — Dev A and Dev D are waiting on this] The R-25 consumer sweep, DEV B — agentic layer, Do not, Done when, Run it (+3 more)

### Community 76 - "DEV D — critical path, sole merger"
Cohesion: 0.18
Nodes (11): DEV D — critical path, sole merger, Done when, Housekeeping, Kicking it off, Merging a branch, R-39 — no evidence at all resolves to GO, R-59 (new) — danger with no opportunity resolves to GO, Start here (+3 more)

### Community 77 - "schema.py"
Cohesion: 0.24
Nodes (9): GET /evidence/{id} Endpoint, MarineObservation Provenance Requirement, Role: Operator 2 — Data Layer, Prompt 1 — Schema + Policy, Role: YOU — Critical Path / Sole Merger, MarineObservation Schema Spec (S8.3), Three-Operator Discipline, The one type allowed to carry a number to a user. CLAUDE.md rule 3: every… (+1 more)

### Community 78 - "DEV C — test suite & gates"
Cohesion: 0.20
Nodes (10): DEV C — test suite & gates, Done when, [P1] G-1 — root cause found, [P1] G-4, G-7 and G-8 — run them, record real output, Ship it, Start here, Task 1 unblocks everyone's test run — do it first, Task 2 — the gates nobody has actually run (+2 more)

### Community 79 - "agentic.py"
Cohesion: 0.22
Nodes (8): Exception, AgenticUnavailable, _composition_context(), _rank_zones(), ORCA's chatbot layer: LLM-assisted zone resolution and localized, grounded…, Raised for any reason the agentic layer can't be used this request. Callers…, The minimal slice of a decision the composer actually needs to phrase an…, The true cross-zone ordering, computed in plain Python from the same cached…

### Community 81 - "ORCA — pre-demo work split"
Cohesion: 0.25
Nodes (8): 1. Why this sprint exists, 2. The merge order is not negotiable, 3. The cut line, 4. Deferred — and said out loud if asked, 5. One note on staffing, ORCA — pre-demo work split, R-39 — no evidence at all resolves to GO, R-59 (new) — danger with no opportunity resolves to GO

### Community 83 - "Your tasks"
Cohesion: 0.33
Nodes (6): 1. Freeze the contract and push — before anyone cuts a branch, 2. [P0] Both verdict guards — one commit, 3. [P0] R-60 — bound the alternative search, 4. [P1] R-54 — probe out of the request path, 5. [cut] R-38 severity · R-40 blind agents, Your tasks

### Community 85 - "test_tomorrow_is_the_same_clock_hour_not_midnight"
Cohesion: 0.33
Nodes (6): parametrize, A day-ahead forecast really is less certain than the current hour. Copying…, Tomorrow" meant 00:00 tomorrow, which is nobody's idea of tomorrow's fishing…, test_fetch_reads_the_current_nowcast_not_midnight(), test_tomorrow_is_the_same_clock_hour_not_midnight(), test_tomorrow_observations_carry_lower_confidence_than_now()

### Community 86 - "4. FOR DEV D — the `CANNOT ASSESS` contract"
Cohesion: 0.50
Nodes (4): 4.1 The action string, 4.2 `agent_findings[].observation_ids` must be present and honest, 4.3 What the answer will say, 4. FOR DEV D — the `CANNOT ASSESS` contract

### Community 87 - "conftest.py"
Cohesion: 0.50
Nodes (3): _no_live_groq_unless_marked(), fixture, Test-suite-wide environment control. orca/api.py loads a git-ignored .env at…

### Community 88 - "test_answer_question_live_end_to_end"
Cohesion: 0.67
Nodes (3): agentic, skipif, test_answer_question_live_end_to_end()

## Ambiguous Edges - Review These
- `MarineObservation` → `ORCA Marine Advisory Dashboard Screenshot`  [AMBIGUOUS]
  docs/screenshots/orca_live_demo.png · relation: conceptually_related_to

## Knowledge Gaps
- **189 isolated node(s):** `{ test, expect }`, `{ spawn }`, `{ test, expect }`, `{ test, expect }`, `{ test, expect }` (+184 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **36 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `MarineObservation` and `ORCA Marine Advisory Dashboard Screenshot`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `MarineObservation` connect `MarineObservation` to `fetch.py`, `_obs`, `test_agents.py`, `test_planner.py`, `test_policy.py`, `_FakeClock`, `_capture_composition`, `schema.py`, `test_agentic.py`, `datetime`, `test_fetch.py`, `MarineRegionsIMBLFetcher`, `load_cached_observations`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **Why does `answer_question()` connect `answer_question` to `_obs`, `compose_grounded_answer`, `test_planner.py`, `_capture_composition`, `_FakeClock`, `test_memory.py`, `agentic.py`, `test_agentic.py`, `test_answer_question_live_end_to_end`, `api.py`?**
  _High betweenness centrality (0.041) - this node is a cross-community bridge._
- **Why does `build_recommendation()` connect `test_planner.py` to `answer_question`, `test_agents.py`, `test_policy.py`, `test_mcp_server.py`, `MarineObservation`, `agentic.py`, `load_cached_observations`, `mcp_server.py`?**
  _High betweenness centrality (0.038) - this node is a cross-community bridge._
- **Are the 10 inferred relationships involving `MarineObservation` (e.g. with `ERDDAPBathymetryFetcher` and `ERDDAPChlorophyllFetcher`) actually correct?**
  _`MarineObservation` has 10 INFERRED edges - model-reasoned connections that need verification._
- **Are the 12 inferred relationships involving `datetime` (e.g. with `_obs()` and `_wave()`) actually correct?**
  _`datetime` has 12 INFERRED edges - model-reasoned connections that need verification._
- **What connects `{ test, expect }`, `{ spawn }`, `{ test, expect }` to the rest of the system?**
  _189 weakly-connected nodes found - possible documentation gaps or missing edges._