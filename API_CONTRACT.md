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
  "offline_mode": true
}
```

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

### 3. `GET /health`
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
