"""Tests for orca/schema.py — written before the implementation.

MarineObservation is the ONE type allowed to carry a number to a user
(CLAUDE.md rule 3). These tests exist to make that enforceable, not just
documented: a bare float, a missing source, or a missing valid_time must
be impossible to construct.
"""
from datetime import datetime, timezone

import pytest

from orca.schema import MarineObservation


def _base_kwargs(**overrides):
    kwargs = dict(
        variable="wave_height_m",
        value=3.1,
        unit="m",
        lat=10.76,
        lon=79.84,
        valid_time=datetime(2026, 8, 26, 4, 0, tzinfo=timezone.utc),
        fetched_at=datetime(2026, 8, 26, 4, 5, tzinfo=timezone.utc),
        source="Open-Meteo Marine",
        confidence=0.71,
        freshness_min=15,
        provenance="https://marine-api.open-meteo.com/v1/marine",
    )
    kwargs.update(overrides)
    return kwargs


def test_valid_observation_constructs():
    obs = MarineObservation(**_base_kwargs())
    assert obs.variable == "wave_height_m"
    assert obs.value == 3.1
    assert obs.source == "Open-Meteo Marine"


@pytest.mark.parametrize("bad_source", ["", None])
def test_missing_source_raises(bad_source):
    with pytest.raises(ValueError, match="source"):
        MarineObservation(**_base_kwargs(source=bad_source))


def test_missing_valid_time_raises():
    with pytest.raises(ValueError, match="valid_time"):
        MarineObservation(**_base_kwargs(valid_time=None))


def test_valid_time_wrong_type_raises():
    with pytest.raises(ValueError, match="valid_time"):
        MarineObservation(**_base_kwargs(valid_time="2026-08-26T04:00:00Z"))


@pytest.mark.parametrize("bad_confidence", [-0.01, 1.01, 2.0, -5])
def test_confidence_out_of_range_raises(bad_confidence):
    with pytest.raises(ValueError, match="confidence"):
        MarineObservation(**_base_kwargs(confidence=bad_confidence))


@pytest.mark.parametrize("ok_confidence", [0.0, 1.0, 0.5])
def test_confidence_boundary_values_are_allowed(ok_confidence):
    obs = MarineObservation(**_base_kwargs(confidence=ok_confidence))
    assert obs.confidence == ok_confidence


def test_missing_provenance_raises():
    with pytest.raises(ValueError, match="provenance"):
        MarineObservation(**_base_kwargs(provenance=""))


def test_to_dict_contains_all_fields_as_json_safe_values():
    obs = MarineObservation(**_base_kwargs())
    d = obs.to_dict()

    assert d["variable"] == "wave_height_m"
    assert d["value"] == 3.1
    assert d["unit"] == "m"
    assert d["lat"] == 10.76
    assert d["lon"] == 79.84
    assert d["source"] == "Open-Meteo Marine"
    assert d["confidence"] == 0.71
    assert d["freshness_min"] == 15
    assert d["provenance"] == "https://marine-api.open-meteo.com/v1/marine"

    # datetimes must serialise to ISO 8601 strings, not datetime objects,
    # because this dict is handed straight to FastAPI/json.dumps.
    assert d["valid_time"] == "2026-08-26T04:00:00+00:00"
    assert d["fetched_at"] == "2026-08-26T04:05:00+00:00"
    assert isinstance(d["valid_time"], str)
    assert isinstance(d["fetched_at"], str)


def test_to_dict_is_json_serialisable():
    import json

    obs = MarineObservation(**_base_kwargs())
    json.dumps(obs.to_dict())  # must not raise
