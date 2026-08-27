"""Tests for orca/planner.py — written before the implementation.

planner.py is: query -> agents over cached evidence -> policy.resolve()
-> structured answer. It is also where the §8.4 demo behaviour actually
lives: if the named/primary zone is denied, try other zones in order and
recommend the first clean one as a "SAFER ALTERNATIVE"; if none is clean,
stay DO NOT GO / SAFER ALTERNATIVE as policy.resolve() decided, with no
zone swap. The flip test (wave height 3.1m vs 1.0m) must change the
*final* recommendation end-to-end, not just the isolated policy call —
that's the actual proof the whole pipeline is wired together live.
"""
from datetime import datetime, timezone

import pytest

from orca.planner import (
    build_recommendation,
    load_cached_observations,
    observation_id,
    observations_for_zone,
    resolve_zone_from_query,
    run_agents,
)
from orca.schema import MarineObservation
from data.fetch import ZONES

ZONE_A = ZONES[0]  # {"name": "Zone A", "lat": 10.76, "lon": 79.84}
ZONE_B = ZONES[1]  # {"name": "Zone B", "lat": 10.85, "lon": 79.95}


def _obs(variable, value, unit, zone, source="Open-Meteo Marine"):
    return MarineObservation(
        variable=variable, value=value, unit=unit,
        lat=zone["lat"], lon=zone["lon"],
        valid_time=datetime(2026, 8, 26, 4, 0, tzinfo=timezone.utc),
        fetched_at=datetime(2026, 8, 26, 4, 5, tzinfo=timezone.utc),
        source=source, confidence=0.85, freshness_min=5,
        provenance="https://example.test/provenance",
    )


def _clean_go_observations(zone):
    """Calm, warm, productive — everything says go."""
    return [
        _obs("wave_height_m", 1.4, "m", zone),
        _obs("wind_speed_kmh", 10.0, "km/h", zone),
        _obs("sst_c", 28.4, "°C", zone),
        _obs("chlorophyll_mg_m3", 2.0, "mg m^-3", zone, source="NOAA CoastWatch ERDDAP (VIIRS chlorophyll-a)"),
    ]


def _dangerous_observations(zone, wave_height=3.1):
    """High chlorophyll (opportunity) contradicted by dangerous waves."""
    return [
        _obs("wave_height_m", wave_height, "m", zone),
        _obs("wind_speed_kmh", 12.0, "km/h", zone),
        _obs("sst_c", 28.4, "°C", zone),
        _obs("chlorophyll_mg_m3", 2.3, "mg m^-3", zone, source="NOAA CoastWatch ERDDAP (VIIRS chlorophyll-a)"),
    ]


# ---------------------------------------------------------------------------
# Cache loading and evidence identity
# ---------------------------------------------------------------------------

def test_load_cached_observations_reads_the_real_cache():
    observations = load_cached_observations()
    assert len(observations) > 0
    assert all(isinstance(o, MarineObservation) for o in observations)


def test_observation_id_is_deterministic():
    obs = _obs("wave_height_m", 3.1, "m", ZONE_A)
    assert observation_id(obs) == observation_id(obs)


def test_observation_id_differs_for_different_observations():
    a = _obs("wave_height_m", 3.1, "m", ZONE_A)
    b = _obs("wave_height_m", 1.4, "m", ZONE_B)
    assert observation_id(a) != observation_id(b)


# ---------------------------------------------------------------------------
# Zone filtering and query -> zone resolution
# ---------------------------------------------------------------------------

def test_observations_for_zone_filters_by_coordinates():
    obs_a = _obs("wave_height_m", 1.0, "m", ZONE_A)
    obs_b = _obs("wave_height_m", 2.0, "m", ZONE_B)
    result = observations_for_zone([obs_a, obs_b], ZONE_A)
    assert obs_a in result
    assert obs_b not in result


