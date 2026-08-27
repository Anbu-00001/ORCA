# ORCA — 32-HOUR WAR PLAN (v3)
### Team ICARUS · SIH26176 · ISRO · Software
### Claude Code writes the code. You own correctness.

---

## 0. WHAT CHANGED IN THIS VERSION

v2 assumed humans typing. They aren't. That changes three things:

1. **Build time collapses.** A working end-to-end system can exist by T-20 instead of T-14. The extra six hours go into rehearsal, hardening and the MCP wrapper — not more features.
2. **Verification becomes the bottleneck.** The risk is no longer "can we write it" but "do we know what we shipped." Section 3 is now the most important section in this document.
3. **Teammates stop being a build dependency entirely.** They can't block you, because they're not writing the system. Their jobs move to deck, QA, rehearsal and logistics — which is where they were always most useful anyway.

**The new central risk:** 4,000 lines of plausible-looking code that nobody has read, with a silent fallback to synthetic data, discovered on stage when a judge asks where a number came from.

---

## 1. WHAT WE'RE JUDGED ON

Internal selection round. Faculty judges, not ISRO scientists.

| What they check | Weight | How we win |
|---|---|---|
| Is anything actually working? | **Highest** | A live demo, however small |
| Do they understand the problem? | High | Name real fisher problems and real existing systems |
| Can they survive Q&A? | High | Section 11 |
| Distinguishable from other teams? | High | Section 2 |
| Feasible by December? | Medium | Show the roadmap, admit what isn't built |

Most teams show only slides. A working demo puts us in a different category. That's the whole strategy.

---

## 2. THE PITCH — MEMORISE

### One-liner
> ORCA gives a fisherman one answer, with the evidence behind it, even when he has no signal.

### 30 seconds
> India already spends crores generating marine data. ISRO's satellites map fishing zones. INCOIS forecasts waves. The lighthouse department broadcasts weather warnings hundreds of kilometres out to sea. It all exists, and it's all free.
>
> But a fisherman at 4 AM doesn't want six portals. He wants one answer: *where do I go, and when do I come back?*
>
> ORCA is the reasoning layer that reads those sources, resolves them when they disagree, and returns one recommendation — with every number traceable to who said it and when.

### The differentiator
> Existing apps *deliver data*. ORCA *makes a decision*, and shows its work.

### Three things that make us not-another-app
**1. It reasons, it doesn't display.** Others show a wave-height chart. ORCA decides whether that wave height means go or stay, for this boat, at this position.

**2. It resolves conflicts deterministically.** Ocean State says "good fishing here." Hazard says "3.1 m waves there." A chatbot blends them. ORCA has a **hard safety rule in code, not in a prompt: the safer option wins** — and it says it overrode the opportunity.

**3. Every number has a source, timestamp and confidence.** Nothing comes from model memory. If we can't trace it, we don't say it.

### Closing line
> A good catch never outranks a safe return home.

---

## 3. DRIVING CLAUDE CODE — THE CORE DISCIPLINE

This is now the section that decides the outcome. Read it twice.

### 3.1 The one thing that will actually go wrong

Claude Code writes code that runs. That is not the same as code that does what you think.

The specific failure that would kill this demo:

```python
try:
    data = fetch_from_open_meteo(bbox)
except Exception:
    data = generate_sample_data(bbox)   # ← looks fine, demo works, claim is false
```

Your entire pitch is *"every number is traceable to a real source."* A silent fallback to synthetic data makes that a lie, and you won't know until a judge asks "where did 3.1 metres come from?" and you can't answer.

**Mandatory rule, put it in CLAUDE.md:** no synthetic or placeholder data anywhere. If a source fails, the code raises loudly and the observation is absent. An absent reading is honest; a fabricated one is fatal.

### 3.2 Other Claude Code failure modes to watch for

| Failure | What it looks like | How you catch it |
|---|---|---|
| **Fabricated API parameters** | Open-Meteo params that don't exist; looks plausible | Print the actual response, not the parsed object |
| **Tests that assert nothing** | `assert result is not None` | Read every test. Break the code deliberately; the test must fail. |
| **Silent schema drift** | Adds a field, changes a name, downstream still "works" | `git diff` on schema.py before every commit |
| **Swallowed exceptions** | `except: pass` | grep for it. Ban it in CLAUDE.md. |
| **Over-abstraction** | Factory classes and plugin registries for five functions | Reject it. Ask for the boring version. |
| **Helpful refactors** | Rewrites something that worked | Freeze files once green. Say so in the prompt. |
| **Confident summaries** | "I've implemented and tested the fetcher" | Never believe a summary. Run it yourself. |

