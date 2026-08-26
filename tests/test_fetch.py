"""Tests for data/fetch.py — written before the implementation.

CLAUDE.md rule 1 is absolute here: no synthetic/mock/placeholder marine
data, ever. These tests use REAL, previously-captured API responses as
fixtures (see tests/fixtures/real_*.json|csv, captured with plain curl
against the live services) so the parsing logic is exercised against
reality, not a guess at the response shape (war plan §3.2, "fabricated
API parameters"). A couple of tests below build a minimal CSV literal by
hand — those are marked clearly and exist only to exercise the
all-cloud-masked edge case in the parser, never to feed data to the app.

Network-touching tests are marked `integration` and skip themselves if
the network is unreachable, so the suite stays green offline (as the
demo itself must run, CLAUDE.md rule 8) while still being exercised for
real whenever a connection exists.
"""
import json
import socket
from datetime import datetime, timezone
from pathlib import Path

import pytest

from data.fetch import (
    ERDDAPChlorophyllFetcher,
    OpenMeteoForecastFetcher,
    OpenMeteoMarineFetcher,
    fetch_all,
    write_cache,
)

FIXTURES = Path(__file__).parent / "fixtures"
ZONE_A = {"name": "Zone A", "lat": 10.76, "lon": 79.84}


def _network_reachable(host="marine-api.open-meteo.com", port=443, timeout=3) -> bool:
    try:
        socket.create_connection((host, port), timeout=timeout).close()
        return True
    except OSError:
        return False


requires_network = pytest.mark.skipif(
    not _network_reachable(), reason="no network reachable from this sandbox"
)


# ---------------------------------------------------------------------------
# OpenMeteoMarineFetcher — parsed against a real captured response
# ---------------------------------------------------------------------------

def test_marine_fetcher_parses_real_fixture_into_observations():
    raw = json.loads((FIXTURES / "real_openmeteo_marine_response.json").read_text())
    fetcher = OpenMeteoMarineFetcher()
    fetched_at = datetime(2026, 8, 26, 5, 0, tzinfo=timezone.utc)

    obs = fetcher._parse_point(raw, ZONE_A, fetched_at)

    variables = {o.variable for o in obs}
    assert "wave_height_m" in variables
    assert "sst_c" in variables
    assert "ocean_current_velocity_kmh" in variables
    assert all(o.source == "Open-Meteo Marine" for o in obs)
    assert all(o.lat == ZONE_A["lat"] and o.lon == ZONE_A["lon"] for o in obs)
    assert all(o.fetched_at == fetched_at for o in obs)
    assert all("marine-api.open-meteo.com" in o.provenance for o in obs)

    wave = next(o for o in obs if o.variable == "wave_height_m")
    assert wave.unit == "m"
    assert isinstance(wave.value, float)
    assert wave.valid_time.tzinfo is not None


def test_marine_fetcher_output_passes_schema_validation():
    """Every observation must construct without raising — proves the
    validator in orca/schema.py accepts what this fetcher actually produces.
    """
    raw = json.loads((FIXTURES / "real_openmeteo_marine_response.json").read_text())
    fetcher = OpenMeteoMarineFetcher()
    obs = fetcher._parse_point(raw, ZONE_A, datetime.now(timezone.utc))
    assert len(obs) > 0  # constructed successfully means schema validation already passed


# ---------------------------------------------------------------------------
# OpenMeteoForecastFetcher (wind/rain) — parsed against a real captured response
# ---------------------------------------------------------------------------

def test_forecast_fetcher_parses_real_fixture_into_observations():
    raw = json.loads((FIXTURES / "real_openmeteo_forecast_response.json").read_text())
    fetcher = OpenMeteoForecastFetcher()
    fetched_at = datetime(2026, 8, 26, 5, 0, tzinfo=timezone.utc)

    obs = fetcher._parse_point(raw, ZONE_A, fetched_at)

    variables = {o.variable for o in obs}
    assert "wind_speed_kmh" in variables
    assert all(o.source == "Open-Meteo Forecast" for o in obs)
    assert all("api.open-meteo.com" in o.provenance for o in obs)


# ---------------------------------------------------------------------------
# ERDDAPChlorophyllFetcher — the risky one. Real 5-day range where only the
# oldest day has any cloud-free pixel; the fetcher must pick THAT day, not
# the most recent (all-NaN) one, and must not fabricate a value for the gap.
# ---------------------------------------------------------------------------

def test_erddap_fetcher_selects_the_most_recent_valid_day_not_the_newest_day():
    csv_text = (FIXTURES / "real_erddap_chlorophyll_5day_range.csv").read_text()
    fetcher = ERDDAPChlorophyllFetcher()

    result = fetcher._parse_csv(csv_text)

    assert result is not None
    valid_date, mean_value, n_pixels = result
    # Ground truth from the real fixture: 2026-07-24 is the only day in this
    # 5-day window with any non-NaN pixel in this box; 07-25..07-28 are 100%
    # cloud-masked. Verified by hand against the raw fixture file.
    assert valid_date == datetime(2026, 7, 24, tzinfo=timezone.utc).date()
    assert n_pixels > 0
    assert 0.5 < mean_value < 5.0  # plausible mg/m^3 range, sanity bound only


