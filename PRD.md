# ORCA — Product Requirements Document

**Product:** ORCA — Oceanic Reasoning & Constraint Architecture
**Team:** ICARUS · **PS:** SIH26176 (ISRO, Software) · **Event:** SIH 2026
**Version:** 2.1 · **Date:** 2026-08-27
**Status:** Part 1 describes v0.2 as built. Part 2 is open for planning.
**v2.1:** Open Decision 8 resolved — a zone with no evidence returns a fourth action, `CANNOT ASSESS`, not a `GO` and
not a `DO NOT GO` (R-39). R-25 amended to cover enum widening, which that decision would otherwise have fallen through.

> **What this document is.** The source of truth for *what ORCA is, what it must do, and what it must never do.*
> Part 1 states what exists. Part 2 states what it becomes.
> Where this document and any other disagree about **product scope or requirements**, this document wins.
> It does not restate the agent coding rules (`CLAUDE.md`), the wire format (`API_CONTRACT.md`),
> the build state (`TEAM_STATUS.md`), or demo-day logistics (`ORCA-32-HOUR-WAR-PLAN-v3.md`) — those stay authoritative in their own lanes.

> **Every requirement carries a status.** **Met** = implemented and covered by a test. **Partial** = implemented with a
> named gap. **Open** = specified here, not yet built. A requirement with no passing test is not Met, however
> convincingly the code reads. §8 lists every Partial and Open in one place.

