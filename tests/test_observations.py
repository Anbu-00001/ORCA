"""The uplink quarantine (docs/MOBILE_APP.md §5, build-order step 6).

The one property this file exists to prove is negative: a reading uploaded
by a boat CANNOT reach the advisory. Everything else here is detail.

That matters more than it sounds. ORCA's whole defensible claim is that
every number it shows traces to a named source with a real provenance
(CLAUDE.md rule 3). The moment a fisherman's typed-in temperature can be
cited in a safety verdict, that claim is false -- and it would be false
quietly, which is worse. So the separation is asserted, not assumed.

Every test runs with no key, no network and no model.
"""
from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from orca import observations
from orca.api import app
from orca.planner import load_cached_observations

client = TestClient(app)


def valid_payload(**overrides) -> dict:
    payload = {
        "variable": "sst_c",
        "value": 29.4,
        "lat": 9.2812,
        "lon": 79.1234,
        "observed_at": "2026-08-29T10:00:00+00:00",
        "method": "instrument",
        "instrument": "NMEA 0183 $SDMTW",
        "position_source": "gps",
        "position_accuracy_m": 8.0,
        "consent": True,
        "device_id": "test-device",
    }
    payload.update(overrides)
    return payload


# --- THE POINT OF THE WHOLE MODULE --------------------------------------

def test_an_uploaded_reading_is_invisible_to_the_advisory(tmp_path):
    """THE test. If this ever fails, ORCA is citing unverified data in a
    safety verdict and the fix is not to adjust this assertion."""
    observations.store(valid_payload(), directory=tmp_path)
    stored = observations.load_all(tmp_path)
    assert len(stored) == 1                      # it really was written

    # The advisory loader reads data/cache/ and must see nothing of it.
    cached = load_cached_observations()
    fleet_sources = {o.source for o in cached if "Fleet" in o.source}
    assert fleet_sources == set(), (
        "a fleet-reported reading reached load_cached_observations()"
    )


def test_the_quarantine_directory_is_not_the_cache_directory():
    """A one-line guard against the single edit that would break
    everything above -- pointing OBSERVATIONS_DIR at data/cache/."""
    from orca.planner import CACHE_DIR
    assert observations.OBSERVATIONS_DIR.resolve() != CACHE_DIR.resolve()
    assert "cache" not in observations.OBSERVATIONS_DIR.name


def test_every_record_says_it_does_not_affect_the_advisory(tmp_path):
    """Carried INSIDE the record, so a row copied into a spreadsheet or a
    plot still states what it is."""
    record = observations.store(valid_payload(), directory=tmp_path)
    assert record["affects_advisory"] is False
    assert "unverified" in record["source"].lower()
    assert "NOT used to compute any advisory" in record["provenance"]


# --- consent ------------------------------------------------------------

@pytest.mark.parametrize("consent", [None, False, "yes", 1])
def test_consent_must_be_explicitly_true(consent, tmp_path):
    """Not defaulted, not truthy-coerced. A boat track is personally
    identifying and commercially sensitive; "1" is not consent."""
    with pytest.raises(observations.ObservationRejected, match="consent"):
        observations.store(valid_payload(consent=consent), directory=tmp_path)


def test_a_refused_upload_writes_nothing(tmp_path):
    with pytest.raises(observations.ObservationRejected):
        observations.store(valid_payload(consent=False), directory=tmp_path)
    assert observations.load_all(tmp_path) == []


# --- position privacy ---------------------------------------------------

def test_position_is_coarsened_by_default(tmp_path):
    """~11 km: enough to place a reading oceanographically, not enough to
    identify someone's fishing ground."""
    record = observations.store(valid_payload(), directory=tmp_path)
    assert record["lat"] == 9.3 and record["lon"] == 79.1
    assert record["position_precision"] == "rounded to 1 dp"


