"""Real marine data fetchers for the Nagapattinam/Chennai coast, Bay of
Bengal (lat 10.5-13.5, lon 79.5-81.5) — the frozen demo region.

CLAUDE.md rule 1 is absolute: no synthetic, mocked, simulated or
placeholder marine data, ever. If a source fails, we log the real error
and move on to the next source. We NEVER substitute generated numbers.
An absent reading is correct; a fabricated one destroys the project's
central claim.

This is the ONLY file allowed to make network calls (CLAUDE.md rule 8).
Run it directly to (re)populate data/cache/; the live API never calls
this module's network functions, only reads the cache it writes.

Sources:
  - Open-Meteo Marine API (marine-api.open-meteo.com) — wave height,
    period, direction, sea surface temperature. No auth.
  - Open-Meteo Forecast API (api.open-meteo.com) — wind, rain. No auth.
  - NOAA CoastWatch ERDDAP, VIIRS chlorophyll-a (coastwatch.noaa.gov) —
    satellite ocean colour. No auth. Frequently cloud-masked (NaN) near
    the coast in monsoon season — see SCRATCH.md. We scan a recent
    window and use the most recent day that has ANY real pixel in-box,
    with the reading's honest (possibly stale) timestamp and a
    confidence that decays with that staleness. If nothing in the
    window is valid, chlorophyll is skipped for that zone and logged
    loudly — never fabricated.
"""
from __future__ import annotations

import csv
import io
import logging
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import requests

if __name__ == "__main__":
    # Allow `python data/fetch.py` to find the orca package at repo root,
    # same as `python -m data.fetch` does automatically.
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from orca.schema import MarineObservation

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("orca.fetch")

CACHE_DIR = Path(__file__).parent / "cache"

BBOX = {"min_lat": 7.8, "max_lat": 13.4, "min_lon": 76.9, "max_lon": 80.6}

# Real, named Tamil Nadu coastal fishing harbours/towns, north to south --
# not arbitrary/equally-spaced points. Coordinates are each sourced from
# that place's Wikipedia article (a couple, marked below, from the more
# specific *fishing harbour* article where one exists, not just the town
# centroid). This spans the whole ~1076 km coastline (Chennai to
# Colachel), not a small cluster near one town.
ZONES = [
    {"name": "Chennai", "lat": 13.1251, "lon": 80.2955},       # Kasimedu fishing harbour
    {"name": "Cuddalore", "lat": 11.75, "lon": 79.75},
    {"name": "Karaikal", "lat": 10.9327, "lon": 79.8319},
    {"name": "Nagapattinam", "lat": 10.7672, "lon": 79.8449},
    {"name": "Point Calimere", "lat": 10.2845, "lon": 79.8241},  # Kodiakkarai
    {"name": "Mandapam", "lat": 9.28, "lon": 79.12},
    {"name": "Rameswaram", "lat": 9.2811, "lon": 79.3151},      # fishing harbour & boat jetty
    {"name": "Thoothukudi", "lat": 8.4730, "lon": 78.1215},     # V.O. Chidambaranar Port
    {"name": "Kanyakumari", "lat": 8.0883, "lon": 77.5385},
    {"name": "Colachel", "lat": 8.1786, "lon": 77.2561},
]

_FORBIDDEN_SOURCE_WORDS = ("mock", "sample", "synthetic", "dummy", "fake")


def _box_around(lat: float, lon: float, half_width_deg: float = 0.15) -> dict:
    return {
        "min_lat": lat - half_width_deg,
        "max_lat": lat + half_width_deg,
        "min_lon": lon - half_width_deg,
        "max_lon": lon + half_width_deg,
    }


