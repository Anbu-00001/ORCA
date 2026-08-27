# SCRATCH.md — ORCA Build Log & Surprises

- Project initialized on 2026-08-26.
- Skeleton structure created following ORCA 32-Hour War Plan (v3).
- Verification complete: Clean commit history with single author.

## 2026-08-27 — 3D visualization build: real bathymetry source found; MOSDAC checked, blocked on auth

- Real seafloor relief source confirmed live and working:
  `oceanwatch.pifsc.noaa.gov/erddap/griddap/ETOPO_2022_v1_60s` (NOAA NCEI
  ETOPO 2022, 60 arc-second, "positive up" sign convention). Two other
  NOAA ERDDAP mirrors (`coastwatch.pfeg.noaa.gov`, `upwell.pfeg.noaa.gov`)
  timed out from this sandbox — don't assume any given NOAA ERDDAP host is
  reachable, check the specific one. Now wired up as
  `ERDDAPBathymetryFetcher` in `data/fetch.py`, cached to
  `data/cache/bathymetry/` (a *subdirectory*, deliberately — see that
  file's docstring for why it can't sit next to the point-observation
  cache files).
- Checked MOSDAC (ISRO's own ocean data portal) as a more India-specific,
  on-theme alternative/supplement, per teammate pointer:
  - `mosdac.gov.in`'s download API requires an authenticated account
    (`user_credentials` in a config file) for every dataset — no
    anonymous/public REST endpoint exists. Confirms what
    `MANUAL_TASKS.md` already flags: MOSDAC registration is a human task,
    not something scriptable in this session without credentials.
  - Their **Indian Mainland Coastal Product** (SARAL/AltiKa altimetry:
    sea surface height, significant wave height, wind speed, 5°N-24°N /
    68°E-90°E, ~180 m along-track) would be a genuinely better,
    India-specific replacement for some of what we currently pull from
    NOAA/Open-Meteo — but it's also login-gated, and its coverage is
    March 2013 - July 2016 (historical, not live), so it wouldn't replace
    the live wave-height feed used for GO/DO NOT GO decisions anyway.
    **Worth registering for post-hackathon**, not blocking anything now.
  - Also checked a teammate-suggested GitHub repo
    (`divya-m984/Oceanographic-Coral-reefs-preservation-and-prediction`):
    it's a coral-reef ML classifier trained entirely on **synthetic**
    sonar/sensor data (the authors say so themselves) and has no 3D/
    three.js visualization. Not usable here — would violate CLAUDE.md
    rule 1 if any of its data touched this repo, and there's no
    visualization technique to borrow either.

## 2026-08-27 — replacing the 4 mock zones with real ones: what was checked

Direct feedback: "four mock zones ... THIS IS JUST CRAP, WE NEED ACTUAL
DATA." Researched real, checkable coordinate sources for Tamil Nadu
coastal fishing locations before touching `data/fetch.py`.

- **CMFRI's GIS-based inventory of 1,278 fish landing centres (359 in
  Tamil Nadu)** — eprints.cmfri.org.in/13606/ — is the right dataset in
  principle (physically-verified GPS coordinates, purpose-built for
  exactly this), but the actual coordinate PDF is "Restricted to
  Registered users only." Not usable without emailing
  cmfrilibrary@gmail.com and waiting. A real, scriptable public dataset
  worth revisiting post-hackathon if that access comes through.
- **INCOIS Potential Fishing Zone WebGIS** (incois.gov.in/MarineFisheries/
  PfzWebGis, incois.gov.in/gisserver/PFZ/) — real, live, ~1223 advisory
  nodes along the whole Indian coast, refreshed daily. WebFetch on these
  pages returns only the page shell (client-side JS renders the actual
  map/data), so no backend API endpoint was discoverable without
  inspecting real network calls in an actual browser — not attempted.
  This is the single most valuable real-time data source ORCA could ever
  plug into (it's literally the same kind of advisory ORCA produces, from
  the government agency that does it for real) — worth a manual
  browser-network-tab investigation by a teammate, not something to
  guess at.