**v2.0 changes.** See [Appendix A](#appendix-a--change-log) for the full list with reasons. In brief: the architecture
diagram in §4 was redrawn to match the code (the old one was wrong in five ways); three principles were added for the
LLM layer that v1.1 predates (**P9–P11**); ten requirements were corrected where they asserted guarantees the system
does not have; twenty-five were added (**R-34 … R-58**, **N-7**) — most of them covering the agentic and memory
subsystems, which had no requirements at all before this pull; a status column was added throughout; and §8 gained
four gaps that were real but unrecorded. Existing R-numbers keep their v1.1 meanings (§16.2).

---

## 0. Read this first

This document is a **specification**, not a manual. It states what must be true. It does not tell you how to run
anything — by design, and because `README.md` already does. Five documents, in the order a newcomer should open them:

| # | Document | What it is | Read it if you are |
|---|---|---|---|
| 1 | **`README.md`** | Quick Start: prerequisites, `pip install`, `.env` / `GROQ_API_KEY`, cache ingest, pytest, Playwright, `uvicorn`, the web server, the MCP server. Plus the repository file map. | anyone, first |
| 2 | **`PRD.md`** (this) | Goal, users, the eleven invariants, the architecture, every requirement with its status, every known gap | anyone, second |
| 3 | **`CLAUDE.md`** | The hard rules that govern code changes. Shorter than this document and non-negotiable. | an agent, or anyone writing code |
| 4 | **`API_CONTRACT.md`** | Authoritative wire format — all 21 `/ask` response fields | integrating against the API |
| 5 | **`TEAM_STATUS.md`** · `SCRATCH.md` · `MANUAL_TASKS.md` | Build state; research notes and measured failures; outstanding human tasks | picking up in-flight work |

Vocabulary and the data dictionary are in [Appendix B](#appendix-b--vocabulary-and-data-dictionary). If a term or a
variable name in here is unfamiliar, it is defined there.

---

## 1. Problem

India already generates world-class marine data. ISRO's satellites map potential fishing zones. INCOIS forecasts ocean state. DGLL broadcasts weather warnings 250 nautical miles offshore. NIOT's buoys feed calibration data. It is comprehensive, it is public, and it is free.

None of it answers the actual question.

A fisherman leaving Nagapattinam at 4:00 AM is not going to reconcile six portals, in English, rendered as charts. And 30 km out he has no cellular signal at all — which is precisely when the decision gets dangerous. Existing apps (SAMUDRA, Fisher Friend, Sagar Vani, mKRISHI) *deliver data* and require connectivity; adoption sits around 4% of roughly 264,000 registered vessels.

The gap is not data, and not delivery. **The missing layer is the one that turns many sources into one decision, and can defend that decision afterwards.**

**Three failure modes any such layer must survive:**

1. **The blend.** Source A says "excellent fishing at Zone A." Source B says "3.1 m waves at Zone A." A language model asked to summarise will produce a confident, readable, averaged answer — and a fisherman will drown following it. A safety rule that lives in a prompt can be argued out of the decision.
2. **The untraceable number.** An advisory system that cannot say where "3.1 metres" came from, and when, is not auditable, and therefore not deployable by a government.
3. **The confident gap.** *(added v2.0)* The question the system cannot answer is more dangerous than the one it answers wrongly, because nothing marks it. Asked "which zone has the worst waves?" against a schema with no way to represent a comparison, an LLM will name a zone — and it will sound exactly as certain as a correct answer. Measured on 2026-08-27: Nagapattinam was named worst while it was the second *calmest* of ten. Nothing was broken; the question simply had no representation, so it silently became a different question.

ORCA exists to solve all three, in that order.

---

## 2. Users and surfaces

| User | Context | What they need | Priority |
|---|---|---|---|
| **Small-vessel fisherman** (primary) | Coastal Tamil Nadu; pre-dawn go/no-go; offline for hours to days; low tolerance for interpretation | One unambiguous answer: where to go, when to return, or don't go | P0 |
| **District fisheries / control room** | Issues advisories, answers for outcomes | The same decision plus its audit trail; ability to query programmatically | P1 |
| **Evaluator / judge / auditor** | Assessing whether the claim holds | To ask "where did that number come from?" and get a real answer in one click | P0 (demo) |
| **Downstream AI system** | Government helpdesk bot, dashboard | ORCA's reasoning callable as a tool (MCP) | P2 |

**Delivery surfaces.** ORCA is **web-first**: a browser page is the primary and only shipped surface for v0.2, and every capability lands there first. A **native/mobile app is a committed follow-on surface** (§12, F-28) — not a permanent non-goal — targeting the offline-in-the-boat case the browser serves worst. No requirement may be designed app-first.

**Explicit non-user for v0.2:** anyone requiring a mobile app, an account, or a language other than English/Tamil.

---

## 3. Product principles

These are invariants, not preferences. A change that violates one is a product change requiring an explicit decision, not an implementation detail.

| # | Principle | Why it is non-negotiable |
|---|---|---|
| **P1** | **No synthetic data, ever.** If a source fails, the reading is absent and the failure is loud. | The entire claim is traceability. One silent fallback to generated data makes every other number unbelievable. An absent reading is honest; a fabricated one is fatal. |
| **P2** | **Every number a user sees carries source, valid time, confidence and provenance.** Bare floats are dropped at the type boundary. | Makes P1 structurally enforced rather than a convention someone remembers. |
| **P3** | **The safety decision is deterministic code, never a model.** No LLM, no network, no randomness in the decision path. | A safety rule that can be prompted around is not a safety rule. This is also what makes it unit-testable, and therefore provable. |
| **P4** | **Safety outranks opportunity, always, and the system says what it overrode.** | The override *and its audit trail* are the product. Silent correctness is indistinguishable from luck. |
| **P5** | **Agents must be able to disagree.** Five narrow agents, not one blended judgement. | A contradiction that never surfaces cannot be resolved. Separation is what makes the conflict visible. |
| **P6** | **The system answers with no network.** All advisory reads come from the local cache. | The decision is needed exactly where coverage is not. |
| **P7** | **Degradation is disclosed, not hidden.** Stale evidence shows its age. Missing evidence is named. A narrowed question is stated as narrowed. | Overstated certainty in a safety system is a defect, not a UX polish issue. |
| **P8** | **A hypothesis is never an observation.** User-authored counterfactuals ("what if the wave height were 3.5 m?") are a separate type, visibly labelled, never written to `data/cache/`, and never carrying a real provider's `source` string. | Added 2026-08-27 for the environment sandbox (§10B). The sandbox necessarily produces marine numbers that no instrument measured — which is precisely what P1 forbids. P1 survives only if fabricated values can never be mistaken for, or promoted into, evidence. Making the boundary a type rather than a convention is the same move P2 makes for P1. |
| **P9** | **The model selects; it never originates.** Every value an LLM returns comes from a closed set defined in code — a real zone name, a real variable name, one of a fixed list of intents. It is re-validated against that set on arrival, regardless of what the provider's strict mode claims. A model may never produce a marine number, a place name, a risk level, or a verdict. | Added 2026-08-27 for the agentic layer (§5.5). P3 keeps the model out of the *decision*; P9 keeps it out of the *facts*. Without it, "parsing only" quietly becomes "inventing, in a field labelled parse". A closed set is checkable; a prompt instruction is not. |
| **P10** | **Every enhancement fails closed, out loud.** Any optional layer that is unavailable — no key, no network, a timeout, a malformed reply — degrades to the deterministic answer and records why. It never returns an error, a hang, or a guess in place of the real answer. | Added 2026-08-27. The general form of P1: an absent enhancement is correct, a guessed one is not. The "out loud" half is equally load-bearing — a server ran a full day with the agentic layer silently off and looked identical to one with it on. Silent fallback is a second kind of untraceable number. |
| **P11** | **Nothing the user typed is stored or replayed.** Conversation memory holds validated enum values only — a real zone name, a real variable name, a time frame. Free text has no channel through it. | Added 2026-08-27. Closes the two documented failure modes of multi-turn chat *structurally* rather than by instruction: hallucination compounding has no wrong text to carry forward, and prompt injection through history has no path that survives reduction to an enum. Delimiters and role markers demonstrably do not hold; a type boundary does. |

---

# PART 1 — AS BUILT (v0.2)

## 4. Architecture

The old v1.1 diagram drew ORCA as one straight pipeline. That was wrong in five ways, and each error hid something
that matters: `schema.py` is not a stage, `api.py` sits *above* the planner rather than below it, the per-zone fan-out
was invisible, the bathymetry and boundary paths bypass the safety cascade entirely, and the network boundary — the
single most important fact about this system — was not drawn at all. What follows matches the code.

### 4.1 Trust zones — the load-bearing picture

ORCA is three nested layers. The rule is that **information flows inward as data and never as instruction**, and each
layer can do strictly less than the one outside it.

```
╔══════════════════════════════════════════════════════════════════════════════════╗
║  SHELL — may call a model. Must fail closed.        orca/agentic.py              ║
║                                                     orca/memory.py               ║
║  Can change:  which real zone the question resolved to (from a closed set of 10) ║
║               which of four answer shapes is composed                            ║
║               the wording, and the language it is worded in                      ║
║  Cannot change:  action · risk_level · hard_deny · any number · any place        ║
║  Never imports orca/policy.py. Structurally, not by convention.                  ║
║                                                                                  ║
║   ┌──────────────────────────────────────────────────────────────────────────┐   ║
║   │  ORCHESTRATION — no model, no network.          orca/planner.py          │   ║
║   │  Fan out across all 10 zones · pick a safer alternative · assemble the    │   ║
║   │  reasoning trace. Knows about geography; knows nothing about safety.      │   ║
║   │                                                                          │   ║
║   │   ┌──────────────────────────────────────────────────────────────────┐   │   ║
║   │   │  CORE — deterministic and frozen (N-5).     orca/schema.py       │   │   ║
║   │   │                                             orca/agents.py       │   │   ║
║   │   │  5 agents → 5 Findings → resolve() → 1 Decision.  orca/policy.py │   │   ║
║   │   │  Pure functions. No I/O of any kind. No clock, no randomness.    │   │   ║
║   │   │  Same input, same output, forever — which is what makes it       │   │   ║
║   │   │  unit-testable, and therefore provable.                          │   │   ║
║   │   └──────────────────────────────────────────────────────────────────┘   │   ║
║   └──────────────────────────────────────────────────────────────────────────┘   ║
╚══════════════════════════════════════════════════════════════════════════════════╝
```

Read it as a claim that can be falsified: if `orca/policy.py` ever appears in an import inside the shell, or the shell
ever writes a field other than wording and zone selection, the architecture has failed and that is the bug.

### 4.2 Ingest — the only place the advisory path touches the network

Ingest runs **ahead of time**, from the command line, never inside a request. This is what makes P6 architectural
rather than a fallback branch.

```
   ══════════════════════════ N E T W O R K   B O U N D A R Y ══════════════════════════
    Crossed by exactly two files (N-7): data/fetch.py, here — and orca/agentic.py,
    which is optional, fails closed, and never touches the advisory numbers.

    Open-Meteo      Open-Meteo       NOAA CoastWatch    NOAA NCEI       Marine Regions
    Marine          Forecast         ERDDAP VIIRS       ETOPO 2022      (VLIZ/IOC-UNESCO)
    waves · SST     wind · gusts     chlorophyll-a      seafloor        India–Sri Lanka
    currents        precip · rain                       relief          IMBL
        │                │                 │                │                │
        └────────────────┴────────┬────────┘                │                │
                                  │                         │                │
                      ADVISORY EVIDENCE            MAP CONTEXT        GEOFENCE CONTEXT
                                  │                         │                │
                                  ▼                         │                │
   ┌──────────────────────────────────────────────┐         │                │
   │  data/fetch.py     `python -m data.fetch`    │         │                │
   │  · per source: fetch → parse → construct     │         │                │
   │    a validated MarineObservation             │         │                │
   │  · a null reading is SKIPPED, never filled   │         │                │
   │  · a failed source is logged and ABSENT      │         │                │
   │  · write_cache() REFUSES any observation     │         │                │
   │    whose source contains mock/sample/        │         │                │
   │    synthetic/dummy/fake  (G-6 enforced at    │         │                │
   │    the boundary, not just grepped for)       │         │                │
   └──────────────────────────────────────────────┘         │                │
            │                        │                      │                │
            ▼                        ▼                      ▼                ▼
    data/cache/*.json      data/cache/forecast/    data/cache/       data/cache/imbl/
    TODAY — the only       TOMORROW — lookups      bathymetry/       imbl_boundary.json
    tier the verdict       only, never the                                   │
    path ever reads        GO/DO-NOT-GO path                                 │
   ═════════════════════════════════════════════════════════════════════════════════════

    The four tiers are separate directories on purpose. load_cached_observations()
    globs *.json NON-RECURSIVELY, so the other three are invisible to the advisory
    path by construction — no guard, no flag, no way to get it wrong later.
```

### 4.3 Request path — what actually happens on one `/ask`

```
  web/index.html                                       MCP client
  chat turn + history                                  (helpdesk bot, dashboard)
        │  HTTP POST /ask                                    │  stdio
        ▼                                                    ▼
  ┌───────────────────────────────────────────┐    ┌──────────────────────┐
  │ orca/api.py                               │    │ orca/mcp_server.py   │
  │ loads cache · probes connectivity for the │    │ ⚠ does not import    │
  │ badge · 503 if the cache is empty         │    │   (§8, F-1)          │
  └───────────────────────────────────────────┘    └──────────────────────┘
        │                                                    │
        ▼                                                    │
  ┌────────────────────────────────────────────────────┐     │
  │ orca/agentic.py — answer_question()   [SHELL]      │     │
  │                                                     │     │
  │  history ──▶ orca/memory.py sanitize()             │     │
  │              raw client JSON  ──▶  ≤3 turns of     │     │
  │              (zone_name | variable | time_frame)   │     │
  │              validated against the real sets.      │     │
  │              Free text cannot survive this. (P11)  │     │
  │                                                     │     │
  │  ZONE RESOLUTION, most trustworthy first:          │     │
  │   1. substring   query literally names a zone  ← always tried first,      │
  │   2. inferred    LLM maps a landmark → closed set    zero network,        │
  │   3. remembered  prior turn's validated zone         never second-guessed │
  │   4. fallback    nearest by coordinates → SAY SO (coverage_note, P7)      │
  └────────────────────────────────────────────────────┘     │
        │  a real zone, or the nearest one, labelled as which │
        ▼                                                    ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │ orca/planner.py — build_recommendation()          [ORCHESTRATION]        │
  │                                                                          │
  │  for EVERY one of the 10 zones — not just the one asked about:           │
  │                                                                          │
  │        observations_for_zone(±0.05°)                                     │
  │                    │                                                     │
  │      ┌────────┬────┴───┬────────┬─────────┐                              │
  │      ▼        ▼        ▼        ▼         ▼                              │
  │   eo_sat  ocean_st  weather  hazard   geofence      [CORE] orca/agents.py│
  │   chl+SST  SST      wind     wave     MPA + IMBL    pure, no I/O         │
  │      │        │        │        │         │                              │
  │      └────────┴────┬───┴────────┴─────────┘                              │
  │                    ▼  5 Findings                                         │
  │        ┌───────────────────────────────────┐                             │
  │        │ orca/policy.py — resolve()  [CORE]│  location-blind, one zone   │
  │        │  R1  any hard_deny   → DO NOT GO  │  returned immediately       │
  │        │  R2  go + risk ≥0.6  → SAFER ALT  │  records what it overrode   │
  │        │  R3  otherwise       → GO         │                             │
  │        └───────────────────────────────────┘                             │
  │                    ▼  one Decision per zone  (10 in total)               │
  │                                                                          │
  │  CROSS-ZONE SELECTION — the planner's job, never the policy's:           │
  │    primary is GO            → GO, here                                   │
  │    primary is not GO        → first other zone that is a GENUINE GO      │
  │                               (a real opportunity finding behind it,     │
  │                               not merely an absence of data) → SAFER ALT │
  │    nothing clean anywhere   → the primary zone's own verdict, unchanged  │
  └──────────────────────────────────────────────────────────────────────────┘
        │  Recommendation: action · reason · evidence · agent_findings · zone_summaries
        ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │ back up into orca/agentic.py — compose_grounded_answer()      [SHELL]    │
  │  Sees: the decision, its reason, evidence ids, values, units. Nothing    │
  │        else — no history, so it cannot compound an earlier answer.       │
  │  Emits: answer_text + cited_evidence_ids. There is NO schema field       │
  │         through which it could alter the verdict even if it tried.       │
  │  Every cited id is re-checked against the real evidence list; an         │
  │  invented one is dropped, never rendered.                                │
  │                                                                          │
  │  ANY failure here → keep the deterministic text verbatim, log why. (P10) │
  └──────────────────────────────────────────────────────────────────────────┘
        │
        ▼   the same JSON either way; `agentic_used` says which path produced it
  web/index.html — map · answer · evidence panel · override banner · 3D views
```

**The fallback chain, stated as a guarantee.** With `GROQ_API_KEY` unset, `/ask` reproduces the pre-agentic,
fully-offline, deterministic output byte for byte. With it set but unreachable, the same is true plus a log line.
There is no third behaviour.

### 4.4 Region and coverage (v0.2)

**Region:** Tamil Nadu coast, Chennai → Colachel. `BBOX` lat 7.8–13.4, lon 76.9–80.6.

**Zones (10, all real named landing points):** Chennai (Kasimedu) · Cuddalore · Karaikal · Nagapattinam ·
Point Calimere (Kodiakkarai) · Mandapam · Rameswaram · Thoothukudi (V.O.C. Port) · Kanyakumari · Colachel.

Roughly 2,700 lines of Python across `orca/` and `data/`. No database, no build step, no agent framework in the core.

---

## 5. Functional requirements

Each requirement names its implementation, its verification, and its status.

### 5.1 Evidence layer

| ID | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| **R-1** | Every marine value in the system is a `MarineObservation` carrying `variable, value, unit, lat, lon, valid_time, fetched_at, source, confidence, freshness_min, provenance`. | `orca/schema.py` | `tests/test_schema.py` | **Met** |
| **R-2** | Construction **raises** on empty `source`, empty `provenance`, non-`datetime` times, or `confidence` outside 0–1. Validation is in `__post_init__`, so it cannot be bypassed. | `orca/schema.py` | `tests/test_schema.py` | **Met** |
| **R-3** | Data is fetched from **real, named** sources only. On source failure: log the real error, continue with other sources, never substitute. | `data/fetch.py` | `tests/test_fetch.py` (incl. live integration) | **Met** |
| **R-4** | Absent data is represented as absent. A zone with no chlorophyll granule yields no chlorophyll observation. A null hourly value is skipped, not interpolated. | `data/fetch.py`, `orca/agents.py` | `tests/test_fetch.py`, `tests/test_agents.py` | **Met** |
| **R-34** | `write_cache()` **refuses** to write any observation whose `source` contains `mock`, `sample`, `synthetic`, `dummy` or `fake`. G-6 is enforced at the write boundary, not only checked by `grep` after the fact. | `data/fetch.py` `_FORBIDDEN_SOURCE_WORDS` | `tests/test_fetch.py` | **Met** |
| **R-35** | The advisory cache, the tomorrow-forecast cache, the bathymetry grid and the IMBL boundary live in **four separate directories**. `load_cached_observations()` globs non-recursively, so only the advisory tier can ever reach the safety cascade. | `data/fetch.py`, `orca/planner.py` | `tests/test_fetch.py`, `tests/test_planner.py` | **Met** |

**Sources in use (all keyless):**

| Source | Variables produced | Role | Endpoint |
|---|---|---|---|
| Open-Meteo Marine | `wave_height_m`, `wave_period_s`, `wave_direction_deg`, `sst_c`, `ocean_current_velocity_kmh`, `ocean_current_direction_deg` | Advisory evidence | `marine-api.open-meteo.com/v1/marine` |
| Open-Meteo Forecast | `wind_speed_kmh`, `wind_gusts_kmh`, `precipitation_mm`, `rain_mm` | Advisory evidence | `api.open-meteo.com/v1/forecast` |
| NOAA CoastWatch ERDDAP | `chlorophyll_mg_m3` (VIIRS) | Advisory evidence | `coastwatch.noaa.gov/erddap/griddap/noaacwNPPVIIRSchlaDaily` |
| NOAA NCEI ETOPO 2022 | `elevation_m` grid | **Map context only** — never advisory evidence | `oceanwatch.pifsc.noaa.gov/erddap/…/ETOPO_2022_v1_60s` |
| Marine Regions (VLIZ / IOC-UNESCO) | India–Sri Lanka IMBL geometry | **Geofence context only** | `geo.vliz.be/geoserver/MarineRegions/wfs` |

MOSDAC and INCOIS are **not** integrated: every dataset requires an authenticated account with no anonymous endpoint. The adapter layer is source-agnostic by design, so this is a configuration change, not a rewrite. See §11.

### 5.2 Agents

| ID | Requirement | Detail | Status |
|---|---|---|---|
| **R-5** | Exactly five agents, each `list[MarineObservation] → Finding`, each answering one question. Pure functions: no network, no LLM, no clock. | `orca/agents.py` | **Met** |
| **R-6** | An agent with no evidence for its variable returns a **neutral** Finding (`suggests_go=False, risk_level=0.0, hard_deny=False`) and states why. It never claims safety or opportunity it cannot support. | Enforced per agent | **Met** |
| **R-7** | Thresholds are documented heuristics with a named provenance where one exists, and are described as such if asked. The 2.5 m hard deny is the Douglas sea scale degree 4→5 boundary (Moderate → Rough), the scale IMD's own Coastal Bulletin speaks in — inherited from maritime convention, not invented here. | Module docstring | **Met** |
| **R-36** | An agent's position must not depend on which observations happen to exist. *(See §8 — currently `geofence_agent` derives its position from `observations[0]`, so a location with no cached readings cannot be geofence-checked at all.)* | `orca/agents.py` | **Open** |

**What each agent can and cannot assert.** This matrix is the precise version of the table v1.1 got wrong — it
described `ocean_state_agent` as risk-only when it is in fact the system's second opportunity source.

| Agent | Question | `suggests_go`? | `risk_level` | `hard_deny`? |
|---|---|---|---|---|
| `eo_satellite_agent` | Are fish likely here? | **Yes** — chlorophyll ≥ 0.5 mg/m³ | always 0.0 | no |
| `ocean_state_agent` | Is the zone favourable? | **Yes** — SST within 27–31 °C | 0.0 (warm) or 0.15 | no |
| `weather_agent` | Wind and rain risk? | never | `min(wind / 40 km/h, 1.0)` | never — hazard owns sea state |
| `hazard_agent` | Is the sea state dangerous? | never | `min(wave / 2.5 m, 1.0)` | **Yes** — wave > 2.5 m |
| `geofence_agent` | Is this a prohibited or contested area? | never | 0.0 · 0.3 (IMBL ≤10 km) · 0.6 (≤5 km) · 1.0 | **Yes** — inside the Gulf of Mannar MPA box, or IMBL ≤ 2 km |

Two consequences worth stating, because they are not obvious and both matter on stage:

- **Only `weather_agent` and `hazard_agent` can produce a `risk_level` in the 0.6–1.0 band without also hard-denying.**
  Rule 2 (`SAFER ALTERNATIVE`) is therefore always wind- or wave-driven in practice.
- **Opportunity can come from SST alone.** When chlorophyll is cloud-masked, `eo_satellite_agent` goes neutral and
  `ocean_state_agent` alone carries the `suggests_go` — so a `GO` can rest on temperature while the fish-finding agent
  is blind. R-40 exists because of this.

### 5.3 Safety policy — the core requirement

| ID | Requirement | Status |
|---|---|---|
| **R-8** | `resolve(findings) → Decision` applies exactly three rules, **in strict order**: (1) any `hard_deny` → `DO NOT GO`, returned immediately, recording every overridden opportunity; (2) any `suggests_go` **and** any `risk_level ≥ 0.6` → `SAFER ALTERNATIVE`, recording the overridden findings; (3) otherwise → `GO`. | **Met** |
| **R-9** | `orca/policy.py` contains no LLM call, no network call, no clock and no randomness. Same input, same output, forever. It is never imported by `orca/agentic.py`. | **Met** |
| **R-10** | `resolve([])` **raises.** *Scope: this is a function-level guarantee about an empty list. It is not, on its own, a guarantee that the system refuses to decide without evidence — see R-39, which is the requirement that actually carries that claim.* | **Met** |
| **R-11** | Every non-`GO` decision carries a human-readable `explanation`, and carries `overridden` naming what was sacrificed and by which agent **whenever any finding suggested go**. A hard denial with no competing opportunity correctly carries an empty `overridden` — nothing was sacrificed. | **Met** |
| **R-12** | The override rule is **mutation-tested**: a test exists that fails if rule 2 is deleted. | **Met** |
| **R-13** | `resolve()` is scoped to one zone and takes no location argument. Cross-zone comparison is the planner's job. | **Met** |
| **R-37** | When more than one finding crosses the danger threshold, the decision must name the **most severe** one, not the first in agent-registration order. `zone_summaries` already applies exactly this principle (`max`, "a single hazard must not be diluted") — the reason string must apply it too. *Currently `danger[0]` and `hard_denials[0]` are list-order picks: with 2.4 m waves and 27.9 km/h wind, ORCA reports the wind and never mentions the sea state.* | **Open** |
| **R-38** | The severity of a verdict must be recoverable from the response without parsing prose. A hard deny that was rerouted to an alternative currently renders as `action: "SAFER ALTERNATIVE"`, indistinguishable from a mild wind override — which the control-room user (§2, P1) consumes programmatically. Add an additive field; do not overload `action`. | **Met** |
| **R-39** | A zone with **no evidence at all** must never resolve to `GO`. Five neutral findings are not five clean bills of health, and "No hazards found; conditions acceptable" from zero observations is exactly the confident-gap failure of §1.3. Such a zone returns a fourth action, **`CANNOT ASSESS`**, carrying an empty `evidence` list and naming the variables it lacked. **Not `DO NOT GO`:** conflating "I know it is dangerous" with "I do not know" is overstated certainty pointed in the safe direction, and a fisherman who learns that `DO NOT GO` sometimes means "no satellite pass today" stops believing `DO NOT GO` at all. *(Open Decision 8, resolved 2026-08-27.)* | **Met** |
| **R-39a** | The `CANNOT ASSESS` verdict is produced in `orca/planner.py`, **not** `orca/policy.py` — the frozen module (N-5) keeps its three rules and its `resolve([])` guard exactly as they are. The planner's test is whether any decision-bearing agent had evidence at that zone, checked before the zone's `Decision` is accepted. | **Met** |
| **R-39b** | A `CANNOT ASSESS` primary zone flows through the planner's **existing** alternative search (R-16): if another zone is a genuine `GO`, the user is told ORCA cannot assess where they asked *and* is offered the real alternative. Inability to assess one zone is not inability to help. | **Met** |
| **R-40** | A verdict must disclose which agents were **blind**, not only which readings were stale. R-33 covers age; nothing yet covers absence, so a `GO` resting on SST alone reads identically to one backed by every source. | **Met** |
| **R-59** | A zone carrying a finding at or above `RISK_OVERRIDE_THRESHOLD` (0.6) must **never** resolve to `GO`, whether or not any agent also suggested go. Rule 2 gates on `opportunity` **and** `danger`; with hazards present but no opportunity, execution falls through to rule 3 and returns `GO`, reason *"No hazards found; conditions acceptable"* — a sentence contradicted by the response's own `evidence` list. *Live on the 2026-08-27 cache at Kanyakumari (wind risk 0.67, wave 0.60) and Colachel (0.63 / 0.57), both rendering as an affirmative "Go to Kanyakumari."* **This is distinct from R-39**: every agent here has evidence, so R-39a's guard passes and this survives untouched. The trigger is inverted — `suggests_go` goes false when water is cold or chlorophyll is cloud-masked, so **the worse the fishing looks, the more likely the safety override is skipped**. Corrected in `orca/planner.py`, one layer above the frozen `policy.py` (N-5), which keeps its three rules unchanged. The corrected verdict is `SAFER ALTERNATIVE` and names the **most severe** danger, not the first — `zone_summaries` already applies that principle (R-18), and there is no reason to introduce a second `danger[0]` on a path written today. `overridden` is empty: nothing was sacrificed (R-11). | **Met** |

Verified by `tests/test_policy.py` (17 test functions, mutation-verified per `TEAM_STATUS.md`).

> **Note on R-37 … R-40.** These are the four places where v1.1 asserted a guarantee the code does not provide. They
> are recorded as **Open** requirements rather than quietly corrected prose, because §16.2 says requirements change
> here first and then in code. None of them touches `orca/policy.py`'s three rules; R-37 changes which finding is
> *named*, R-39 adds a guard *before* `resolve()` is reached, and R-38/R-40 are additive response fields.
>
> **Note on R-59 (v2.2).** R-59 is not one of those four: it is not a guarantee this document overstated, it is a
> second fail-open found while implementing R-39, in code R-39 does not touch. It is recorded here because §16.2
> requires it, and it takes priority over every Open item above it — R-39 fails open on zones ORCA has no data for;
> R-59 fails open on two zones ORCA has *good* data for, and says "No hazards found" while listing the hazards.
> Like R-39 it is corrected one layer above the frozen `policy.py`, whose three rules stay exactly as they are.

### 5.4 Planner

| ID | Requirement | Status |
|---|---|---|
| **R-14** | Resolve the queried zone by name substring, falling back to nearest zone by coordinates. This substring pass is deterministic, runs first, and is **never second-guessed** by the agentic layer. | **Met** |
| **R-15** | Run all five agents against **every** zone, not just the queried one. | **Met** |
| **R-16** | If the primary zone is not `GO`, search the remaining zones in order for one that is a **genuine** `GO` — a real opportunity finding behind it, not merely an absence of data (`decision.chosen is not None`). The first such zone becomes a `SAFER ALTERNATIVE`. If none exists, return the primary zone's own verdict unchanged. | **Met** |
| **R-60** | The R-16 alternative search is **bounded by distance**: a candidate zone further than `MAX_ALTERNATIVE_KM` from the primary zone is skipped, and if none remains the existing no-swap text is used (*"conditions are borderline… no clearly safer nearby zone found; proceed with caution or wait"*). Unbounded, the search offers reroutes no boat can take: on the 2026-08-27 cache **every** alternative resolved to Chennai — Thoothukudi 569 km, Mandapam 446 km, Rameswaram 441 km, Point Calimere 320 km — because R-16 takes the first genuine `GO` in `ZONES` order, and Chennai is first in that list. Distance is measured with `orca.agents._haversine_km`; no second distance function is introduced. **`MAX_ALTERNATIVE_KM = 100`.** Unlike the 2.5 m hard deny (R-7), this has no external provenance and is not presented as if it did: it is one boat's divert range, stated as arithmetic — a mechanised Tamil Nadu trawler cruising ~7 kn (~13 km/h) spends ~7.5 h steaming 100 km, which is the outer edge of what can be added to a single-day trip before fuel and crew endurance, not weather, become the binding constraint. A diversion the crew cannot reach before dark is not a safer alternative; it is a worse one presented confidently. | **Met** |
| **R-17** | A concrete `chosen_zone` is returned **only** when the user is actually being sent somewhere (a `GO`, or a `SAFER ALTERNATIVE` with a real zone swap). Otherwise `null` — never a zone the system is not recommending. | **Met** |
| **R-18** | Expose the full reasoning trace: `agent_findings` (the primary zone's five raw findings), `zone_summaries` (per zone: worst-agent `risk_level` using max — a single hazard must not be diluted by four calm agents — plus `hard_deny`), and `primary_zone` (the zone the *question* was about, which differs from `chosen_zone` on a reroute). All surface computation already performed; none is a separate calculation. | **Met** |
| **R-19** | **No code path may select a response, a verdict, or an evidence set by matching against query text.** Special-casing a scripted demo query is the hardcoded-demo failure mode and is prohibited. Zone resolution under R-14 is the single permitted query-string branch, and it chooses *where to look*, never *what to answer*. `demo/scenarios.json` is a read-only transcript generated from the live API, never a response source. | **Met** |
| **R-56** | Zone identity is by name. Two zones sharing a name collide in the planner's per-zone result map — a constraint to respect when zones become configuration (F-4). | **Met** |

*(v1.1's R-19 read "no code path may branch on a query string", which R-14 violates by construction. The intent was
always the narrower rule now stated.)*

### 5.5 Agentic layer

The shell from §4.1. It exists because the deterministic core answers one question well — "should I go?" — and a
fisherman asks many shapes of question, in Tamil, with the subject left out.

| ID | Requirement | Status |
|---|---|---|
| **R-41** | Every field an LLM returns is constrained to a **closed set defined in code**, sent as a strict JSON schema, and **re-validated against that same set on arrival**. The sets are defined once and used for both, so a value can never be added to the schema and then silently normalised away by an out-of-date validator. An out-of-set value becomes the documented default, never an error and never a pass-through. (P9) | **Met** |
| **R-42** | The composition step receives **no conversation history** — only the decision computed from real cached data on this request. It therefore cannot repeat, reinforce, or compound anything an earlier answer got wrong. Its schema has no field for `action`, `risk_level`, `hard_deny`, or any number. | **Met** |
| **R-43** | Every evidence id the composer cites is checked against the real evidence list; an id the model invented is dropped, never rendered. Citation hallucination is a documented failure mode *under* schema constraints, so the schema is not treated as sufficient. | **Met** |
| **R-44** | The layer **fails closed on every path**: no key, network error, timeout, malformed JSON, or schema violation raises `AgenticUnavailable`, which is caught by its own caller and yields the exact deterministic result. `GROQ_API_KEY` unset reproduces the pre-agentic output byte for byte. No bare `except`. (P10) | **Met** |
| **R-45** | Every fallback is **logged with its reason**. A silent fallback is a second untraceable number: a server ran a full day with the layer off and was indistinguishable from one with it on. The API logs its mode at startup for the same reason. *Currently the composition fallback logs; the extraction fallback (`except AgenticUnavailable: pass`) does not — the one place the module does not follow its own stated lesson.* | **Partial** |
| **R-46** | The layer must **name the questions it cannot answer** rather than coercing them into the nearest answerable one. A comparison across zones gets the real ordering computed in plain Python from real observations, or the composer is told in as many words that it has no ordering and may not rank. Unit conversion, second zones, species, tides and routes each get a stated caveat. (P7, §1.3) | **Met** |
| **R-47** | A question about tomorrow is answered from **tomorrow's cache**, through the same agents and the same `resolve()` — a forecast verdict is a real verdict, not a weaker one. If that cache is empty, the answer states that it reflects current conditions instead. Chlorophyll has no forecast equivalent and returns "no reading", never today's value relabelled. | **Met** |
| **R-48** | How the answered zone was chosen is reported (`zone_match`: `exact` \| `inferred` \| `remembered` \| `fallback`), and a `fallback` is stated to the user in words. Answering about the nearest zone is a reasonable default; presenting it as what was asked is not. (P7) | **Met** |
| **R-49** | The total time the agentic layer may add to one request must be **bounded and stated**. Two sequential calls at an 8 s timeout each can add up to 16 s — on a wifi-off stage with a key present, this is the slow path a judge would see. | **Open** |

### 5.6 Conversation memory

| ID | Requirement | Status |
|---|---|---|
| **R-50** | A conversation turn is reduced on ingest to at most three values from closed sets — a real zone name, a real variable name, one of two time frames. **Free text has no channel through this module.** `ConversationTurn` is frozen. (P11) | **Met** |
| **R-51** | `sanitize()` is the only constructor from outside input. Malformed or hostile history degrades to "no memory" — never a rejected request, never an exception on the `/ask` path. A fisherman's safety answer must not be lost because a client sent a bad optional field. | **Met** |
| **R-52** | At most 3 prior turns are kept. Memory exists to resolve "what about tomorrow?" against its immediate subject, not to be a transcript. | **Met** |
| **R-53** | History reaches **only** the extraction step, never composition. | **Met** |

### 5.7 API

| ID | Requirement | Status |
|---|---|---|
| **R-20** | Endpoints: `POST /ask`, `GET /evidence/{id}`, `GET /bathymetry`, `GET /health`. | **Met** |
| **R-21** | `/ask` and `/evidence` read their marine evidence **only** from the cache, online or offline. | **Met** |
| **R-22** | Every number in every response resolves to a `MarineObservation` retrievable by id. Ids are content-derived — `"obs_" + sha1(source\|variable\|lat\|lon\|valid_time)[:10]`, computed in `orca/planner.py`, stable across restarts and not stored on the observation. | **Met** |
| **R-23** | `GET /bathymetry` returns **503** when the cache is unpopulated — never a fabricated or empty 200. | **Met** |
| **R-24** | `GET /health` reports `offline_mode`, `cache_age_min`, `cache_observation_count`. | **Met** |
| **R-25** | New response fields must be **additive**; existing clients ignore unknown keys. **A new *value* in an existing enum is not additive** — a client switching on `action` lands in its default branch, and if that default is "proceed" the widening has built a fail-open. Widening an enum therefore requires: a version note in `API_CONTRACT.md`, the safe interpretation of the new value stated there in words, and every known consumer checked. *(Amended v2.1 — R-39's `CANNOT ASSESS` is the first case.)* | **Met** |
| **R-57** | `/ask` accepts an optional `history`. It is typed `Any` deliberately: `orca/memory.sanitize()` is the single validation gate, and any narrower annotation hands part of that job to Pydantic, which rejects with a 422 instead of degrading to "no memory" (R-51). | **Met** |
| **R-54** | The connectivity probe that drives the `offline_mode` badge is a **display concern** and must not sit in the `/ask` request path. *Currently `_is_reachable()` — a live socket connect to `marine-api.open-meteo.com:443` — is called on every `/ask`, contradicting N-6 and `orca/api.py`'s own docstring. It cannot change the answer, but it is a network call in the request path and DNS resolution is not bounded by its 0.75 s timeout.* | **Met** |

Wire format is specified in `API_CONTRACT.md`, which remains authoritative for field shapes.

### 5.8 Interface

| ID | Requirement | Status |
|---|---|---|
| **R-26** | One self-contained page, no build step. Map left ~60%; right ~40%: answer card (large), expandable evidence panel, override banner, offline toggle, conversation history. | **Met** |
| **R-27** | Every displayed number expands to its source, timestamp, confidence and provenance link. | **Met** |
| **R-28** | The amber override banner appears **only** when `overridden` is non-empty. | **Met** |
| **R-29** | Tamil is answered in Tamil when the question is in Tamil, including Tamil written in Latin script. Live ASR remains out of scope for v0.2 — it is a reliability coin-flip on stage. | **Met** |
| **R-30** | 3D views (reasoning graph, ocean diorama) render only real response data — actual `agent_findings`, `evidence`, `zone_summaries`, and real NOAA bathymetry. Vertical exaggeration (1/1200) is a stated presentation choice applied to rendering only, never to underlying values. Both degrade to an inert placeholder — never a fabricated one — when their data source is unavailable. | **Met** |
| **R-58** | The page states which mode produced the answer (`agentic_used`) and renders `coverage_note` when present. (P7, P10) | **Met** |
| **R-55** | `?mock=1` renders `web/mock_response.json` for Playwright. It is not reachable from the UI and is never a fallback — an unreachable API renders an explicit `ERROR`, not a mock. But the mock render is **visually identical to a real advisory and screenshot-able as one**. F-19d will forbid exactly this for hypotheticals; the same standard must apply here. | **Open** |

### 5.9 Offline behaviour

| ID | Requirement | Status |
|---|---|---|
| **R-31** | The full advisory path works with the network physically off. This is architectural (§4.2), not a fallback branch: the cache is populated ahead of time and the agentic layer fails closed. | **Met** |
| **R-32** | `offline_mode` is a **display concern only**. It changes no decision. | **Met** |
| **R-33** | In degraded mode the UI shows each reading's **age**, and states that evidence is older than live. *Currently the evidence panel shows `valid_time` and the source's own `confidence`, but not `freshness_min` or a computed age; and nothing anywhere reduces confidence at read time, though the offline badge's tooltip says "confidence adjusted". Chlorophyll confidence does decay with staleness — but at fetch time, per-source, which is a different thing.* | **Partial** |

## 6. Non-functional requirements

| ID | Requirement | Status |
|---|---|---|
| **N-1** | Python 3.11, FastAPI, plain HTML + MapLibre/three.js from CDN. No database. No build step. JSON files on disk. | **Met** |
| **N-2** | A fresh `git clone` + `pip install -r requirements.txt` runs the full system with no local state. | **Met** |
| **N-3** | No agent framework in the core reasoning path. Orchestration is five function calls and one policy check; a framework would add failure modes for a problem that does not exist here. The agentic layer is a fixed-code-path *workflow*, not an autonomous agent that picks its own next step. | **Met** |
| **N-4** | No swallowed exceptions. `except: pass` is prohibited repo-wide, and a typed `except X: pass` must still record why. | **Partial** — see R-45 |
| **N-5** | `orca/schema.py` and `orca/policy.py` are frozen once green. Changes require an explicit decision plus re-running the policy and schema suites including the mutation check. | **Met** |
| **N-6** | No external call in the `/ask` request path other than the optional, fail-closed agentic call. | **Met** — R-54 closed 2026-08-28 |
| **N-7** | **Exactly two files may touch the network**: `data/fetch.py` (ahead of time, populating the cache) and `orca/agentic.py` (optional, fail-closed, never touching a marine number). A network call anywhere else is a bug. | **Met** — R-54 closed 2026-08-28 |

## 7. Acceptance gates

The demo is not "working" until all of these pass. Each is chosen because it catches a specific way the system could be fake.

| Gate | Command / action | Catches | Last verified |
|---|---|---|---|
| **G-1** | `pytest -q` green (currently requires `--ignore=tests/test_mcp_server.py`, see §8) | General regression | ⚠ not re-run this session |
| **G-2** | `npx playwright test` green | Interface regression | ⚠ not re-run this session |
| **G-3** | Raise a zone's wave height past 2.5 m; the cited reason must change to the sea state | A hardcoded demo string masquerading as a policy | ✅ 2026-08-27 |
| **G-4** | Delete policy rule 2; a test must fail | A safety rule that isn't actually load-bearing | ⚠ not re-run this session |
| **G-5** | `grep -rn "except:" orca/ data/` returns nothing | Swallowed failures (violates P1) | ✅ 2026-08-27 |
| **G-6** | `grep -rni "mock\|sample\|synthetic\|dummy" data/cache/*.json` returns nothing | Synthetic data (violates P1) | ✅ 2026-08-27 |
| **G-7** | Fresh clone runs the demo | Hidden dependence on local state | ⚠ not re-run this session |
| **G-8** | Wifi **physically** off, `/ask` still answers, badge flips | Offline claim (P6) | ⚠ not re-run this session |
| **G-9** | Evidence panel shows source + timestamp for every number | Traceability claim (P2) | ✅ 2026-08-27 |
| **G-10** | Unset `GROQ_API_KEY`; `/ask` returns the deterministic answer unchanged | Fail-closed claim (P10, R-44) | ⚠ not re-run this session |
| **G-11** | Send `history` as a string, a number, and a list of hostile instructions; the answer is unaffected and no free text reaches a prompt | Memory injection (P11, R-50) | ⚠ not re-run this session |
| **G-12** | Ask a comparison question ("which place has the worst waves?"); the named zone matches the ranking computed in plain Python | The confident gap (§1.3, R-46) | ⚠ not re-run this session |
| **G-13** | Ask about a coordinate with no cached readings; the answer is `CANNOT ASSESS` with an empty evidence list — never `GO` | The confident gap inside our own deterministic core (R-39) | ✅ 2026-08-28 (`test_r39_zone_with_no_observations_cannot_be_assessed`) |
| **G-14** | Sweep all ten zones; **no zone may return `GO` while carrying a finding ≥ 0.6**, and no zone may pair the reason *"No hazards found; conditions acceptable"* with a non-empty hazard. Kanyakumari and Colachel are the live regressions | Danger with no opportunity failing open to `GO` (R-59) | ✅ 2026-08-28 (`test_g14_no_live_zone_resolves_to_go_while_carrying_a_hazard`) |

`tests/` currently defines **203 test functions** across 10 files; `e2e/` defines **39 Playwright tests** across 3 specs
(counted statically, not executed — this environment has no installed dependencies). `G-5`, `G-6`, `G-3` and `G-9` were
re-verified directly. **Re-run everything on the presentation machine before relying on any of it.**

## 8. Known gaps

Stated plainly. Each is a deliberate position, not an oversight.

| Gap | Status | Position |
|---|---|---|
| **`orca/mcp_server.py` does not import.** It uses `mcp.server.fastmcp.FastMCP`, which does not exist in the installed `mcp==2.1.1`. The single `ModuleNotFoundError` aborts collection for the whole `pytest -q` run. | **Open — highest priority code defect** | Fix: `from mcp.server.mcpserver import MCPServer`, `MCPServer("orca")`. Last known-good at commit `4cdcb13`. Stretch-goal file; does not touch the core demo path. |
| **A zone with no evidence resolves to `GO`.** Five neutral findings reach rule 3 and produce "No hazards found; conditions acceptable" from an empty evidence list. `resolve([])` raising does not prevent this, because the planner never calls it with an empty list. | **Closed 2026-08-28** | R-39/39a/39b, now fully specified: return `CANNOT ASSESS` from the planner, leave `policy.py` frozen, reroute to a genuine alternative if one exists. Widening `action` also triggers the amended R-25 — `API_CONTRACT.md` and every consumer. Add G-13. This is the §1.3 failure inside our own deterministic core. |
| **A zone with danger but no opportunity resolves to `GO`.** Rule 2 gates on `opportunity` **and** `danger`; hazards with nothing suggesting go fall through to rule 3. Live today at Kanyakumari (0.67) and Colachel (0.63), both rendering *"Go to Kanyakumari."* with the contradicting readings in the same response's `evidence`. **Distinct from R-39** — every agent here has evidence, so R-39a's guard passes and this survives R-39/39a/39b shipped in full. | **Closed 2026-08-28** | R-59. Corrected in the planner (`policy.py` frozen, N-5), returning `SAFER ALTERNATIVE` naming the **worst** danger with an empty `overridden` (R-11). The trigger is inverted: `suggests_go` goes false when water is cold or chlorophyll is cloud-masked, so the worse the fishing looks, the more likely the override is skipped. Add G-14. |
| **Alternative reroutes are unbounded by distance.** Every non-`GO` zone today reroutes to Chennai — Thoothukudi 569 km, Mandapam 446 km, Point Calimere 320 km (with Karaikal 72 km away) — because R-16 takes the first genuine `GO` in `ZONES` order. | **Closed 2026-08-28** | R-60. Skip candidates beyond `MAX_ALTERNATIVE_KM` using the existing `_haversine_km`; the no-swap text already exists and is the honest answer when nothing is near. |
| **The decision names the first danger, not the worst.** `danger[0]` and `hard_denials[0]` are agent-registration-order picks. With 2.4 m waves (risk 0.96) and 27.9 km/h wind (0.70), ORCA reports the wind. | **Open** | R-37. `zone_summaries` already does this correctly with `max`; the reason string needs the same rule. |
| **`geofence_agent` cannot fire for any covered zone.** Nearest approach to the IMBL across all 10 zones is 22.7 km (Rameswaram) against a 10 km advisory band, and no zone falls inside the Krusadai MPA box. Both boundaries are real and correctly implemented; neither is currently reachable. Its position also comes from `observations[0]`, so an uncovered point cannot be checked at all. | **Accepted for v0.2, but say so if asked** | R-36. The agent is honest, not decorative by intent — but a judge asking "show me the geofence deny" cannot currently be shown one from a covered zone. |
| **A rerouted hard deny is indistinguishable from a mild override** in the `action` field. Only the prose differs. | **Open** | R-38. Matters for the control-room user, who consumes this programmatically. |
| **R-33 is not implemented.** No reading age is displayed and no confidence is reduced at read time, while the offline tooltip says "confidence adjusted". | **Open** | The claim in the tooltip should be removed or made true. Making it true is the better fix. |
| **The extraction fallback is silent.** `except AgenticUnavailable: pass`, where composition's equivalent logs — the one place the module does not follow the lesson its own comment states. | **Open** | R-45. One line. |
| **`_is_reachable()` runs inside `/ask`.** Benign — it cannot change the answer — but it is a network call in the request path, contradicting N-6, N-7 and `orca/api.py`'s own docstring. DNS resolution is not bounded by its 0.75 s timeout. | **Closed 2026-08-28** | R-54. Move it to `/health`, or state the exception explicitly. |
| **Agentic latency is unbounded at ~16 s worst case** (two sequential 8 s timeouts) when a key is present and the network is unreachable — precisely the wifi-off stage condition. | **Open** | R-49. A single wall-clock budget for the whole layer. |
| **`?mock=1` renders a fabricated advisory that is screenshot-identical to a real one.** Not reachable from the UI and never a fallback — but unmarked. | **Open** | R-55. §10B.1 will forbid exactly this for hypotheticals; v0.2 should not ship the thing it is about to prohibit. |
| **No real "return by \<time\>" forecasting.** ORCA holds today and tomorrow, not an hourly series. | **Accepted** | Would need a multi-hour forecast series per observation. Deliberately **not** fabricated as a number with nothing behind it (P1). Roadmap item F-18. |
| **Wave hard-deny may not fire on live data.** Real wave heights at these points have stayed well under 2.5 m throughout the build; the live conflict is currently wind-driven. The rule is real and tested. | **Accepted** | Re-run `scripts/generate_demo_scenarios.py` close to presentation to see which zone actually conflicts. Do not assume a scripted example. |
| **Tamil phrasing is AI-written and unconfirmed by a native speaker.** | **Open — human task** | See `MANUAL_TASKS.md`. |
| **Chlorophyll is absent for many zones.** VIIRS NRT is frequently cloud-masked at this bbox. | **Correct behaviour** | This is R-4 working. Absence is logged, never filled. But see R-40: absence should also be *disclosed* in the answer, not only in the evidence list. |
| **The Gulf of Mannar polygon is a box around Krusadai Island's published coordinate**, not the park's full official boundary (unavailable without a WDPA account). | **Accepted** | An approximation of one real, verifiable restricted feature — stated as such if asked. |
| **Thresholds are heuristics.** 2.5 m has real Douglas-scale provenance; 0.5 mg/m³ and 27–31 °C do not. | **Accepted** | Stated as such if asked. |

## 9. Success criteria (v0.2)

| Criterion | Bar |
|---|---|
| Something real works, live | The full path runs on the presentation laptop, twice clean, wifi off |
| The conflict is visible | A judge sees an override banner and understands what was overridden and why |
| Traceability survives challenge | "Where did that number come from?" is answered in one click, from the running system |
| The model's limits are visible | Unset the key mid-demo; the answer stays correct and the badge says so |
| The built/planned line is explicit | We state exactly what is not built rather than blurring it — §8 is read aloud, not hidden |
| Q&A holds | Prior art named unprompted; "did AI write this?" answered calmly and directly |

---

# PART 2 — ROADMAP

Not built. Ordered by dependency, not ambition. Nothing here may weaken §3.

## 10. Phase 1 — Harden (immediately post-selection)

| ID | Item | Rationale |
|---|---|---|
| **F-1** | Fix `orca/mcp_server.py`; restore the full suite to a single green `pytest -q` | Known defect; blocks the stretch differentiator |
| **F-2** | Vendor three.js locally | Closes the last offline gap (P6) |
| **F-3** | Record and verify the Tamil sample with a native speaker | Only remaining interface gap |
| **F-4** | Widen zone coverage beyond ten fixed points; make zones configuration, not code (respecting R-20) | Ten hardcoded points is still a prototype shortcut |
| **F-5** | Automate cache refresh on a schedule | Manual refresh before each demo is fragile |
| **F-29** | **Close the eight Open requirements in §5** — R-36, R-37, R-38, R-39, R-40, R-45, R-49, R-54, R-55 | These are the gap between what this document claimed and what the system does. R-39 is a safety defect and should not wait for a phase. |

## 10B. Phase 1B — Environment sandbox

The largest addition to product scope since v0.1. It changes what ORCA *is* for an evaluator: today ORCA answers "should I go, and why?" With the sandbox it also answers **"what would have to be true for the answer to change?"** — the question that proves the policy is real rather than a rendered string.

**What already exists and is not being rebuilt:** multi-source live ingestion; `build_recommendation(...)` already accepts injected observations, zones and a resolved zone, so the sandbox entry point exists at function level; and the chat surface, which v1.1 listed as missing, now ships.

**What is missing:** a named, holdable environment-state object, and any API path that accepts modified values.

### 10B.1 The P1 boundary

A counterfactual fabricates a marine number. **P1 is not relaxed.** It is preserved by making the fabrication structurally incapable of impersonating evidence (P8):

| Rule | Requirement |
|---|---|
| **F-19a** | A hypothetical value is a distinct type (working name `HypotheticalValue`) — **not** a `MarineObservation`. It is never accepted by any function typed to take observations without an explicit adaptation step that stamps it. That adaptation step is the one seam in the system where a fabricated number can become an input; it lives in one named module and is reviewed as such. |
| **F-19b** | Its `source` is fixed to `"USER HYPOTHESIS (not measured)"`; its `provenance` records **the baseline observation id it replaced** plus the user's instruction. It never carries a real provider name or URL. |
| **F-19c** | No sandbox value is ever written to any cache tier, ever persisted as an observation, or ever returned by `GET /evidence/{id}` as though measured. Sandbox state is request/session-scoped and in-memory. |
| **F-19d** | Any response computed from a modified environment is flagged at the top level (`"hypothetical": true`) and the UI renders it in a visually distinct, unmistakable treatment. A counterfactual result must never be screenshot-able as a real advisory. **This standard applies retroactively to `?mock=1` (R-55).** |
| **F-19e** | G-6 must continue to return nothing after any sandbox session. This becomes a **regression gate**, not a one-time check. |

### 10B.2 Environment state

| ID | Item |
|---|---|
| **F-20** | A first-class **`EnvironmentState`**: a named, immutable snapshot of every observation across all zones at one instant, with the cache `fetched_at` it derives from. Today the environment is implicit in a list loaded per request; the sandbox needs something a user can hold and diff. |
| **F-21** | **Fork-and-perturb.** `EnvironmentState.perturb(...)` returns a **new** state with named values overridden, leaving the baseline untouched. Every perturbed state records its parent and the full ordered list of edits that produced it — the sandbox gets the same audit trail the advisory has (P2 applied to hypotheses). |
| **F-22** | **Consistency rules across coupled variables.** Raising wind without touching waves produces a physically incoherent sea state, and an incoherent sandbox teaches the user something false. Coupled variables must either move together under a documented, stated relationship, or the system must **decline to model** the combination and say so. **Silent independent perturbation of coupled variables is prohibited** — it is P1's failure mode wearing a different hat. Which relationships are defensible enough to encode is **Open Decision 6**; until answered, the sandbox exposes only variables it can perturb honestly in isolation. |
| **F-23** | **Diff-first response.** The sandbox's primary output is not a new recommendation but the **delta**: which agent findings flipped, which policy rule fired instead, and the single threshold crossing responsible. "Wave 1.6 m → 2.6 m crosses the 2.5 m hard-deny; `hazard_agent` flips; rule 1 now fires before rule 2 is reached." |

### 10B.3 Sandbox controls

| ID | Item |
|---|---|
| **F-25** | **Prompt → perturbation parsing.** Natural-language instructions ("make it stormier in Karaikal", "what if waves hit 3 m?") resolve to explicit, named variable edits. **The parse is displayed back as a concrete edit list before it is applied**, and the *confirmed* list is what executes — otherwise the display is theatre. |
| **F-26** | **The P9 boundary holds.** If a model is used for F-25 it may emit only a **constrained edit instruction** (variable, zone, value) validated against an allow-list — the same closed-set-plus-re-validation discipline R-41 already implements, owned in one named location. It never produces a marine number that reaches the user un-validated, never phrases the safety verdict, and `orca/policy.py` stays exactly as it is. The parsed edit is re-run through the **same** deterministic agents and `resolve()` as a real query — that identity is the whole point of the feature. A parse failure is stated, never guessed. |
| **F-27** | **Offline degradation (P6, P10).** If F-25 requires network, the sandbox must degrade to structured controls (per-variable sliders/fields) producing identical perturbations with no network. The sandbox may not be the first feature that breaks the offline claim. |

### 10B.4 Why this is worth building

It converts the project's central claim from an assertion into something a judge can operate. G-3 is currently a thing *we* run in a terminal. F-19…F-27 make it a thing *the evaluator* does, in the browser, against the live system — and the audit trail proves the policy that answered them is the same deterministic code that answers a real query.

**Sequencing:** after Phase 1. Additive to the demo path; per §16 it must not alter Part 1 behaviour.

## 11. Phase 2 — Real Indian sources

| ID | Item | Notes |
|---|---|---|
| **F-6** | MOSDAC integration | Requires an authenticated institutional account; no anonymous endpoint exists. Human registration task, already flagged. |
| **F-7** | INCOIS PFZ + ocean-state integration | The authoritative national source; registration in progress |
| **F-8** | Copernicus Marine (`copernicusmarine`) | Free account, no volume or bandwidth quotas |
| **F-9** | Source-quality weighting in confidence | Once multiple sources cover one variable, confidence should reflect agreement, not just per-source metadata |

The adapter layer is source-agnostic specifically so F-6 through F-8 are configuration changes. If any of them requires touching `policy.py`, the abstraction has failed and that is the bug to fix.

## 12. Phase 3 — Reaching the boat

The genuinely hard part, and the honest answer to "what's unsolved?"

| ID | Item | Notes |
|---|---|---|
| **F-10** | **NavIC messaging** | ISRO already broadcasts PFZ and cyclone alerts to 40,000+ vessels; the Signal-in-Space ICD is published. We plug into it, not compete with it. Needs hardware. |
| **F-11** | **DGLL NAVTEX** | 7 stations, 518 kHz English / 490 kHz local, 250 nm range, no user fees |
| **F-12** | **In-situ collection via NMEA 0183/2000** | Every boat's fish-finder already measures sea temperature over a standard port and nobody collects it. Also the second revenue path: cal/val data ISRO and INCOIS currently pay moored buoys to gather. |
| **F-13** | **The return path** | Broadcast channels are one-way. Getting data *from* a boat out of coverage is the open problem. We name it rather than claim it. |
| **F-14** | Drift modelling (OpenDrift) for search-and-rescue / man-overboard | Established tool; reads Copernicus currents directly |
| **F-28** | **Mobile app** as a second delivery surface | The browser serves the actual use case worst exactly where it matters: a phone at 4 AM, out of coverage, with the page not already open. An app owns **on-device cache persistence, background refresh while signal exists, and launch-to-answer with no network** — the same cache contract, a different shell. Web stays the surface of record; the app must not fork the advisory logic or gain a capability the web page lacks. Platform choice is **Open Decision 7**. |

## 13. Phase 4 — Production posture

| ID | Item |
|---|---|
| **F-15** | LangGraph for orchestration **at the periphery only**. The safety override stays plain, unit-tested Python — permanently. This is a P3 boundary, not a migration. |
| **F-16** | Structured output validation at the LLM boundary. R-41's closed-set discipline is already this pattern by hand; a library may replace the mechanics but never the re-validation. |
| **F-17** | Real deployment target: a state fisheries department or coastal district administration, as public safety infrastructure. |
| **F-18** | Multi-hour forecast series → genuine `return by <time>` (unblocks the §8 gap and fixes the contract example). |

## 14. Permanent non-goals

Not "later" — **no**, unless a principle in §3 changes first.

- **An LLM anywhere in the decision path.** Selection and phrasing only, forever (P3, P9).
- **A model originating a marine number, a place, a risk level, or a verdict** — including inside a field labelled "parse" (P9).
- **Synthetic, mocked or interpolated marine data**, including "reasonable" gap-filling (P1).
- **Silent degradation of any kind** — a stale reading, a blind agent, a narrowed question, or an enhancement that quietly turned itself off (P7, P10).
- **Free user text stored or replayed into a prompt** (P11).
- **Blended multi-agent output** that hides a contradiction (P5).
- **Requiring a new device**, a login, or that a fisherman learn something new. The roadmap plugs into gear boats already carry.
- **Any number rendered without provenance** (P2).
- **A hypothetical value entering the evidence store**, being returned as a measurement, or being rendered without its hypothetical marking (P8).
- **A sandbox that answers from a different code path than a real query.** If the counterfactual doesn't run through the same agents and the same `resolve()`, it proves nothing and is worse than not shipping it.

Out of scope for the **current phase** specifically (not permanent): mobile app (committed as F-28), live Tamil ASR, a Jac/Mojo rewrite, accounts/database, training any model.

---

## 15. Open decisions

| # | Question | Needed by |
|---|---|---|
| 1 | Is `Miscellaneous` the correct SIH 2026 theme for this ISRO PS? | Submission |
| 2 | Who holds institutional credentials for MOSDAC/INCOIS registration? | Phase 2 start |
| 3 | Do the ten fixed zones become a config file, a grid, or user-drawn areas? | F-4 |
| 4 | Does confidence become a computed multi-source agreement score, or stay per-source metadata? | F-9 |
| 5 | Which hardware partner for NavIC receiver access before the finale? | Phase 3 |
| 6 | **Which coupled-variable relationships are defensible enough to encode in the sandbox** (wind↔wave especially), and which combinations must the system refuse to model rather than fake? Answering "all of them" reintroduces synthetic data through the back door. | F-22 |
| 7 | **App platform for F-28** — PWA (reuses the existing page, no store, weakest offline guarantees) vs. native/Flutter (real background refresh and on-device cache, a second codebase to keep from forking the logic)? | Phase 3 |
| ~~8~~ | **RESOLVED 2026-08-27 — a fourth action, `CANNOT ASSESS`.** ORCA's thesis is that it can defend its answer, and "I have no evidence for this zone" is a defensible answer — the one §1.3 exists for. `DO NOT GO` was rejected because it buys client safety by conflating *danger* with *ignorance*, and teaches users to discount the one verdict that must never be discounted. The fail-open risk this creates for existing clients is real and is handled by the amended R-25, not ignored. See R-39, R-39a, R-39b. | ✅ done |
| 9 | **Should `geofence_agent` take a location argument** rather than inferring one from `observations[0]` (R-36)? It would break the uniform `list[MarineObservation] → Finding` signature that R-5 makes a virtue. The alternative — a synthetic position-carrying observation — is worse. | F-29 |
| 10 | **Is a hard deny that was successfully rerouted still a `DO NOT GO`** for the control room, even though the fisherman is being sent somewhere safe (R-38)? | F-29 |

---

## 16. Change control

1. **§3 principles change only by explicit decision**, recorded here with a date and a reason. They are the product.
2. **Requirements (R-\*, N-\*) change here first**, then in code. A requirement without a passing test is not Met — and its status column must say so rather than the prose implying otherwise.
3. **`orca/schema.py` and `orca/policy.py` are frozen** (N-5). Modifying them requires re-running the schema and policy suites plus the mutation check in `tests/test_policy.py`.
4. **New API fields must be additive** (R-25) and land in `API_CONTRACT.md` in the same change.
5. **Anything that would fabricate a number is rejected outright**, regardless of how much better the demo would look. That trade has already been made, permanently.
6. **The sandbox (§10B) is the one place fabricated numbers exist**, and only as hypotheses under P8. Any change that lets a hypothetical value cross into evidence — by type, by cache write, by unmarked rendering, or by a shared `source` string — is a P1 violation and is rejected, not reviewed.
7. **Any new file that touches the network requires an N-7 amendment**, recorded here with a date, a reason, and a stated fail-closed guarantee — as `orca/agentic.py` was on 2026-08-27.

---

## Appendix A — Change log

Recorded per §16.1 and §16.2. Nothing below was changed silently.

### v2.1 — 2026-08-27

**Open Decision 8 resolved: a zone with no evidence returns `CANNOT ASSESS`.** A fourth `action` value, produced in
`orca/planner.py` so `orca/policy.py` stays frozen (N-5), rerouting to a genuine alternative where one exists.
`DO NOT GO` was the safer-looking option and was rejected: it conflates danger with ignorance, and a `DO NOT GO` that
sometimes means "no satellite pass today" is a `DO NOT GO` fishermen learn to discount. Requirements **R-39**,
**R-39a**, **R-39b**; gate **G-13**.

**R-25 amended** to cover **enum widening**, not just new fields. A new value in an existing enum is *not* additive —
a client switching on `action` falls to its default branch, and a "proceed" default turns the widening into a
fail-open. This was the precise hole Decision 8 would have fallen through. Widening now requires a version note in
`API_CONTRACT.md`, the safe interpretation stated in words, and every known consumer checked.

### v2.0 — 2026-08-27

**Principles added.** **P9** (the model selects, never originates), **P10** (every enhancement fails closed, out loud),
**P11** (nothing the user typed is stored or replayed). All three describe invariants `orca/agentic.py` and
`orca/memory.py` already enforce; v1.1 predates both files. P7 was widened from staleness alone to cover missing
evidence and narrowed questions.

**Architecture redrawn (§4).** The v1.1 diagram was inaccurate in five ways: it drew `orca/schema.py` as a pipeline
stage (it is a type constraint on every arrow), placed `orca/api.py` *below* the policy (it sits above the planner and
calls down), showed no per-zone fan-out (all 10 zones run, not just the queried one), omitted the bathymetry and IMBL
paths (which bypass the safety cascade entirely), and drew no network boundary at all — the single most important fact
about the system. §4 is now three diagrams: trust zones, ingest, and the request path.

**Requirements corrected.** Each of these asserted something the code does not do:

| Was | Now |
|---|---|
| R-10 "The system refuses to decide on no evidence" | Scoped to the function-level guarantee it actually is; the system-level claim moved to **R-39** and marked Open |
| R-19 "No code path may branch on a query string" | Contradicted R-14 by construction. Narrowed to what was always meant: no path may select a *response* by query text |
| §5.2 agent table: `ocean_state_agent` as risk-only | It is the second `suggests_go` source, and often the only overridden finding. Replaced with a full capability matrix |
| R-21 / N-6 "no network call in the request path" | `_is_reachable()` runs on every `/ask`. Recorded as **R-54**, Open |
| R-33 "reduces confidence, shows age" | Neither happens. Marked **Partial** with the specifics |
| R-11 "every non-GO decision carries `overridden`" | True only when something suggested go. Stated precisely |
| R-16 | Omitted the `chosen is not None` condition that keeps an empty zone from being offered as a safer alternative |
| R-22 | Never said where an observation id comes from. Now specifies the content hash |
| §5.1 source table | Variable lists were incomplete for both Open-Meteo sources; two sources were missing entirely |
| "1,200 lines", "4 zones", "Bay of Bengal, Nagapattinam→Chennai" | ~2,700 lines, 10 zones, the full Tamil Nadu coast |

**Requirements added.** R-34, R-35 (cache-tier isolation and the write-time synthetic-source guard — both already
implemented, both previously uncredited); R-36 … R-40 (the five gaps between claim and code); R-41 … R-49 (the agentic
layer); R-50 … R-53 (memory); R-54 (probe placement); R-55 (mock marking); R-56 … R-58 (zone identity, the `history`
field, the agentic display flag); N-7 (exactly two files may touch the network). Existing R-numbers keep their v1.1
meanings; every addition took a new number.

**Gates added.** G-10 (fail-closed), G-11 (memory injection), G-12 (the confident gap). Every gate now carries a
last-verified date instead of two of nine carrying test counts.

**§8 gained four unrecorded gaps:** the no-evidence `GO`, the first-not-worst hazard naming, `geofence_agent`'s
unreachability across all 10 zones, and the unmarked `?mock=1` render.

**Status columns added throughout.** Of the 58 requirements in Part 1: 48 are **Met**, two are **Partial** (R-33,
R-45 — plus N-4, N-6 and N-7 among the non-functionals), and **eight are Open**: R-36, R-37, R-38, R-39, R-40, R-49,
R-54, R-55. Roadmap item F-29 tracks all eight. None is a new problem introduced by this revision — they are the ones
v1.1 asserted as Met.

---

## Appendix B — Vocabulary and data dictionary

### B.1 Marine variables

Every one is a `MarineObservation` (R-1). Names are exact: they are the strings agents match on, the strings
`orca/memory.py`'s `LOOKUP_VARIABLES` validates against, and the strings that appear in an API response.

| `variable` | Unit | Source | Read by |
|---|---|---|---|
| `wave_height_m` | m | Open-Meteo Marine | `hazard_agent` — **the hard-deny variable** |
| `wave_period_s` | s | Open-Meteo Marine | *no agent* — lookup only |
| `wave_direction_deg` | ° | Open-Meteo Marine | *no agent* — lookup only |
| `sst_c` | °C | Open-Meteo Marine | `eo_satellite_agent`, `ocean_state_agent` |
| `ocean_current_velocity_kmh` | km/h | Open-Meteo Marine | `ocean_state_agent` (reported, not scored) |
| `ocean_current_direction_deg` | ° | Open-Meteo Marine | *no agent* — lookup only |
| `wind_speed_kmh` | km/h | Open-Meteo Forecast | `weather_agent` — **the risk_level variable** |
| `wind_gusts_kmh` | km/h | Open-Meteo Forecast | *no agent* — lookup only |
| `precipitation_mm` | mm | Open-Meteo Forecast | `weather_agent` (fallback for `rain_mm`) |
| `rain_mm` | mm | Open-Meteo Forecast | `weather_agent` (reported, not scored) |
| `chlorophyll_mg_m3` | mg m⁻³ | NOAA CoastWatch VIIRS | `eo_satellite_agent` — **the opportunity variable** |

**Four variables are cached but read by no agent.** That is deliberate, not dead weight: they are real readings a
fisherman can ask for directly (`answer_kind: "data_lookup"`, R-46), and widening the *decision* to use them would be
a policy change under §16. Do not quietly wire one into an agent.

Two values are **not** `MarineObservation`s and never reach the safety cascade:

| Value | Shape | Role |
|---|---|---|
| `elevation_m` | grid of `{lat, lon, elevation_m}` | Map context only. Positive up: positive is land, negative is depth. |
| IMBL geometry | list of coordinate segments | Geofence context only, read by `geofence_agent` for proximity. |

### B.2 Glossary

| Term | Meaning |
|---|---|
| **SIH** · **PS** | Smart India Hackathon; Problem Statement (ours is SIH26176, ISRO) |
| **PFZ** | Potential Fishing Zone — ISRO/INCOIS advisories on where fish are likely |
| **SST** | Sea surface temperature |
| **IMBL** | International Maritime Boundary Line — here, the India–Sri Lanka treaty boundary. Crossing it is how fishermen get detained. |
| **MPA** | Marine Protected Area. Ours is the Gulf of Mannar Marine National Park (Krusadai Island). |
| **Douglas sea scale** | Century-old maritime sea-state scale; degree 4→5 (Moderate→Rough) sits at 2.5 m, which is where `hazard_agent` hard-denies |
| **ERDDAP** | NOAA's open data server protocol — how we query VIIRS and ETOPO without a key |
| **VIIRS** | Visible Infrared Imaging Radiometer Suite — the satellite instrument behind our chlorophyll readings |
| **ETOPO 2022** | NOAA NCEI global relief model — our seafloor bathymetry |
| **NRT** | Near-real-time. NRT satellite products lag and are frequently cloud-masked, which is why chlorophyll is often absent (R-4). |
| **bbox** | Bounding box — the lat/lon rectangle a query covers |
| **MOSDAC** | ISRO's Meteorological & Oceanographic Satellite Data Archival Centre. Account-gated; not integrated (F-6). |
| **INCOIS** | Indian National Centre for Ocean Information Services — the authoritative national ocean-state source (F-7) |
| **NIOT** | National Institute of Ocean Technology — operates the moored buoy network |
| **DGLL** | Directorate General of Lighthouses & Lightships — runs India's NAVTEX stations |
| **NAVTEX** | Maritime safety broadcast over MF radio, 250 nm range, no user fees (F-11) |
| **NavIC** | ISRO's regional satellite navigation system; already broadcasts alerts to 40,000+ vessels (F-10) |
| **NMEA 0183 / 2000** | The standard serial protocols marine electronics speak — how a fish-finder's sensors could be read (F-12) |
| **WDPA** | World Database on Protected Areas — holds the exact MPA boundary we could not obtain without an account |
| **MCP** | Model Context Protocol — lets another AI system call ORCA's reasoning as a tool |
| **Groq** | The inference provider behind the optional agentic layer. Unset the key and ORCA is fully deterministic (R-44). |

---

*"A good catch never outranks a safe return home."*