class OpenMeteoMarineFetcher:
    """Wave height/period/direction and SST, per point, as of now."""

    BASE_URL = "https://marine-api.open-meteo.com/v1/marine"
    SOURCE_NAME = "Open-Meteo Marine"
    HOURLY_VARS = (
        "wave_height,wave_period,wave_direction,sea_surface_temperature,"
        "ocean_current_velocity,ocean_current_direction"
    )
    # Open-Meteo exposes every hourly variable as a `current` variable
    # too, under the same name; fetch() uses these, fetch_tomorrow()
    # still needs the hourly series.
    CURRENT_VARS = HOURLY_VARS
    _VAR_MAP = {
        "wave_height": "wave_height_m",
        "wave_period": "wave_period_s",
        "wave_direction": "wave_direction_deg",
        "sea_surface_temperature": "sst_c",
        "ocean_current_velocity": "ocean_current_velocity_kmh",
        "ocean_current_direction": "ocean_current_direction_deg",
    }
    # Open-Meteo does not publish per-point uncertainty. We use a fixed,
    # documented heuristic: high confidence for the nearest forecast hour,
    # lower for a day ahead (see _parse_point_at_offset).
    NEAR_TERM_CONFIDENCE = 0.9
    NEXT_DAY_CONFIDENCE = 0.75

    def fetch(self, points: list[dict]) -> list[MarineObservation]:
        fetched_at = datetime.now(timezone.utc)
        observations: list[MarineObservation] = []
        for point in points:
            params = {
                "latitude": point["lat"],
                "longitude": point["lon"],
                "current": self.CURRENT_VARS,
                "timezone": "UTC",
            }
            resp = requests.get(self.BASE_URL, params=params, timeout=15)
            resp.raise_for_status()
            observations.extend(self._parse_point(resp.json(), point, fetched_at))
        return observations

    def _parse_point(self, raw: dict, point: dict, fetched_at: datetime) -> list[MarineObservation]:
        """Read Open-Meteo's `current` block -- the model's own nowcast
        for this instant, which the API refreshes every 15 minutes
        (the response carries `interval: 900`).

        This previously read hourly[0], which with timezone=UTC is
        00:00 today. By the time the cache was written at 08:38 UTC
        every "current" reading was already 8.6 hours old (the cached
        freshness_min said 518 and nobody looked) and drifted further
        all day -- while still being handed to the user carrying
        NEAR_TERM_CONFIDENCE 0.9. Measured at Karaikal on 2026-08-27
        that understated real wind by 64% (13.7 km/h shown vs 22.5
        actual) and hid 40.7 km/h gusts entirely. A stale reading
        presented as the current one is the same class of falsehood as
        a fabricated one, which is what CLAUDE.md rule 3 exists to
        stop."""
        current = raw["current"]
        units = raw.get("current_units", {})
        valid_time = datetime.fromisoformat(current["time"]).replace(tzinfo=timezone.utc)

        request_url = (
            f"{self.BASE_URL}?latitude={point['lat']}&longitude={point['lon']}"
            f"&current={self.CURRENT_VARS}&timezone=UTC"
        )

        observations = []
        for raw_var, schema_var in self._VAR_MAP.items():
            value = current.get(raw_var)
            if value is None:
                continue  # absent reading is honest; do not fabricate a value
            observations.append(
                MarineObservation(
                    variable=schema_var,
                    value=float(value),
                    unit=units.get(raw_var, ""),
                    lat=point["lat"],
                    lon=point["lon"],
                    valid_time=valid_time,
                    fetched_at=fetched_at,
                    source=self.SOURCE_NAME,
                    confidence=self.NEAR_TERM_CONFIDENCE,
                    freshness_min=max(0, int((fetched_at - valid_time).total_seconds() // 60)),
                    provenance=request_url,
                )
            )
        return observations

    def fetch_tomorrow(self, points: list[dict]) -> list[MarineObservation]:
        """Same live forecast fetch() already makes (forecast_days=2 asks
        for ~48 hourly points; fetch()/_parse_point() have only ever kept
        idx 0), read one day ahead instead. A SEPARATE request on
        purpose, not a second return channel out of fetch() -- sharing
        that path risked the already-tested 'now' pipeline every agent
        and the live safety policy depend on, for a feature that isn't on
        that request path at all (this only ever populates
        data/cache/forecast/, which orca/planner.py's
        load_cached_observations() never reads -- see write_cache()'s
        cache_dir param and that loader's docstring). See SCRATCH.md for
        the full reasoning.
        """
        fetched_at = datetime.now(timezone.utc)
        observations: list[MarineObservation] = []
        for point in points:
            params = {
                "latitude": point["lat"],
                "longitude": point["lon"],
                "hourly": self.HOURLY_VARS,
                "timezone": "UTC",
                "forecast_days": 2,
            }
            resp = requests.get(self.BASE_URL, params=params, timeout=15)
            resp.raise_for_status()
            observations.extend(
                self._parse_point_at_offset(
                    # Same clock hour tomorrow, not 00:00 -- a midnight
                    # reading is not what anyone means by "tomorrow".
                    resp.json(), point, fetched_at, hours_ahead=24 + fetched_at.hour
                )
            )
        return observations

    def _parse_point_at_offset(
        self, raw: dict, point: dict, fetched_at: datetime, hours_ahead: int
    ) -> list[MarineObservation]:
        hourly = raw["hourly"]
        units = raw.get("hourly_units", {})
        idx = hours_ahead
        if idx >= len(hourly.get("time", [])):
            return []  # forecast_days didn't cover this far -- no fabricated fallback
        valid_time = datetime.fromisoformat(hourly["time"][idx]).replace(tzinfo=timezone.utc)

        request_url = (
            f"{self.BASE_URL}?latitude={point['lat']}&longitude={point['lon']}"
            f"&hourly={self.HOURLY_VARS}&timezone=UTC&forecast_days=2"
        )

        observations = []
        for raw_var, schema_var in self._VAR_MAP.items():
            series = hourly.get(raw_var)
            if series is None or idx >= len(series) or series[idx] is None:
                continue  # absent reading is honest; do not fabricate a value
            observations.append(
                MarineObservation(
                    variable=schema_var,
                    value=float(series[idx]),
                    unit=units.get(raw_var, ""),
                    lat=point["lat"],
                    lon=point["lon"],
                    valid_time=valid_time,
                    fetched_at=fetched_at,
                    source=self.SOURCE_NAME,
                    # A day-ahead forecast is genuinely less certain than
                    # the current hour. Claiming NEAR_TERM_CONFIDENCE for
                    # it would be a quiet overstatement of exactly the
                    # kind CLAUDE.md rule 3 exists to prevent -- the
                    # number reaching the user must carry honest
                    # confidence, not a copied-over one.
                    confidence=self.NEXT_DAY_CONFIDENCE if hours_ahead >= 24 else self.NEAR_TERM_CONFIDENCE,
                    freshness_min=max(0, int((fetched_at - valid_time).total_seconds() // 60)),
                    provenance=request_url,
                )
            )
        return observations


class OpenMeteoForecastFetcher:
    """Wind speed/gusts and precipitation, per point, near-term forecast."""

    BASE_URL = "https://api.open-meteo.com/v1/forecast"
    SOURCE_NAME = "Open-Meteo Forecast"
    HOURLY_VARS = "wind_speed_10m,wind_gusts_10m,wind_direction_10m,precipitation,rain"
    # Open-Meteo exposes every hourly variable as a `current` variable
    # too, under the same name; fetch() uses these, fetch_tomorrow()
    # still needs the hourly series.
    CURRENT_VARS = HOURLY_VARS
    _VAR_MAP = {
        "wind_speed_10m": "wind_speed_kmh",
        "wind_gusts_10m": "wind_gusts_kmh",
        # Direction is the input the drift model cannot work without: leeway
        # is decomposed downwind and crosswind of the TRUE wind bearing, so a
        # speed with no bearing gives a drift circle, not a drift box.
        "wind_direction_10m": "wind_direction_deg",
        "precipitation": "precipitation_mm",
        "rain": "rain_mm",
    }
    NEAR_TERM_CONFIDENCE = 0.9
    NEXT_DAY_CONFIDENCE = 0.75

    def fetch(self, points: list[dict]) -> list[MarineObservation]:
        fetched_at = datetime.now(timezone.utc)
        observations: list[MarineObservation] = []
        for point in points:
            params = {
                "latitude": point["lat"],
                "longitude": point["lon"],
                "current": self.CURRENT_VARS,
                "timezone": "UTC",
            }
            resp = requests.get(self.BASE_URL, params=params, timeout=15)
            resp.raise_for_status()
            observations.extend(self._parse_point(resp.json(), point, fetched_at))
        return observations

    def _parse_point(self, raw: dict, point: dict, fetched_at: datetime) -> list[MarineObservation]:
        """Wind/rain equivalent of
        OpenMeteoMarineFetcher._parse_point() -- same `current` nowcast
        block, same reason. See that method's docstring for the
        measured staleness this replaced."""
        current = raw["current"]
        units = raw.get("current_units", {})
        valid_time = datetime.fromisoformat(current["time"]).replace(tzinfo=timezone.utc)

        request_url = (
            f"{self.BASE_URL}?latitude={point['lat']}&longitude={point['lon']}"
            f"&current={self.CURRENT_VARS}&timezone=UTC"
        )

        observations = []
        for raw_var, schema_var in self._VAR_MAP.items():
            value = current.get(raw_var)
            if value is None:
                continue  # absent reading is honest; do not fabricate a value
            observations.append(
                MarineObservation(
                    variable=schema_var,
                    value=float(value),
                    unit=units.get(raw_var, ""),
                    lat=point["lat"],
                    lon=point["lon"],
                    valid_time=valid_time,
                    fetched_at=fetched_at,
                    source=self.SOURCE_NAME,
                    confidence=self.NEAR_TERM_CONFIDENCE,
                    freshness_min=max(0, int((fetched_at - valid_time).total_seconds() // 60)),
                    provenance=request_url,
                )
            )
        return observations

    def fetch_tomorrow(self, points: list[dict]) -> list[MarineObservation]:
        """Wind's equivalent of OpenMeteoMarineFetcher.fetch_tomorrow() --
        same reasoning, see that method's docstring."""
        fetched_at = datetime.now(timezone.utc)
        observations: list[MarineObservation] = []
        for point in points:
            params = {
                "latitude": point["lat"],
                "longitude": point["lon"],
                "hourly": self.HOURLY_VARS,
                "timezone": "UTC",
                "forecast_days": 2,
            }
            resp = requests.get(self.BASE_URL, params=params, timeout=15)
            resp.raise_for_status()
            observations.extend(
                self._parse_point_at_offset(
                    # Same clock hour tomorrow, not 00:00 -- a midnight
                    # reading is not what anyone means by "tomorrow".
                    resp.json(), point, fetched_at, hours_ahead=24 + fetched_at.hour
                )
            )
        return observations

    def _parse_point_at_offset(
        self, raw: dict, point: dict, fetched_at: datetime, hours_ahead: int
    ) -> list[MarineObservation]:
        hourly = raw["hourly"]
        units = raw.get("hourly_units", {})
        idx = hours_ahead
        if idx >= len(hourly.get("time", [])):
            return []
        valid_time = datetime.fromisoformat(hourly["time"][idx]).replace(tzinfo=timezone.utc)

        request_url = (
            f"{self.BASE_URL}?latitude={point['lat']}&longitude={point['lon']}"
            f"&hourly={self.HOURLY_VARS}&timezone=UTC&forecast_days=2"
        )

        observations = []
        for raw_var, schema_var in self._VAR_MAP.items():
            series = hourly.get(raw_var)
            if series is None or idx >= len(series) or series[idx] is None:
                continue  # absent reading is honest; do not fabricate a value
            observations.append(
                MarineObservation(
                    variable=schema_var,
                    value=float(series[idx]),
                    unit=units.get(raw_var, ""),
                    lat=point["lat"],
                    lon=point["lon"],
                    valid_time=valid_time,
                    fetched_at=fetched_at,
                    source=self.SOURCE_NAME,
                    # See OpenMeteoMarineFetcher._parse_point_at_offset --
                    # a day-ahead forecast carries honest, lower confidence.
                    confidence=self.NEXT_DAY_CONFIDENCE if hours_ahead >= 24 else self.NEAR_TERM_CONFIDENCE,
                    freshness_min=max(0, int((fetched_at - valid_time).total_seconds() // 60)),
                    provenance=request_url,
                )
            )
        return observations


class ERDDAPChlorophyllFetcher:
    """NOAA CoastWatch ERDDAP VIIRS daily chlorophyll-a, per point.

    Satellite ocean colour is frequently cloud-masked near this coast.
    We query a rolling window and use the most recent day with at least
    one real pixel in a small box around the point, not the newest day
    outright (which is very often 100% NaN — see SCRATCH.md).
    """

    BASE_URL = "https://coastwatch.noaa.gov/erddap/griddap/noaacwNPPVIIRSchlaDaily.csv"
    DATASET_ID = "noaacwNPPVIIRSchlaDaily"
    VARIABLE = "chlor_a"
    SOURCE_NAME = "NOAA CoastWatch ERDDAP (VIIRS chlorophyll-a)"
    LOOKBACK_DAYS = 15
    BOX_HALF_WIDTH_DEG = 0.15
    # coastwatch.noaa.gov returns 403 for the default python-requests
    # User-Agent. Self-identify honestly instead — this is normal API
    # etiquette, not evasion.
    HEADERS = {"User-Agent": "ORCA-SIH2026/1.0 (marine advisory prototype, SIH26176)"}

    def fetch(self, points: list[dict]) -> list[MarineObservation]:
        fetched_at = datetime.now(timezone.utc)
        observations: list[MarineObservation] = []

        for point in points:
            box = _box_around(point["lat"], point["lon"], self.BOX_HALF_WIDTH_DEG)
            query_url = self._build_url(box)
            resp = requests.get(query_url, timeout=30, headers=self.HEADERS)
            if resp.status_code == 400:
                # ERDDAP returns 400 for e.g. "no matching data" — real
                # server response, not a network failure. Log and move on.
                logger.warning("ERDDAP returned 400 for %s: %s", point["name"], resp.text[:200])
                continue
            resp.raise_for_status()

            result = self._parse_csv(resp.text)
            if result is None:
                logger.warning(
                    "No cloud-free chlorophyll pixel for %s in the last %d days — skipping, not fabricating.",
                    point["name"], self.LOOKBACK_DAYS,
                )
                continue

            valid_date, mean_value, n_pixels = result
            valid_time = datetime(valid_date.year, valid_date.month, valid_date.day, 12, tzinfo=timezone.utc)
            staleness_days = (fetched_at.date() - valid_date).days
            observations.append(
                MarineObservation(
                    variable="chlorophyll_mg_m3",
                    value=mean_value,
                    unit="mg m^-3",
                    lat=point["lat"],
                    lon=point["lon"],
                    valid_time=valid_time,
                    fetched_at=fetched_at,
                    source=self.SOURCE_NAME,
                    confidence=self._confidence_for_staleness(staleness_days),
                    freshness_min=staleness_days * 24 * 60,
                    provenance=query_url,
                )
            )
        return observations

    def _build_url(self, box: dict) -> str:
        # Anchored to the dataset's own "last" available granule, not
        # wall-clock today: this NRT product can lag real time by weeks
        # (SCRATCH.md), so a wall-clock window can miss all available data.
        return (
            f"{self.BASE_URL}?{self.VARIABLE}"
            f"[(last-{self.LOOKBACK_DAYS}):1:(last)]"
            f"[(0.0)]"
            f"[({box['min_lat']}):({box['max_lat']})]"
            f"[({box['min_lon']}):({box['max_lon']})]"
        )

    def _parse_csv(self, csv_text: str) -> tuple[date, float, int] | None:
        """Return (most_recent_valid_date, mean_value, n_pixels) or None
        if every pixel in the window is cloud-masked (NaN).
        """
        reader = csv.reader(io.StringIO(csv_text))
        rows = list(reader)
        if len(rows) < 3:
            return None
        data_rows = rows[2:]  # row 0 = names, row 1 = units

        by_date: dict[date, list[float]] = {}
        for row in data_rows:
            if len(row) < 5:
                continue
            time_str, _altitude, _lat, _lon, value_str = row[:5]
            if value_str.strip().upper() == "NAN":
                continue
            try:
                value = float(value_str)
                day = datetime.fromisoformat(time_str.replace("Z", "+00:00")).date()
            except ValueError:
                continue
            by_date.setdefault(day, []).append(value)

        if not by_date:
            return None

        latest_valid_date = max(by_date)
        values = by_date[latest_valid_date]
        return latest_valid_date, sum(values) / len(values), len(values)

    def _confidence_for_staleness(self, staleness_days: int) -> float:
        # Documented heuristic: confidence decays linearly with the age of
        # the last cloud-free pass, floored so we never claim zero trust
        # in a real (if old) reading.
        return max(0.3, 0.9 - 0.05 * staleness_days)


class ERDDAPBathymetryFetcher:
    """NOAA NCEI ETOPO 2022 (60 arc-second) global relief -- real seafloor
    elevation for the 3D ocean/geospatial view, gridded once over the
    whole demo BBOX.

    Deliberately NOT a per-point MarineObservation stream like the
    fetchers above: this is a static 2022 seabed/topography survey, not a
    live advisory signal, so it never flows through orca/policy.py or
    orca/agents.py (CLAUDE.md rule 4 -- the safety cascade only ever sees
    real advisory evidence). It's cached to its own subdirectory for the
    same reason -- see write_bathymetry_cache().

    `z` is "positive up" (source: dataset .das attributes) -- positive
    values are land elevation, negative values are depth below sea
    level. We keep that sign convention and call the field `elevation_m`
    rather than `depth_m` to avoid an inverted-sign bug down the line.
    """

    BASE_URL = "https://oceanwatch.pifsc.noaa.gov/erddap/griddap/ETOPO_2022_v1_60s.csv"
    DATASET_ID = "ETOPO_2022_v1_60s"
    SOURCE_NAME = "NOAA NCEI ETOPO 2022 (60 arc-second)"
    # Native resolution is 60 arc-second (~1.85 km at this latitude).
    # Stride 4 -> ~7.4 km spacing: plenty smooth for a 3D relief mesh
    # without a multi-thousand-point payload (verified against the real
    # host: BBOX at stride 4 is ~1400 points, ~64 KB as CSV).
    STRIDE = 4

    def fetch(self, box: dict) -> dict:
        query_url = self._build_url(box)
        resp = requests.get(query_url, timeout=30)
        resp.raise_for_status()

        points = self._parse_csv(resp.text)
        if not points:
            raise ValueError(
                "ETOPO bathymetry query returned no usable points -- refusing to "
                "cache an empty grid as if it were real relief data"
            )

        return {
            "source": self.SOURCE_NAME,
            "dataset_id": self.DATASET_ID,
            "provenance": query_url,
            "fetched_at": datetime.now(timezone.utc).isoformat(),
            "bbox": box,
            "stride": self.STRIDE,
            "points": points,
        }

    def _build_url(self, box: dict) -> str:
        return (
            f"{self.BASE_URL}?z"
            f"[({box['min_lat']}):{self.STRIDE}:({box['max_lat']})]"
            f"[({box['min_lon']}):{self.STRIDE}:({box['max_lon']})]"
        )

    def _parse_csv(self, csv_text: str) -> list[dict]:
        reader = csv.reader(io.StringIO(csv_text))
        rows = list(reader)
        if len(rows) < 3:
            return []
        data_rows = rows[2:]  # row 0 = names, row 1 = units

        points: list[dict] = []
        for row in data_rows:
            if len(row) < 3:
                continue
            lat_str, lon_str, z_str = row[:3]
            if z_str.strip().upper() == "NAN":
                continue  # no fabricated fill value -- point is simply omitted
            try:
                points.append({"lat": float(lat_str), "lon": float(lon_str), "elevation_m": float(z_str)})
            except ValueError:
                continue
        return points


def write_bathymetry_cache(grid: dict, cache_dir: Path) -> Path:
    """Writes to its own subdirectory (default data/cache/bathymetry/),
    never data/cache/ directly -- orca/planner.py's load_cached_observations()
    globs cache_dir/*.json non-recursively and parses every match as a
    list of MarineObservation dicts; a bathymetry grid is a single dict
    with a `points` key, not a list, and mixing them in the same
    directory would break every /ask call. See tests/test_fetch.py.
    """
    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    path = cache_dir / "bathymetry_grid.json"
    path.write_text(__import__("json").dumps(grid, indent=2))
    return path


class MarineRegionsIMBLFetcher:
    """The real India-Sri Lanka maritime boundary (IMBL), from Marine
    Regions (Flanders Marine Institute / IOC-UNESCO) -- the standard
    reference used worldwide for EEZ/boundary geometry, not an ORCA
    approximation. Like bathymetry, this is real reference geometry, not
    a MarineObservation: orca/agents.py's geofence_agent reads the cached
    segments directly to compute a real distance-to-boundary, rather than
    this flowing through orca/policy.py as "evidence".

    Only the 4 real "Sri Lanka - India" treaty-line segments are kept
    (not the full global eez_boundaries layer) -- verified by hand
    against the fetched geometry that these cover Gulf of Mannar and
    Palk Bay/Strait, i.e. exactly the water off the Tamil Nadu coast in
    data/fetch.py's ZONES (see SCRATCH.md for the verification).
    """

    BASE_URL = "https://geo.vliz.be/geoserver/MarineRegions/wfs"
    SOURCE_NAME = "Marine Regions (Flanders Marine Institute / IOC-UNESCO) -- India-Sri Lanka IMBL"
    LINE_NAME = "Sri Lanka - India"

    def fetch(self) -> dict:
        params = self._build_params()
        resp = requests.get(self.BASE_URL, params=params, timeout=30)
        resp.raise_for_status()

        segments = self._parse_geojson(resp.json())
        if not segments:
            raise ValueError(
                "Marine Regions WFS returned no India-Sri Lanka IMBL segments -- "
                "refusing to cache an empty boundary as if it were real geometry"
            )

        return {
            "source": self.SOURCE_NAME,
            "provenance": resp.url,
            "fetched_at": datetime.now(timezone.utc).isoformat(),
            "segments": segments,
        }

    def _build_params(self) -> dict:
        return {
            "service": "WFS",
            "version": "1.0.0",
            "request": "GetFeature",
            "typeName": "eez_boundaries",
            "outputformat": "application/json",
            "CQL_FILTER": f"line_name='{self.LINE_NAME}'",
        }

    def _parse_geojson(self, data: dict) -> list[list[tuple[float, float]]]:
        segments = []
        for feature in data.get("features", []):
            for line in feature.get("geometry", {}).get("coordinates", []):
                # GeoJSON is [lon, lat]; we store (lat, lon) to match
                # MarineObservation's field order everywhere else.
                segments.append([(pt[1], pt[0]) for pt in line])
        return segments


def write_imbl_cache(boundary: dict, cache_dir: Path) -> Path:
    """Writes to its own subdirectory (data/cache/imbl/), for the same
    reason write_bathymetry_cache() does: load_cached_observations()
    globs data/cache/*.json non-recursively and would choke on this
    dict-with-a-`segments`-key shape if it landed next to the
    point-observation files.
    """
    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    path = cache_dir / "imbl_boundary.json"
    path.write_text(__import__("json").dumps(boundary, indent=2))
    return path


def fetch_all(points: list[dict] | None = None) -> tuple[dict[str, list[MarineObservation]], dict[str, str]]:
    """Run every fetcher. Returns (results_by_source, errors_by_source).

    A source that fails is recorded in `errors` and simply absent (or
    empty) from `results` — never backfilled with placeholder data.
    """
    points = points or ZONES
    fetchers = {
        OpenMeteoMarineFetcher.SOURCE_NAME: OpenMeteoMarineFetcher(),
        OpenMeteoForecastFetcher.SOURCE_NAME: OpenMeteoForecastFetcher(),
        ERDDAPChlorophyllFetcher.SOURCE_NAME: ERDDAPChlorophyllFetcher(),
    }
    results: dict[str, list[MarineObservation]] = {}
    errors: dict[str, str] = {}

    for source_name, fetcher in fetchers.items():
        try:
            obs = fetcher.fetch(points)
            results[source_name] = obs
            logger.info("%s: %d observations", source_name, len(obs))
        except Exception as exc:  # noqa: BLE001 — logged loudly, re-raised as an error entry, never swallowed
            logger.error("%s FAILED: %s", source_name, exc)
            errors[source_name] = str(exc)

    return results, errors


def _slug(source_name: str) -> str:
    return source_name.lower().replace(" ", "-").replace("(", "").replace(")", "")


def write_cache(source_name: str, observations: list[MarineObservation], cache_dir: Path = CACHE_DIR) -> Path:
    for obs in observations:
        if any(bad in obs.source.lower() for bad in _FORBIDDEN_SOURCE_WORDS):
            raise ValueError(f"refusing to cache observation with suspicious source: {obs.source!r}")

    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    filename = f"{_slug(source_name)}_{date.today().isoformat()}.json"
    path = cache_dir / filename
    path.write_text(
        __import__("json").dumps([o.to_dict() for o in observations], indent=2)
    )
    return path



# ---------------------------------------------------------------------------
# IMD storm/cyclone warnings, via the public CAP feed
# ---------------------------------------------------------------------------


class IMDCapAlertFetcher:
    """India Meteorological Department warnings, as signed CAP 1.2 documents.

    Why this feed and not IMD's own API: api.imd.gov.in has exactly the
    endpoints we want (/api/v1/seabulletin, /portwarning, /coastalbulletin,
    /cyclone_track) but every one of them is behind static-IP whitelisting
    granted by application to IMD's ISSD. A demo machine cannot hold a
    whitelisted IP, so those are documented in docs/RESEARCH.md as the
    production path and not used here.

    This feed is the same authority publishing through the CAP alert hub:
    public, unauthenticated, OASIS CAP v1.2, and every document carries an
    XML-DSig RSA-SHA256 signature. Each alert states its own polygon, onset
    and expiry, which is what lets orca/alerts.py answer "is THIS zone under
    a warning right now" without ORCA inventing anything.

    What it does NOT do: this is IMD's national feed. On an ordinary day it
    carries inland rainfall warnings and nothing over the Tamil Nadu coast,
    and the honest answer for a zone is then "no active IMD warning" -- not
    a reassurance that the weather is fine. See orca/alerts.py.
    """

    RSS_URL = "https://cap-sources.s3.amazonaws.com/in-imd-en/rss.xml"
    SOURCE_NAME = "India Meteorological Department (CAP v1.2 public feed)"
    CAP_NS = {"cap": "urn:oasis:names:tc:emergency:cap:1.2"}
    # The feed keeps a long tail. Anything already expired is dropped at
    # match time; this only bounds how many documents we pull per refresh.
    MAX_ALERTS = 25

    def fetch(self) -> dict:
        resp = requests.get(self.RSS_URL, timeout=30)
        resp.raise_for_status()

        links = self._parse_rss(resp.text)
        alerts = []
        for link in links[: self.MAX_ALERTS]:
            alerts.append(self._fetch_one(link))

        return {
            "source": self.SOURCE_NAME,
            "provenance": self.RSS_URL,
            "fetched_at": datetime.now(timezone.utc).isoformat(),
            "alerts": alerts,
        }

    def _parse_rss(self, xml_text: str) -> list[str]:
        import xml.etree.ElementTree as ET

        root = ET.fromstring(xml_text)
        links = []
        for item in root.iter("item"):
            link = item.findtext("link")
            if link:
                links.append(link.strip())
        return links

    def _fetch_one(self, cap_url: str) -> dict:
        import xml.etree.ElementTree as ET

        resp = requests.get(cap_url, timeout=30)
        resp.raise_for_status()
        root = ET.fromstring(resp.text)
        ns = self.CAP_NS

        info = root.find("cap:info", ns)
        if info is None:
            raise ValueError(f"CAP document has no <info> block: {cap_url}")

        area = info.find("cap:area", ns)
        polygon = None
        area_desc = None
        if area is not None:
            area_desc = (area.findtext("cap:areaDesc", namespaces=ns) or "").strip() or None
            polygon = self._parse_polygon(area.findtext("cap:polygon", namespaces=ns))

        return {
            "identifier": (root.findtext("cap:identifier", namespaces=ns) or "").strip(),
            "sender_name": (info.findtext("cap:senderName", namespaces=ns) or "").strip(),
            "event": (info.findtext("cap:event", namespaces=ns) or "").strip(),
            "headline": (info.findtext("cap:headline", namespaces=ns) or "").strip(),
            "description": (info.findtext("cap:description", namespaces=ns) or "").strip(),
            "instruction": (info.findtext("cap:instruction", namespaces=ns) or "").strip(),
            "severity": (info.findtext("cap:severity", namespaces=ns) or "").strip(),
            "urgency": (info.findtext("cap:urgency", namespaces=ns) or "").strip(),
            "certainty": (info.findtext("cap:certainty", namespaces=ns) or "").strip(),
            "onset": (info.findtext("cap:onset", namespaces=ns) or "").strip() or None,
            "expires": (info.findtext("cap:expires", namespaces=ns) or "").strip() or None,
            "sent": (root.findtext("cap:sent", namespaces=ns) or "").strip() or None,
            "area_desc": area_desc,
            # (lat, lon) pairs, matching MarineObservation field order. None
            # means the alert named no polygon -- it is kept and shown, but
            # orca/alerts.py will never claim it covers a specific zone.
            "polygon": polygon,
            "web": (info.findtext("cap:web", namespaces=ns) or "").strip() or None,
            "provenance": cap_url,
            "signed": root.find(".//{http://www.w3.org/2000/09/xmldsig#}Signature") is not None,
        }

    def _parse_polygon(self, raw: str | None) -> list[list[float]] | None:
        """CAP polygons are space-separated "lat,lon" pairs.

        A malformed pair is fatal rather than skipped: a polygon missing a
        vertex is a DIFFERENT polygon, and silently shrinking a storm
        warning's footprint is exactly the fabrication rule 1 forbids.
        """
        if not raw or not raw.strip():
            return None
        points = []
        for pair in raw.split():
            lat_s, _, lon_s = pair.partition(",")
            points.append([float(lat_s), float(lon_s)])
        if len(points) < 3:
            raise ValueError(f"CAP polygon has fewer than 3 vertices: {raw!r}")
        return points


def write_cap_alert_cache(payload: dict, cache_dir: Path) -> Path:
    """Own subdirectory, same reason as write_imbl_cache()."""
    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    path = cache_dir / "imd_cap_alerts.json"
    path.write_text(__import__("json").dumps(payload, indent=2, ensure_ascii=False))
    return path


def main() -> int:
    results, errors = fetch_all()

    for source_name, obs in results.items():
        if obs:
            path = write_cache(source_name, obs)
            print(f"PASS  {source_name}: {len(obs)} observations -> {path}")
        else:
            print(f"WARN  {source_name}: 0 observations (nothing to cache)")

    for source_name, error in errors.items():
        print(f"FAIL  {source_name}: {error}", file=sys.stderr)

    # Bathymetry and IMBL boundary are map/geofence context, not advisory
    # evidence (see their fetcher docstrings) -- fetched and reported
    # separately, and their failure never fails the advisory-critical run
    # above or below (returned exit code still reflects `results`/`errors`
    # from the point fetchers only).
    try:
        grid = ERDDAPBathymetryFetcher().fetch(BBOX)
        path = write_bathymetry_cache(grid, cache_dir=CACHE_DIR / "bathymetry")
        print(f"PASS  {ERDDAPBathymetryFetcher.SOURCE_NAME}: {len(grid['points'])} grid points -> {path}")
    except Exception as exc:  # noqa: BLE001 — logged loudly, never swallowed
        print(f"FAIL  {ERDDAPBathymetryFetcher.SOURCE_NAME}: {exc}", file=sys.stderr)

    try:
        boundary = MarineRegionsIMBLFetcher().fetch()
        path = write_imbl_cache(boundary, cache_dir=CACHE_DIR / "imbl")
        n_points = sum(len(seg) for seg in boundary["segments"])
        print(f"PASS  {MarineRegionsIMBLFetcher.SOURCE_NAME}: {n_points} boundary points -> {path}")
    except Exception as exc:  # noqa: BLE001 — logged loudly, never swallowed
        print(f"FAIL  {MarineRegionsIMBLFetcher.SOURCE_NAME}: {exc}", file=sys.stderr)

    # IMD storm/cyclone warnings. Same isolation as bathymetry/IMBL: these
    # are alert documents, not advisory evidence, so a feed outage never
    # fails the GO / DO NOT GO run -- the app then says "not checked",
    # which is the honest state and a different thing from "all clear".
    try:
        payload = IMDCapAlertFetcher().fetch()
        path = write_cap_alert_cache(payload, cache_dir=CACHE_DIR / "alerts")
        print(f"PASS  {IMDCapAlertFetcher.SOURCE_NAME}: {len(payload['alerts'])} alerts -> {path}")
    except Exception as exc:  # noqa: BLE001 — logged loudly, never swallowed
        print(f"FAIL  {IMDCapAlertFetcher.SOURCE_NAME}: {exc}", file=sys.stderr)

    # Tomorrow's forecast, for orca/agentic.py's "what about tomorrow"
    # data_lookup answers only -- never read by load_cached_observations()
    # (own subdirectory, same isolation pattern as bathymetry/imbl above),
    # so its failure can't affect the live GO/DO NOT GO path either.
    for fetcher in (OpenMeteoMarineFetcher(), OpenMeteoForecastFetcher()):
        try:
            obs = fetcher.fetch_tomorrow(ZONES)
            if obs:
                path = write_cache(fetcher.SOURCE_NAME, obs, cache_dir=CACHE_DIR / "forecast")
                print(f"PASS  {fetcher.SOURCE_NAME} (tomorrow): {len(obs)} observations -> {path}")
            else:
                print(f"WARN  {fetcher.SOURCE_NAME} (tomorrow): 0 observations (nothing to cache)")
        except Exception as exc:  # noqa: BLE001 — logged loudly, never swallowed
            print(f"FAIL  {fetcher.SOURCE_NAME} (tomorrow): {exc}", file=sys.stderr)

    return 1 if errors and not results else 0


if __name__ == "__main__":
    raise SystemExit(main())
