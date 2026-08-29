"""Five independent agents, each answering one question about one zone.

Each function is list[MarineObservation] -> Finding: pure, no network
calls (data/fetch.py already did that), no LLM calls. Observations are
assumed pre-filtered to a single zone by orca/planner.py.

They have to be able to disagree — that's what lets policy.resolve()
catch "good fishing here" contradicting "dangerous waves here" (war plan
§11). Thresholds here are simplified, documented heuristics for a
prototype, not oceanographic ground truth — we say so if asked.

An agent given no evidence for its variable must never claim safety or
opportunity it can't support: it returns a neutral Finding (suggests_go
False, risk_level 0.0, hard_deny False) and says why in `reason`.
"""
import json
import math
from pathlib import Path

from orca.policy import Finding
from orca.schema import MarineObservation

# --- eo_satellite_agent ---
CHLOROPHYLL_PRODUCTIVE_MG_M3 = 0.5
SST_PRODUCTIVE_RANGE_C = (27.0, 31.0)

# --- weather_agent ---
WIND_RISK_SCALE_KMH = 40.0  # risk_level reaches 1.0 at/above this sustained wind speed

# --- hazard_agent ---
# 2.5 m is not an arbitrary cutoff: it's the real Douglas sea scale
# boundary between degree 4 "Moderate" (1.25-2.50 m) and degree 5
# "Rough" (2.50-4.00 m) -- the scale IMD's own Coastal Bulletin speaks in
# ("Sea Condition: Smooth to Slight" etc.) and mariners have used for over
# a century (H.P. Douglas, c.1917-1920; WMO notes the scale has no
# official international status but remains in everyday marine-forecast
# use). web/index.html's Douglas ruler renders this same boundary as the
# visible "ORCA stops here" line -- it's one number, inherited from
# maritime convention, not invented for this project.
WAVE_HARD_DENY_M = 2.5

# --- geofence_agent ---
# A real protected area: Krusadai Island (9.20N, 79.17E), part of the
# Gulf of Mannar Marine National Park, India's first marine biosphere
# reserve. This is a small box around the island's own published
# coordinate, not the park's full ~160km official boundary (that exact
# shape isn't publicly downloadable without a WDPA account -- see
# SCRATCH.md) -- an approximation of one real, specific, verifiable
# restricted feature, not an invented one. It sits ~7km from Mandapam and
# ~15km from Rameswaram in data/fetch.py's ZONES -- close enough to be a
# real nearby hazard for those queries, not overlapping either point.
PROHIBITED_ZONE = [
    (9.175, 79.145),
    (9.175, 79.195),
    (9.225, 79.195),
    (9.225, 79.145),
]


def _find(observations: list[MarineObservation], variable: str) -> MarineObservation | None:
    for obs in observations:
        if obs.variable == variable:
            return obs
    return None


def eo_satellite_agent(observations: list[MarineObservation]) -> Finding:
    chl = _find(observations, "chlorophyll_mg_m3")
    sst = _find(observations, "sst_c")
    evidence = [o for o in (chl, sst) if o is not None]

    if chl is None:
        return Finding(
            agent_name="eo_satellite_agent",
            suggests_go=False,
            risk_level=0.0,
            hard_deny=False,
            reason="No satellite chlorophyll data for this zone (cloud cover or no recent pass)",
            observations=evidence,
        )

    productive = chl.value >= CHLOROPHYLL_PRODUCTIVE_MG_M3
    if productive and sst is not None and SST_PRODUCTIVE_RANGE_C[0] <= sst.value <= SST_PRODUCTIVE_RANGE_C[1]:
        reason = f"Elevated chlorophyll ({chl.value:.2f} mg/m³) and warm SST ({sst.value:.1f}°C) suggest fish aggregation"
    elif productive:
        reason = f"Elevated chlorophyll ({chl.value:.2f} mg/m³) indicates productive waters"
    else:
        reason = f"Chlorophyll ({chl.value:.2f} mg/m³) below productivity threshold ({CHLOROPHYLL_PRODUCTIVE_MG_M3} mg/m³)"

    return Finding(
        agent_name="eo_satellite_agent",
        suggests_go=productive,
        risk_level=0.0,
        hard_deny=False,
        reason=reason,
        observations=evidence,
    )


