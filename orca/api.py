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
from orca import agents, alerts as alerts_mod, drift as drift_mod, observations
from orca.agentic import answer_question, is_configured, quota_snapshot
from orca.planner import (
    build_recommendation,
    load_cached_observations,
    load_forecast_observations,
    observation_id,
    observations_for_zone,
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

# IMD's public CAP warning feed, cached by data/fetch.py. Same reasoning as
# BATHYMETRY_CACHE_PATH: these are signed alert documents, not point
# observations, so they do not travel through orca/planner.py as evidence.
CAP_ALERTS_CACHE_PATH = (
    Path(__file__).resolve().parent.parent / "data" / "cache" / "alerts" / "imd_cap_alerts.json"
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
        # The India-Sri Lanka maritime boundary, so the phone can warn a
        # crew approaching it with no signal. This is the ONE thing the
        # mobile app does that the web client cannot: a background service
        # watching GPS against this geometry (see mobile/README.md).
        #
        # SHIPPED FROM THE SERVER, NOT HARDCODED IN THE APP, and that is
        # the important part. docs/MOBILE_APP.md §2 forbids the client
        # owning a threshold, because a second copy of a safety constant
        # is a second thing that can disagree with orca/agents.py. Here
        # the client executes geometry and constants it was GIVEN: change
        # IMBL_URGENT_KM in one place and every phone follows.
        #
        # Note what this is not: a verdict. The phone reports a DISTANCE
        # and a warning band. It never says GO or DO NOT GO -- that stays
        # orca/policy.py's, and reaches the phone only inside `zones`.
        "boundary": _boundary_payload(),
        # IMD's own storm/cyclone warnings, shipped WHOLE -- polygons and
        # all -- rather than pre-matched to the ten zone centroids. A boat
        # 40 km offshore is not at a zone centroid, and the only position
        # that matters for "is the warning over ME" is the phone's own GPS
        # fix. So the phone runs the same point-in-polygon orca/alerts.py
        # runs, against geometry IMD published and signed.
        #
        # Same principle as `boundary` above: the client executes geometry
        # it was GIVEN. It does not own a threshold and does not decide a
        # severity -- CAP states its own.
        "alerts": _alerts_payload(),
        # The four numbers orca/drift.py needs, per zone, so a crew whose
        # engine has died can get a drift box with no signal. Surfaced
        # explicitly because wind/current DIRECTION are not part of the
        # advisory evidence set -- they change no verdict -- but without
        # them a drift box cannot be computed at all.
        "drift_inputs": _drift_inputs(selected, observations),
    }


def _alerts_payload() -> dict | None:
    """The cached IMD CAP feed, with expired warnings already dropped.

    Returns None if the feed has never been fetched, and the phone shows
    "not checked" rather than "all clear". Those are different facts and
    conflating them is the Ockhi failure in miniature: the crews who died
    were not told the warning was missing, they were told nothing.
    """
    path = CAP_ALERTS_CACHE_PATH
    if not path.exists():
        logger.warning(
            "IMD CAP cache absent at %s -- /bundle will carry no storm warnings "
            "and the app will say so. Run `python -m data.fetch`.",
            path,
        )
        return None
    raw = json.loads(path.read_text())
    now = datetime.now(timezone.utc)
    live = []
    for alert in raw.get("alerts", []):
        expires = alert.get("expires")
        if expires:
            try:
                if datetime.fromisoformat(expires) <= now:
                    continue
            except ValueError:
                # An unparseable expiry is not a licence to drop a storm
                # warning -- keep it and let the phone show it as undated.
                logger.warning("CAP alert %s has unparseable expires=%r", alert.get("identifier"), expires)
        live.append(alert)
    return {
        "source": raw.get("source"),
        "provenance": raw.get("provenance"),
        "fetched_at": raw.get("fetched_at"),
        "alerts": live,
    }


def _drift_inputs(selected: list[dict], observations: list) -> list[dict]:
    """Wind and current, speed AND direction, at each zone.

    Missing entries are reported as null, never defaulted. orca/drift.py
    refuses on a null and the phone shows why -- a drift box built on an
    assumed wind direction is a fabricated position, and this one gets
    read out to a rescue.
    """
    def _at(zone: dict, variable: str) -> dict | None:
        matches = [
            o for o in observations
            if o.variable == variable
            and abs(o.lat - zone["lat"]) < 1e-6
            and abs(o.lon - zone["lon"]) < 1e-6
        ]
        if not matches:
            return None
        newest = max(matches, key=lambda o: o.fetched_at)
        return {
            "value": newest.value,
            "unit": newest.unit,
            "source": newest.source,
            "valid_time": newest.valid_time.isoformat(),
            "id": observation_id(newest),
        }

    rows = []
    for zone in selected:
        rows.append({
            "zone": zone["name"],
            "lat": zone["lat"],
            "lon": zone["lon"],
            "wind_speed_kmh": _at(zone, "wind_speed_kmh"),
            "wind_direction_deg": _at(zone, "wind_direction_deg"),
            "current_speed_kmh": _at(zone, "ocean_current_velocity_kmh"),
            "current_direction_deg": _at(zone, "ocean_current_direction_deg"),
        })
    return rows


def _boundary_payload() -> dict | None:
    """The IMBL geometry plus the distance bands orca/agents.py uses.

    Returns None rather than a guess if the cache was never populated --
    a phone with no boundary data must show no boundary warning, not a
    wrong one (CLAUDE.md rule 1).
    """
    path = agents.IMBL_CACHE_PATH
    if not path.exists():
        logger.warning(
            "IMBL cache absent at %s -- /bundle will carry no boundary geometry "
            "and the mobile boundary watch will stay silent. Run `python -m data.fetch`.",
            path,
        )
        return None
    raw = json.loads(path.read_text())
    return {
        "source": raw.get("source"),
        "provenance": raw.get("provenance"),
        "fetched_at": raw.get("fetched_at"),
        "segments": raw.get("segments", []),
        # Read from orca/agents.py, never restated. If these ever drift
        # from what the geofence agent uses, the phone and the advisory
        # would disagree about the same boundary.
        "bands_km": {
            "urgent": agents.IMBL_URGENT_KM,
            "warning": agents.IMBL_WARNING_KM,
            "advisory": agents.IMBL_ADVISORY_KM,
        },
    }


@app.post("/observations")
def post_observation(payload: dict) -> dict:
    """The uplink: a boat reports a reading it measured itself.

    This endpoint is the ONLY way data enters ORCA from outside
    data/fetch.py, and everything it accepts is quarantined --
    data/observations/, never data/cache/. Nothing here can influence any
    advisory, now or later, and tests/test_observations.py asserts that
    load_cached_observations() provably cannot see what this writes.

    See orca/observations.py for why that separation is the whole design:
    published in-situ Bay of Bengal work calibrates its sensors in a lab
    against an ice-water reference, and an uncalibrated hull transducer is
    a different kind of data entirely. Useful, and only useful if nothing
    ever presents it as the other thing.

    Typed as a bare dict on purpose. orca/observations.py is the SINGLE
    validation gate (same argument as AskRequest.history above): a
    Pydantic model here would reject with a 422 carrying a schema dump,
    where observations.py rejects with a sentence a fisherman can act on
    ("check the unit and the sensor").
    """
    try:
        record = observations.store(payload)
    except observations.ObservationRejected as exc:
        # 422, not 500: the upload was understood and refused on its
        # merits. Refusing loudly is the point (CLAUDE.md rule 2).
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return {
        "stored": True,
        "id": record["id"],
        "record": record,
        # Restated in the RESPONSE, not just in the record. Whoever wires
        # a client to this should not be able to miss it.
        "note": (
            "Stored for research export only. This reading is quarantined: it "
            "does not affect any ORCA advisory, and never will."
        ),
    }


@app.get("/pfz")
def potential_fishing_zones() -> dict:
    """Potential Fishing Zones, ranked. SIH26176's FIRST example query.

    The problem statement opens with "Where is the nearest Potential
    Fishing Zone today?" and ORCA could already answer it -- it just never
    exposed the answer. eo_satellite_agent() has computed exactly this
    since the beginning: chlorophyll at or above
    CHLOROPHYLL_PRODUCTIVE_MG_M3, with SST inside SST_PRODUCTIVE_RANGE_C,
    is the standard PFZ signature INCOIS itself uses. This endpoint reads
    those SAME constants and the SAME cached observations and ranks the
    ten zones by them.

    NO NEW SCIENCE, and deliberately so. A second productivity rule would
    be a second thing that can disagree with the agent whose finding the
    verdict already cites.

    WHAT IS HONESTLY ABSENT. A zone with no cloud-free chlorophyll pixel
    gets `productive: null`, not `false`. VIIRS cannot see through cloud,
    and six of ten zones had no usable pixel in a 15-day window when this
    was written. "We could not see" and "there are no fish" are different
    statements and conflating them is exactly what CLAUDE.md rule 1
    forbids. The client renders the two differently.

    ALSO ABSENT: how many fish. INCOIS's own PFZ advisories carry the same
    limitation, and stating it is the honest thing to do -- chlorophyll is
    a proxy for primary productivity, not a catch estimate.
    """
    observations = load_cached_observations()
    if not observations:
        raise HTTPException(
            status_code=503,
            detail="No cached observations -- run `python -m data.fetch` first",
        )

    entries = []
    for zone in ZONES:
        local = observations_for_zone(observations, zone)
        chl = _reading(local, "chlorophyll_mg_m3")
        sst = _reading(local, "sst_c")

        if chl is None:
            productive = None          # unseen, NOT unproductive
            why = ("No cloud-free satellite chlorophyll pixel for this zone. "
                   "ORCA cannot tell whether the water here is productive.")
        else:
            warm = sst is not None and (
                agents.SST_PRODUCTIVE_RANGE_C[0] <= sst.value <= agents.SST_PRODUCTIVE_RANGE_C[1]
            )
            productive = chl.value >= agents.CHLOROPHYLL_PRODUCTIVE_MG_M3 and warm
            if productive:
                why = (f"Chlorophyll {chl.value:.2f} mg/m³ is at or above the "
                       f"{agents.CHLOROPHYLL_PRODUCTIVE_MG_M3} mg/m³ productivity threshold, "
                       f"and SST {sst.value:.1f}°C is inside the "
                       f"{agents.SST_PRODUCTIVE_RANGE_C[0]}-{agents.SST_PRODUCTIVE_RANGE_C[1]}°C range "
                       "fish aggregate in.")
            elif chl.value >= agents.CHLOROPHYLL_PRODUCTIVE_MG_M3:
                why = (f"Chlorophyll {chl.value:.2f} mg/m³ is productive, but SST "
                       + (f"{sst.value:.1f}°C is outside the aggregation range."
                          if sst is not None else "is not available."))
            else:
                why = (f"Chlorophyll {chl.value:.2f} mg/m³ is below the "
                       f"{agents.CHLOROPHYLL_PRODUCTIVE_MG_M3} mg/m³ threshold.")

        entries.append({
            "zone": zone["name"],
            "lat": zone["lat"],
            "lon": zone["lon"],
            "productive": productive,
            "why": why,
            # Every number carries its observation id, so the client can
            # tap through to source, valid_time and confidence exactly as
            # the evidence panel does (CLAUDE.md rule 3).
            "chlorophyll": _cited(chl),
            "sst": _cited(sst),
        })

    # Productive first, then by chlorophyll descending. Unseen zones sort
    # LAST rather than being dropped: a crew needs to know ORCA could not
    # see a place, not to have it quietly vanish from the list.
    def sort_key(e):
        seen = e["productive"] is not None
        chl_value = e["chlorophyll"]["value"] if e["chlorophyll"] else -1.0
        return (not seen, not bool(e["productive"]), -chl_value)

    entries.sort(key=sort_key)
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "criteria": {
            "chlorophyll_min_mg_m3": agents.CHLOROPHYLL_PRODUCTIVE_MG_M3,
            "sst_range_c": list(agents.SST_PRODUCTIVE_RANGE_C),
            "note": ("Chlorophyll is a proxy for primary productivity, not a catch "
                     "estimate. ORCA reports where conditions favour aggregation, "
                     "never how many fish are present."),
        },
        "zones": entries,
    }