### 3.3 The loop

```
1. YOU state the acceptance check first, in one sentence
2. Prompt Claude Code for ONE file
3. RUN IT. Read real output, not the summary.
4. Wrong? Paste the actual error text back. Don't re-describe it.
5. Green? git commit immediately. Small commits are your undo button.
6. Next file.
```

**Never let Claude Code touch more than one file per prompt on the critical path.** When something breaks at hour 24, you need to know which commit did it.

### 3.4 CLAUDE.md — create this before anything else

Drop this in the repo root. It persists across sessions and stops drift.

```markdown
# ORCA — project rules for Claude Code

## Context
Marine advisory system for Indian fishermen. SIH 2026 prototype.
Demoed live on 28th. Reliability beats features. Boring beats clever.

## HARD RULES — never violate

1. NO SYNTHETIC DATA. Never generate, mock, simulate or fall back to
   placeholder marine data. If a source fails, raise loudly. An absent
   reading is correct; a fabricated one destroys the project's claim.
2. NO `except: pass`. No swallowed exceptions anywhere.
3. Every number shown to a user MUST be a MarineObservation carrying
   source, valid_time, confidence and provenance. Bare floats are dropped.
4. orca/policy.py contains NO LLM calls. Deterministic Python only.
   It is the project's safety guarantee and must be unit-testable.
5. Do not modify orca/schema.py or orca/policy.py once tests pass,
   unless explicitly asked.
6. No new dependencies without asking. No frameworks for five functions.
7. Prefer boring, readable code. No factories, no plugin registries,
   no premature abstraction.
8. The demo must run with NO network access. Everything reads from
   data/cache/. Any network call outside data/fetch.py is a bug.

## Stack
Python 3.11, FastAPI, plain HTML + MapLibre from CDN.
No build step. No database. JSON files on disk.

## Definition of done
A task is done when I have RUN it and seen correct output —
not when you report it as complete. Always show me the command to run
and the output you got.
```

### 3.5 Three-operator discipline

Three of you have Claude Code. That's leverage, but concurrent AI-generated edits to the same repo is how you get a merge disaster at hour 25.

**Rules:**
- **One repo, one merger.** You. Nobody else merges to main.
- Others work on branches and only in **their own files** — never `schema.py`, `policy.py`, `planner.py`.
- Each operator's CLAUDE.md rules are identical. Copy the file, don't paraphrase it.
- **If any single operator's branch isn't green by its kill time, it's abandoned, not rescued.** Section 4.

---

## 4. ROLES

Fill in real names now.

### 🔴 YOU — critical path, sole merger

Own: `schema.py`, `policy.py`, `agents.py`, `planner.py`, `api.py`, `demo/scenarios.json`, `API_CONTRACT.md`, CLAUDE.md, all merges, the demo path.

**You must be able to run the entire demo alone if everyone else disappears.** Test that assumption at T-20 by running it on a fresh clone.

You do **not** do: deck design, screenshots, food, venue logistics.

---

### 🟡 OPERATOR 2 — data layer (branch: `data`)

**Deliverable:** `data/fetch.py` writing real observations to `data/cache/`.

**Kill time: T-24.**

**Your mechanical check:**
```bash
git checkout data && python data/fetch.py && python -c "
import json,glob
fs=glob.glob('data/cache/*.json'); assert fs,'no cache files'
d=json.load(open(fs[0])); assert isinstance(d,list) and d
req={'variable','value','unit','lat','lon','valid_time','source','confidence','provenance'}
assert not req-set(d[0]), f'missing {req-set(d[0])}'
srcs={o[\"source\"] for o in d}
assert not any('sample' in s.lower() or 'mock' in s.lower() or 'test' in s.lower() for s in srcs), f'SYNTHETIC DATA: {srcs}'
print(f'PASS — {len(d)} obs, sources: {srcs}')"
```
That last assertion is the important one. It catches the failure in 3.1.

**Fallback:** you prompt Claude Code for it yourself. With the contract already written it's about 20 minutes. **Don't spend an hour rescuing a branch.**

---

### 🟡 OPERATOR 3 — frontend (branch: `web`)

**Deliverable:** `web/index.html`, single self-contained page.

