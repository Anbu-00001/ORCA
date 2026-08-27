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
    _zone_by_substring,
)
from orca.schema import MarineObservation
from data.fetch import ZONES

# Real zones, picked (not indexed positionally) so the query text below
# ("fishing in Nagapattinam") unambiguously name-matches the one this
# test rigs observations for. Nagapattinam and Karaikal are real,
# geographically adjacent Tamil Nadu fishing harbours (data/fetch.py) --
# a genuine "is there a clean zone nearby" pair, not an arbitrary one.
ZONE_A = next(z for z in ZONES if z["name"] == "Nagapattinam")
ZONE_B = next(z for z in ZONES if z["name"] == "Karaikal")


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
    zone = resolve_zone_from_query("Should I go fishing near Nagapattinam?", lat=10.76, lon=79.84)
    assert zone["name"] == "Nagapattinam"


def test_resolve_zone_from_query_matches_case_insensitively():
    zone = resolve_zone_from_query("what about karaikal today", lat=10.76, lon=79.84)
    assert zone["name"] == "Karaikal"


def test_resolve_zone_from_query_falls_back_to_nearest_zone():
    zone = resolve_zone_from_query("Is it safe to go out today?", lat=ZONE_B["lat"], lon=ZONE_B["lon"])
    assert zone["name"] == "Karaikal"


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
    rec = build_recommendation("Should I go fishing near Nagapattinam?", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    assert rec.action == "GO"
    assert rec.chosen_zone["name"] == "Nagapattinam"
    assert rec.overridden == []


def test_build_recommendation_hard_deny_falls_back_to_safer_zone():
    """The money shot from §8.4: Nagapattinam denied on waves, Karaikal is clean."""
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(ZONE_B)
    rec = build_recommendation("Should I go fishing near Nagapattinam?", ZONE_A["lat"], ZONE_A["lon"], observations=obs)

    assert rec.action == "SAFER ALTERNATIVE"
    assert rec.chosen_zone["name"] == "Karaikal"
    assert len(rec.overridden) >= 1
    assert any("ocean_state" in f.agent_name for f in rec.overridden)
    assert "3.1" in rec.reason


def test_build_recommendation_no_clean_alternative_stays_do_not_go():
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _dangerous_observations(ZONE_B, wave_height=2.8)
    rec = build_recommendation("Should I go fishing near Nagapattinam?", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    assert rec.action == "DO NOT GO"
    assert rec.chosen_zone is None


def test_build_recommendation_flip_wave_height_changes_decision_end_to_end():
    """The exact §8.4 verification, run through the full planner pipeline,
    not just policy.resolve() in isolation.
    """
    dangerous_obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(ZONE_B)
    dangerous_rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=dangerous_obs)
    assert dangerous_rec.action == "SAFER ALTERNATIVE"
    assert dangerous_rec.chosen_zone["name"] == "Karaikal"

    safe_obs = _clean_go_observations(ZONE_A)  # wave_height_m = 1.4, well under 2.5
    safe_rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=safe_obs)
    assert safe_rec.action == "GO"
    assert safe_rec.chosen_zone["name"] == "Nagapattinam"


def test_recommendation_evidence_includes_the_hazard_that_caused_the_override():
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(ZONE_B)
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    wave_values = [o.value for o in rec.evidence if o.variable == "wave_height_m" and o.lat == ZONE_A["lat"]]
    assert 3.1 in wave_values


def test_recommendation_id_has_rec_prefix():
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=_clean_go_observations(ZONE_A))
    assert rec.id.startswith("rec_")


def test_recommendation_to_dict_matches_api_contract_shape():
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=_clean_go_observations(ZONE_A))
    d = rec.to_dict()
    for key in ("id", "action", "reason", "recommendation", "chosen_zone", "overridden", "evidence", "offline_mode"):
        assert key in d
    assert isinstance(d["evidence"], list)
    if d["evidence"]:
        for required in ("id", "variable", "value", "unit", "lat", "lon", "valid_time", "source", "confidence", "provenance"):
            assert required in d["evidence"][0]


def test_build_recommendation_raises_on_zero_observations_everywhere():
    with pytest.raises(ValueError):
        build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=[])


