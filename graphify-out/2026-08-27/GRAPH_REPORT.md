# Graph Report - ORCA  (2026-08-27)

## Corpus Check
- Corpus is ~26,962 words - fits in a single context window. You may not need a graph.

## Summary
- 362 nodes · 731 edges · 43 communities (16 shown, 27 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 61 edges (avg confidence: 0.92)
- Token cost: 192,333 input · 0 output

## Community Hubs (Navigation)
- Data Fetchers (Open-Meteo/ERDDAP)
- API Contract & Manual Tasks
- Domain Agents
- Planner & Recommendation
- Safety Policy Tests
- MCP Server & FastAPI Endpoints
- CLAUDE.md Hard Rules
- Prior Art & Rehearsal Logistics
- MarineObservation Schema Tests
- Demo Scenario Generation
- API Endpoint Tests
- Playwright Package Config
- Roadmap & Scope Decisions
- Live E2E Spec
- Mock-Mode E2E Spec
- Claude Code Prompting Discipline
- ORCA Pitch
- Playwright Config
- Screen Recording Fallback
- Copernicus Marine Library
- Deck Fixes
- DGLL Lighthouses
- Failure Modes & Fallbacks
- geo-mcp-servers Registry
- NIOT OMNI Buoys
- NOAA Marine MCP Server
- open-meteo-mcp Server
- OpenDrift
- Open-Meteo Marine API
- PydanticAI LLM Boundary
- qgis-mcp Server
- Presenter Role
- QA/Runner Role
- Stretch Scope List
- Tech Stack Verdicts
- Executive Summary
- httpx Dependency
- pydantic Dependency
- requests Dependency
- uvicorn Dependency

## God Nodes (most connected - your core abstractions)
1. `MarineObservation` - 47 edges
2. `build_recommendation()` - 25 edges
3. `_obs()` - 21 edges
4. `resolve()` - 20 edges
5. `Repository Structure` - 18 edges
6. `Finding` - 17 edges
7. `_finding()` - 16 edges
8. `OpenMeteoMarineFetcher` - 14 edges
9. `hazard_agent()` - 13 edges
10. `Repo Layout (S8.1)` - 13 edges

## Surprising Connections (you probably didn't know these)
- `ORCA Marine Advisory Dashboard Screenshot` --conceptually_related_to--> `MarineObservation`  [AMBIGUOUS]
  docs/screenshots/orca_live_demo.png → orca/schema.py
- `OpenMeteoMarineFetcher` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py
- `OpenMeteoForecastFetcher` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py
- `ERDDAPChlorophyllFetcher` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py
- `fetch_all()` --uses--> `MarineObservation`  [INFERRED]
  data/fetch.py → orca/schema.py

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Deterministic, LLM-Free Safety Override Pattern** — claude_policy_no_llm, orca_policy, readme_deterministic_safety_policy, orca_32_hour_war_plan_v3_safety_policy_code, team_status_no_llm_in_reasoning_path [INFERRED 0.85]
- **MarineObservation Provenance Traceability Pattern** — claude_marineobservation_requirement, orca_schema, api_contract_post_ask, api_contract_get_evidence, readme_data_traceability_schema, web_index_renderevidence [INFERRED 0.85]
- **Offline-First Cache Design Pattern** — claude_offline_only, orca_32_hour_war_plan_v3_offline_mode, team_status_offline_display_only, orca_api, web_index_refreshhealth [INFERRED 0.85]

## Communities (43 total, 27 thin omitted)

### Community 0 - "Data Fetchers (Open-Meteo/ERDDAP)"
Cohesion: 0.07
Nodes (39): _box_around(), ERDDAPChlorophyllFetcher, fetch_all(), main(), OpenMeteoForecastFetcher, OpenMeteoMarineFetcher, Path, Real marine data fetchers for the Nagapattinam/Chennai coast, Bay of Bengal… (+31 more)

### Community 1 - "API Contract & Manual Tasks"
Cohesion: 0.09
Nodes (36): GET /evidence/{id} Endpoint, GET /health Endpoint, POST /ask Endpoint, Freeze schema.py and policy.py Rule, MarineObservation Provenance Requirement, ORCA Tech Stack, Record Real Tamil Audio Sample, Test on the Actual Presentation Laptop (+28 more)

### Community 2 - "Domain Agents"
Cohesion: 0.11
Nodes (40): eo_satellite_agent(), _find(), geofence_agent(), hazard_agent(), ocean_state_agent(), _point_in_polygon(), Five independent agents, each answering one question about one zone. Each…, Standard ray-casting point-in-polygon test. (+32 more)

### Community 3 - "Planner & Recommendation"
Cohesion: 0.13
Nodes (32): build_recommendation(), _collect_evidence(), observation_id(), observations_for_zone(), query -> agents over cached evidence -> policy.resolve() -> structured answer.…, Recommendation, _render_text(), resolve_zone_from_query() (+24 more)

### Community 4 - "Safety Policy Tests"
Cohesion: 0.17
Nodes (25): Decision, resolve(), _finding(), parametrize, Tests for orca/policy.py — the safety decision engine, written first. This is…, CLAUDE.md rule 4: orca/policy.py contains NO LLM calls. This is the project's…, Rule 1 must short-circuit before rule 2 is even considered., If the 'opportunity and danger' branch in resolve() is ever removed, this exact… (+17 more)

### Community 5 - "MCP Server & FastAPI Endpoints"
Cohesion: 0.11
Nodes (21): BaseModel, Expose ORCA as an MCP Server (S6.2), weather-mcp Server, ask(), AskRequest, health(), _is_reachable(), Best-effort connectivity probe for the /health "offline_mode" badge ONLY. Never… (+13 more)

### Community 6 - "CLAUDE.md Hard Rules"
Cohesion: 0.11
Nodes (23): Boring, Readable Code Rule, Definition of Done, No New Dependencies Rule, No Swallowed Exceptions Rule, No Synthetic Data Rule, No Network Access at Demo Rule, policy.py No-LLM Guarantee, ORCA Live Demo Screenshot (+15 more)

### Community 7 - "Prior Art & Rehearsal Logistics"
Cohesion: 0.11
Nodes (21): Rehearsal Task, Sleep Task, Venue Logistics, DGLL NAVTEX, Fisher Friend (FFMA), GEMINI / DAT-SG, Hour-by-Hour Schedule (S10), INCOIS SAMUDRA (+13 more)

### Community 8 - "MarineObservation Schema Tests"
Cohesion: 0.24
Nodes (15): ORCA Marine Advisory Dashboard Screenshot, MarineObservation, pytest==9.1.1, _base_kwargs(), parametrize, Tests for orca/schema.py — written before the implementation. MarineObservation…, test_confidence_boundary_values_are_allowed(), test_confidence_out_of_range_raises() (+7 more)

### Community 9 - "Demo Scenario Generation"
Cohesion: 0.19
Nodes (14): demo/scenarios.json, Backup the Deck, 5-Minute Demo Script (S9), Final Checklist T-2 (S15), Safety Policy resolve() Function (S8.4), get_evidence(), Scenario Example: Safety Override in Action, Wave-Height Flip End-to-End Verification (+6 more)

### Community 10 - "API Endpoint Tests"
Cohesion: 0.14
Nodes (5): Tests for orca/api.py — written before the implementation. These run against…, Sanity: the endpoint is actually consulting real per-zone data, not returning…, The actual §8.6 guarantee: /ask and /evidence read only from data/cache/ and…, test_ask_and_evidence_are_unaffected_by_connectivity(), test_ask_different_zones_can_produce_different_actions()

### Community 11 - "Playwright Package Config"
Cohesion: 0.20
Nodes (9): description, devDependencies, @playwright/test, name, private, scripts, test:e2e, version (+1 more)

### Community 12 - "Roadmap & Scope Decisions"
Cohesion: 0.50
Nodes (4): MOSDAC / INCOIS Registration, Data Source Decision (S8.2), Scope: DO NOT BUILD List (S7), What's NOT Built

## Ambiguous Edges - Review These
- `MarineObservation` → `ORCA Marine Advisory Dashboard Screenshot`  [AMBIGUOUS]
  docs/screenshots/orca_live_demo.png · relation: conceptually_related_to

## Knowledge Gaps
- **50 isolated node(s):** `{ test, expect }`, `{ test, expect }`, `name`, `private`, `version` (+45 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **27 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `MarineObservation` and `ORCA Marine Advisory Dashboard Screenshot`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `MarineObservation` connect `MarineObservation Schema Tests` to `Data Fetchers (Open-Meteo/ERDDAP)`, `API Contract & Manual Tasks`, `Domain Agents`, `Planner & Recommendation`, `MCP Server & FastAPI Endpoints`?**
  _High betweenness centrality (0.159) - this node is a cross-community bridge._
- **Why does `Prior Art & Comparative Alignment Table` connect `Prior Art & Rehearsal Logistics` to `Data Fetchers (Open-Meteo/ERDDAP)`?**
  _High betweenness centrality (0.091) - this node is a cross-community bridge._
- **Why does `Repository Structure` connect `API Contract & Manual Tasks` to `Data Fetchers (Open-Meteo/ERDDAP)`, `Domain Agents`, `Planner & Recommendation`, `MCP Server & FastAPI Endpoints`, `Demo Scenario Generation`?**
  _High betweenness centrality (0.055) - this node is a cross-community bridge._
- **Are the 22 inferred relationships involving `MarineObservation` (e.g. with `ERDDAPChlorophyllFetcher` and `fetch_all()`) actually correct?**
  _`MarineObservation` has 22 INFERRED edges - model-reasoned connections that need verification._
- **Are the 3 inferred relationships involving `build_recommendation()` (e.g. with `Decision` and `Finding`) actually correct?**
  _`build_recommendation()` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Are the 6 inferred relationships involving `datetime` (e.g. with `_obs()` and `test_erddap_fetcher_selects_the_most_recent_valid_day_not_the_newest_day()`) actually correct?**
  _`datetime` has 6 INFERRED edges - model-reasoned connections that need verification._