def _reading(observations, variable):
    """Newest observation of one variable, or None. Selection only."""
    matches = [o for o in observations if o.variable == variable]
    return max(matches, key=lambda o: o.fetched_at) if matches else None


def _cited(observation) -> dict | None:
    if observation is None:
        return None
    return {
        "value": observation.value,
        "unit": observation.unit,
        "source": observation.source,
        "valid_time": observation.valid_time.isoformat(),
        "confidence": observation.confidence,
        "id": observation_id(observation),
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


@app.get("/alerts")
def storm_alerts(lat: float | None = None, lon: float | None = None) -> dict:
    """IMD warnings, optionally sorted against one position.

    With no lat/lon this is the raw cached feed. With a position it is
    orca/alerts.py's three buckets -- covering / ungeolocated / elsewhere
    -- which is the same computation the phone runs offline against its
    own GPS fix.
    """
    payload = _alerts_payload()
    if lat is None or lon is None:
        if payload is None:
            raise HTTPException(
                status_code=503,
                detail=(
                    "No IMD CAP feed cached -- run `python -m data.fetch`. "
                    "ORCA reports that it has not checked, rather than reporting all clear."
                ),
            )
        return payload
    return {
        "lat": lat,
        "lon": lon,
        **alerts_mod.active_alerts_for(lat, lon, payload, datetime.now(timezone.utc)),
    }


@app.get("/drift")
def drift(lat: float, lon: float, hours: float = 6.0, zone: str | None = None) -> dict:
    """Where a hull with a dead engine ends up, by the Leeway model.

    The wind and current used are the newest cached readings for `zone`
    (or the nearest ORCA zone, if not named) -- ORCA has no field at
    arbitrary points and will not interpolate one into existence.
    """
    if hours <= 0 or hours > 48:
        raise HTTPException(status_code=400, detail="hours must be between 0 and 48")

    observations = load_cached_observations()
    if not observations:
        raise HTTPException(
            status_code=503,
            detail="No cached observations -- run `python -m data.fetch` first",
        )

    if zone:
        match = [z for z in ZONES if z["name"].lower() == zone.strip().lower()]
        if not match:
            raise HTTPException(
                status_code=400,
                detail=f"Unknown zone {zone!r}. ORCA covers: {', '.join(z['name'] for z in ZONES)}",
            )
        source_zone = match[0]
    else:
        source_zone = min(
            ZONES,
            key=lambda z: (z["lat"] - lat) ** 2 + (z["lon"] - lon) ** 2,
        )

    inputs = _drift_inputs([source_zone], observations)[0]

    def _val(key):
        entry = inputs.get(key)
        return entry["value"] if entry else None

    result = drift_mod.drift_forecast(
        lat, lon,
        _val("wind_speed_kmh"), _val("wind_direction_deg"),
        _val("current_speed_kmh"), _val("current_direction_deg"),
        hours=hours,
    )
    return {
        "requested": {"lat": lat, "lon": lon, "hours": hours},
        "readings_from": {
            "zone": source_zone["name"],
            "lat": source_zone["lat"],
            "lon": source_zone["lon"],
            "note": (
                "Wind and current are the newest cached readings at this zone, "
                "not at the requested position. ORCA does not interpolate a field "
                "it did not measure."
            ),
        },
        "inputs": inputs,
        "drift": result,
    }


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