# ---------------------------------------------------------------------------
# agent_findings / zone_summaries — full reasoning trace, for the 3D
# evidence-reasoning graph and geospatial risk-terrain visualizations.
# Nothing here is fabricated: both fields surface computation
# build_recommendation already does internally (run_agents() per zone,
# resolve() per zone) but previously discarded once the final answer was
# picked. See orca/planner.py Recommendation docstring.
# ---------------------------------------------------------------------------

def test_recommendation_agent_findings_covers_all_five_agents():
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=_clean_go_observations(ZONE_A))
    names = {f.agent_name for f in rec.agent_findings}
    assert names == {
        "eo_satellite_agent", "ocean_state_agent", "weather_agent",
        "hazard_agent", "geofence_agent",
    }


def test_recommendation_agent_findings_are_the_primary_zones_findings():
    """agent_findings must reflect the queried (primary) zone, not whichever
    zone ends up chosen after a SAFER ALTERNATIVE swap."""
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(ZONE_B)
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    assert rec.chosen_zone["name"] == "Karaikal"  # swapped

    hazard = next(f for f in rec.agent_findings if f.agent_name == "hazard_agent")
    assert hazard.hard_deny is True
    assert any(o.lat == ZONE_A["lat"] for o in hazard.observations)


def test_recommendation_to_dict_agent_findings_shape():
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=_clean_go_observations(ZONE_A))
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
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    names = {s["name"] for s in rec.zone_summaries}
    assert names == {z["name"] for z in ZONES}
    for s in rec.zone_summaries:
        for required in ("name", "lat", "lon", "action", "risk_level", "hard_deny"):
            assert required in s


def test_recommendation_zone_summaries_risk_level_reflects_worst_agent():
    """Nagapattinam has a hard-denying wave reading (3.1m > 2.5m limit) --
    hazard_agent's risk_level for that is min(3.1/2.5, 1.0) == 1.0, and
    that's the worst (max) of Nagapattinam's five agents, so the summary
    must surface it, not silently pick a calmer agent's number.
    """
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(ZONE_B)
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    zone_a_summary = next(s for s in rec.zone_summaries if s["name"] == "Nagapattinam")
    assert zone_a_summary["risk_level"] == pytest.approx(1.0)
    assert zone_a_summary["hard_deny"] is True
    assert zone_a_summary["action"] == "DO NOT GO"

    zone_b_summary = next(s for s in rec.zone_summaries if s["name"] == "Karaikal")
    assert zone_b_summary["hard_deny"] is False
    assert zone_b_summary["action"] == "GO"


def test_recommendation_zone_summaries_in_to_dict():
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=_clean_go_observations(ZONE_A))
    d = rec.to_dict()
    assert "zone_summaries" in d
    assert len(d["zone_summaries"]) == len(ZONES)


# ---------------------------------------------------------------------------
# _zone_by_substring / resolved_zone — the seam orca/agentic.py hooks into.
# resolve_zone_from_query()'s own behaviour must not change at all (these
# mirror the existing tests above it); _zone_by_substring is the new,
# directly-testable piece, and resolved_zone is the override orca/agentic.py
# uses to hand in an already-picked zone without recomputing it.
# ---------------------------------------------------------------------------

def test_zone_by_substring_matches_named_zone():
    zone = _zone_by_substring("Should I go fishing near Nagapattinam?", ZONES)
    assert zone["name"] == "Nagapattinam"


def test_zone_by_substring_returns_none_when_nothing_matches():
    assert _zone_by_substring("Is it safe to go out today?", ZONES) is None


def test_resolve_zone_from_query_unchanged_when_no_match_still_falls_back_to_nearest():
    # Regression guard for the _zone_by_substring extraction: behaviour of
    # the public function must be identical to before the refactor.
    zone = resolve_zone_from_query("Is it safe to go out today?", lat=ZONE_B["lat"], lon=ZONE_B["lon"])
    assert zone["name"] == ZONE_B["name"]


def test_build_recommendation_resolved_zone_overrides_query_matching():
    # A query that would substring-match Nagapattinam, but resolved_zone
    # says Karaikal -- resolved_zone must win, proving orca/agentic.py can
    # actually steer zone selection.
    rec = build_recommendation(
        "Should I go fishing near Nagapattinam?",
        ZONE_A["lat"], ZONE_A["lon"],
        observations=_clean_go_observations(ZONE_B),
        resolved_zone=ZONE_B,
    )
    assert rec.recommendation.startswith(f"Go to {ZONE_B['name']}")


