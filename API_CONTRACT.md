# ORCA API Contract

## Base URL
`http://localhost:8000`

## Endpoints

### 1. `POST /ask`
Submit a user query (e.g. location, target area, intent) and receive a deterministic safety recommendation with full evidence provenance.

#### Request Body
```json
{
  "query": "Should I go fishing in Zone A from Nagapattinam?",
  "lat": 10.76,
  "lon": 79.84
}
```

#### Response Body
```json
{
  "id": "rec_123456",
  "action": "SAFER ALTERNATIVE", // "GO" | "DO NOT GO" | "SAFER ALTERNATIVE"
  "reason": "Opportunity overridden by hazard: significant wave height 3.1 m at Zone A exceeds safety threshold (2.5 m).",
  "recommendation": "Do not go to Zone A. Go to Zone B — lower expected catch, wave height 1.4 m. Return by 4 PM.",
  "chosen_zone": {
    "name": "Zone B",
    "lat": 10.85,
    "lon": 79.95
  },
  "overridden": [
    {
      "agent": "ocean_state_agent",
      "reason": "Zone A recommended based on SST (28.4°C) & high chlorophyll"
    }
  ],
  "evidence": [
    {
      "id": "obs_wave_01",
      "variable": "wave_height_m",
      "value": 3.1,
      "unit": "m",
      "lat": 10.76,
      "lon": 79.84,
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
      "name": "Zone A",
      "lat": 10.76,
      "lon": 79.84,
      "action": "DO NOT GO",
      "risk_level": 1.0,
      "hard_deny": true
    }
  ]
}
```

`agent_findings` and `zone_summaries` are additive (existing clients can
ignore them). `agent_findings` is the primary/queried zone's raw output
from all 5 agents (not just the ones that got overridden) -- added for
the 3D evidence-reasoning graph. `zone_summaries` is one entry per
evaluated zone with a worst-agent `risk_level` and `hard_deny` -- added
for the geospatial risk-terrain view. Both surface computation
`build_recommendation()` already did internally; neither is fabricated.

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
  "lat": 10.76,
  "lon": 79.84,
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
  "bbox": { "min_lat": 10.5, "max_lat": 13.5, "min_lon": 79.5, "max_lon": 81.5 },
  "stride": 4,
  "points": [
    { "lat": 10.76, "lon": 79.84, "elevation_m": -14.3 }
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