def ocean_state_agent(observations: list[MarineObservation]) -> Finding:
    sst = _find(observations, "sst_c")
    current = _find(observations, "ocean_current_velocity_kmh")
    evidence = [o for o in (sst, current) if o is not None]

    if sst is None:
        return Finding(
            agent_name="ocean_state_agent",
            suggests_go=False,
            risk_level=0.0,
            hard_deny=False,
            reason="No sea surface temperature data for this zone",
            observations=evidence,
        )

    warm = SST_PRODUCTIVE_RANGE_C[0] <= sst.value <= SST_PRODUCTIVE_RANGE_C[1]
    current_note = f", current {current.value:.1f} km/h" if current is not None else ""
    reason = f"SST {sst.value:.1f}°C {'within' if warm else 'outside'} productive range{current_note}"

    return Finding(
        agent_name="ocean_state_agent",
        suggests_go=warm,
        risk_level=0.0 if warm else 0.15,
        hard_deny=False,
        reason=reason,
        observations=evidence,
    )


def weather_agent(observations: list[MarineObservation]) -> Finding:
    wind = _find(observations, "wind_speed_kmh")
    rain = _find(observations, "rain_mm") or _find(observations, "precipitation_mm")
    evidence = [o for o in (wind, rain) if o is not None]

    if wind is None:
        return Finding(
            agent_name="weather_agent",
            suggests_go=False,
            risk_level=0.0,
            hard_deny=False,
            reason="No wind data for this zone",
            observations=evidence,
        )

    risk_level = min(wind.value / WIND_RISK_SCALE_KMH, 1.0)
    rain_note = f", rain {rain.value:.1f}mm" if rain is not None else ""
    reason = f"Wind {wind.value:.1f} km/h{rain_note}"

    return Finding(
        agent_name="weather_agent",
        suggests_go=False,  # this agent only ever flags risk, never opportunity
        risk_level=risk_level,
        hard_deny=False,  # hazard_agent owns hard denial for sea state
        reason=reason,
        observations=evidence,
    )


def hazard_agent(observations: list[MarineObservation]) -> Finding:
    wave = _find(observations, "wave_height_m")

    if wave is None:
        return Finding(
            agent_name="hazard_agent",
            suggests_go=False,
            risk_level=0.0,
            hard_deny=False,
            reason="No wave height data for this zone — hazard could not be assessed",
            observations=[],
        )

    hard_deny = wave.value > WAVE_HARD_DENY_M
    risk_level = min(wave.value / WAVE_HARD_DENY_M, 1.0)
    reason = f"Significant wave height {wave.value:.1f} m"
    if hard_deny:
        reason += f" exceeds {WAVE_HARD_DENY_M} m safety limit"

    return Finding(
        agent_name="hazard_agent",
        suggests_go=False,
        risk_level=risk_level,
        hard_deny=hard_deny,
        reason=reason,
        observations=[wave],
    )


IMBL_CACHE_PATH = Path(__file__).resolve().parent.parent / "data" / "cache" / "imbl" / "imbl_boundary.json"
# Escalating-proximity bands to the real India-Sri Lanka maritime boundary
# (see data/fetch.py's MarineRegionsIMBLFetcher). Proximity-based, not a
# side-of-line crossing test: determining which side of a multi-segment
# treaty line a point falls on reliably is a harder problem than this
# prototype takes on, and getting it wrong could wrongly clear a boat
# that has actually crossed. Being this close to an international
# boundary is, on its own, a legitimate reason to stop regardless of
# which side you believe you're on -- a conservative, honest simplification,
# not a claim of exact crossing detection.
IMBL_URGENT_KM = 2.0
IMBL_WARNING_KM = 5.0
IMBL_ADVISORY_KM = 10.0

_imbl_segments_cache: list | None = None


def _load_imbl_segments() -> list:
    """Lazy module-level cache of the real IMBL geometry, read once. Falls
    back to an empty list (geofence_agent then just skips the IMBL check,
    same as any other agent with no evidence for its variable) if
    data/fetch.py hasn't been run yet -- never fabricated, never crashes.
    """
    global _imbl_segments_cache
    if _imbl_segments_cache is None:
        loaded = json.loads(IMBL_CACHE_PATH.read_text()).get("segments", []) if IMBL_CACHE_PATH.exists() else []
        _imbl_segments_cache = loaded
    return _imbl_segments_cache


