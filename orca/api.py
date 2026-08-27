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
import logging
import os
import socket
from datetime import datetime, timezone
from pathlib import Path

from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from orca.agentic import answer_question, is_configured
from orca.planner import load_cached_observations, load_forecast_observations, observation_id

logger = logging.getLogger("orca.api")

ENV_FILE = Path(__file__).resolve().parent.parent / ".env"


def _load_env_file(path: Path = ENV_FILE) -> None:
    """Read KEY=VALUE lines from a git-ignored .env into os.environ.

    Fifteen lines of stdlib instead of a dependency (CLAUDE.md rule 6).
    It exists because of a real, silent, day-long failure: .env held a
    valid GROQ_API_KEY, nothing ever read it, so is_configured() was
    False on the running server and every /ask fell back to the
    deterministic template. That fallback is correct behaviour -- but it
    is indistinguishable, from the outside, from the agentic layer
    working, which is how it went unnoticed until the answers were read
    side by side. Hence the startup log below: ORCA now states which
    mode it is in rather than leaving it to be inferred.

    An already-set variable always wins, so a real environment (CI, a
    container, `export`) is never overridden by a stale file.
    """
    if not path.is_file():
        return
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        key, sep, value = line.partition("=")
        if not sep:
            continue
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


_load_env_file()
logger.warning(
    "ORCA agentic layer: %s",
    "ON (GROQ_API_KEY found)" if is_configured()
    else "OFF (no GROQ_API_KEY) -- /ask will return deterministic template text",
)

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
    # Optional conversation memory, sent by the client. Typed as Any, not
    # `list | None`, on purpose: orca/memory.sanitize() is the SINGLE
    # validation gate for this field (it reduces whatever arrives to
    # validated enum facts and drops everything else), and any annotation
    # narrower than Any hands part of that job to Pydantic -- which
    # rejects with a 422 instead of degrading to "no memory".
    #
    # That difference is not cosmetic and was caught by a real e2e test:
    # with `list | None`, sending a string here failed the whole request,
    # contradicting the guarantee stated in orca/memory.py's docstring.
    # A fisherman's safety answer must not be lost because a client sent
    # a malformed optional field. See orca/memory.py.
    history: Any = None


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
            request.query,
            request.lat,
            request.lon,
            observations=observations,
            offline_mode=offline,
            history=request.history,
            # Tomorrow's cached forecast, for "what about tomorrow"
            # data_lookup answers only. Empty if data/fetch.py hasn't
            # populated it -- absent, never fabricated.
            forecast_observations=load_forecast_observations(),
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
