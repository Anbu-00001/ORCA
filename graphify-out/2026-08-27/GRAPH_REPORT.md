# Graph Report - ORCA  (2026-08-27)

## Corpus Check
- 55 files · ~117,201 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 533 nodes · 1070 edges · 51 communities (19 shown, 32 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 49 edges (avg confidence: 0.76)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `a27bb567`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- test_fetch.py
- planner.py
- test_agents.py
- test_planner.py
- test_policy.py
- api.py
- CLAUDE.md Creation Directive (S3.4 / Prompt 0)
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

## God Nodes (most connected - your core abstractions)
1. `MarineObservation` - 52 edges
2. `build_recommendation()` - 32 edges
3. `_obs()` - 26 edges
4. `resolve()` - 20 edges
5. `_clean_go_observations()` - 19 edges
6. `Repository Structure` - 18 edges
7. `answer_question()` - 17 edges
8. `geofence_agent()` - 16 edges
9. `Finding` - 16 edges
10. `_finding()` - 16 edges

## Surprising Connections (you probably didn't know these)
- `ORCA Marine Advisory Dashboard Screenshot` --conceptually_related_to--> `MarineObservation`  [AMBIGUOUS]
  docs/screenshots/orca_live_demo.png → orca/schema.py
- `OpenMeteoMarineFetcher` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py
- `ERDDAPChlorophyllFetcher` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py
- `ERDDAPBathymetryFetcher` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py
- `MarineRegionsIMBLFetcher` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Deterministic, LLM-Free Safety Override Pattern** — claude_policy_no_llm, orca_policy, readme_deterministic_safety_policy, orca_32_hour_war_plan_v3_safety_policy_code, team_status_no_llm_in_reasoning_path [INFERRED 0.85]
- **MarineObservation Provenance Traceability Pattern** — claude_marineobservation_requirement, orca_schema, api_contract_post_ask, api_contract_get_evidence, readme_data_traceability_schema, web_index_renderevidence [INFERRED 0.85]
- **Offline-First Cache Design Pattern** — claude_offline_only, orca_32_hour_war_plan_v3_offline_mode, team_status_offline_display_only, orca_api, web_index_refreshhealth [INFERRED 0.85]

## Communities (51 total, 32 thin omitted)

### Community 0 - "test_fetch.py"
Cohesion: 0.06
Nodes (52): _box_around(), ERDDAPBathymetryFetcher, ERDDAPChlorophyllFetcher, fetch_all(), main(), MarineRegionsIMBLFetcher, OpenMeteoMarineFetcher, Path (+44 more)

### Community 1 - "planner.py"
Cohesion: 0.06
Nodes (64): GET /evidence/{id} Endpoint, GET /health Endpoint, POST /ask Endpoint, Freeze schema.py and policy.py Rule, MarineObservation Provenance Requirement, ORCA Tech Stack, Real marine data fetchers for the Nagapattinam/Chennai coast, Bay of Bengal…, demo/scenarios.json (+56 more)

### Community 2 - "test_agents.py"
Cohesion: 0.09
Nodes (47): eo_satellite_agent(), _find(), geofence_agent(), hazard_agent(), _load_imbl_segments(), ocean_state_agent(), _point_in_polygon(), Lazy module-level cache of the real IMBL geometry, read once. Falls back to an… (+39 more)

### Community 3 - "test_planner.py"
Cohesion: 0.10
Nodes (44): build_recommendation(), observations_for_zone(), The zero-risk, zero-network first-pass zone match: does a known zone's name…, resolve_zone_from_query(), run_agents(), _zone_by_substring(), Wave-Height Flip End-to-End Verification, _clean_go_observations() (+36 more)

### Community 4 - "test_policy.py"
Cohesion: 0.16
Nodes (26): _render_text(), Decision, resolve(), _finding(), parametrize, Tests for orca/policy.py — the safety decision engine, written first. This is…, CLAUDE.md rule 4: orca/policy.py contains NO LLM calls. This is the project's…, Rule 1 must short-circuit before rule 2 is even considered. (+18 more)

### Community 5 - "api.py"
Cohesion: 0.10
Nodes (24): BaseModel, ask(), AskRequest, bathymetry(), get_evidence(), health(), _is_reachable(), FastAPI surface: POST /ask, GET /evidence/{id}, GET /health. Matches… (+16 more)

### Community 6 - "CLAUDE.md Creation Directive (S3.4 / Prompt 0)"
Cohesion: 0.11
Nodes (22): Boring, Readable Code Rule, Definition of Done, No New Dependencies Rule, No Swallowed Exceptions Rule, No Synthetic Data Rule, No Network Access at Demo Rule, policy.py No-LLM Guarantee, ORCA Live Demo Screenshot (+14 more)

### Community 7 - "Prior Art Table (S12)"
Cohesion: 0.11
Nodes (21): Rehearsal Task, Sleep Task, Venue Logistics, DGLL NAVTEX, Fisher Friend (FFMA), GEMINI / DAT-SG, Hour-by-Hour Schedule (S10), INCOIS SAMUDRA (+13 more)

### Community 8 - "MarineObservation"
Cohesion: 0.16
Nodes (19): OpenMeteoForecastFetcher, Wind speed/gusts and precipitation, per point, near-term forecast., datetime, ORCA Marine Advisory Dashboard Screenshot, MarineObservation, pytest==9.1.1, test_forecast_fetcher_parses_real_fixture_into_observations(), _base_kwargs() (+11 more)

### Community 9 - "three-viz.js"
Cohesion: 0.09
Nodes (19): ACTION_COLOR, AGENT_SHORT_NAMES, ensureOceanDiorama(), ensureReasoningGraph(), params, wireReasoningToggle(), wireViewToggle(), attachInteraction() (+11 more)

### Community 10 - "test_api.py"
Cohesion: 0.11
Nodes (7): Tests for orca/api.py — written before the implementation. These run against…, Sanity: the endpoint is actually consulting real per-zone data, not returning…, An absent bathymetry cache is an honest 503, never a fabricated or silently-…, The actual §8.6 guarantee: /ask and /evidence read only from data/cache/ and…, test_ask_and_evidence_are_unaffected_by_connectivity(), test_ask_different_zones_can_produce_different_actions(), test_bathymetry_missing_cache_returns_503_not_empty_200()

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
Cohesion: 0.09
Nodes (40): agentic, Exception, AgenticUnavailable, answer_question(), compose_grounded_answer(), extract_query_intent(), is_configured(), _post() (+32 more)

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
- **Why does `MarineObservation` connect `MarineObservation` to `test_fetch.py`, `planner.py`, `test_agents.py`, `test_planner.py`, `test_policy.py`, `api.py`, `test_agentic.py`?**
  _High betweenness centrality (0.139) - this node is a cross-community bridge._
- **Why does `Prior Art & Comparative Alignment Table` connect `Prior Art Table (S12)` to `planner.py`?**
  _High betweenness centrality (0.057) - this node is a cross-community bridge._
- **Why does `build_recommendation()` connect `test_planner.py` to `planner.py`, `test_policy.py`, `api.py`, `MarineObservation`, `test_agentic.py`?**
  _High betweenness centrality (0.040) - this node is a cross-community bridge._
- **Are the 9 inferred relationships involving `MarineObservation` (e.g. with `ERDDAPBathymetryFetcher` and `ERDDAPChlorophyllFetcher`) actually correct?**
  _`MarineObservation` has 9 INFERRED edges - model-reasoned connections that need verification._
- **Are the 7 inferred relationships involving `datetime` (e.g. with `_obs()` and `_obs()`) actually correct?**
  _`datetime` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `{ test, expect }`, `{ spawn }`, `{ test, expect }` to the rest of the system?**
  _69 weakly-connected nodes found - possible documentation gaps or missing edges._