def test_resolve_zone_from_query_matches_named_zone():
    zone = resolve_zone_from_query("Should I go fishing in Zone A from Nagapattinam?", lat=10.76, lon=79.84)
    assert zone["name"] == "Zone A"


def test_resolve_zone_from_query_matches_case_insensitively():
    zone = resolve_zone_from_query("what about zone b today", lat=10.76, lon=79.84)
    assert zone["name"] == "Zone B"


def test_resolve_zone_from_query_falls_back_to_nearest_zone():
    zone = resolve_zone_from_query("Is it safe to go out today?", lat=ZONE_B["lat"], lon=ZONE_B["lon"])
    assert zone["name"] == "Zone B"


# ---------------------------------------------------------------------------
# Agent orchestration
# ---------------------------------------------------------------------------

def test_run_agents_returns_five_findings_with_expected_names():
    findings = run_agents(_clean_go_observations(ZONE_A))
    names = {f.agent_name for f in findings}
    assert names == {
        "eo_satellite_agent", "ocean_state_agent", "weather_agent",
        "hazard_agent", "geofence_agent",
    }


# ---------------------------------------------------------------------------
# build_recommendation — the end-to-end structured answer
# ---------------------------------------------------------------------------

def test_build_recommendation_clean_go():
    obs = _clean_go_observations(ZONE_A)
    rec = build_recommendation("Should I go fishing in Zone A?", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    assert rec.action == "GO"
    assert rec.chosen_zone["name"] == "Zone A"
    assert rec.overridden == []


def test_build_recommendation_hard_deny_falls_back_to_safer_zone():
    """The money shot from §8.4: Zone A denied on waves, Zone B is clean."""
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(ZONE_B)
    rec = build_recommendation("Should I go fishing in Zone A from Nagapattinam?", ZONE_A["lat"], ZONE_A["lon"], observations=obs)

    assert rec.action == "SAFER ALTERNATIVE"
    assert rec.chosen_zone["name"] == "Zone B"
    assert len(rec.overridden) >= 1
    assert any("ocean_state" in f.agent_name for f in rec.overridden)
    assert "3.1" in rec.reason


def test_build_recommendation_no_clean_alternative_stays_do_not_go():
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _dangerous_observations(ZONE_B, wave_height=2.8)
    rec = build_recommendation("Should I go fishing in Zone A?", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    assert rec.action == "DO NOT GO"
    assert rec.chosen_zone is None


def test_build_recommendation_flip_wave_height_changes_decision_end_to_end():
    """The exact §8.4 verification, run through the full planner pipeline,
    not just policy.resolve() in isolation.
    """
    dangerous_obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(ZONE_B)
    dangerous_rec = build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=dangerous_obs)
    assert dangerous_rec.action == "SAFER ALTERNATIVE"
    assert dangerous_rec.chosen_zone["name"] == "Zone B"

    safe_obs = _clean_go_observations(ZONE_A)  # wave_height_m = 1.4, well under 2.5
    safe_rec = build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=safe_obs)
    assert safe_rec.action == "GO"
    assert safe_rec.chosen_zone["name"] == "Zone A"


def test_recommendation_evidence_includes_the_hazard_that_caused_the_override():
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(ZONE_B)
    rec = build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    wave_values = [o.value for o in rec.evidence if o.variable == "wave_height_m" and o.lat == ZONE_A["lat"]]
    assert 3.1 in wave_values


def test_recommendation_id_has_rec_prefix():
    rec = build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=_clean_go_observations(ZONE_A))
    assert rec.id.startswith("rec_")


def test_recommendation_to_dict_matches_api_contract_shape():
    rec = build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=_clean_go_observations(ZONE_A))
    d = rec.to_dict()
    for key in ("id", "action", "reason", "recommendation", "chosen_zone", "overridden", "evidence", "offline_mode"):
        assert key in d
    assert isinstance(d["evidence"], list)
    if d["evidence"]:
        for required in ("id", "variable", "value", "unit", "lat", "lon", "valid_time", "source", "confidence", "provenance"):
            assert required in d["evidence"][0]


