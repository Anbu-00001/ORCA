"""The Leeway drift model.

This is the one number in ORCA that gets read out loud to a rescue
service, so the tests here are mostly about directions being the right way
round. A sign error in the wind convention does not crash anything -- it
quietly sends a search boat 180 degrees the wrong way.

Every test is closed-form: the expected values are computed from Allen &
Plourde's published coefficients by hand, not captured from a previous
run of this code.
"""
from __future__ import annotations

import math

import pytest

from orca.drift import (
    FISHING_VESSEL_GENERAL,
    bearing_to_compass,
    drift_forecast,
)


# --- direction conventions, the part most likely to be backwards ---------

def test_wind_from_the_north_drives_the_hull_south():
    """`wind_direction_deg` is meteorological: the direction it blows FROM.

    A north wind pushes a boat south. If this ever reads 0 degrees, the
    convention has been flipped somewhere.
    """
    r = drift_forecast(13.0, 80.3, 20.0, 0.0, 0.0, 0.0, hours=6)
    assert r["ok"]
    assert r["bearing_deg"] == pytest.approx(180.0, abs=0.5)
    assert bearing_to_compass(r["bearing_deg"]) == "S"
    assert r["centre"][0] < 13.0  # actually moved south


def test_current_direction_is_where_it_flows_to_not_where_it_comes_from():
    """Open-Meteo documents ocean_current_direction as the heading the
    current is going TOWARDS -- the opposite convention to wind."""
    r = drift_forecast(13.0, 80.3, 0.0, 0.0, 2.0, 90.0, hours=6)
    assert r["bearing_deg"] == pytest.approx(90.0, abs=0.5)
    assert r["centre"][1] > 80.3  # actually moved east


def test_a_pure_current_carries_the_hull_at_exactly_the_current_speed():
    """With no wind there is no leeway, so 2 km/h for 6 h is 12 km. Any
    other answer means leeway is leaking in when it should be zero."""
    r = drift_forecast(13.0, 80.3, 0.0, 0.0, 2.0, 90.0, hours=6)
    assert r["distance_km"] == pytest.approx(12.0, abs=0.05)


# --- the published coefficients, applied as published -------------------

def test_downwind_leeway_is_two_point_four_seven_percent_of_wind_speed():
    """FISHING-VESSEL-1's DWL slope is 2.47 (percent of W10), offset 0.

    Hand-computed: 20 km/h = 5.5556 m/s; 2.47% of that is 0.13722 m/s;
    over 6 h (21600 s) that is 2.964 km. No current, so that is the whole
    displacement.
    """
    expected_km = 0.0247 * (20.0 / 3.6) * 21600 / 1000.0
    r = drift_forecast(13.0, 80.3, 20.0, 0.0, 0.0, 0.0, hours=6)
    assert r["distance_km"] == pytest.approx(expected_km, abs=0.01)


def test_drift_distance_is_linear_in_time_because_the_field_is_held_fixed():
    """Not a modelling claim -- a statement of the limitation. The model
    holds wind and current constant, so doubling the horizon doubles the
    distance. The 24 h note has to say so, and the next test checks it."""
    six = drift_forecast(13.0, 80.3, 20.0, 45.0, 1.0, 200.0, hours=6)
    twelve = drift_forecast(13.0, 80.3, 20.0, 45.0, 1.0, 200.0, hours=12)
    # Tolerance is the 2-decimal rounding on distance_km, not slack in the model.
    assert twelve["distance_km"] == pytest.approx(2 * six["distance_km"], abs=0.02)
    assert twelve["bearing_deg"] == pytest.approx(six["bearing_deg"], abs=1e-6)


def test_the_long_horizon_admits_it_is_a_sketch():
    short = drift_forecast(13.0, 80.3, 20.0, 0.0, 1.0, 90.0, hours=6)
    long = drift_forecast(13.0, 80.3, 20.0, 0.0, 1.0, 90.0, hours=24)
    assert "reasonable" in short["confidence_note"]
    assert "sketch" in long["confidence_note"]


# --- the uncertainty box ------------------------------------------------

