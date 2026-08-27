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
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from data.fetch import (
    BBOX,
    CACHE_DIR,
    ERDDAPBathymetryFetcher,
    ERDDAPChlorophyllFetcher,
    MarineRegionsIMBLFetcher,
    OpenMeteoForecastFetcher,
    OpenMeteoMarineFetcher,
    fetch_all,
    write_bathymetry_cache,
    write_cache,
    write_imbl_cache,
)
from orca.planner import load_cached_observations, load_forecast_observations

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

requires_marineregions_network = pytest.mark.skipif(
    not _network_reachable(host="geo.vliz.be"), reason="Marine Regions WFS host unreachable from this sandbox"
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
# fetch_tomorrow() / _parse_point_at_offset() — the same real fixtures
# already used above (48 real hourly points, confirmed by hand: idx 24 is
# safely in range, and is a genuinely different calendar day from idx 0 --
# see SCRATCH.md), read one day ahead instead of "now". Never touches
# fetch()/_parse_point() -- see fetch_tomorrow()'s docstring for why.
# ---------------------------------------------------------------------------

def test_marine_fetcher_tomorrow_offset_is_a_real_different_day_than_now():
    raw = json.loads((FIXTURES / "real_openmeteo_marine_response.json").read_text())
    fetcher = OpenMeteoMarineFetcher()
    fetched_at = datetime(2026, 8, 26, 5, 0, tzinfo=timezone.utc)

    now_obs = fetcher._parse_point(raw, ZONE_A, fetched_at)
    tomorrow_obs = fetcher._parse_point_at_offset(raw, ZONE_A, fetched_at, hours_ahead=24)

    assert len(tomorrow_obs) > 0
    now_wave = next(o for o in now_obs if o.variable == "wave_height_m")
    tomorrow_wave = next(o for o in tomorrow_obs if o.variable == "wave_height_m")
    assert tomorrow_wave.valid_time.date() > now_wave.valid_time.date()
    # fetch() now reports the `current` nowcast (an arbitrary minute of
    # today), so a fixed 24h delta no longer holds -- and never was the
    # point. What must hold is that "tomorrow" is genuinely the next day.
    assert tomorrow_wave.valid_time.date() == now_wave.valid_time.date() + timedelta(days=1)
    assert tomorrow_wave.source == "Open-Meteo Marine"  # honest about which real source this still is


@pytest.mark.parametrize(
    "fetcher_cls,fixture_name",
    [
        (OpenMeteoMarineFetcher, "real_openmeteo_marine_response.json"),
        (OpenMeteoForecastFetcher, "real_openmeteo_forecast_response.json"),
    ],
)
def test_tomorrow_observations_carry_lower_confidence_than_now(fetcher_cls, fixture_name):
    """A day-ahead forecast really is less certain than the current hour.
    Copying NEAR_TERM_CONFIDENCE onto it would quietly overstate exactly
    what CLAUDE.md rule 3 makes every number carry honestly.

    Parametrized across BOTH fetchers deliberately: the first version of
    this fix landed on the marine fetcher only, and a marine-only test
    passed while the wind fetcher silently kept writing 0.9 for tomorrow
    (caught by reading the real cache, not by the suite -- see SCRATCH.md).
    """
    raw = json.loads((FIXTURES / fixture_name).read_text())
    fetcher = fetcher_cls()
    fetched_at = datetime(2026, 8, 26, 5, 0, tzinfo=timezone.utc)

    now_obs = fetcher._parse_point(raw, ZONE_A, fetched_at)
    tomorrow_obs = fetcher._parse_point_at_offset(raw, ZONE_A, fetched_at, hours_ahead=24)

    assert now_obs and tomorrow_obs
    assert all(o.confidence == fetcher.NEAR_TERM_CONFIDENCE for o in now_obs)
    assert all(o.confidence == fetcher.NEXT_DAY_CONFIDENCE for o in tomorrow_obs)
    assert fetcher.NEXT_DAY_CONFIDENCE < fetcher.NEAR_TERM_CONFIDENCE


def test_marine_fetcher_tomorrow_offset_out_of_range_returns_empty_not_fabricated():
    raw = json.loads((FIXTURES / "real_openmeteo_marine_response.json").read_text())
    obs = OpenMeteoMarineFetcher()._parse_point_at_offset(
        raw, ZONE_A, datetime.now(timezone.utc), hours_ahead=9999
    )
    assert obs == []


def test_forecast_fetcher_tomorrow_offset_is_a_real_different_day_than_now():
    raw = json.loads((FIXTURES / "real_openmeteo_forecast_response.json").read_text())
    fetcher = OpenMeteoForecastFetcher()
    fetched_at = datetime(2026, 8, 26, 5, 0, tzinfo=timezone.utc)

    now_obs = fetcher._parse_point(raw, ZONE_A, fetched_at)
    tomorrow_obs = fetcher._parse_point_at_offset(raw, ZONE_A, fetched_at, hours_ahead=24)

    now_wind = next(o for o in now_obs if o.variable == "wind_speed_kmh")
    tomorrow_wind = next(o for o in tomorrow_obs if o.variable == "wind_speed_kmh")
    assert tomorrow_wind.valid_time.date() > now_wind.valid_time.date()
    assert tomorrow_wind.source == "Open-Meteo Forecast"


def test_forecast_cache_directory_is_invisible_to_load_cached_observations(tmp_path):
    """The regression this guards: forecast/*.json must never be counted
    as part of the live, safety-critical observation set (Path.glob is
    non-recursive, but this proves it, not just asserts it by reading the
    stdlib docs)."""
    raw = json.loads((FIXTURES / "real_openmeteo_marine_response.json").read_text())
    tomorrow_obs = OpenMeteoMarineFetcher()._parse_point_at_offset(
        raw, ZONE_A, datetime.now(timezone.utc), hours_ahead=24
    )
    write_cache("Open-Meteo Marine", tomorrow_obs, cache_dir=tmp_path / "forecast")

    now_obs = OpenMeteoMarineFetcher()._parse_point(raw, ZONE_A, datetime.now(timezone.utc))
    write_cache("Open-Meteo Marine", now_obs, cache_dir=tmp_path)

    loaded = load_cached_observations(cache_dir=tmp_path)
    assert len(loaded) == len(now_obs)  # tomorrow's must not have been swept in


def test_load_forecast_observations_reads_back_what_was_written(tmp_path):
    raw = json.loads((FIXTURES / "real_openmeteo_marine_response.json").read_text())
    tomorrow_obs = OpenMeteoMarineFetcher()._parse_point_at_offset(
        raw, ZONE_A, datetime.now(timezone.utc), hours_ahead=24
    )
    write_cache("Open-Meteo Marine", tomorrow_obs, cache_dir=tmp_path / "forecast")

    loaded = load_forecast_observations(cache_dir=tmp_path / "forecast")
    assert len(loaded) == len(tomorrow_obs)
    assert {o.variable for o in loaded} == {o.variable for o in tomorrow_obs}


def test_load_forecast_observations_returns_empty_list_when_uncached(tmp_path):
    # Absent is correct, not an error -- CLAUDE.md rule 1's spirit.
    assert load_forecast_observations(cache_dir=tmp_path / "does_not_exist") == []


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


# ---------------------------------------------------------------------------
# MarineRegionsIMBLFetcher -- the real India-Sri Lanka maritime boundary
# (IMBL), for a real distance-to-boundary geofence check. Same shape as
# bathymetry: reference geometry, not a MarineObservation, cached to its
# own subdirectory so it can't collide with load_cached_observations().
# ---------------------------------------------------------------------------

def test_imbl_fetcher_parses_real_fixture_into_segments():
    raw = json.loads((FIXTURES / "real_marineregions_imbl_response.json").read_text())
    segments = MarineRegionsIMBLFetcher()._parse_geojson(raw)

    assert len(segments) == 4  # 4 real treaty-line features in the fixture
    for seg in segments:
        assert len(seg) >= 2
        for lat, lon in seg:
            # Sanity bounds for this boundary's real extent -- catches an
            # accidental (lat, lon) vs (lon, lat) swap, a classic GeoJSON bug.
            assert 0 < lat < 15
            assert 70 < lon < 85


def test_imbl_fetcher_build_url_uses_cql_filter():
    fetcher = MarineRegionsIMBLFetcher()
    params = fetcher._build_params()
    assert params["typeName"] == "eez_boundaries"
    assert "Sri Lanka - India" in params["CQL_FILTER"]


def test_imbl_fetcher_raises_not_fabricates_on_zero_segments(monkeypatch):
    class _FakeResponse:
        url = "https://example.test/wfs?empty"

        def json(self):
            return {"type": "FeatureCollection", "features": []}

        def raise_for_status(self):
            pass

    monkeypatch.setattr("data.fetch.requests.get", lambda *a, **k: _FakeResponse())
    with pytest.raises(ValueError):
        MarineRegionsIMBLFetcher().fetch()


def test_write_imbl_cache_is_not_swept_by_load_cached_observations(tmp_path):
    raw = json.loads((FIXTURES / "real_marineregions_imbl_response.json").read_text())
    segments = MarineRegionsIMBLFetcher()._parse_geojson(raw)
    boundary = {
        "source": MarineRegionsIMBLFetcher.SOURCE_NAME,
        "provenance": "https://example.test/provenance",
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "segments": segments,
    }
    write_imbl_cache(boundary, cache_dir=tmp_path / "imbl")

    raw_obs = json.loads((FIXTURES / "real_openmeteo_marine_response.json").read_text())
    obs = OpenMeteoMarineFetcher()._parse_point(raw_obs, ZONE_A, datetime.now(timezone.utc))
    write_cache("Open-Meteo Marine", obs, cache_dir=tmp_path)

    loaded = load_cached_observations(cache_dir=tmp_path)  # must not raise
    assert len(loaded) == len(obs)


@requires_marineregions_network
@pytest.mark.integration
def test_real_imbl_fetch_integration():
    boundary = MarineRegionsIMBLFetcher().fetch()
    assert len(boundary["segments"]) > 0
    total_points = sum(len(seg) for seg in boundary["segments"])
    assert total_points > 10  # the real boundary is a multi-point treaty line, not a stub


# ---------------------------------------------------------------------------
# "Now" must mean now.
#
# fetch() used to read hourly[0], which with timezone=UTC is 00:00 today.
# The cache written at 08:38 UTC therefore carried readings 8.6 hours old
# (freshness_min said 518) while presenting them at NEAR_TERM_CONFIDENCE
# 0.9. Measured against the live API at Karaikal on 2026-08-27 that
# understated real wind by 64% -- 13.7 km/h shown against 22.5 km/h
# actual -- and hid 40.7 km/h gusts entirely.
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "fetcher_cls,fixture",
    [
        (OpenMeteoMarineFetcher, "real_openmeteo_marine_response.json"),
        (OpenMeteoForecastFetcher, "real_openmeteo_forecast_response.json"),
    ],
)
def test_fetch_reads_the_current_nowcast_not_midnight(fetcher_cls, fixture):
    raw = json.loads((FIXTURES / fixture).read_text())
    fetched_at = datetime.fromisoformat(raw["current"]["time"]).replace(tzinfo=timezone.utc)

    observations = fetcher_cls()._parse_point(raw, ZONE_A, fetched_at)

    assert observations, "the current block must yield real observations"
    for obs in observations:
        # The nowcast instant, not 00:00 -- and therefore genuinely fresh.
        assert obs.valid_time.isoformat().startswith(raw["current"]["time"])
        assert obs.freshness_min == 0
        # Each value is the current block's own, never the hourly series'.
        raw_var = next(k for k, v in fetcher_cls._VAR_MAP.items() if v == obs.variable)
        assert obs.value == float(raw["current"][raw_var])


@pytest.mark.parametrize(
    "fetcher_cls,fixture",
    [
        (OpenMeteoMarineFetcher, "real_openmeteo_marine_response.json"),
        (OpenMeteoForecastFetcher, "real_openmeteo_forecast_response.json"),
    ],
)
def test_tomorrow_is_the_same_clock_hour_not_midnight(fetcher_cls, fixture):
    """"Tomorrow" meant 00:00 tomorrow, which is nobody's idea of
    tomorrow's fishing conditions. It now means this time tomorrow."""
    raw = json.loads((FIXTURES / fixture).read_text())
    fetched_at = datetime(2026, 8, 27, 14, 30, tzinfo=timezone.utc)

    observations = fetcher_cls()._parse_point_at_offset(
        raw, ZONE_A, fetched_at, hours_ahead=24 + fetched_at.hour
    )

    assert observations
    for obs in observations:
        assert obs.valid_time.hour == fetched_at.hour
        assert obs.valid_time.date() == fetched_at.date() + timedelta(days=1)
        assert obs.confidence == fetcher_cls.NEXT_DAY_CONFIDENCE
