# ORCA API Contract

## Base URL
`http://localhost:8000`

## Endpoints

### 1. `POST /ask`
Submit a user query (e.g. location, target area, intent) and receive a deterministic safety recommendation with full evidence provenance.

#### Request Body
```json
{
  "query": "Should I go fishing near Nagapattinam?",
  "lat": 10.7672,
  "lon": 79.8449
}
```

#### Response Body
```json
{
  "id": "rec_123456",
  "action": "SAFER ALTERNATIVE", // "GO" | "DO NOT GO" | "SAFER ALTERNATIVE"
  "reason": "Opportunity overridden by hazard: significant wave height 3.1 m at Nagapattinam exceeds safety threshold (2.5 m).",
  "recommendation": "Do not go to Nagapattinam. Go to Karaikal — lower expected catch, wave height 1.4 m.",
  "chosen_zone": {
    "name": "Karaikal",
    "lat": 10.9327,
    "lon": 79.8319
  },
  "overridden": [
    {
      "agent": "ocean_state_agent",
      "reason": "Nagapattinam recommended based on SST (28.4°C) & high chlorophyll"
    }
  ],
  "evidence": [
    {
      "id": "obs_wave_01",
      "variable": "wave_height_m",
      "value": 3.1,
      "unit": "m",
      "lat": 10.7672,
      "lon": 79.8449,
      "valid_time": "2026-08-26T04:00:00Z",
      "source": "Open-Meteo Marine",
      "confidence": 0.71,
      "provenance": "https://marine-api.open-meteo.com/v1/marine"
    }
  ],
  "offline_mode": true,
  "agent_findings": [
    {
      "agent": "hazard_agent",
      "suggests_go": false,
      "risk_level": 1.0,
      "hard_deny": true,
      "reason": "Significant wave height 3.1 m exceeds 2.5 m safety limit",
      "observation_ids": ["obs_wave_01"]
    }
  ],
  "zone_summaries": [
    {
      "name": "Nagapattinam",
      "lat": 10.7672,
      "lon": 79.8449,
      "action": "DO NOT GO",
      "risk_level": 1.0,
      "hard_deny": true
    }
  ],
  "agentic_used": false,
  "detected_language": "en",
  "cited_evidence_ids": [],
  "zone_match": "exact",
  "answer_kind": "verdict",
  "time_frame": "now",
  "coverage_note": null,
  "lookup": null,
  "primary_zone": { "name": "Nagapattinam", "lat": 10.7672, "lon": 79.8449 }
}
```

#### Optional request field: `history`
`POST /ask` also accepts an optional `history` array for multi-turn
follow-ups ("what about tomorrow?"). Each entry carries **only** three
validated values -- never question text, never a previous answer:

```json
"history": [
  { "zone_name": "Karaikal", "variable": "wave_height_m", "time_frame": "now" }
]
```

`zone_name` must be one of the real `ZONES`, `variable` one of the real
observation variables, `time_frame` `"now"` or `"tomorrow"`; at most 3
entries are kept. Anything else -- a wrong type, an invented place, an
injected instruction, a non-list -- is reduced to nothing by
`orca/memory.py`'s `sanitize()` and the request still answers normally
(**never** a 4xx). That module's docstring explains why the field is
structured facts rather than a transcript: it makes hallucination
compounding and prompt injection through history structurally
impossible, not merely unlikely. History reaches only the extraction
step, never answer composition.

---

`chosen_zone`, the entries of `zone_summaries`, and the map markers all
come from `data/fetch.py`'s `ZONES` — 10 real, named Tamil Nadu coastal
fishing harbours/towns (Chennai down to Colachel), not `Zone A`/`Zone
B`/etc. placeholders.

`agent_findings` and `zone_summaries` are additive (existing clients can
ignore them). `agent_findings` is the primary/queried zone's raw output
from all 5 agents (not just the ones that got overridden) -- added for
the 3D evidence-reasoning graph. `zone_summaries` is one entry per
evaluated zone with a worst-agent `risk_level` and `hard_deny` -- added
for the geospatial risk-terrain view. Both surface computation
`build_recommendation()` already did internally; neither is fabricated.

`agentic_used`, `detected_language`, and `cited_evidence_ids` are also
additive, from `orca/agentic.py` (the chatbot's agentic layer -- see that
file's module docstring for the full design). With `GROQ_API_KEY` unset,
or on any failure of the optional Groq call, these are always `false` /
`"en"` / `[]` and every other field is byte-for-byte what plain
`build_recommendation()` always produced -- this is CLAUDE.md rule 8's
guarantee, not just a default. When configured and reachable:
- `agentic_used` is `true` if the LLM contributed to zone resolution
  and/or answer phrasing this request.
- `detected_language` is the query's detected language (`"ta"` for
  Tamil, `"en"` for English, `"other"` otherwise) -- `recommendation` is
  phrased in it.
