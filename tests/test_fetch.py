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
    BBOX,
    CACHE_DIR,
    ERDDAPBathymetryFetcher,
    ERDDAPChlorophyllFetcher,
    OpenMeteoForecastFetcher,
    OpenMeteoMarineFetcher,
    fetch_all,
    write_bathymetry_cache,
    write_cache,
)
from orca.planner import load_cached_observations

FIXTURES = Path(__file__).parent / "fixtures"
ZONE_A = {"name": "Nagapattinam", "lat": 10.7672, "lon": 79.8449}


def _network_reachable(host="marine-api.open-meteo.com", port=443, timeout=3) -> bool:
    try:
        socket.create_connection((host, port), timeout=timeout).close()
        return True
    except OSError:
        return False


requires_network = pytest.mark.skipif(
    not _network_reachable(), reason="no network reachable from this sandbox"
)

# Separate, host-specific check: the ETOPO ERDDAP host has been flaky from
# this sandbox even when marine-api.open-meteo.com is fine (two of three
# NOAA ERDDAP mirrors timed out during research; oceanwatch.pifsc.noaa.gov
# was the one that worked -- see SCRATCH.md), so bathymetry's own
# integration test shouldn't ride on an unrelated host's reachability.
requires_bathymetry_network = pytest.mark.skipif(
    not _network_reachable(host="oceanwatch.pifsc.noaa.gov"),
    reason="ETOPO ERDDAP host unreachable from this sandbox",
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


# ---------------------------------------------------------------------------
# ERDDAPBathymetryFetcher -- real seafloor relief for the 3D ocean view.
# Unlike the fetchers above, this is one grid covering the whole demo
# region, not a per-zone MarineObservation -- it's map context (CLAUDE.md
# rule 4: policy.py never sees a static 2022 seabed survey), so it's
# parsed and cached differently, and the isolation from
# orca.planner.load_cached_observations() is load-bearing (see test
# below) -- get that wrong and every /ask response silently breaks.
# ---------------------------------------------------------------------------

def test_bathymetry_fetcher_parses_real_fixture_into_grid():
    csv_text = (FIXTURES / "real_erddap_bathymetry_sample.csv").read_text()
    points = ERDDAPBathymetryFetcher()._parse_csv(csv_text)

    assert len(points) == 16
    for p in points:
        assert set(p.keys()) == {"lat", "lon", "elevation_m"}

    # Ground truth from the real fixture (hand-verified): nearshore point is
    # slightly above sea level (land), the deep offshore corner is far below.
    nearshore = next(p for p in points if p["lat"] < 10.6 and p["lon"] < 79.6)
    offshore = next(p for p in points if p["lat"] > 11.4 and p["lon"] > 80.4)
    assert nearshore["elevation_m"] == pytest.approx(5.638, abs=0.01)
    assert offshore["elevation_m"] == pytest.approx(-2207.19, abs=0.01)
    assert offshore["elevation_m"] < 0 < nearshore["elevation_m"]


def test_bathymetry_fetcher_skips_nan_pixels():
    # Minimal hand-built CSV literal, only to exercise the cloud/no-data
    # edge case -- never used to feed the app real data (see file docstring).
    csv_text = (
        "latitude,longitude,z\n"
        "degrees_north,degrees_east,meters\n"
        "10.5,79.5,NaN\n"
        "10.5,79.6,-100.0\n"
    )
    points = ERDDAPBathymetryFetcher()._parse_csv(csv_text)
    assert len(points) == 1
    assert points[0]["elevation_m"] == -100.0


def test_bathymetry_fetcher_build_url_uses_bracket_stride_syntax():
    fetcher = ERDDAPBathymetryFetcher()
    url = fetcher._build_url(BBOX)
    assert url.startswith(fetcher.BASE_URL)
    assert f"[({BBOX['min_lat']}):{fetcher.STRIDE}:({BBOX['max_lat']})]" in url
    assert f"[({BBOX['min_lon']}):{fetcher.STRIDE}:({BBOX['max_lon']})]" in url


def test_bathymetry_fetcher_raises_not_fabricates_on_zero_points(monkeypatch):
    """fetch() must refuse to hand back an empty grid dressed up as real
    data -- mirrors test_marine_fetcher_raises_on_network_error_does_not_fabricate
    above, but for the "server answered, everything was NaN" case rather
    than a network error.
    """
    empty_csv = "latitude,longitude,z\ndegrees_north,degrees_east,meters\n10.5,79.5,NaN\n"

    class _FakeResponse:
        text = empty_csv

        def raise_for_status(self):
            pass

    monkeypatch.setattr("data.fetch.requests.get", lambda *a, **k: _FakeResponse())
    with pytest.raises(ValueError):
        ERDDAPBathymetryFetcher().fetch(BBOX)


def test_write_bathymetry_cache_produces_contract_shaped_json(tmp_path):
    csv_text = (FIXTURES / "real_erddap_bathymetry_sample.csv").read_text()
    points = ERDDAPBathymetryFetcher()._parse_csv(csv_text)
    grid = {
        "source": ERDDAPBathymetryFetcher.SOURCE_NAME,
        "dataset_id": ERDDAPBathymetryFetcher.DATASET_ID,
        "provenance": "https://example.test/provenance",
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "bbox": BBOX,
        "points": points,
    }
    path = write_bathymetry_cache(grid, cache_dir=tmp_path / "bathymetry")

    assert path.exists()
    data = json.loads(path.read_text())
    for required in ("source", "dataset_id", "provenance", "fetched_at", "bbox", "points"):
        assert required in data
    assert len(data["points"]) == 16


def test_write_bathymetry_cache_is_not_swept_by_load_cached_observations(tmp_path):
    """The regression this guards against: load_cached_observations() globs
    cache_dir/*.json non-recursively and parses every file as a list of
    MarineObservation dicts. A bathymetry grid is a single dict with a
    `points` key, not a list -- if it ever landed directly in data/cache/
    (not a subdirectory), this call would crash with a TypeError deep
    inside /ask, for every single query, until someone noticed.
    """
    csv_text = (FIXTURES / "real_erddap_bathymetry_sample.csv").read_text()
    points = ERDDAPBathymetryFetcher()._parse_csv(csv_text)
    grid = {
        "source": ERDDAPBathymetryFetcher.SOURCE_NAME,
        "dataset_id": ERDDAPBathymetryFetcher.DATASET_ID,
        "provenance": "https://example.test/provenance",
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "bbox": BBOX,
        "points": points,
    }
    write_bathymetry_cache(grid, cache_dir=tmp_path / "bathymetry")

    # Also write one real, normal observation cache file directly in the
    # parent dir, so this proves load_cached_observations still works
    # normally alongside the (correctly isolated) bathymetry subdirectory.
    raw = json.loads((FIXTURES / "real_openmeteo_marine_response.json").read_text())
    obs = OpenMeteoMarineFetcher()._parse_point(raw, ZONE_A, datetime.now(timezone.utc))
    write_cache("Open-Meteo Marine", obs, cache_dir=tmp_path)

    loaded = load_cached_observations(cache_dir=tmp_path)  # must not raise
    assert len(loaded) == len(obs)


@requires_bathymetry_network
@pytest.mark.integration
def test_real_bathymetry_fetch_integration():
    fetcher = ERDDAPBathymetryFetcher()
    grid = fetcher.fetch(BBOX)
    assert len(grid["points"]) > 0
    elevations = [p["elevation_m"] for p in grid["points"]]
    # Sanity range for this coastal Bay of Bengal box: nowhere near
    # Everest or the Mariana Trench, but real elevation/depth spread.
    assert min(elevations) < 0  # some real seafloor in-box
    assert max(elevations) > -8000
    assert min(elevations) > -11000
