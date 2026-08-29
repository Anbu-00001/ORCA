"""Tests for orca/agents.py — written before the implementation.

Each agent is a pure function: list[MarineObservation] -> Finding. Agents
receive observations already filtered to ONE zone (planner.py's job) and
must never claim more than the evidence in front of them supports — an
empty/missing input must produce a neutral, low-risk-level, non-suggesting
Finding that says so, never a fabricated conclusion.
"""
from datetime import datetime, timezone

import pytest

from orca.agents import (
    eo_satellite_agent,
    geofence_agent,
    hazard_agent,
    ocean_state_agent,
    weather_agent,
)
from orca.schema import MarineObservation


def _obs(variable, value, unit="", lat=10.76, lon=79.84, source="Open-Meteo Marine", confidence=0.9):
    return MarineObservation(
        variable=variable,
        value=value,
        unit=unit,
        lat=lat,
        lon=lon,
        valid_time=datetime(2026, 8, 26, 4, 0, tzinfo=timezone.utc),
        fetched_at=datetime(2026, 8, 26, 4, 5, tzinfo=timezone.utc),
        source=source,
        confidence=confidence,
        freshness_min=5,
        provenance="https://example.test/provenance",
    )


# ---------------------------------------------------------------------------
# eo_satellite_agent — candidate zones from SST + chlorophyll
# ---------------------------------------------------------------------------

def test_eo_satellite_high_chlorophyll_and_warm_sst_suggests_go():
    obs = [_obs("chlorophyll_mg_m3", 2.3, "mg m^-3"), _obs("sst_c", 28.4, "°C")]
    finding = eo_satellite_agent(obs)
    assert finding.agent_name == "eo_satellite_agent"
    assert finding.suggests_go is True
    assert finding.hard_deny is False


def test_eo_satellite_low_chlorophyll_does_not_suggest_go():
    obs = [_obs("chlorophyll_mg_m3", 0.05, "mg m^-3"), _obs("sst_c", 28.4, "°C")]
    finding = eo_satellite_agent(obs)
    assert finding.suggests_go is False


def test_eo_satellite_missing_chlorophyll_is_honest_not_go():
    """No fabricated opportunity when the satellite pass was cloud-masked."""
    obs = [_obs("sst_c", 28.4, "°C")]
    finding = eo_satellite_agent(obs)
    assert finding.suggests_go is False
    assert "no" in finding.reason.lower() or "chlorophyll" in finding.reason.lower()


def test_eo_satellite_empty_observations_is_neutral():
    finding = eo_satellite_agent([])
    assert finding.suggests_go is False
    assert finding.hard_deny is False
    assert finding.risk_level == 0.0


def test_eo_satellite_finding_carries_source_observations_as_evidence():
    chl = _obs("chlorophyll_mg_m3", 2.3, "mg m^-3")
    sst = _obs("sst_c", 28.4, "°C")
    finding = eo_satellite_agent([chl, sst])
    assert chl in finding.observations
    assert sst in finding.observations


# ---------------------------------------------------------------------------
# ocean_state_agent — zone quality from sea temp and currents
# ---------------------------------------------------------------------------

def test_ocean_state_warm_sst_suggests_go():
    obs = [_obs("sst_c", 28.5, "°C"), _obs("ocean_current_velocity_kmh", 1.2, "km/h")]
    finding = ocean_state_agent(obs)
    assert finding.agent_name == "ocean_state_agent"
    assert finding.suggests_go is True


def test_ocean_state_cold_sst_does_not_suggest_go():
    obs = [_obs("sst_c", 19.0, "°C")]
    finding = ocean_state_agent(obs)
    assert finding.suggests_go is False


def test_ocean_state_missing_sst_is_neutral_not_go():
    obs = [_obs("ocean_current_velocity_kmh", 1.2, "km/h")]
    finding = ocean_state_agent(obs)
    assert finding.suggests_go is False
    assert finding.risk_level == 0.0


# ---------------------------------------------------------------------------
# weather_agent — wind/rain risk. Never hard-denies (that's hazard_agent's
# job); never suggests_go (it only ever flags risk).
# ---------------------------------------------------------------------------

def test_weather_high_wind_gives_high_risk():
    obs = [_obs("wind_speed_kmh", 38.0, "km/h")]
    finding = weather_agent(obs)
    assert finding.agent_name == "weather_agent"
    assert finding.risk_level > 0.8
    assert finding.suggests_go is False
    assert finding.hard_deny is False


def test_weather_calm_wind_gives_low_risk():
    obs = [_obs("wind_speed_kmh", 5.0, "km/h")]
    finding = weather_agent(obs)
    assert finding.risk_level < 0.2


