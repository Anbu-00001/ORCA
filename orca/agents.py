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
from orca.policy import Finding
from orca.schema import MarineObservation

# --- eo_satellite_agent ---
CHLOROPHYLL_PRODUCTIVE_MG_M3 = 0.5
SST_PRODUCTIVE_RANGE_C = (27.0, 31.0)

# --- weather_agent ---
WIND_RISK_SCALE_KMH = 40.0  # risk_level reaches 1.0 at/above this sustained wind speed

# --- hazard_agent ---
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


def geofence_agent(observations: list[MarineObservation]) -> Finding:
    if not observations:
        return Finding(
            agent_name="geofence_agent",
            suggests_go=False,
            risk_level=0.0,
            hard_deny=False,
            reason="No location data available to check against restricted zones",
            observations=[],
        )

    ref = observations[0]
    inside = _point_in_polygon(ref.lat, ref.lon, PROHIBITED_ZONE)

    return Finding(
        agent_name="geofence_agent",
        suggests_go=False,
        risk_level=1.0 if inside else 0.0,
        hard_deny=inside,
        reason="Location is inside a designated restricted marine zone" if inside else "Outside restricted zones",
        observations=[ref],
    )