**Hard requirement:** built against `API_CONTRACT.md` and `web/mock_response.json` only. "I'm blocked on the backend" is not possible — that's what the contract is for.

**Kill time: T-16.**

**Check:** open with the mock. Answer renders? Evidence panel expands? Amber banner appears when `overrides` is non-empty? Thirty seconds.

**Fallback:** plain page with a formatted `<pre>` block and a static coastline image with markers. Ugly, works, demos fine.

---

### 🟢 QA / RUNNER — no code

- Open the deck in real PowerPoint, check every slide for overflow
- Screenshot every demo screen as it appears
- Record a screen capture at T-8
- **Test the demo on the actual presentation laptop**
- Deck to two USBs and cloud
- Food, charger, venue recce

---

### 🟢 PRESENTER — no code

- Memorise section 2 word-perfect
- Memorise section 12 (prior art)
- Rehearse section 9 eight times, twice on the real laptop
- Prepare every answer in section 11

**He is asleep at 4 AM, not debugging.** Strategic decision, not kindness. With Claude coding, he can rehearse against a real build from T-20 — a big advantage over v2.

---

## 5. TECH STACK — VERDICTS

You asked about Jac/Jaseci, Mojo, Zig, JAX.

**Jac / Jaseci — real, interesting, wrong week.** AI-native language, Python-like syntax, compiles to Python bytecode / JS / native. Its `by llm()` construct replaces a function body with an LLM call; byLLM supports tool calling and MCP. Actively shipping through July 2026. Their argument — that agents produce better code in Jac because glue disappears and cross-tier mistakes become compile errors — is *directly relevant* to a Claude-Code-driven build.

**Verdict: NO this week.** Claude Code is far more reliable in Python, where its training data is deepest. Jac debugging at hour 20 with sparse community answers is unforced risk. **But** `byllm` installs as a plain Python library — that's the low-risk door, after the review.

**Mojo — NO.** Compute-bound performance language. We're I/O-bound.
**Zig — NO.** Systems language, no AI ecosystem here.
**JAX — NO.** Training and autodiff. We train nothing.

**Core stack:** Python 3.11 + FastAPI + plain HTML/MapLibre. No build step, no database.

**Agent orchestration — no framework for the core.** LangGraph is the 2026 standard for production agent systems and belongs on slide 3 as our production path. But our orchestration is five function calls and one policy check. A framework adds a learning curve and failure modes for a problem we don't have.

**Say this if asked** — it's a strength:
> "The safety policy is deliberately not in an agent framework. It's deterministic Python with unit tests, because a safety rule that can be prompted out of a decision isn't a safety rule. LangGraph is our production orchestration path, but the override logic stays as verifiable code."

Use **PydanticAI** only at the LLM boundary — parse the query in, write the explanation out. Typed outputs, minimal ceremony.

---

## 6. CONNECTORS, MCP SERVERS AND PLUGINS

### 6.1 Install these into Claude Code before you write anything

Real force multiplier: Claude can query live marine data *while building*, so adapters get written against real response shapes instead of guesses. This directly prevents the fabricated-parameter failure in 3.2.

| Server | Gives you | Key |
|---|---|---|
| **weather-mcp** (`weather-mcp/weather-mcp`) | 17 tools — marine (wave height, swell, currents, Douglas Sea Scale), alerts, radar, lightning, rivers, history to 1940. MIT, 2,520 tests, NOAA + Open-Meteo. **Safety-graded output, not raw numbers** | **None** |
| **open-meteo-mcp** (`cmer81/open-meteo-mcp`) | Full Open-Meteo: marine, ERA5 archive, geocoding, elevation, GFS | **None** |
| **NOAA Marine MCP** | CO-OPS tide stations, NDBC buoys, water levels | None |
| **qgis-mcp** (`nkarasiak`) | 117 QGIS tools | None |

**Registry:** `sparkgeo/geo-mcp-servers` — curated, health-checked (54 active as of 14 Aug 2026).

```bash
claude mcp add weather --command "npx" --args "-y" "weather-mcp"
```

Then, before writing `fetch.py`:
> "Using the weather MCP, get marine conditions for lat 11.0 lon 79.9. Show me the exact raw response shape."

Now the adapter is written against reality. `weather-mcp` also produces **safety-graded assessments with plain-language recommendations** — the same move ORCA makes. Read how they structure it; it will sharpen your hazard agent.