def test_build_recommendation_resolved_zone_none_matches_old_behaviour():
    rec = build_recommendation(
        "Should I go fishing near Nagapattinam?",
        ZONE_A["lat"], ZONE_A["lon"],
        observations=_clean_go_observations(ZONE_A),
        resolved_zone=None,
    )
    assert rec.recommendation.startswith(f"Go to {ZONE_A['name']}")


def test_recommendation_agentic_fields_default_to_plain_behaviour():
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=_clean_go_observations(ZONE_A))
    assert rec.agentic_used is False
    assert rec.detected_language == "en"
    assert rec.cited_evidence_ids == []
    d = rec.to_dict()
    assert d["agentic_used"] is False
    assert d["detected_language"] == "en"
    assert d["cited_evidence_ids"] == []


# ---------------------------------------------------------------------------
# R-59 / G-14 — danger with no opportunity must never resolve to GO
#
# policy.py gates rule 2 on opportunity AND danger, so a zone with hazards
# but nothing suggesting go falls through to rule 3 and returns GO with the
# reason "No hazards found; conditions acceptable" -- contradicted by the
# same response's own evidence. The correction is in the planner;
# policy.py stays frozen (N-5), and tests/test_policy.py's
# test_danger_without_opportunity_does_not_trigger_rule_2 must stay green.
# ---------------------------------------------------------------------------

def _danger_without_opportunity(zone, wind_speed_kmh=30.0):
    """Hazardous wind, and nothing an agent would call an opportunity:
    water too cold to be productive and effectively no chlorophyll. This
    is the live Kanyakumari/Colachel shape -- the trigger is inverted, so
    the worse the fishing looks, the more likely the override is skipped.
    """
    return [
        _obs("wave_height_m", 1.2, "m", zone),
        _obs("wind_speed_kmh", wind_speed_kmh, "km/h", zone),
        _obs("sst_c", 21.0, "°C", zone),
        _obs("chlorophyll_mg_m3", 0.05, "mg m^-3", zone, source="NOAA CoastWatch ERDDAP (VIIRS chlorophyll-a)"),
    ]


def test_r59_danger_without_opportunity_never_resolves_to_go():
    obs = _danger_without_opportunity(ZONE_A)
    findings = run_agents(obs)
    # Precondition: this really is the R-59 shape, not an R-39 one --
    # there IS evidence, and there IS danger, and nothing suggests go.
    assert any(f.observations for f in findings)
    assert any(f.risk_level >= 0.6 for f in findings)
    assert not any(f.suggests_go for f in findings)

    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    assert rec.action != "GO"
    assert "No hazards found" not in rec.reason


def test_r59_overridden_is_empty_because_nothing_was_sacrificed():
    """R-11: overridden names what was given up. Nothing suggested go, so
    nothing was given up -- an empty list is the honest answer here.
    """
    rec = build_recommendation(
        "Nagapattinam", ZONE_A["lat"], ZONE_A["lon"],
        observations=_danger_without_opportunity(ZONE_A),
    )
    assert rec.overridden == []


def test_r59_names_the_worst_danger_not_the_first():
    """R-37's principle applied on the path written for R-59: with wind and
    waves both over threshold, the more severe one is named.
    """
    obs = [
        _obs("wave_height_m", 2.4, "m", ZONE_A),      # high risk, under the 2.5 hard deny
        _obs("wind_speed_kmh", 26.0, "km/h", ZONE_A),  # over threshold, but milder
        _obs("sst_c", 21.0, "°C", ZONE_A),
        _obs("chlorophyll_mg_m3", 0.05, "mg m^-3", ZONE_A, source="NOAA CoastWatch ERDDAP (VIIRS chlorophyll-a)"),
    ]
    findings = run_agents(obs)
    danger = [f for f in findings if f.risk_level >= 0.6]
    assert len(danger) >= 2, "fixture must produce more than one danger for this to mean anything"
    worst = max(danger, key=lambda f: f.risk_level)

    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    assert rec.reason == worst.reason


