"""Matching IMD CAP warnings to a zone.

The property that matters is not "does it find alerts" -- most days there
are none over Tamil Nadu, and that is the correct answer. It is that the
three buckets stay apart: a warning ORCA cannot geolocate must never be
counted as covering you, and a warning 700 km away must never be shown as
if it were overhead.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from orca.alerts import (
    active_alerts_for,
    distance_to_polygon_km,
    point_in_polygon,
    worst_severity,
)

NOW = datetime(2026, 8, 30, 6, 0, tzinfo=timezone.utc)
LATER = (NOW + timedelta(days=1)).isoformat()
EARLIER = (NOW - timedelta(days=1)).isoformat()

# A square over the Bay of Bengal, well offshore of Chennai.
SQUARE = [[12.0, 80.0], [14.0, 80.0], [14.0, 82.0], [12.0, 82.0], [12.0, 80.0]]


def _alert(**kw):
    base = {
        "identifier": "urn:oid:test",
        "event": "Cyclone",
        "headline": "test",
        "severity": "Severe",
        "expires": LATER,
        "polygon": SQUARE,
        "area_desc": "TEST AREA",
    }
    base.update(kw)
    return base


def _payload(*alerts):
    return {
        "source": "India Meteorological Department (CAP v1.2 public feed)",
        "provenance": "https://cap-sources.s3.amazonaws.com/in-imd-en/rss.xml",
        "fetched_at": NOW.isoformat(),
        "alerts": list(alerts),
    }


# --- the geometry -------------------------------------------------------

def test_a_point_inside_the_polygon_is_inside():
    assert point_in_polygon(13.0, 81.0, SQUARE)


def test_a_point_outside_the_polygon_is_outside():
    assert not point_in_polygon(13.0, 79.0, SQUARE)   # west of it
    assert not point_in_polygon(20.0, 81.0, SQUARE)   # north of it


def test_a_degenerate_polygon_contains_nothing_rather_than_everything():
    """Two vertices is not an area. Returning True here would put every
    boat inside a malformed warning."""
    assert not point_in_polygon(13.0, 81.0, [[12.0, 80.0], [14.0, 82.0]])
    assert not point_in_polygon(13.0, 81.0, [])


def test_distance_to_a_polygon_is_zero_ish_at_its_own_vertex():
    assert distance_to_polygon_km(12.0, 80.0, SQUARE) == pytest.approx(0.0, abs=0.001)


def test_distance_grows_as_you_move_away():
    near = distance_to_polygon_km(13.0, 79.0, SQUARE)
    far = distance_to_polygon_km(13.0, 70.0, SQUARE)
    assert far > near > 0


# --- the three buckets --------------------------------------------------

def test_a_warning_over_your_head_is_covering():
    r = active_alerts_for(13.0, 81.0, _payload(_alert()), NOW)
    assert r["checked"]
    assert len(r["covering"]) == 1
    assert r["covering"][0]["distance_km"] == 0.0
    assert not r["elsewhere"]


def test_a_warning_somewhere_else_is_elsewhere_and_carries_a_real_distance():
    r = active_alerts_for(13.0, 70.0, _payload(_alert()), NOW)
    assert not r["covering"]
    assert len(r["elsewhere"]) == 1
    assert r["elsewhere"][0]["distance_km"] > 500


def test_a_warning_with_no_polygon_is_never_counted_as_covering_you():
    """CAP allows an alert to name only a geocode. ORCA cannot test
    containment against that, so it says so instead of assuming either
    way."""
    r = active_alerts_for(13.0, 81.0, _payload(_alert(polygon=None)), NOW)
    assert not r["covering"]
    assert not r["elsewhere"]
    assert len(r["ungeolocated"]) == 1


def test_an_expired_warning_is_dropped_from_every_bucket():
    r = active_alerts_for(13.0, 81.0, _payload(_alert(expires=EARLIER)), NOW)
    assert not r["covering"]
    assert not r["elsewhere"]
    assert not r["ungeolocated"]


def test_a_warning_with_no_expiry_is_kept_because_absent_is_not_expired():
    r = active_alerts_for(13.0, 81.0, _payload(_alert(expires=None)), NOW)
    assert len(r["covering"]) == 1


def test_elsewhere_is_sorted_nearest_first():
    far = _alert(polygon=[[0.0, 60.0], [1.0, 60.0], [1.0, 61.0], [0.0, 60.0]])
    r = active_alerts_for(13.0, 79.0, _payload(far, _alert()), NOW)
    dists = [a["distance_km"] for a in r["elsewhere"]]
    assert dists == sorted(dists)


# --- never fetched is not the same as nothing published -----------------

def test_no_cache_is_reported_as_not_checked_not_as_all_clear():
    """The distinction the whole feature rests on. "We did not look" and
    "IMD has published nothing" must never render the same way."""
    r = active_alerts_for(13.0, 81.0, None, NOW)
    assert r["checked"] is False
    assert r["reason"]
    assert r["covering"] == []


def test_an_empty_feed_is_checked_with_nothing_found():
    r = active_alerts_for(13.0, 81.0, _payload(), NOW)
    assert r["checked"] is True
    assert r["reason"] is None
    assert r["covering"] == []


def test_the_result_carries_the_feed_source_and_fetch_time():
    r = active_alerts_for(13.0, 81.0, _payload(_alert()), NOW)
    assert "India Meteorological Department" in r["source"]
    assert r["fetched_at"]
    assert r["provenance"].startswith("https://")


# --- severity -----------------------------------------------------------

def test_worst_severity_picks_the_most_severe_cap_level():
    assert worst_severity([_alert(severity="Minor"), _alert(severity="Extreme")]) == "Extreme"
    assert worst_severity([_alert(severity="Moderate"), _alert(severity="Severe")]) == "Severe"


def test_worst_severity_of_nothing_is_none_not_a_reassuring_word():
    assert worst_severity([]) is None


def test_an_unknown_severity_string_sorts_last_rather_than_crashing():
    assert worst_severity([_alert(severity="Weird"), _alert(severity="Minor")]) == "Minor"