### 6.2 ⭐ Expose ORCA *as* an MCP server

Roughly 40 lines with the Python MCP SDK. With Claude coding, it's a 30-minute job — and it's the highest differentiation-per-minute available.

On stage:
> "ORCA also speaks the Model Context Protocol. Any AI assistant — Claude, a government helpdesk bot, a district control-room dashboard — can query our reasoning layer as a tool. We're not just an app; we're marine decision-making as callable infrastructure."

**Do it at T-14 if the core is green. Skip if not.**

### 6.3 Marine data connectors

| Library | Use | Auth |
|---|---|---|
| **Open-Meteo Marine API** | Wave height, period, direction, SST. **Primary — works now** | None |
| **`erddapy`** | Python client for any ERDDAP incl. NOAA CoastWatch | None |
| **`copernicusmarine`** | Official toolbox, `open_dataset()` → xarray. **No quotas on volume or bandwidth** | Free account |
| **`xarray` + `netCDF4`** | NetCDF/GRIB — what MOSDAC and INCOIS ship | — |
| **OpenDrift** | Open-source ocean drift model, reads Copernicus currents directly | None |

**OpenDrift is worth one roadmap sentence** — it's the established tool for "where will this drift" (search and rescue, man overboard). Naming it shows you know the field.

### 6.4 For the roadmap slides
- **ISRO NavIC Messaging** — broadcasts INCOIS PFZ and cyclone alerts; 40,000+ vessels; ISRO publishes the Signal-in-Space ICD
- **DGLL NAVTEX** — 7 stations, 518 kHz English / 490 kHz local languages, 250 nautical miles, no user fees
- **DGLL lighthouses** — 205 total, 87 already AIS shore stations
- **NIOT OMNI buoys** — ~12 moored buoys feeding INCOIS, used in satellite cal/val
- **NMEA 0183 / 2000** — standard port on marine electronics; depth sounders report sea temperature over it

---

## 7. SCOPE

### ✅ BUILD
1. Backend pulling **real marine data** for one stretch of coast
2. Normaliser → every reading becomes a `MarineObservation`
3. Five agents, each answering one question
4. **Safety policy** resolving contradictions and logging overrides
5. API returning one recommendation + its evidence
6. One web page: map, answer, expandable evidence panel
7. **Offline toggle** proving it works with the network off

### 🟨 STRETCH — only if core is green at T-14
8. MCP server wrapper (6.2)
9. Slide 2 diagram redrawn to show the conflict

### ❌ DO NOT BUILD
Flutter app · NavIC/LoRa hardware · live Tamil ASR · Jac/Mojo rewrite · login/accounts/database · training anything · visual polish before T-8.

Everything else goes on a whiteboard called **LATER**.

---

## 8. BUILD SPEC

### 8.1 Repo
```
orca/
├── CLAUDE.md               # §3.4 — FIRST FILE YOU CREATE
├── API_CONTRACT.md
├── data/
│   ├── cache/              # real observations only
│   └── fetch.py            # Operator 2
├── orca/
│   ├── schema.py           # you
│   ├── policy.py           # you — the innovation
│   ├── agents.py           # you
│   ├── planner.py          # you
│   └── api.py              # you
├── web/index.html          # Operator 3
├── demo/scenarios.json     # you
├── tests/
└── SCRATCH.md              # one line per surprise
```

### 8.2 Data — the critical decision

**Do not block on MOSDAC or INCOIS.** Registration may not approve in 32 hours. Register anyway (good deck line), build against no-key sources:

- **Primary: Open-Meteo Marine API** — wave height, period, direction, SST
- **Secondary: NOAA CoastWatch ERDDAP** via `erddapy`
- **If time: Copernicus Marine** — free account, no quotas
- **MOSDAC / INCOIS:** deck, screenshots, roadmap

**Stage line:**
> "For the prototype we're on open marine APIs so we could iterate fast. MOSDAC and INCOIS registration is in progress. The adapter layer is source-agnostic — swapping them in is a config change, not a rewrite."

That's a strength, and it's exactly what slide 3's Data Broker claims.

**The demo reads only from `data/cache/`. It must never make a network call during judging.**

### 8.3 Schema