def test_the_box_has_four_corners_and_encloses_the_centre():
    r = drift_forecast(13.0, 80.3, 25.0, 90.0, 1.0, 180.0, hours=6)
    box = r["box"]
    assert len(box) == 4
    lats = [c[0] for c in box]
    lons = [c[1] for c in box]
    assert min(lats) <= r["centre"][0] <= max(lats)
    assert min(lons) <= r["centre"][1] <= max(lons)


def test_the_box_grows_with_wind_because_leeway_uncertainty_scales_with_it():
    """Both the slope and its standard deviation multiply W10, so a
    stronger wind must widen the search area, not just move it."""
    def spread(wind):
        r = drift_forecast(13.0, 80.3, wind, 0.0, 0.0, 0.0, hours=6)
        lats = [c[0] for c in r["box"]]
        lons = [c[1] for c in r["box"]]
        return (max(lats) - min(lats)) + (max(lons) - min(lons))

    assert spread(40.0) > spread(10.0)


def test_a_calm_sea_still_leaves_a_box_from_the_offset_terms():
    """At zero wind the slope terms vanish but the +/-1 sigma offsets do
    not, so the box collapses toward a point without becoming one. A
    zero-area box would be a claim of certainty nobody has."""
    r = drift_forecast(13.0, 80.3, 0.0, 0.0, 0.0, 0.0, hours=6)
    lats = [c[0] for c in r["box"]]
    assert max(lats) - min(lats) > 0.0


def test_the_hull_never_drifts_upwind():
    """The -1 sigma downwind bound is floored at zero. Without the floor a
    light wind with a large sigma would put a corner of the box UPWIND,
    which no hull does."""
    r = drift_forecast(13.0, 80.3, 5.0, 0.0, 0.0, 0.0, hours=12)
    # Wind from the north: nothing may end up north of where it started.
    assert all(corner[0] <= 13.0 + 1e-9 for corner in r["box"])


# --- refusing rather than guessing --------------------------------------

@pytest.mark.parametrize(
    "kwargs,expected",
    [
        (dict(wind_speed_kmh=None), "wind speed"),
        (dict(wind_direction_deg=None), "wind direction"),
        (dict(current_speed_kmh=None), "current speed"),
        (dict(current_direction_deg=None), "current direction"),
    ],
)
def test_a_missing_input_is_refused_never_defaulted(kwargs, expected):
    """CLAUDE.md rule 1. A drift box built on an assumed wind direction is
    a fabricated position, and this one gets passed to a rescue."""
    args = dict(
        lat=13.0, lon=80.3, wind_speed_kmh=20.0, wind_direction_deg=0.0,
        current_speed_kmh=1.0, current_direction_deg=90.0, hours=6,
    )
    args.update(kwargs)
    r = drift_forecast(**args)
    assert r["ok"] is False
    assert expected in r["missing"]
    assert "guess" in r["reason"]


# --- provenance ---------------------------------------------------------

def test_the_coefficients_name_their_source():
    """These numbers are empirical and are not ORCA's. If they are ever
    edited, the citation has to be edited with them."""
    assert "CG-D-08-99" in FISHING_VESSEL_GENERAL["source"]
    assert "Allen & Plourde" in FISHING_VESSEL_GENERAL["source"]
    assert FISHING_VESSEL_GENERAL["dwl_slope"] == 2.47
    assert FISHING_VESSEL_GENERAL["cwl_slope"] == 2.76


def test_every_result_carries_its_model_and_source():
    r = drift_forecast(13.0, 80.3, 20.0, 0.0, 1.0, 90.0, hours=6)
    assert r["source"]
    assert r["provenance"].startswith("https://")
    assert r["model"]


def test_compass_names_round_to_the_nearest_of_sixteen_points():
    assert bearing_to_compass(0.0) == "N"
    assert bearing_to_compass(90.0) == "E"
    assert bearing_to_compass(180.0) == "S"
    assert bearing_to_compass(270.0) == "W"
    assert bearing_to_compass(359.9) == "N"
    assert bearing_to_compass(22.5) == "NNE"