def test_weather_never_hard_denies_even_at_extreme_wind():
    obs = [_obs("wind_speed_kmh", 200.0, "km/h")]
    finding = weather_agent(obs)
    assert finding.hard_deny is False
    assert finding.risk_level == 1.0  # clamped, not fabricated beyond 1.0


def test_weather_missing_wind_data_is_neutral():
    finding = weather_agent([])
    assert finding.risk_level == 0.0
    assert finding.suggests_go is False


# ---------------------------------------------------------------------------
# hazard_agent — hard_deny if wave height > 2.5 m; scaled risk_level.
# This is the agent behind the §8.4 flip test.
# ---------------------------------------------------------------------------

def test_hazard_wave_above_threshold_hard_denies():
    obs = [_obs("wave_height_m", 3.1, "m")]
    finding = hazard_agent(obs)
    assert finding.agent_name == "hazard_agent"
    assert finding.hard_deny is True
    assert finding.risk_level == 1.0


def test_hazard_wave_below_threshold_does_not_hard_deny():
    obs = [_obs("wave_height_m", 1.4, "m")]
    finding = hazard_agent(obs)
    assert finding.hard_deny is False


def test_hazard_wave_exactly_at_threshold_does_not_hard_deny():
    """> 2.5m denies; == 2.5m does not (spec says strictly greater than)."""
    obs = [_obs("wave_height_m", 2.5, "m")]
    finding = hazard_agent(obs)
    assert finding.hard_deny is False


def test_hazard_wave_just_above_threshold_hard_denies():
    obs = [_obs("wave_height_m", 2.51, "m")]
    finding = hazard_agent(obs)
    assert finding.hard_deny is True


def test_hazard_risk_level_scales_with_wave_height():
    low = hazard_agent([_obs("wave_height_m", 0.5, "m")])
    mid = hazard_agent([_obs("wave_height_m", 1.5, "m")])
    assert low.risk_level < mid.risk_level < 1.0


def test_hazard_flip_from_dangerous_to_safe_matches_demo_scenario():
    """The exact §8.4 verification: 3.1m denies, 1.0m does not."""
    dangerous = hazard_agent([_obs("wave_height_m", 3.1, "m")])
    safe = hazard_agent([_obs("wave_height_m", 1.0, "m")])
    assert dangerous.hard_deny is True
    assert safe.hard_deny is False


def test_hazard_missing_wave_data_does_not_fabricate_safety():
    """No wave reading must NOT silently mean 'safe' — risk_level stays 0
    but the reason must say data is missing, not that conditions are fine.
    """
    finding = hazard_agent([])
    assert finding.hard_deny is False
    assert finding.risk_level == 0.0
    assert "no" in finding.reason.lower() or "missing" in finding.reason.lower() or "wave" in finding.reason.lower()


# ---------------------------------------------------------------------------
# geofence_agent — hard_deny inside a hardcoded prohibited polygon.
# ---------------------------------------------------------------------------

def test_geofence_point_inside_polygon_hard_denies():
    from orca.agents import PROHIBITED_ZONE

    lats = [v[0] for v in PROHIBITED_ZONE]
    lons = [v[1] for v in PROHIBITED_ZONE]
    inside_lat = sum(lats) / len(lats)
    inside_lon = sum(lons) / len(lons)

    obs = [_obs("wave_height_m", 0.5, "m", lat=inside_lat, lon=inside_lon)]
    finding = geofence_agent(obs)
    assert finding.agent_name == "geofence_agent"
    assert finding.hard_deny is True


def test_geofence_point_outside_polygon_does_not_hard_deny():
    obs = [_obs("wave_height_m", 0.5, "m", lat=10.76, lon=79.84)]  # Nagapattinam, well outside
    finding = geofence_agent(obs)
    assert finding.hard_deny is False


def test_geofence_empty_observations_does_not_fabricate_a_denial():
    finding = geofence_agent([])
    assert finding.hard_deny is False


# ---------------------------------------------------------------------------
# geofence_agent + real IMBL (India-Sri Lanka maritime boundary) proximity.
# A simple straight test segment (not real geometry -- see file docstring
# convention: hand-built fixtures only to exercise a specific numeric
# branch) at latitude 10.0N lets us place points at known approximate
# distances south of it. ~111 km/degree of latitude is used to pick each
# point comfortably inside one risk band, not at its exact edge, so this
# doesn't become a floating-point boundary test.
# ---------------------------------------------------------------------------

_TEST_IMBL_SEGMENT = [[(10.0, 80.0), (10.0, 81.0)]]


def test_geofence_far_from_imbl_is_clear():
    obs = [_obs("wave_height_m", 0.5, "m", lat=9.0, lon=80.5)]  # ~111 km south
    finding = geofence_agent(obs, imbl_segments=_TEST_IMBL_SEGMENT)
    assert finding.hard_deny is False
    assert finding.risk_level == 0.0