def test_erddap_fetcher_returns_none_when_entire_window_is_cloud_masked():
    # Hand-built CSV in ERDDAP's exact real header/row format — this exists
    # ONLY to exercise the "give up honestly" branch of the parser. It is
    # never written to data/cache/ or shown to a user.
    all_nan_csv = (
        "time,altitude,latitude,longitude,chlor_a\n"
        "UTC,m,degrees_north,degrees_east,mg m^-3\n"
        "2026-07-27T12:00:00Z,0.0,10.76,79.84,NaN\n"
        "2026-07-28T12:00:00Z,0.0,10.76,79.84,NaN\n"
    )
    fetcher = ERDDAPChlorophyllFetcher()
    assert fetcher._parse_csv(all_nan_csv) is None


def test_erddap_confidence_decays_with_staleness():
    fetcher = ERDDAPChlorophyllFetcher()
    fresh = fetcher._confidence_for_staleness(0)
    stale = fetcher._confidence_for_staleness(10)
    very_stale = fetcher._confidence_for_staleness(60)
    assert fresh > stale > very_stale
    assert 0.0 <= very_stale <= 1.0
    assert 0.0 <= fresh <= 1.0


# ---------------------------------------------------------------------------
# Failure handling — CLAUDE.md rule 1: raise loudly, never fabricate.
# ---------------------------------------------------------------------------

def test_marine_fetcher_raises_on_network_error_does_not_fabricate(monkeypatch):
    import requests

    def broken_get(*args, **kwargs):
        raise requests.exceptions.ConnectionError("simulated network failure")

    monkeypatch.setattr("data.fetch.requests.get", broken_get)
    fetcher = OpenMeteoMarineFetcher()
    with pytest.raises(requests.exceptions.ConnectionError):
        fetcher.fetch([ZONE_A])


def test_fetch_all_continues_when_one_source_fails_and_never_fabricates(monkeypatch):
    """If Open-Meteo Marine is down, ERDDAP/Forecast results still come
    back, and the failed source is simply absent — never backfilled with
    placeholder data.
    """
    import requests

    def broken_get(*args, **kwargs):
        raise requests.exceptions.ConnectionError("simulated outage")

    monkeypatch.setattr(OpenMeteoMarineFetcher, "fetch", lambda self, points: (_ for _ in ()).throw(
        requests.exceptions.ConnectionError("simulated outage")
    ))

    results, errors = fetch_all([ZONE_A])

    assert "Open-Meteo Marine" in errors
    assert "Open-Meteo Marine" not in results or results["Open-Meteo Marine"] == []
    for source, obs_list in results.items():
        for obs in obs_list:
            assert "mock" not in obs.source.lower()
            assert "sample" not in obs.source.lower()
            assert "synthetic" not in obs.source.lower()
            assert "dummy" not in obs.source.lower()


# ---------------------------------------------------------------------------
# Cache writer — this is what Operator 2's mechanical check (§4) validates.
# ---------------------------------------------------------------------------

def test_write_cache_produces_contract_valid_json(tmp_path):
    raw = json.loads((FIXTURES / "real_openmeteo_marine_response.json").read_text())
    obs = OpenMeteoMarineFetcher()._parse_point(raw, ZONE_A, datetime.now(timezone.utc))

    path = write_cache("Open-Meteo Marine", obs, cache_dir=tmp_path)

    assert path.exists()
    data = json.loads(path.read_text())
    assert isinstance(data, list) and data
    required = {"variable", "value", "unit", "lat", "lon", "valid_time", "source", "confidence", "provenance"}
    assert not required - set(data[0].keys())
    sources = {o["source"] for o in data}
    assert not any(
        bad in s.lower() for s in sources for bad in ("sample", "mock", "synthetic", "dummy", "test")
    )


def test_write_cache_filename_is_slug_and_date(tmp_path):
    raw = json.loads((FIXTURES / "real_openmeteo_marine_response.json").read_text())
    obs = OpenMeteoMarineFetcher()._parse_point(raw, ZONE_A, datetime.now(timezone.utc))
    path = write_cache("Open-Meteo Marine", obs, cache_dir=tmp_path)
    assert path.name.startswith("open-meteo-marine_")
    assert path.suffix == ".json"


# ---------------------------------------------------------------------------
# Live integration — the real "Operator 2 mechanical check" from the plan,
# run for real because this sandbox does have network access right now.
# ---------------------------------------------------------------------------

@requires_network
@pytest.mark.integration
def test_real_openmeteo_marine_fetch_integration():
    fetcher = OpenMeteoMarineFetcher()
    obs = fetcher.fetch([ZONE_A])
    assert len(obs) > 0
    assert all(o.source == "Open-Meteo Marine" for o in obs)
    assert all(0.0 <= o.confidence <= 1.0 for o in obs)