def test_precision_is_possible_but_must_be_asked_for(tmp_path):
    record = observations.store(
        valid_payload(precise_position=True), directory=tmp_path)
    assert record["lat"] == 9.2812
    assert record["position_precision"] == "exact"


def test_the_raw_device_id_is_never_stored(tmp_path):
    """Repeat reports from one boat must be groupable for QC without the
    boat being identifiable."""
    record = observations.store(
        valid_payload(device_id="imei-490123456789012"), directory=tmp_path)
    blob = json.dumps(record)
    assert "490123456789012" not in blob
    assert record["reporter"].startswith("boat_")
    # Stable, or grouping is impossible.
    again = observations.observation_record(valid_payload(device_id="imei-490123456789012"))
    assert again["reporter"] == record["reporter"]


def test_how_the_position_was_obtained_is_part_of_the_reading(tmp_path):
    with pytest.raises(observations.ObservationRejected, match="position_source"):
        observations.store(valid_payload(position_source=None), directory=tmp_path)


# --- what is accepted ---------------------------------------------------

def test_only_variables_a_boat_can_actually_measure_are_accepted(tmp_path):
    """A variable nobody on a boat can measure is a variable whose uploads
    are guesses. Chlorophyll is satellite-derived; there is no transducer."""
    with pytest.raises(observations.ObservationRejected, match="not something ORCA accepts"):
        observations.store(valid_payload(variable="chlorophyll_mg_m3"), directory=tmp_path)


@pytest.mark.parametrize("value", [85.0, -3.0])
def test_implausible_values_are_refused_with_a_usable_reason(value, tmp_path):
    """85 degC is a Fahrenheit reading in a Celsius field -- the single
    most likely real-world upload error."""
    with pytest.raises(observations.ObservationRejected, match="plausible range"):
        observations.store(valid_payload(value=value), directory=tmp_path)


def test_confidence_is_capped_below_every_real_source(tmp_path):
    """data/cache/ sources run 0.7-0.95. Fleet data must never rank with
    them, and an eyeballed reading must rank below an instrument one."""
    instrument = observations.store(valid_payload(), directory=tmp_path)
    manual = observations.store(
        valid_payload(method="manual", instrument=None), directory=tmp_path)
    assert instrument["confidence"] <= 0.35
    assert manual["confidence"] < instrument["confidence"]


def test_whether_a_human_or_an_instrument_read_it_is_recorded(tmp_path):
    with pytest.raises(observations.ObservationRejected, match="method"):
        observations.store(valid_payload(method="guess"), directory=tmp_path)


# --- storage ------------------------------------------------------------

def test_the_store_is_append_only(tmp_path):
    """A research dataset whose history can be rewritten in place is not a
    research dataset. Whoever runs QC needs what actually arrived."""
    observations.store(valid_payload(value=29.1), directory=tmp_path)
    observations.store(valid_payload(value=29.2), directory=tmp_path)
    records = observations.load_all(tmp_path)
    assert [r["value"] for r in records] == [29.1, 29.2]


# --- the HTTP surface ---------------------------------------------------

def test_the_endpoint_stores_and_says_what_it_did(tmp_path, monkeypatch):
    monkeypatch.setattr(observations, "OBSERVATIONS_DIR", tmp_path)
    response = client.post("/observations", json=valid_payload())
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["stored"] is True
    assert body["id"].startswith("fleet_")
    # The caveat is in the RESPONSE, not only in the record, so a client
    # author cannot miss it.
    assert "does not affect any ORCA advisory" in body["note"]


def test_a_refused_upload_returns_422_with_a_sentence_not_a_schema_dump(tmp_path, monkeypatch):
    monkeypatch.setattr(observations, "OBSERVATIONS_DIR", tmp_path)
    response = client.post("/observations", json=valid_payload(consent=False))
    assert response.status_code == 422
    detail = response.json()["detail"]
    assert "consent" in detail
    # Something a fisherman could act on, not a validation trace.
    assert detail.startswith("Upload refused")
