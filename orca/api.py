"""FastAPI surface: POST /ask, GET /bundle, GET /evidence/{id}, GET /health.

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

from data.fetch import ZONES
from orca.agentic import answer_question, is_configured, quota_snapshot
from orca.planner import (
    build_recommendation,
    load_cached_observations,
    load_forecast_observations,
    observation_id,
)

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

    R-54: called from /health and nowhere else. It used to run on every
    /ask, which put a live socket connect in the request path and
    contradicted N-6, N-7 and this module's own docstring — DNS
    resolution is not bounded by the timeout argument, so a black-holed
    DNS server could stall an answer that needs no network at all. It is
    a display concern; it belongs on the endpoint that drives the badge.
    """
    try:
        socket.create_connection((host, port), timeout=timeout).close()
        return False  # reachable => not offline
    except OSError:
        return True


@app.post("/ask")
def ask(request: AskRequest) -> dict:
    observations = load_cached_observations()
    # R-54: no probe here. /ask answers from data/cache/ unconditionally,
    # so this answer *was* computed offline — that is a fact about how it
    # was produced, not a measurement of the network, and it is true on
    # every request. The badge's live connectivity reading comes from
    # /health, which is where the socket connect now lives.
    offline = True
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


@app.get("/bundle")
def bundle(zones: str | None = None) -> dict:
    """The offline downlink: every zone's verdict in ONE request.

    A boat leaves harbour, loses signal a few km out, and needs the whole
    advisory already on the phone. Ten separate POST /ask calls over a
    marginal harbour link is the wrong shape for that -- one request that
    either wholly succeeds or wholly fails is the right one.

    THE CONTRACT (docs/MOBILE_APP.md §4.2): this is a pure fan-out over
    the same build_recommendation() /ask runs. No new reasoning, no new
    thresholds, no second code path. If /bundle could ever produce a
    verdict /ask would not have produced for the same zone, it would be
    wrong -- so tests/test_bundle.py asserts exactly that, zone by zone.

    WHY THE MODEL IS NOT CALLED HERE. /ask routes through
    answer_question(), which may make up to two LLM calls. Ten zones
    would be up to twenty, which exhausts a free tier in a single tap --
    and would buy nothing, because every zone is named explicitly, so
    there is no description to resolve and no free-text question to
    interpret. What the crew carries to sea is the deterministic answer,
    which is the only kind that is reproducible without a network anyway.
    The verdict is identical either way; that is the whole point of
    orca/policy.py being where it is.

    THERE IS NO `valid_until`, DELIBERATELY. docs/MOBILE_APP.md §4.2
    sketched one and it cannot honestly be filled in. These are NOWCAST
    observations: every valid_time is in the past (measured here --
    latest reading 18:15, fetched 18:23), so the latest valid_time is the
    moment the readings DESCRIBE, not a moment they stop being usable. A
    field named valid_until would assert a shelf life no source publishes,
    and picking a round 12 h would be inventing one (CLAUDE.md rule 1).

    So the bundle reports two facts and lets the client age them:
      cache_fetched_at      when ORCA collected the newest reading
      latest_reading_time   the moment the newest reading describes
    The device supplies the third -- now -- which is the only clock that
    knows how long the bundle has been at sea. See docs/MOBILE_APP.md §4.3.
    """
    observations = load_cached_observations()
    if not observations:
        raise HTTPException(
            status_code=503,
            detail="No cached observations -- run `python -m data.fetch` first",
        )

    if zones is None:
        selected = list(ZONES)
    else:
        wanted = [name.strip().lower() for name in zones.split(",") if name.strip()]
        selected = [z for z in ZONES if z["name"].lower() in wanted]
        unknown = sorted(set(wanted) - {z["name"].lower() for z in selected})
        if unknown:
            raise HTTPException(
                status_code=400,
                detail=(
                    f"Unknown zone(s): {', '.join(unknown)}. "
                    f"ORCA covers: {', '.join(z['name'] for z in ZONES)}"
                ),
            )

    entries = []
    for zone in selected:
        recommendation = build_recommendation(
            zone["name"],
            zone["lat"],
            zone["lon"],
            observations=observations,
            offline_mode=True,
            resolved_zone=zone,
        )
        entries.append(recommendation.to_dict())

    # Both timestamps are read off real observations, not the clock, so a
    # bundle built twice from the same cache is identical.
    fetched_at = max(o.fetched_at for o in observations)
    latest_reading = max(o.valid_time for o in observations)
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "cache_fetched_at": fetched_at.isoformat(),
        "latest_reading_time": latest_reading.isoformat(),
        "zone_count": len(entries),
        "zones": entries,
    }


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
        # What the LLM provider last said about remaining headroom. The
        # free-tier token budget is what degraded a live demo, and it is
        # knowable in advance -- every response carries x-ratelimit-*.
        # Empty until a question has been asked: unknown, never "fine".
        "agentic_configured": is_configured(),
        "llm_quota": quota_snapshot(),
    }