def test_geofence_within_advisory_band_raises_risk_but_not_hard_deny():
    obs = [_obs("wave_height_m", 0.5, "m", lat=9.93, lon=80.5)]  # ~7.8 km south
    finding = geofence_agent(obs, imbl_segments=_TEST_IMBL_SEGMENT)
    assert finding.hard_deny is False
    assert 0.0 < finding.risk_level < 1.0
    assert "IMBL" in finding.reason


def test_geofence_within_urgent_band_hard_denies():
    obs = [_obs("wave_height_m", 0.5, "m", lat=9.99, lon=80.5)]  # ~1.1 km south
    finding = geofence_agent(obs, imbl_segments=_TEST_IMBL_SEGMENT)
    assert finding.hard_deny is True
    assert finding.risk_level == 1.0
    assert "IMBL" in finding.reason


def test_geofence_missing_imbl_data_does_not_fabricate_proximity():
    """No cached boundary (fresh clone, data/fetch.py not run yet) must
    degrade to "can't check that", never a fabricated safe/unsafe claim."""
    obs = [_obs("wave_height_m", 0.5, "m", lat=9.99, lon=80.5)]
    finding = geofence_agent(obs, imbl_segments=[])
    assert finding.hard_deny is False
    assert finding.risk_level == 0.0


def test_geofence_mpa_and_imbl_risk_combine_as_the_worse_of_the_two():
    from orca.agents import PROHIBITED_ZONE

    lats = [v[0] for v in PROHIBITED_ZONE]
    lons = [v[1] for v in PROHIBITED_ZONE]
    inside_mpa = (sum(lats) / len(lats), sum(lons) / len(lons))

    obs = [_obs("wave_height_m", 0.5, "m", lat=inside_mpa[0], lon=inside_mpa[1])]
    # Far from the test IMBL segment -- the MPA hit alone must still hard-deny.
    finding = geofence_agent(obs, imbl_segments=_TEST_IMBL_SEGMENT)
    assert finding.hard_deny is True
    assert finding.risk_level == 1.0


# ---------------------------------------------------------------------------
# Every agent returns a Finding with risk_level in [0,1] (enforced by
# Finding's own validator) for a range of real-shaped inputs.
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("agent", [eo_satellite_agent, ocean_state_agent, weather_agent, hazard_agent, geofence_agent])
def test_all_agents_handle_empty_input_without_raising(agent):
    finding = agent([])
    assert 0.0 <= finding.risk_level <= 1.0


# --- R-36: an agent's position must not depend on which observations exist ---
# geofence_agent answers a question about GEOMETRY. Deriving its position
# from observations[0] meant a point with no cached readings could not be
# geofence-checked at all, and meant the check silently moved to whichever
# observation happened to sort first. `position` is keyword-only, so R-5's
# uniform `agent(observations)` call still works for all five agents.
_INSIDE_MPA = (9.20, 79.17)      # Krusadai I., Gulf of Mannar Marine National Park
_OPEN_WATER = (13.08, 80.29)     # Chennai


def test_r36_geofence_checks_the_given_position_with_no_observations_at_all():
    finding = geofence_agent([], position=_INSIDE_MPA)
    assert finding.hard_deny is True
    assert "restricted marine zone" in finding.reason
    # Geometry is not evidence: there is no reading to cite, and the empty
    # list is what keeps R-39's "no evidence at this zone" guard truthful.
    assert finding.observations == []


def test_r36_geofence_position_wins_over_the_observations_coordinate():
    """The bug this closes: the check ran wherever observations[0] happened
    to be, not where the question was about."""
    far_away = _obs("wave_height_m", 1.0, lat=_OPEN_WATER[0], lon=_OPEN_WATER[1])
    assert geofence_agent([far_away]).hard_deny is False
    assert geofence_agent([far_away], position=_INSIDE_MPA).hard_deny is True


def test_r36_geofence_without_position_still_falls_back_to_observations():
    inside = _obs("wave_height_m", 1.0, lat=_INSIDE_MPA[0], lon=_INSIDE_MPA[1])
    assert geofence_agent([inside]).hard_deny is True


def test_r36_geofence_with_neither_position_nor_observations_is_neutral():
    finding = geofence_agent([])
    assert finding.hard_deny is False and finding.risk_level == 0.0
    assert "No location data" in finding.reason


def test_r5_uniform_signature_survives_the_position_argument():
    """R-5: exactly five agents, each list[MarineObservation] -> Finding.
    `position` is keyword-only and optional, so the uniform call still
    works for every agent -- that is what made Open Decision 9 resolvable
    without a synthetic position-carrying observation (CLAUDE.md rule 1).
    """
    from orca.planner import AGENTS
    assert len(AGENTS) == 5
    for agent in AGENTS:
        finding = agent([])
        assert finding.agent_name