def test_g14_no_live_zone_resolves_to_go_while_carrying_a_hazard():
    """The G-14 gate, over the real cache rather than a fixture. Kanyakumari
    (0.67) and Colachel (0.63) are the two live regressions.
    """
    observations = load_cached_observations()
    for zone in ZONES:
        rec = build_recommendation(f"fishing at {zone['name']}", zone["lat"], zone["lon"], observations=observations)
        worst = max((f.risk_level for f in rec.agent_findings), default=0.0)
        if worst >= 0.6:
            assert rec.action != "GO", f"{zone['name']} resolves to GO carrying risk {worst}"
            assert "No hazards found" not in rec.reason, f"{zone['name']} claims no hazards at risk {worst}"


# ---------------------------------------------------------------------------
# R-39 / G-13 — no evidence at all must never resolve to GO
# ---------------------------------------------------------------------------

def test_r39_zone_with_no_observations_cannot_be_assessed():
    """Five neutral findings are not five clean bills of health. Deliberately
    CANNOT ASSESS and not DO NOT GO: conflating "I do not know" with "I know
    it is dangerous" teaches users to discount the verdict that must never
    be discounted (Open Decision 8, resolved).
    """
    blind = {"name": "Blind Zone", "lat": 12.0, "lon": 85.0}
    rec = build_recommendation("fishing at Blind Zone", blind["lat"], blind["lon"], observations=_clean_go_observations(ZONE_A), zones=[blind])
    assert rec.action == "CANNOT ASSESS"
    assert rec.evidence == []
    assert rec.chosen_zone is None
    assert "cannot assess" in rec.recommendation.lower()
    # It must not read as a safety judgement in either direction.
    assert not rec.recommendation.startswith("Go to")
    assert "Do not go" not in rec.recommendation


def test_r39b_cannot_assess_still_offers_a_genuine_nearby_alternative():
    """Inability to assess one zone is not inability to help -- but the
    verdict stays CANNOT ASSESS, because ORCA still cannot assess where
    they actually asked about.
    """
    blind = {"name": "Blind Zone", "lat": ZONE_B["lat"] + 0.2, "lon": ZONE_B["lon"] + 0.1}
    rec = build_recommendation(
        "fishing at Blind Zone", blind["lat"], blind["lon"],
        observations=_clean_go_observations(ZONE_B), zones=[blind, ZONE_B],
    )
    assert rec.action == "CANNOT ASSESS"
    assert rec.chosen_zone["name"] == ZONE_B["name"]
    assert "Do not go" not in rec.recommendation


# ---------------------------------------------------------------------------
# R-60 — the alternative search is bounded by distance
# ---------------------------------------------------------------------------

def test_r60_nearby_alternative_is_still_offered():
    """The cap must not break the §8.4 demo: Nagapattinam -> Karaikal is
    18 km, comfortably inside it.
    """
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(ZONE_B)
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=obs)
    assert rec.action == "SAFER ALTERNATIVE"
    assert rec.chosen_zone["name"] == ZONE_B["name"]


def test_r60_alternative_beyond_the_cap_is_not_offered():
    """Chennai is 267 km from Nagapattinam -- a real zone, genuinely clean,
    and not a place a boat diverts to. The honest answer is the no-swap text.
    """
    far = next(z for z in ZONES if z["name"] == "Chennai")
    obs = _dangerous_observations(ZONE_A, wave_height=3.1) + _clean_go_observations(far)
    rec = build_recommendation("Nagapattinam", ZONE_A["lat"], ZONE_A["lon"], observations=obs, zones=[ZONE_A, far])
    assert rec.chosen_zone is None
    assert "Chennai" not in rec.recommendation


def test_r60_cap_matches_the_prd():
    """The cap is a documented figure (PRD R-60), not an incidental one.
    If it moves, the PRD row moves with it -- §16.2.
    """
    from orca import planner
    assert planner.MAX_ALTERNATIVE_KM == 100.0


def test_r60_uses_the_existing_haversine_not_a_second_one():
    """R-60 explicitly reuses orca.agents._haversine_km, which is already
    tested. A second distance function is the thing to avoid.
    """
    from orca import agents, planner
    assert planner._haversine_km is agents._haversine_km