- `cited_evidence_ids` are the real `evidence[].id`s the composed
  `recommendation` text actually drew on, server-validated against the
  real evidence list (any id the model named that isn't real is dropped,
  never shown as if it were).
- A cheap, zero-risk substring match against the real zone list always
  wins over an LLM guess when it finds one; the LLM's `zone_name` is only
  consulted (to map free text like "the harbour jetty" or "the
  southernmost tip of India" onto a real zone) when that substring match
  finds nothing, and even then it can only ever pick a zone that's
  genuinely in `data/fetch.py`'s `ZONES` -- never an invented place.
- `zone_match` says how the answered zone was arrived at, so a guess is
  never presented as a certainty: `"exact"` (the query named it),
  `"inferred"` (an LLM mapped a landmark onto it), `"remembered"`
  (carried from the prior turn), `"fallback"` (nothing matched -- nearest
  by coordinates). `"fallback"` is the only one that sets
  `coverage_note`.
- `coverage_note` is an honest caveat string (or `null`) stating that
  ORCA is answering about a place the user didn't name, and/or that no
  forecast was cached so the answer reflects current conditions rather
  than tomorrow's. The UI renders it verbatim; it is never composed in
  the browser.
- `answer_kind` is `"verdict"` (the default), `"data_lookup"` (they asked
  for one specific measurement), or `"off_topic"` (nothing to do with the
  sea -- the UI suppresses the GO/DO NOT GO badge, evidence and Douglas
  ruler in that case, since none of it was asked about).
- `time_frame` is `"now"` or `"tomorrow"`. A `"tomorrow"` question is
  answered from the separately-cached forecast observations, run through
  the **identical** deterministic policy -- a forecast verdict is a real
  verdict, not a weaker one. Those observations carry an honestly lower
  `confidence` (0.75 vs 0.9) because a day-ahead forecast genuinely is
  less certain.
- `lookup` is the single real observation a `data_lookup` asked for --
  `{variable, value, unit, valid_time, source, confidence, id}`, whose
  `id` resolves through `/evidence/{id}` like any other number -- or
  `{variable, time_frame, missing: true}` when ORCA has no such reading
  (e.g. chlorophyll for tomorrow: it is a satellite observation, not a
  forecast). A missing reading is never substituted or estimated.
- A narrow `data_lookup` **never** suppresses a `DO NOT GO`: the composer
  is explicitly instructed to state the danger regardless of how narrow
  the question was.
- `primary_zone` is the zone the **question was about**, which is not the
  same as `chosen_zone` (where ORCA is sending them). They differ on a
  `SAFER ALTERNATIVE`, and `chosen_zone` is `null` entirely on a
  `DO NOT GO`. Anything resolving a follow-up pronoun ("what's the wave
  height *there*?") should use `primary_zone` — using `chosen_zone` made
  a Rameswaram question silently become a Chennai one.

---

### 2. `GET /evidence/{id}`
Retrieve a specific observation by ID.

#### Response Body
```json
{
  "id": "obs_wave_01",
  "variable": "wave_height_m",
  "value": 3.1,
  "unit": "m",
  "lat": 10.7672,
  "lon": 79.8449,
  "valid_time": "2026-08-26T04:00:00Z",
  "fetched_at": "2026-08-26T04:05:00Z",
  "source": "Open-Meteo Marine",
  "confidence": 0.71,
  "freshness_min": 15,
  "provenance": "https://marine-api.open-meteo.com/v1/marine"
}
```

---

### 3. `GET /bathymetry`
Real seafloor relief for the region (NOAA NCEI ETOPO 2022, 60 arc-second),
used only by the 3D geospatial view -- map context, not advisory evidence,
so it never flows through the safety policy. 503 if the cache hasn't been
populated yet (`python -m data.fetch`), never a fabricated/empty 200.

#### Response Body
```json
{
  "source": "NOAA NCEI ETOPO 2022 (60 arc-second)",
  "dataset_id": "ETOPO_2022_v1_60s",
  "provenance": "https://oceanwatch.pifsc.noaa.gov/erddap/griddap/ETOPO_2022_v1_60s.csv?...",
  "fetched_at": "2026-08-27T04:36:00Z",
  "bbox": { "min_lat": 7.8, "max_lat": 13.4, "min_lon": 76.9, "max_lon": 80.6 },
  "stride": 4,
  "points": [
    { "lat": 10.7672, "lon": 79.8449, "elevation_m": -14.3 }
  ]
}
```
`elevation_m` is "positive up": positive is land elevation, negative is
depth below sea level.

---

### 4. `GET /health`
System status, offline status, and data cache freshness.

#### Response Body
```json
{
  "status": "ok",
  "offline_mode": true,
  "cache_age_min": 12,
  "cache_observation_count": 42
}
```