def test_build_recommendation_raises_on_zero_observations_everywhere():
    with pytest.raises(ValueError):
        build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=[])


# ---------------------------------------------------------------------------
# agent_findings / zone_summaries — full reasoning trace, for the 3D
# evidence-reasoning graph and geospatial risk-terrain visualizations.
# Nothing here is fabricated: both fields surface computation
# build_recommendation already does internally (run_agents() per zone,
# resolve() per zone) but previously discarded once the final answer was
# picked. See orca/planner.py Recommendation docstring.
# ---------------------------------------------------------------------------

def test_recommendation_agent_findings_covers_all_five_agents():
    rec = build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=_clean_go_observations(ZONE_A))
    names = {f.agent_name for f in rec.agent_findings}
    assert names == {
        "eo_satellite_agent", "ocean_state_agent", "weather_agent",
        "hazard_agent", "geofence_agent",
    }


def test_recommendation_agent_findings_are_the_primary_zones_findings():
    """agent_findings must reflect the queried (primary) zone, not whichever
    zone ends up chosen after a SAFER ALTERNATIVE swap."""
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(ZONE_B)
    rec = build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    assert rec.chosen_zone["name"] == "Zone B"  # swapped

    hazard = next(f for f in rec.agent_findings if f.agent_name == "hazard_agent")
    assert hazard.hard_deny is True
    assert any(o.lat == ZONE_A["lat"] for o in hazard.observations)


def test_recommendation_to_dict_agent_findings_shape():
    rec = build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=_clean_go_observations(ZONE_A))
    d = rec.to_dict()
    assert "agent_findings" in d
    assert len(d["agent_findings"]) == 5
    evidence_ids = {o["id"] for o in d["evidence"]}
    for f in d["agent_findings"]:
        for required in ("agent", "suggests_go", "risk_level", "hard_deny", "reason", "observation_ids"):
            assert required in f
        # every referenced id must resolve to a real evidence entry --
        # no dangling ids, no data invented just for the graph view.
        for obs_id in f["observation_ids"]:
            assert obs_id in evidence_ids


def test_recommendation_zone_summaries_covers_every_zone():
    obs = _clean_go_observations(ZONE_A) + _clean_go_observations(ZONE_B)
    rec = build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    names = {s["name"] for s in rec.zone_summaries}
    assert names == {z["name"] for z in ZONES}
    for s in rec.zone_summaries:
        for required in ("name", "lat", "lon", "action", "risk_level", "hard_deny"):
            assert required in s


def test_recommendation_zone_summaries_risk_level_reflects_worst_agent():
    """Zone A has a hard-denying wave reading (3.1m > 2.5m limit) --
    hazard_agent's risk_level for that is min(3.1/2.5, 1.0) == 1.0, and
    that's the worst (max) of Zone A's five agents, so the summary must
    surface it, not silently pick a calmer agent's number.
    """
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(ZONE_B)
    rec = build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    zone_a_summary = next(s for s in rec.zone_summaries if s["name"] == "Zone A")
    assert zone_a_summary["risk_level"] == pytest.approx(1.0)
    assert zone_a_summary["hard_deny"] is True
    assert zone_a_summary["action"] == "DO NOT GO"

    zone_b_summary = next(s for s in rec.zone_summaries if s["name"] == "Zone B")
    assert zone_b_summary["hard_deny"] is False
    assert zone_b_summary["action"] == "GO"


def test_recommendation_zone_summaries_in_to_dict():
    rec = build_recommendation("Zone A", ZONE_A["lat"], ZONE_A["lon"], observations=_clean_go_observations(ZONE_A))
    d = rec.to_dict()
    assert "zone_summaries" in d
    assert len(d["zone_summaries"]) == len(ZONES)