```python
@dataclass
class MarineObservation:
    variable: str          # "wave_height_m", "sst_c", "chlorophyll_mg_m3"
    value: float
    unit: str
    lat: float
    lon: float
    valid_time: datetime   # what time this reading is FOR
    fetched_at: datetime   # when we got it
    source: str            # "Open-Meteo Marine", "NOAA ERDDAP", "boat echo sounder"
    confidence: float
    freshness_min: int
    provenance: str        # exact URL or dataset id
```

**Enforced:** any number reaching a user must be one of these. Make it a real assertion.

### 8.4 Safety policy — THIS IS THE DEMO

```python
def resolve(findings: list[Finding]) -> Decision:
    for f in findings:                          # 1. hard denials win outright
        if f.hard_deny:
            return Decision("DO NOT GO", reason=f.reason,
                            overridden=[x for x in findings if x.suggests_go])

    opportunity = [f for f in findings if f.suggests_go]
    danger      = [f for f in findings if f.risk_level >= 0.6]

    if opportunity and danger:                  # 2. safety beats opportunity
        return Decision("SAFER ALTERNATIVE",
                        chosen=safer_of(opportunity, danger),
                        overridden=opportunity,
                        explanation="Opportunity overridden by hazard")
    ...
```

**Hardcode into `demo/scenarios.json`:**
> Ocean State: *"Zone A — 28.4 °C, high chlorophyll, strong aggregation, conf 0.86"*
> Hazard: *"Zone A — significant wave height 3.1 m, conf 0.71"*
>
> **Output:** *"Do not go to Zone A. Go to Zone B — about 40% lower expected catch, wave height 1.4 m. Return by 4 PM."*
> Visibly: *"Overridden: Ocean State recommendation, on wave-height hazard."*

**Verification that matters:** deliberately flip the wave height to 1.0 m and confirm the recommendation changes to Zone A. If it doesn't, the policy isn't wired in and you're demoing a hardcoded string.

### 8.5 Frontend + the Tamil decision

Map left 60%. Right 40%: answer card (large), evidence panel (expandable to source/timestamp/confidence), amber override banner (only when `overrides` non-empty), offline toggle top-right.

**Tamil — live ASR is a coin flip you can't afford.**
- **Primary: text box.** Always works.
- **Voice: "play sample query"** — pre-recorded Tamil audio with pre-computed transcription. Identical to a judge, cannot fail.
- Live ASR only if reliable by T-10.

If asked: *"That's a recorded sample for demo reliability — the pipeline behind it is real, here's the transcription step."*

**Offline tiles:** pre-download for the one region, or static coastline image with markers.

### 8.6 Offline mode

The toggle must be **visible**: badge flips to "OFFLINE — cached evidence", each item shows its age, confidence drops, and a line appears: *"Degraded mode — evidence older than live, confidence adjusted."*

**Physically turn wifi off during the demo.** Don't simulate it.

### 8.7 Prompts

**Prompt 0 — scaffold. First thing you run:**
```
Create the repo skeleton in section 8.1 of my plan. Create CLAUDE.md with
exactly the content I paste below — do not edit or improve it. Create empty
placeholder files for the rest. Then stop and show me the tree.

[paste §3.4]
```

**Prompt 1 — schema + policy. You, alone, before assigning anything:**
```
Read CLAUDE.md first and follow it exactly.

orca/schema.py: MarineObservation dataclass — variable, value, unit, lat, lon,
valid_time, fetched_at, source, confidence (0-1), freshness_min, provenance.
Add to_dict() and a validator that RAISES on missing source or valid_time.

orca/policy.py: Finding dataclass (agent_name, suggests_go, risk_level 0-1,
hard_deny, reason, observations) and resolve(findings) -> Decision.

Rules in strict order:
1. any hard_deny -> "DO NOT GO", return immediately
2. any suggests_go AND any risk_level >= 0.6 -> "SAFER ALTERNATIVE",
   record overridden findings
3. otherwise -> "GO"

Decision carries action, reason, chosen, overridden, explanation.
Write pytest tests covering all three paths PLUS a test that fails if
rule 2 is removed. No LLM calls in this file.

Show me the exact command to run the tests and the output you got.
```

**Prompt 2 — data (Operator 2):**
```
Read CLAUDE.md first. Rule 1 (no synthetic data) is absolute.

Write data/fetch.py for the Nagapattinam/Chennai coast
(lat 10.5-13.5, lon 79.5-81.5).

Primary: Open-Meteo Marine API (no auth) — wave height, period, SST.
Secondary: NOAA CoastWatch ERDDAP via erddapy.

Before writing the adapter, use the weather MCP to fetch one real response
and show me its actual shape. Do not guess parameter names.

Each adapter: a class with fetch(bbox, time_range) -> list[MarineObservation],
importing orca/schema.py. Write to data/cache/<source>_<date>.json.

If a source fails: log the real error and continue with others. NEVER
substitute generated data. Source strings must name the real provider.
```

