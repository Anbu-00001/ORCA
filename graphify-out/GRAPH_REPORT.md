# Graph Report - ORCA  (2026-08-27)

## Corpus Check
- 61 files · ~131,887 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 649 nodes · 1363 edges · 64 communities (32 shown, 32 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 58 edges (avg confidence: 0.76)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `bfd452b2`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- write_cache
- generate_demo_scenarios.py
- test_agents.py
- test_planner.py
- schema.py
- test_mcp_server.py
- test_memory.py
- Prior Art Table (S12)
- MarineObservation
- three-viz.js
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
- ERDDAPChlorophyllFetcher
- MarineRegionsIMBLFetcher
- build_docx.py
- api.py
- test_fetch.py
- test_frontend_constants.py
- ERDDAPBathymetryFetcher
- test_real_bathymetry_fetch_integration
- mcp_server.py
- fetch.py

## God Nodes (most connected - your core abstractions)
1. `MarineObservation` - 58 edges
2. `answer_question()` - 33 edges
3. `build_recommendation()` - 32 edges
4. `_obs()` - 26 edges
5. `sanitize()` - 24 edges
6. `OpenMeteoMarineFetcher` - 22 edges
7. `_obs()` - 22 edges
8. `resolve()` - 20 edges
9. `load_cached_observations()` - 19 edges
10. `_clean_go_observations()` - 19 edges

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

## Communities (64 total, 32 thin omitted)

### Community 0 - "write_cache"
Cohesion: 0.15
Nodes (15): Path, Writes to its own subdirectory (default data/cache/bathymetry/), never…, Writes to its own subdirectory (data/cache/imbl/), for the same reason…, _slug(), write_bathymetry_cache(), write_cache(), write_imbl_cache(), The regression this guards: forecast/*.json must never be counted as part of… (+7 more)

### Community 1 - "generate_demo_scenarios.py"
Cohesion: 0.19
Nodes (14): demo/scenarios.json, Backup the Deck, Refresh Data Cache Before Demo, 5-Minute Demo Script (S9), Final Checklist T-2 (S15), Safety Policy resolve() Function (S8.4), Scenario Example: Safety Override in Action, Wave-Height Flip End-to-End Verification (+6 more)

### Community 2 - "test_agents.py"
Cohesion: 0.08
Nodes (52): _distance_to_imbl_km(), eo_satellite_agent(), _find(), geofence_agent(), _haversine_km(), hazard_agent(), _load_imbl_segments(), ocean_state_agent() (+44 more)

### Community 3 - "test_planner.py"
Cohesion: 0.08
Nodes (57): Find the one real observation a data_lookup question asked for. Returns None…, _resolve_lookup(), get_evidence(), build_recommendation(), _collect_evidence(), load_cached_observations(), _load_observations_from(), observation_id() (+49 more)

### Community 4 - "schema.py"
Cohesion: 0.06
Nodes (58): GET /evidence/{id} Endpoint, Boring, Readable Code Rule, Definition of Done, Freeze schema.py and policy.py Rule, MarineObservation Provenance Requirement, No New Dependencies Rule, No Swallowed Exceptions Rule, No Synthetic Data Rule (+50 more)

### Community 5 - "test_mcp_server.py"
Cohesion: 0.25
Nodes (8): ask_marine_advisory(), get_evidence(), Get a fishing-safety recommendation for a location on the Nagapattinam/Chennai…, Look up a single marine observation by id, with full provenance., Tests for orca/mcp_server.py (war plan S6.2 stretch goal). Two layers: the…, test_ask_marine_advisory_returns_contract_shaped_dict(), test_get_evidence_resolves_a_real_id_from_ask(), test_get_evidence_unknown_id_returns_error_not_exception()

### Community 6 - "test_memory.py"
Cohesion: 0.12
Nodes (35): ConversationTurn, last_zone(), Conversation memory for ORCA's chatbot layer. The single design rule here, and…, The most recent real zone the conversation was about, if any. Used to resolve a…, The exact, minimal structure handed to the extraction model. Plain dicts of…, One prior turn, reduced to validated facts. Frozen: once sanitized, a turn…, Reduce whatever the client sent to at most MAX_TURNS validated turns. Anything…, sanitize() (+27 more)

### Community 7 - "Prior Art Table (S12)"
Cohesion: 0.11
Nodes (21): Rehearsal Task, Sleep Task, Venue Logistics, DGLL NAVTEX, Fisher Friend (FFMA), GEMINI / DAT-SG, Hour-by-Hour Schedule (S10), INCOIS SAMUDRA (+13 more)

### Community 8 - "MarineObservation"
Cohesion: 0.15
Nodes (17): Same live forecast fetch() already makes (forecast_days=2 asks for ~48 hourly…, Wind's equivalent of OpenMeteoMarineFetcher.fetch_tomorrow() -- same reasoning,…, ORCA Marine Advisory Dashboard Screenshot, MarineObservation, pytest==9.1.1, _base_kwargs(), parametrize, Tests for orca/schema.py — written before the implementation. MarineObservation… (+9 more)

### Community 9 - "three-viz.js"
Cohesion: 0.09
Nodes (19): ACTION_COLOR, AGENT_SHORT_NAMES, ensureOceanDiorama(), ensureReasoningGraph(), params, wireReasoningToggle(), wireViewToggle(), attachInteraction() (+11 more)

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
Cohesion: 0.06
Nodes (71): agentic, Exception, AgenticUnavailable, answer_question(), compose_grounded_answer(), _composition_context(), extract_query_intent(), is_configured() (+63 more)

### Community 51 - "datetime"
Cohesion: 0.18
Nodes (12): OpenMeteoMarineFetcher, Wave height/period/direction and SST, per point, near-term forecast., datetime, parametrize, A day-ahead forecast really is less certain than the current hour. Copying…, Every observation must construct without raising — proves the validator in…, test_marine_fetcher_output_passes_schema_validation(), test_marine_fetcher_parses_real_fixture_into_observations() (+4 more)

### Community 52 - "web/index.html"
Cohesion: 0.22
Nodes (11): GET /health Endpoint, POST /ask Endpoint, Record Real Tamil Audio Sample, Frontend + Tamil Decision (S8.5), Role: Operator 3 — Frontend, refreshHealth(), renderEvidence(), renderRecommendation() (+3 more)

### Community 53 - "Repository Structure"
Cohesion: 0.24
Nodes (10): ORCA Tech Stack, Test on the Actual Presentation Laptop, Prompt 4 — Frontend, Repo Layout (S8.1), Scope: BUILD List (S7), Quick Start Guide, Repository Structure, fastapi==0.141.1 (+2 more)

### Community 54 - "ERDDAPChlorophyllFetcher"
Cohesion: 0.20
Nodes (8): _box_around(), ERDDAPChlorophyllFetcher, NOAA CoastWatch ERDDAP VIIRS daily chlorophyll-a, per point. Satellite ocean…, Return (most_recent_valid_date, mean_value, n_pixels) or None if every pixel in…, date, test_erddap_confidence_decays_with_staleness(), test_erddap_fetcher_returns_none_when_entire_window_is_cloud_masked(), test_erddap_fetcher_selects_the_most_recent_valid_day_not_the_newest_day()

### Community 55 - "MarineRegionsIMBLFetcher"
Cohesion: 0.23
Nodes (9): fetch_all(), main(), MarineRegionsIMBLFetcher, The real India-Sri Lanka maritime boundary (IMBL), from Marine Regions…, Run every fetcher. Returns (results_by_source, errors_by_source). A source that…, If Open-Meteo Marine is down, ERDDAP/Forecast results still come back, and the…, test_fetch_all_continues_when_one_source_fails_and_never_fabricates(), test_imbl_fetcher_build_url_uses_cql_filter() (+1 more)

### Community 56 - "build_docx.py"
Cohesion: 0.44
Nodes (11): add_bullet(), add_callout(), add_code_block(), add_heading_1(), add_heading_2(), add_heading_3(), add_p(), build_document() (+3 more)

### Community 57 - "api.py"
Cohesion: 0.24
Nodes (10): BaseModel, Prompt 3 — Agents + API, ask(), AskRequest, bathymetry(), health(), _is_reachable(), FastAPI surface: POST /ask, GET /evidence/{id}, GET /health. Matches… (+2 more)

### Community 58 - "test_fetch.py"
Cohesion: 0.25
Nodes (9): OpenMeteoForecastFetcher, Wind speed/gusts and precipitation, per point, near-term forecast., load_forecast_observations(), Tomorrow's forecast only -- orca/agentic.py's data_lookup path is the only…, Tests for data/fetch.py — written before the implementation. CLAUDE.md rule 1…, test_forecast_fetcher_parses_real_fixture_into_observations(), test_forecast_fetcher_tomorrow_offset_is_a_real_different_day_than_now(), test_load_forecast_observations_reads_back_what_was_written() (+1 more)

### Community 59 - "test_frontend_constants.py"
Cohesion: 0.22
Nodes (9): fixture, html(), _js_number(), Drift guards for constants that necessarily exist twice. web/ is plain HTML/JS…, The no-network fallback map is hand-placed SVG; its labels must still name the…, The browser may send fewer turns than the server keeps, never more -- anything…, test_frontend_history_cap_does_not_exceed_the_backend_cap(), test_frontend_svg_fallback_covers_exactly_the_real_zones() (+1 more)

### Community 60 - "ERDDAPBathymetryFetcher"
Cohesion: 0.24
Nodes (7): ERDDAPBathymetryFetcher, NOAA NCEI ETOPO 2022 (60 arc-second) global relief -- real seafloor elevation…, fetch() must refuse to hand back an empty grid dressed up as real data --…, test_bathymetry_fetcher_build_url_uses_bracket_stride_syntax(), test_bathymetry_fetcher_parses_real_fixture_into_grid(), test_bathymetry_fetcher_raises_not_fabricates_on_zero_points(), test_bathymetry_fetcher_skips_nan_pixels()

### Community 61 - "test_real_bathymetry_fetch_integration"
Cohesion: 0.29
Nodes (7): integration, requires_bathymetry_network, requires_marineregions_network, requires_network, test_real_bathymetry_fetch_integration(), test_real_imbl_fetch_integration(), test_real_openmeteo_marine_fetch_integration()

### Community 62 - "mcp_server.py"
Cohesion: 0.29
Nodes (6): Expose ORCA as an MCP Server (S6.2), weather-mcp Server, ORCA as an MCP server (war plan S6.2, stretch goal -- core was green first).…, System Architecture Diagram, mcp==2.1.1, What's Built and Verified Table

### Community 63 - "fetch.py"
Cohesion: 0.40
Nodes (5): Real marine data fetchers for the Nagapattinam/Chennai coast, Bay of Bengal…, erddapy Library, ERDDAP via Raw HTTP, Not erddapy, Zone-Scoped Policy, Planner Does Zone Comparison, ZONES constant

## Ambiguous Edges - Review These
- `MarineObservation` → `ORCA Marine Advisory Dashboard Screenshot`  [AMBIGUOUS]
  docs/screenshots/orca_live_demo.png · relation: conceptually_related_to

## Knowledge Gaps
- **69 isolated node(s):** `{ test, expect }`, `{ spawn }`, `{ test, expect }`, `{ test, expect }`, `name` (+64 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **32 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `MarineObservation` and `ORCA Marine Advisory Dashboard Screenshot`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `MarineObservation` connect `MarineObservation` to `write_cache`, `test_agents.py`, `test_planner.py`, `schema.py`, `test_agentic.py`, `datetime`, `ERDDAPChlorophyllFetcher`, `MarineRegionsIMBLFetcher`, `test_fetch.py`, `ERDDAPBathymetryFetcher`, `fetch.py`?**
  _High betweenness centrality (0.137) - this node is a cross-community bridge._
- **Why does `Prior Art & Comparative Alignment Table` connect `Prior Art Table (S12)` to `fetch.py`?**
  _High betweenness centrality (0.048) - this node is a cross-community bridge._
- **Why does `answer_question()` connect `test_agentic.py` to `api.py`, `test_planner.py`, `test_memory.py`?**
  _High betweenness centrality (0.042) - this node is a cross-community bridge._
- **Are the 9 inferred relationships involving `MarineObservation` (e.g. with `ERDDAPBathymetryFetcher` and `ERDDAPChlorophyllFetcher`) actually correct?**
  _`MarineObservation` has 9 INFERRED edges - model-reasoned connections that need verification._
- **Are the 10 inferred relationships involving `datetime` (e.g. with `_obs()` and `_obs()`) actually correct?**
  _`datetime` has 10 INFERRED edges - model-reasoned connections that need verification._
- **What connects `{ test, expect }`, `{ spawn }`, `{ test, expect }` to the rest of the system?**
  _69 weakly-connected nodes found - possible documentation gaps or missing edges._