- **Protected Planet / WDPA API** (api.protectedplanet.net) — 401
  Unauthorized without a token; the website's search/download UI didn't
  expose a token-free path either. Used a real, specific, named feature
  instead (Krusadai Island, 9.20°N 79.17°E, sourced from its own
  Wikipedia article) rather than trying to approximate the Gulf of
  Mannar park's full ~160km boundary without real boundary data — see
  `orca/agents.py`'s `PROHIBITED_ZONE` comment.
- **What worked:** every zone's coordinate is sourced from its own
  Wikipedia article (a couple from the more specific *fishing harbour*
  article, e.g. Rameswaram: 9.2811°N 79.3151°E specifically for "Rameswaram
  Fishing Harbour and Boat Jetty", not just the town). Verifiable,
  citable, real — just not from a single authoritative GIS dataset the
  way CMFRI's would have been.
- **OpenFreeMap** (openfreemap.org) confirmed working with zero
  friction: no API key, no signup, `https://tiles.openfreemap.org/styles/
  liberty` returns a real styled vector basemap. Replaced
  `demotiles.maplibre.org` in `web/index.html`.

## 2026-08-27 — verifying the "Authenticity & Feature Upgrade Plan" doc's endpoints

A teammate-sourced research doc claimed several Indian government API
endpoints as ready to use. Verified each one directly rather than trusting
the doc (it says to do this itself) -- two of its central claims turned
out to be wrong or incomplete in ways worth recording before anyone
builds on them:

- **`erddap.incois.gov.in`** (INCOIS's own ERDDAP -- the single biggest
  win the doc proposes) is real and live -- `curl -k` confirms a genuine
  INCOIS-issued cert (`CN=*.incois.gov.in`, issued by GlobalSign) -- but
  the server has a TLS chain misconfiguration: it doesn't send the
  GlobalSign intermediate certificate, so standard verification fails
  with "unable to get local issuer certificate". This is NOT something
  to work around with `verify=False` (a real security regression, ruled
  out) -- it needs either the missing intermediate bundled explicitly
  (attempted; `secure.globalsign.com` was itself unreachable from this
  sandbox to fetch it) or for INCOIS to fix their server. Blocked, not
  abandoned -- retry from an environment that can reach GlobalSign's
  cert repository.
- **`api.imd.gov.in`** (IMD's marine/cyclone/nowcast API) -- the doc
  claims "No key required on the endpoints I checked." Every single
  endpoint tested, including the most basic (`cityforecast`), returned
  `401 {"error":"API key missing"}`. The 28KB public API reference page
  contains zero mention of "key", "register", "contact", or "apisetu" --
  no visible self-service path to get one. Real blocker, needs a human to
  find IMD's actual key-issuance process (possibly a direct request to
  IMD, not a web form) -- added to MANUAL_TASKS.md.
- **Confirmed working, no auth needed:** Marine Regions WFS
  (`geo.vliz.be`, real India-Sri Lanka IMBL treaty-line geometry -- now
  wired into `orca/agents.py`'s `geofence_agent`, see below) and
  INCOIS's PFZ text advisory page (`incois.gov.in/MarineFisheries/
  TextDataHome`, real "Forecast Date"/"Valid upto" fields confirmed in
  the HTML) -- not yet ingested into the app, just verified reachable.
- **Google Fonts family names, verified before use, one was wrong as
  written:** "Archivo Condensed" is not a real family -- Archivo is a
  variable font and "condensed" is its `wdth` axis, requested as
  `family=Archivo:ital,wdth,wght@0,75,700` and applied via CSS
  `font-stretch: condensed`, not a separate `font-family` name. "Noto
  Sans Tamil UI" (which sounded like it might be an OS-only naming
  convention) is in fact a real, distinct Google Fonts family --
  confirmed by fetching its actual `@font-face` CSS.
- **A real bug caught before shipping:** the first draft of the Douglas
  sea scale table in `web/index.html` was off by one band -- it had the
  2.5 m boundary sitting between "Slight" and "Moderate" instead of the
  real "Moderate" and "Rough", which would have directly contradicted
  the whole point of the feature (that `WAVE_HARD_DENY_M` sits exactly
  on the real Douglas 4/5 boundary). Caught by re-checking the rendered
  band name for a known input (3.1 m) against the WMO table before
  trusting the first draft.

## 2026-08-27 — chatbot layer research: confirmed it's substring matching, not NLU

Direct feedback: the fishermen/researcher "chatbot" is "MOCK BASED ON THE
LOCATION WE TOUCH." Verified against the actual code before proposing
anything (research only, no implementation — explicit instruction).

- **Confirmed, precisely:** `resolve_zone_from_query()`
  (`orca/planner.py:78-84`) lowercases the query and checks whether any of
  the 10 zone names appears as a substring; if none match, it falls back to
  nearest-zone-by-lat/lon. There is no tokenization, no intent
  classification, no entity extraction, no multi-turn state, and — per
  `orca/mcp_server.py`'s own docstring — no LLM call anywhere in the path.
  Clicking a map marker (`web/index.html:604 selectZone()`) literally does
  `queryInput.value = name` — it fills the box with the zone's own name, so
  of course the substring match "works" on click. Free-typed English that
  doesn't contain a zone name verbatim (a village name, "near the harbour
  jetty", a symptom-first question, anything in Tamil) silently falls
  through to the nearest-zone-by-default-coordinates fallback, i.e. it
  answers a different question than what was asked, without saying so.
  This is a real, accurate complaint, not a matter of degree.
- **Prior art checked, found none doing this better:** INCOIS's own
  fisherman-facing system, **Sagar Vani** (SMS/IVRS/voice-call/app,
  Gaian Solutions), pushes structured PFZ/OSF/high-wave/tsunami advisories
  outward — it is not a conversational system a fisherman queries in free
  text. No deployed Indian fisheries chatbot with real agentic/LLM
  reasoning was found. This is a genuine gap ORCA can differentiate on, not
  a solved problem to copy.
- **Recommended pattern, per Anthropic's own "Building effective agents"**
  ([anthropic.com/news/building-effective-agents](https://www.anthropic.com/news/building-effective-agents)):
  ORCA's task (turn one free-text question + optional map click into a
  zone/intent, call one existing deterministic function, phrase the
  result) is a bounded, well-specified task — exactly the case Anthropic's
  own guidance says to use a **workflow** (fixed code path, one augmented
  LLM call) for, not an open-ended autonomous **agent** that decides its
  own next tool call. Matches this project's "boring beats clever" rule
  directly — an autonomous multi-step agent would be the wrong tool here,
  not just an unaffordable one.
- **Concrete shape researched (4 stages, not built):**
  1. *Structured extraction* — one constrained LLM call
     (`output_config.format` JSON schema, Anthropic API) turns free text
     into `{zone_or_coords, intent, language}`. This is the only place an
     LLM touches anything, and it produces structured data, not prose.
  2. *Deterministic computation* — unchanged: `build_recommendation()`
     (`orca/planner.py:157`) runs exactly as it does today. The LLM calls
     it as a tool; it does not see or influence `orca/policy.py`
     (CLAUDE.md rule 4 stays intact by construction, not by discipline).
  3. *Grounded composition* — a second constrained call turns the
     `Recommendation` dict into natural, multilingual prose, instructed to
     quote fields verbatim rather than recompute them, and to cite
     `observation_id`s already in the payload rather than invent new
     support. Research on citation-grounding
     ([arxiv.org/pdf/2606.00898](https://arxiv.org/pdf/2606.00898)) is
     blunt that citations still hallucinate even under this constraint —
     so any citation the LLM produces should be checked against the real
     `evidence[].id` list server-side before rendering, not trusted
     as-is.
  4. *Deterministic fallback* — if the LLM call fails, times out, or
     there's no key/network (the demo runs with **no network access**,
     CLAUDE.md rule 8, and this would be the first live call in
     `orca/api.py`'s request path), fall straight back to today's
     substring-match + template path. The richer layer must be strictly
     additive and optional, never a single point of failure for the demo.
  This is the same shape independently converged on by current
  safety-critical-agent literature (schema-in -> closed deterministic core
  -> guardrail -> deterministic fallback) —
  [arxiv.org/html/2604.13630v1](https://arxiv.org/html/2604.13630v1),
  [doi.org/10.3390/a19080627](https://doi.org/10.3390/a19080627).
- **Model choice:** this is a bounded classify-then-compose task, not deep
  coding/research — a cheap, fast model at low/medium effort is the
  correctly-sized choice here, not the biggest available model run at
  max effort.
- **Voice/language layer checked:** Bhashini (India's public ASR/
  translation/TTS initiative, 300+ models, Tamil supported) is the right
  real source for turning voice into text and for real multilingual
  output — same category of source as MOSDAC/IMD found earlier in this
  file. Checked directly: its own docs say usage is "for the purposes of
  PoC only" and paid access requires contacting the Bhashini team
  directly; no visible self-serve API key page was found. Same shape of
  blocker as IMD's key-issuance gap — needs a human to chase, not
  something to build against blind.
- **What was deliberately left out of this design, per direct
  instruction:** a walker-agent framework (Jaseci) and a Brier-score
  scoring layer wired into the decision path. The underlying instinct —
  measure whether the system's calls are actually well-calibrated over
  time — is sound and has real precedent (this file's own "off-by-one
  Douglas band" catch was exactly a manual version of that check), but it
  belongs as a small deterministic Python eval harness (a golden-question
  regression file, the same shape as `tests/test_agents.py`) logging
  agreement between the LLM's extracted zone/intent and ground truth —
  not a new agent framework or a metric bolted onto `orca/policy.py`,
  which CLAUDE.md rule 4/5 rule out anyway.
- **Not started:** none of the above is implemented. This is written up
  as research per direct instruction ("no implementation yet, research
  only"); `TEAM_STATUS.md`'s existing "LLM/agentic planning layer" backlog
  item already flags that this needs explicit sign-off before building
  (new paid dependency, first live network call in the request path) —
  that requirement stands.

## 2026-08-27 — built the chatbot's agentic layer (orca/agentic.py, Groq)

User picked Groq over Anthropic Claude (free developer tier, no billing
setup needed for a hackathon prototype) after `ant auth status` confirmed
no Anthropic credential was already available in this sandbox either way
-- see TEAM_STATUS.md's "Agentic chatbot layer" section for the full
design and what was verified live. Notes that didn't fit there:

- **Groq API specifics, verified against their docs before writing any
  code** (not assumed): endpoint is
  `https://api.groq.com/openai/v1/chat/completions` (OpenAI-compatible).
  Strict JSON-schema structured output
  (`response_format: {type: "json_schema", json_schema: {strict: true,
  ...}}`) is only guaranteed on `openai/gpt-oss-20b`, `openai/gpt-oss-120b`,
  and `qwen/qwen3.8-27b` -- picked the two gpt-oss models for exactly that
  reason, not the bigger/flashier `llama-3.3-70b-versatile` (which isn't
  in the strict-mode list).
- **A real security incident during this build, not hypothetical:** the
  user pasted a live Groq key into a comment in `web/three-viz.js` (a
  frontend file -- shipped to the browser as plain text, no build step,
  visible via view-source to anyone). Caught immediately: `git log`
  confirmed it had never been committed, stripped the line, moved the key
  to a new git-ignored `.env` (`.gitignore` didn't have an `.env` pattern
  at all before this -- added one), and added `.env.example` as the
  documented, safe template. Backend reads it via plain `os.environ` --
  no `python-dotenv` added (CLAUDE.md rule 6: no new deps without asking;
  `requests`, already a dependency, was enough for the whole feature).
- **First real end-to-end proof the zone-resolution improvement is real,
  not just plausible:** "Is it safe to fish near the southernmost tip of
  India today?" against Kanyakumari -- plain substring matching
  (the old, only, behaviour) could never have resolved this; the strict
  schema constrained to the 10 real zone names correctly returned
  "Kanyakumari" on the first live call. Same for a real Tamil query with
  a rigged 3.1 m wave height: language correctly detected as `ta`, and
  the composed Tamil answer stated the real 3.1 m / 2.5 m numbers
  correctly, not a hallucinated paraphrase -- grounding held under a full
  script switch, which is a meaningfully harder case than English
  rephrasing.
- **A test-writing mistake caught by the test itself, not by inspection:**
  first draft of `test_answer_question_substring_hit_never_calls_the_model`
  asserted zero network calls at all when substring matching found a
  zone -- wrong, because composition (phrasing) is a deliberately
  separate, independent use of the network from zone resolution, and
  runs regardless of how the zone was found. The test failed honestly
  against the real implementation, which is what caught the wrong
  premise; fixed to assert the actually-guaranteed thing (the *zone
  extraction* schema specifically is never requested once substring
  matching already hit).
- Composition's system prompt went through one real revision after
  reading actual model output: the first version restated the decision
  JSON's own field names back ("The decision is GO with the reason:
  ...") instead of speaking like a person -- fixed by explicitly telling
  the model not to narrate the JSON's structure. Caught by reading a real
  response, not by guessing what "natural" would mean.

## 2026-08-27 — the four question types + memory layer: what was risky

Built gaps 1-4 from the "how to answer more indirect questions" analysis
(bare-number lookups, multi-turn memory, out-of-coverage honesty,
off-topic refusal). Notes on the parts that were genuinely risky, and the
bugs found by actually running it rather than by reading the code:

- **The "tomorrow" data was already being fetched and thrown away.**
  `data/fetch.py` has always asked Open-Meteo for `forecast_days=2` (~48
  hourly points per variable per zone) and `_parse_point()` has always
  kept `idx = 0` and discarded the other 47. So "what about tomorrow"
  needed no new data source at all -- just a second, separate read of a
  response we were already paying for. Added `fetch_tomorrow()` /
  `_parse_point_at_offset()` as a SEPARATE path rather than widening
  `fetch()`, deliberately: the "now" pipeline feeds the live safety
  policy and every agent, and this feature isn't on that request path.
  It writes to `data/cache/forecast/` -- its own subdirectory, the same
  isolation pattern bathymetry and IMBL already use, so
  `load_cached_observations()` (non-recursive glob) cannot sweep it into
  the safety-critical observation set.
- **Confidence had to drop for a day-ahead reading, and the first fix
  only landed on half of it.** Copying `NEAR_TERM_CONFIDENCE = 0.9` onto
  a 24h forecast would have quietly overstated exactly what CLAUDE.md
  rule 3 makes every number carry honestly, so `NEXT_DAY_CONFIDENCE =
  0.75`. The bug: the edit matched only `OpenMeteoMarineFetcher` (the two
  fetchers' code differed by one trailing comment), so the wind fetcher
  kept writing 0.9 for tomorrow -- and the marine-only test passed.
  Caught by reading the actual regenerated cache, not by the suite. The
  test is now parametrized across BOTH fetchers.
- **Composition was ~3,200 tokens per request and rate-limited the demo
  after two questions.** The composer was handed the entire
  `Recommendation.to_dict()` -- all 10 `zone_summaries`, all 5
  `agent_findings`, every evidence item's full provenance URL and
  coordinates. Groq's free tier allows 8,000 TPM on
  `openai/gpt-oss-120b`, so the third question in a minute got a real
  429 and silently fell back to template text (observed live; the
  fallback worked exactly as designed, which is why it looked like
  "composition randomly not applying" rather than an error).
  `_composition_context()` now sends only action/reason/chosen_zone and
  each evidence item's id/variable/value/unit: **~470 tokens, an 85%
  cut**. None of the dropped bulk was reachable in the output anyway, and
  less irrelevant material is a grounding win too, not just a cost one.
- **The model copied the JSON into the answer verbatim.** First version
  of the data_lookup prompt said "stated EXACTLY as given here (same
  number, same unit): {json.dumps(lookup)}" -- and the live answer began
  `{"variable": "wave_height_m", "value": 0.84, ...}`. Fixed by spelling
  the value out in the prompt as a sentence and explicitly forbidding
  JSON/braces/field names in the reply. Caught by reading real output.
- **A `data_lookup` that is also a DO NOT GO with no named zone dropped
  the number entirely.** `chosen_zone` is None on a DO NOT GO (there is
  nowhere to send them) and `resolved_zone` is None when they named no
  place -- "what's the wave height?" in dangerous conditions hits both at
  once, and `_resolve_lookup()` returned None. Now falls back to
  `zone_summaries[0]`, which planner.py builds primary-zone-first, so the
  reading always has a real home. This is the mirror image of the
  hard-deny rule below: one guards against burying the safety fact, this
  one against dropping the question that was actually asked.
- **`history: list | None` on the Pydantic model contradicted its own
  docstring.** `orca/memory.py` promises a malformed or hostile history
  degrades to "no memory", never a rejected request -- but Pydantic
  rejected a non-list with 422 *before* `sanitize()` ever ran. Caught by
  an e2e test, not by the Python-level tests (which call
  `answer_question()` directly and so bypass the HTTP boundary
  entirely). Field is now typed `Any`, with `sanitize()` as the single
  validation gate, and both a parametrized pytest and an e2e case pin it.
- **Memory design, the part that was explicitly required not to
  hallucinate.** Research (summarized with sources in the entry above) is
  consistent on two points: concatenating raw transcripts is what makes
  models drift and reinforce their own earlier mistakes, and prompt
  injection through history is architectural -- delimiters and role
  markers demonstrably do not hold. So `orca/memory.py` stores no user
  text at all: a turn is reduced on ingest to `{zone_name, variable,
  time_frame}`, each re-validated against the real closed sets, capped at
  3, frozen once built. A bad turn can leave behind only a zone that
  really exists and a variable that really exists, so there is no wrong
  *text* to carry forward. The other half of the guarantee is in
  `orca/agentic.py`: history reaches ONLY extraction, never composition
  -- so the composer literally cannot repeat or compound an earlier
  answer, because it has never seen one. Both halves are asserted against
  the real outbound payloads in tests, not just documented.
- **Enum literals were written twice** (`("en","ta","other")`,
  `("verdict","data_lookup")`) -- once in the JSON schema sent to the
  model, once in the re-validation of its reply. Adding a language would
  have let the model return it and then had re-validation silently
  normalize it away. Hoisted to `LANGUAGES`/`INTENTS` constants, matching
  what `orca/memory.py` already does for `TIME_FRAMES`/`LOOKUP_VARIABLES`.
- **Three frontend/backend constants necessarily exist twice** (ZONES,
  `WAVE_HARD_DENY_M`, the history cap) because `web/` has no build step
  and cannot import Python. Rather than add a runtime `/zones` dependency
  the offline demo would have to survive, `tests/test_frontend_constants.py`
  parses the real values out of `web/index.html` and asserts they match
  the Python ones. Drift now fails at CI time instead of misleading
  someone on stage.

## 2026-08-27 — chatbot e2e re-test: two bugs only a real conversation exposed

Re-ran the chatbot layer end to end (full Playwright suite + a scripted
4-turn conversation driven through a real browser against the real
backend and the real Groq API). Every automated test was already green;
the conversation transcript exposed two bugs none of them could have
caught, because both are about *what the answer means across turns*
rather than whether a field is populated:

- **Memory recorded the wrong conversational subject.** Turn 1 asked
  about **Rameswaram**, got a SAFER ALTERNATIVE pointing at Chennai, and
  `web/index.html`'s `rememberTurn()` stored `chosen_zone` -- Chennai. So
  turn 2, "What's the wave height *there*?", silently answered about
  Chennai. `chosen_zone` is the *answer* ("where we're sending you"); the
  *subject* is the place they asked about, and on a DO NOT GO
  `chosen_zone` is `None` entirely, which would drop the thread
  altogether. Added an explicit `primary_zone` to `Recommendation` (the
  zone `build_recommendation()` actually resolved) and pointed
  `rememberTurn()` at it. Both cases now have tests -- SAFER ALTERNATIVE
  and DO NOT GO.
- **The composer hallucinated a claim about ORCA's own capabilities.**
  Turn 3, "And what about tomorrow?", produced: *"...we don't have
  tomorrow's readings yet"* -- while stating correct forecast figures in
  the same sentence, from a forecast cache that was fully populated. Not
  a number hallucination (every figure was right) but a false statement
  that would make a fisherman distrust a working feature. Root cause:
  `_composition_context()` stripped `time_frame`, so the model received
  unlabelled readings, was asked about tomorrow, and hedged. Fixed by
  including `readings_are_for` in the context plus an explicit
  instruction never to claim missing data for that day. Verified after
  the fix: the same turn now reports 28.2 km/h wind, which is genuinely
  tomorrow's Rameswaram forecast (today's is 25.9), carrying the honest
  0.75 next-day confidence.

Worth recording as a method note: both bugs were invisible to the test
suite because every assertion passed -- the fields were present, the
types were right, the numbers traced to real observations. What was wrong
was the *meaning* of an answer given what had been asked two turns
earlier. Scripting a real conversation and reading the transcript found
in one run what field-level assertions could not.
