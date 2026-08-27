"""FastAPI surface: POST /ask, GET /evidence/{id}, GET /health.

Matches API_CONTRACT.md. Every number in every response traces to a
MarineObservation id via /evidence/{id} (CLAUDE.md rule 3). This module
itself makes no network calls (CLAUDE.md rule 8). /ask delegates to
orca.agentic.answer_question(), which always runs the same
build_recommendation() this file used to call directly, and only ever
*adds* an optional, fail-closed network call on top of it (GROQ_API_KEY
unset -> identical to the old direct call, byte-for-byte). See
orca/agentic.py's module docstring for the rule 8 exception this is.

Does not modify orca/schema.py or orca/policy.py.
"""
from __future__ import annotations

import json
import socket
from datetime import datetime, timezone
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from orca.agentic import answer_question
from orca.planner import load_cached_observations, observation_id

app = FastAPI(title="ORCA", description="Marine advisory reasoning layer")

# Seafloor relief for the geospatial 3D view -- map context, not advisory
# evidence (see data/fetch.py ERDDAPBathymetryFetcher), so it's served
# from its own cache file rather than through orca.planner.
BATHYMETRY_CACHE_PATH = (
    Path(__file__).resolve().parent.parent / "data" / "cache" / "bathymetry" / "bathymetry_grid.json"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class AskRequest(BaseModel):
    query: str
    lat: float
    lon: float


def _is_reachable(host: str = "marine-api.open-meteo.com", port: int = 443, timeout: float = 0.75) -> bool:
    """Best-effort connectivity probe for the /health "offline_mode" badge
    ONLY. Never used to change what /ask or /evidence serve — both always
    read data/cache/ regardless of this result (CLAUDE.md rule 8).
    """
    try:
        socket.create_connection((host, port), timeout=timeout).close()
        return False  # reachable => not offline
    except OSError:
        return True


@app.post("/ask")
def ask(request: AskRequest) -> dict:
    observations = load_cached_observations()
    offline = _is_reachable()
    try:
        recommendation = answer_question(
            request.query, request.lat, request.lon, observations=observations, offline_mode=offline
        )
    except ValueError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return recommendation.to_dict()


@app.get("/evidence/{observation_id_}")
def get_evidence(observation_id_: str) -> dict:
    observations = load_cached_observations()
    for obs in observations:
        if observation_id(obs) == observation_id_:
            return {**obs.to_dict(), "id": observation_id_}
    raise HTTPException(status_code=404, detail=f"No observation with id {observation_id_!r}")


@app.get("/bathymetry")
def bathymetry() -> dict:
    if not BATHYMETRY_CACHE_PATH.exists():
        raise HTTPException(
            status_code=503,
            detail="Bathymetry cache not populated -- run `python -m data.fetch` first",
        )
    return json.loads(BATHYMETRY_CACHE_PATH.read_text())


@app.get("/health")
def health() -> dict:
    observations = load_cached_observations()
    now = datetime.now(timezone.utc)
    if observations:
        newest_fetch = max(o.fetched_at for o in observations)
        cache_age_min = max(0, int((now - newest_fetch).total_seconds() // 60))
    else:
        cache_age_min = 0
    return {
        "status": "ok",
        "offline_mode": _is_reachable(),
        "cache_age_min": cache_age_min,
        "cache_observation_count": len(observations),
    }