**Prompt 3 — agents + API (you):**
```
Read CLAUDE.md. Using orca/schema.py and orca/policy.py unchanged, write
orca/agents.py with five functions, each list[MarineObservation] -> Finding:

eo_satellite_agent  - candidate zones from SST + chlorophyll
weather_agent       - wind/rain risk
ocean_state_agent   - zone quality from sea temp and currents
hazard_agent        - hard_deny if wave height > 2.5 m; scaled risk_level
geofence_agent      - hard_deny inside a hardcoded prohibited polygon

orca/planner.py: query -> agents over cached evidence -> policy.resolve()
-> structured answer.

orca/api.py FastAPI: POST /ask, GET /evidence/{id},
GET /health (offline_mode, cache_age_min). CORS on.
Every number in every response traces to a MarineObservation id.

Do not modify schema.py or policy.py.
```

**Prompt 4 — frontend (Operator 3):**
```
Read CLAUDE.md. Build web/index.html — one self-contained page, no build step,
MapLibre GL JS from CDN, plain CSS.

Read API_CONTRACT.md for the exact response shape. Until the API is live, load
web/mock_response.json. You must never be blocked on the backend.

Map left 60%. Right 40%: answer card (large), evidence panel (each number
expandable to source/timestamp/confidence), amber override banner shown ONLY
when overrides is non-empty. Offline toggle top-right calling GET /health.
Bottom: text input plus "Play sample Tamil query" loading a local audio file
and filling the input with its known transcription.

Plain and readable. Clarity over polish. Do not touch any file in orca/.
```

---

## 9. DEMO SCRIPT — 5 MINUTES

**[0:00–0:30]**
> "It's 4 AM in Nagapattinam. A fisherman is deciding whether to take his boat out. The data to answer that exists — ISRO has it, INCOIS has it. It's spread across six portals, in English, and 30 kilometres out he has no signal at all."

**[0:30–1:00]** "ORCA reads those sources and gives him one answer, with the evidence behind it. Let me show you."

**[1:00–2:00] Straightforward query.** Answer + zone on map. **Open the evidence panel.**
> "Every number here has a source and a timestamp. This wave height came from this forecast, issued at this time, with this confidence. Nothing came from a language model's memory."

**[2:00–3:15] The conflict — the money shot.** Run Zone A. **Pause. Let them read the amber banner.**
> "One agent found excellent fishing at Zone A. Another found 3.1 metre waves at the same spot. They contradict.
>
> ORCA didn't average them and didn't guess. A hard safety rule — code, not a prompt — says the safer option wins. So it sent him to Zone B: less fish, but he comes home.
>
> And notice it *told us what it overrode*. That audit trail is the difference between a chatbot and something a government can deploy."

**[3:15–4:00] Offline.** Physically switch off wifi. Show them.
> "Still works. Cached evidence, reduced confidence, and it says the data is three hours old rather than pretending it's live."

**[4:00–5:00] Where it goes**
> "In the full build this connects to three things India already runs and nobody has joined. NavIC already broadcasts fishing-zone and cyclone alerts to boats far out at sea. The lighthouse department already broadcasts weather warnings 250 nautical miles out, in local languages. And every boat's own fish-finder already measures sea temperature — nobody collects it.
>
> All three exist. All three are free. ORCA is the plug that joins them."

**Then stop talking.**

---

## 10. HOUR-BY-HOUR (revised for Claude-speed)

### T-32 → T-31 · SCAFFOLD (you, alone)
CLAUDE.md, repo skeleton, `API_CONTRACT.md`. Install weather MCP. Freeze the region: **Nagapattinam/Chennai, Bay of Bengal** — never changes.

### T-31 → T-29 · CORE (you, alone)
`schema.py` + `policy.py` + tests. **Do not assign anything until these are green.** Verify by deliberately breaking rule 2 and confirming a test fails.

### T-29 → T-28 · BRIEF THE TEAM
Branches, task cards, kill times in writing. Copy CLAUDE.md to both operators verbatim.

