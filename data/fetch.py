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

BBOX = {"min_lat": 10.5, "max_lat": 13.5, "min_lon": 79.5, "max_lon": 81.5}

# Named sample points across the region. Zone A/B coordinates match the
# worked example in API_CONTRACT.md so the whole system stays consistent.
ZONES = [
    {"name": "Zone A", "lat": 10.76, "lon": 79.84},
    {"name": "Zone B", "lat": 10.85, "lon": 79.95},
    {"name": "Zone C", "lat": 11.50, "lon": 80.20},
    {"name": "Zone D", "lat": 12.80, "lon": 80.50},
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
    """Wave height/period/direction and SST, per point, near-term forecast."""

    BASE_URL = "https://marine-api.open-meteo.com/v1/marine"
    SOURCE_NAME = "Open-Meteo Marine"
    HOURLY_VARS = (
        "wave_height,wave_period,wave_direction,sea_surface_temperature,"
        "ocean_current_velocity,ocean_current_direction"
    )
    _VAR_MAP = {
        "wave_height": "wave_height_m",
        "wave_period": "wave_period_s",
        "wave_direction": "wave_direction_deg",
        "sea_surface_temperature": "sst_c",
        "ocean_current_velocity": "ocean_current_velocity_kmh",
        "ocean_current_direction": "ocean_current_direction_deg",
    }
    # Open-Meteo does not publish per-point uncertainty. We use a fixed,
    # documented heuristic: high confidence for the nearest forecast hour.
    NEAR_TERM_CONFIDENCE = 0.9

    def fetch(self, points: list[dict]) -> list[MarineObservation]:
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
            observations.extend(self._parse_point(resp.json(), point, fetched_at))
        return observations

    def _parse_point(self, raw: dict, point: dict, fetched_at: datetime) -> list[MarineObservation]:
        hourly = raw["hourly"]
        units = raw.get("hourly_units", {})
        idx = 0  # nearest upcoming hourly slot
        valid_time = datetime.fromisoformat(hourly["time"][idx]).replace(tzinfo=timezone.utc)

        request_url = (
            f"{self.BASE_URL}?latitude={point['lat']}&longitude={point['lon']}"
            f"&hourly={self.HOURLY_VARS}&timezone=UTC&forecast_days=2"
        )

        observations = []
        for raw_var, schema_var in self._VAR_MAP.items():
            series = hourly.get(raw_var)
            if series is None or series[idx] is None:
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
                    confidence=self.NEAR_TERM_CONFIDENCE,
                    freshness_min=max(0, int((fetched_at - valid_time).total_seconds() // 60)),
                    provenance=request_url,
                )
            )
        return observations


class OpenMeteoForecastFetcher:
    """Wind speed/gusts and precipitation, per point, near-term forecast."""

    BASE_URL = "https://api.open-meteo.com/v1/forecast"
    SOURCE_NAME = "Open-Meteo Forecast"
    HOURLY_VARS = "wind_speed_10m,wind_gusts_10m,precipitation,rain"
    _VAR_MAP = {
        "wind_speed_10m": "wind_speed_kmh",
        "wind_gusts_10m": "wind_gusts_kmh",
        "precipitation": "precipitation_mm",
        "rain": "rain_mm",
    }
    NEAR_TERM_CONFIDENCE = 0.9

    def fetch(self, points: list[dict]) -> list[MarineObservation]:
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
            observations.extend(self._parse_point(resp.json(), point, fetched_at))
        return observations

    def _parse_point(self, raw: dict, point: dict, fetched_at: datetime) -> list[MarineObservation]:
        hourly = raw["hourly"]
        units = raw.get("hourly_units", {})
        idx = 0
        valid_time = datetime.fromisoformat(hourly["time"][idx]).replace(tzinfo=timezone.utc)

        request_url = (
            f"{self.BASE_URL}?latitude={point['lat']}&longitude={point['lon']}"
            f"&hourly={self.HOURLY_VARS}&timezone=UTC&forecast_days=2"
        )

        observations = []
        for raw_var, schema_var in self._VAR_MAP.items():
            series = hourly.get(raw_var)
            if series is None or series[idx] is None:
                continue
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
                    confidence=self.NEAR_TERM_CONFIDENCE,
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

    # Bathymetry is map context, not advisory evidence (see
    # ERDDAPBathymetryFetcher docstring) -- fetched and reported
    # separately, and its failure never fails the advisory-critical run
    # above or below (returned exit code still reflects `results`/`errors`
    # from the point fetchers only).
    try:
        grid = ERDDAPBathymetryFetcher().fetch(BBOX)
        path = write_bathymetry_cache(grid, cache_dir=CACHE_DIR / "bathymetry")
        print(f"PASS  {ERDDAPBathymetryFetcher.SOURCE_NAME}: {len(grid['points'])} grid points -> {path}")
    except Exception as exc:  # noqa: BLE001 — logged loudly, never swallowed
        print(f"FAIL  {ERDDAPBathymetryFetcher.SOURCE_NAME}: {exc}", file=sys.stderr)

    return 1 if errors and not results else 0


if __name__ == "__main__":
    raise SystemExit(main())