def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _point_to_segment_km(plat: float, plon: float, alat: float, alon: float, blat: float, blon: float) -> float:
    """Distance from (plat, plon) to the nearest point on segment A-B, via
    a local equirectangular projection (fine at the scale of one boundary
    segment) so the perpendicular-projection-and-clamp math is simple,
    then converted back with real haversine distance for the final figure.
    """
    lat0 = math.radians(plat)
    kx, ky = 111.32 * math.cos(lat0), 110.57
    px, py = plon * kx, plat * ky
    ax, ay = alon * kx, alat * ky
    bx, by = blon * kx, blat * ky
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return _haversine_km(plat, plon, alat, alon)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    clat, clon = (ay + t * dy) / ky, (ax + t * dx) / kx
    return _haversine_km(plat, plon, clat, clon)


def _distance_to_imbl_km(lat: float, lon: float, segments: list) -> float | None:
    if not segments:
        return None
    best = math.inf
    for segment in segments:
        for i in range(len(segment) - 1):
            alat, alon = segment[i]
            blat, blon = segment[i + 1]
            best = min(best, _point_to_segment_km(lat, lon, alat, alon, blat, blon))
    return best


def _point_in_polygon(lat: float, lon: float, polygon: list[tuple[float, float]]) -> bool:
    """Standard ray-casting point-in-polygon test."""
    inside = False
    n = len(polygon)
    for i in range(n):
        lat1, lon1 = polygon[i]
        lat2, lon2 = polygon[(i + 1) % n]
        if ((lon1 > lon) != (lon2 > lon)) and (
            lat < (lat2 - lat1) * (lon - lon1) / (lon2 - lon1) + lat1
        ):
            inside = not inside
    return inside


def geofence_agent(
    observations: list[MarineObservation],
    imbl_segments: list | None = None,
    *,
    position: tuple[float, float] | None = None,
) -> Finding:
    """R-36: where this check runs must not depend on which observations
    happen to exist.

    `position` is the (lat, lon) actually asked about. orca/planner.py
    passes the zone's own coordinates, which it always knows, so the
    geofence is now checked at the queried location rather than at
    whichever observation sorted first. Previously a point with no cached
    readings could not be geofence-checked AT ALL -- and being inside a
    marine national park is true whether or not a satellite passed over
    it that morning. Geometry is not evidence-dependent.

    Resolves Open Decision 9 without breaking R-5's uniform
    `list[MarineObservation] -> Finding` shape: `position` is
    KEYWORD-ONLY and optional, so `agent(observations)` still calls all
    five agents identically, and the rejected alternative -- a synthetic
    position-carrying observation -- stays rejected (CLAUDE.md rule 1).

    Falls back to observations[0] only when no position is supplied, so
    existing direct callers keep their behaviour exactly.
    """
    ref = observations[0] if observations else None
    if position is not None:
        lat, lon = position
    elif ref is not None:
        lat, lon = ref.lat, ref.lon
    else:
        return Finding(
            agent_name="geofence_agent",
            suggests_go=False,
            risk_level=0.0,
            hard_deny=False,
            reason="No location data available to check against restricted zones",
            observations=[],
        )

    inside_mpa = _point_in_polygon(lat, lon, PROHIBITED_ZONE)

    segments = imbl_segments if imbl_segments is not None else _load_imbl_segments()
    imbl_km = _distance_to_imbl_km(lat, lon, segments)

    reasons: list[str] = []
    risk_level = 0.0
    hard_deny = False

    if inside_mpa:
        hard_deny = True
        risk_level = 1.0
        reasons.append("Location is inside a designated restricted marine zone (Gulf of Mannar Marine National Park)")

    if imbl_km is not None:
        if imbl_km <= IMBL_URGENT_KM:
            hard_deny = True
            risk_level = 1.0
            reasons.append(f"{imbl_km:.1f} km from the India-Sri Lanka maritime boundary (IMBL) -- too close to proceed")
        elif imbl_km <= IMBL_WARNING_KM:
            risk_level = max(risk_level, 0.6)
            reasons.append(f"{imbl_km:.1f} km from the IMBL -- warning zone")
        elif imbl_km <= IMBL_ADVISORY_KM:
            risk_level = max(risk_level, 0.3)
            reasons.append(f"{imbl_km:.1f} km from the IMBL -- advisory zone")

    if not reasons:
        reasons.append("Outside restricted zones")

    return Finding(
        agent_name="geofence_agent",
        suggests_go=False,
        risk_level=risk_level,
        hard_deny=hard_deny,
        reason="; ".join(reasons),
        # Cite an observation only if one exists. This finding's content
        # comes from geometry, not from a reading, so with no cached data
        # there is nothing to cite -- and an empty list here is what keeps
        # R-39's "no evidence at this zone" guard reading true.
        observations=[ref] if ref is not None else [],
    )