### T-28 → T-24 · PARALLEL
You: agents + planner + api. Op2: fetcher. Op3: frontend on mock. Presenter: memorising. **T-24: Op2 kill time.**

### T-24 → T-20 · FIRST INTEGRATION
Real data → agents → policy → API → page. **T-20 gate: fresh clone, `pip install -r requirements.txt`, run the demo. Does it work?** If yes you're ahead of schedule and the presenter starts rehearsing on the real build. If no, cut scope now.

### T-20 → T-13 · SLEEP, STAGGERED
Presenter + QA + one operator sleep the full block. You and one other sleep the second half. **Awake time is integration, not features.**

### T-13 → T-8 · HARDEN
Offline toggle, tested with wifi physically off. Conflict scenario verified by flipping the wave height. **T-16: Op3 kill time.** MCP wrapper and slide 2 diagram only if core is green. Presenter rehearses on the real build. **T-8: full path runs twice clean.**

### T-8 → T-4 · FREEZE
**Bug fixes only. No refactors — tell Claude Code explicitly.** Three dress rehearsals on the presentation laptop. Screen recording + screenshots. Deck to two USBs and cloud.

### T-4 → T-1 · REST
Yes, really.

### T-1 → T-0 · VENUE
Arrive early. Projector, laptop, power. Run once on venue wifi, once with wifi off. Open every tab before judges enter.

---

## 11. Q&A PREP

**Q: How is this different from SAMUDRA / Fisher Friend / Sagar Vani?**
> Those deliver data and need cellular coverage — adoption is around 4% of vessels. ORCA makes a decision instead of showing a dashboard, resolves contradictions with a hard safety rule, and is designed to keep working past coverage by riding broadcast channels that already reach boats offshore.

**Q: How do you stop the AI hallucinating a wave height?**
> The model never produces a number. It parses the question and writes the explanation. Every value is a structured observation carrying source, timestamp and confidence, and a validator raises on anything without them. The safety policy is deterministic code, not a prompt — it can't be argued out of a decision.

**Q: Are you connected to ISRO's data?**
> For the prototype we're on open marine APIs so we could iterate fast. MOSDAC and INCOIS registration is in progress. The adapter layer is source-agnostic — swapping them in is a config change, not a rewrite.

**Q: Did AI write this code?**
> Yes, with AI assistance, and we reviewed and tested it. The safety policy has unit tests including one that fails if the override rule is removed — that's the part we couldn't afford to get wrong, so it's the part we verified hardest.

*(Answer this one calmly and directly. Defensiveness is the only wrong answer.)*

**Q: Why not LangGraph / CrewAI?**
> LangGraph is our production orchestration path and it's on slide 3. But the safety override is deliberately plain Python with unit tests, because a safety rule that can be prompted out of a decision isn't a safety rule.

**Q: Fishermen won't adopt an app.**
> Correct, so we're not betting on adoption of a new device. Our roadmap plugs into gear boats already have — the fish-finder they own, and government broadcast channels their receivers already pick up. The design goal is that a fisherman learns nothing new.

**Q: What's built versus planned?**
> Built and running: evidence layer, five agents, safety policy, offline mode, this interface. Designed but not built: NavIC and broadcast integration — that needs hardware we'd source before the finale. We'd rather tell you exactly where that line is than blur it.

**Q: Why not just ChatGPT with a weather plugin?**
> It would give a confident number with no source and no way to check it, and no rule preventing it recommending a profitable but dangerous option. For a safety system, traceability and a hard override are the requirement, not features.

**Q: What if data is stale or missing?**
> It says so. Degraded mode shows the age of every reading, reduces confidence, and never implies certainty it doesn't have.

**Q: Who pays?**
> A state fisheries department or coastal district administration — public safety infrastructure. Second path: the in-situ readings we'd collect from boats are cal/val data ISRO and INCOIS currently pay to gather from moored buoys.

**Q: Six agents seems over-engineered.**
> They have to be able to disagree. One model produces a blended answer with no visible contradiction. Separating them is what lets us catch "good fishing here" contradicting "dangerous waves here" — and that catch is the whole safety argument.

**Q: Hardest unsolved part?**
> Getting data to a boat genuinely out of coverage for days. The broadcast channels are one-way — the boat receives but can't reply. The return path is the open problem, and we'd rather name it than pretend we've solved it.

**If you don't know:** *"I don't have that in front of me — can I follow up?"* One honest "I don't know" buys credibility for everything else.

---

## 12. PRIOR ART — NAME THESE UNPROMPTED

Highest-leverage page for the presenter.

| System | What it is | Why we differ |
|---|---|---|
| **INCOIS SAMUDRA** | Official INCOIS app — PFZ, ocean state, tsunami, 8 languages | Displays data; needs connectivity |
| **Fisher Friend (FFMA)** | MSSRF app, ~10,000 users | Tiny adoption vs ~264,000 vessels |
| **Sagar Vani** | INCOIS multi-channel dissemination | Broadcast, not decision-making |
| **mKRISHI Fisheries** | TCS PFZ advisories | Advisory delivery only |
| **NavIC Messaging** | ISRO broadcasts PFZ + cyclone alerts, 40,000+ vessels | **We plug into it, not compete** |
| **GEMINI / DAT-SG** | ISRO devices delivering alerts offshore | Transport. We're the reasoning layer above. |
| **INCOIS SMS** | ~7 lakh fishermen | One-way text, no reasoning |

**The framing sentence:**
> "India isn't short of marine data or delivery channels — it has excellent ones. What's missing is the layer that turns them into a single decision a fisherman can act on. We're not replacing SAMUDRA or NavIC. We sit on top of them."

---

## 13. FAILURE MODES

| If this breaks | Fall back to |
|---|---|
| Demo crashes on stage | **Screen recording**, already open in a tab |
| Projector won't connect | Screenshots inside the deck |
| Data fetch fails at venue | Reads from cache — handled by design |
| Map tiles don't load | Static coastline image with markers |
| Tamil audio fails | Type the query. "We also support voice." |
| Nobody knows an answer | "I don't have that in front of me, can I follow up?" |
| Presenter sick | Second speaker knows the script. **Pick them now.** |
| An operator's branch never lands | Section 4 fallbacks. Execute, don't rescue. |
| **Claude Code breaks something at hour 28** | `git reset --hard` to the last green commit. This is why commits are small. |

**T-8 checklist:**
- [ ] Screen recording saved locally, open in a tab
- [ ] Screenshots of every demo screen, in the deck appendix
- [ ] Deck as .pptx AND .pdf, two USBs + cloud
- [ ] **Fresh clone runs the demo** — proves nothing depends on your local state
- [ ] Demo runs with wifi off, tested
- [ ] `grep -rn "except:" orca/ data/` returns nothing
- [ ] `grep -rni "mock\|sample\|synthetic\|dummy" data/cache/*.json` returns nothing
- [ ] Charger. Both USBs.

---

## 14. DECK — REMAINING FIXES

Working file: `ICARUS-ORCA-SIH26176-v2.pptx`

Done: PS-ID, slide 2 title, duplicate footers, page numbers, SIH badge on all six, slide 3 diagram with the boat adapter, slide 6 "WHAT WE PLUG INTO", citation strip.

**Still to do:**
1. Open in real PowerPoint, check every slide for overflow (3 and 4 especially)
2. Slide 2's agent diagram is a flat picture and doesn't show the conflict — our stated innovation isn't depicted
3. Add a real demo screenshot to slide 5 once the build works
4. Verify "Miscellaneous" is a valid SIH 2026 theme for this ISRO PS
5. Team name matches registration everywhere

---

## 15. FINAL CHECKLIST — T-2

- [ ] Demo runs end to end on the **presentation laptop**
- [ ] Demo runs with **wifi off**
- [ ] Conflict scenario triggers the amber banner
- [ ] Flipping the wave height changes the recommendation (proves the policy is live)
- [ ] Evidence panel shows source + timestamp for every number
- [ ] No synthetic data anywhere in `data/cache/`
- [ ] Deck opens in PowerPoint, no overflow, no "SIH176"
- [ ] Screen recording open in a tab
- [ ] Presenter rehearsed 8+ times
- [ ] Presenter can name all seven prior-art systems from memory
- [ ] Second speaker briefed
- [ ] Everyone slept 5+ hours in the last 24
- [ ] Charger and both USBs packed

---

## ONE LAST THING

Claude Code will happily generate a great deal of code very quickly. The temptation at T-8 will be enormous, because adding a feature will *feel* almost free.

It isn't. The cost isn't writing it — it's that nobody has read it, and it's now in the demo path.

**Freeze at T-8. Rehearse instead. Sleep instead.**

Good luck.
