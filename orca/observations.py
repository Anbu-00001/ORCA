"""The uplink quarantine: accepting boat-reported readings without lying.

WHY THIS FILE IS SEPARATE FROM EVERYTHING ELSE
----------------------------------------------
Every boat with a fish-finder is already measuring sea temperature and
depth. The standard NMEA 0183 sentences are real and universal --
`$SDMTW` (mean temperature of water), `$SDDBT` (depth below transducer),
`$SDDPT` (depth) -- and nobody collects any of it. The Bay of Bengal is
chronically under-sampled in situ; ORCA's own cache currently has six
zones with no cloud-free chlorophyll pixel in 15 days. A fleet reporting
bulk temperature at depth, with timestamps and positions, is a dataset
that does not presently exist.

That is the opportunity. This module is about the danger.

THE BAR THIS DATA DOES NOT MEET
-------------------------------
Published in-situ work in the northern Bay of Bengal (36-station CTD
surveys run from chartered fishing vessels) calibrates its temperature
sensors in a laboratory against a constant-temperature bath and an
ice-water reference point before every cruise. That is what research-grade
in-situ SST means.

A reading off an uncalibrated hull transducer, or typed in by a fisherman,
is not that and will never be that. It is a different kind of data:
enormously higher volume, far lower precision, unknown bias. Useful --
genuinely, and to real researchers -- but only if nothing anywhere ever
presents it as the other thing.

So CLAUDE.md rule 1 applies with full force. Quietly mixing these readings
into data/cache/ would be exactly the fabrication that rule forbids, with
extra steps: the advisory would begin citing numbers whose provenance is
"someone said so".

WHAT THIS MODULE GUARANTEES
---------------------------
1. A SEPARATE STORE. Records go to data/observations/, never data/cache/.
   orca/planner.py's load_cached_observations() globs data/cache/*.json
   and must continue to see nothing from boats -- tests/test_observations.py
   asserts precisely that.
2. NOT A MarineObservation. These are deliberately a different type with
   a different name. A MarineObservation carries a source ORCA can point
   at; this carries a claim. Making them the same type would be the first
   step towards them being treated the same way, and orca/schema.py is
   frozen anyway (CLAUDE.md rule 5).
3. IT DOES NOT FEED THE ADVISORY. Not weighted, not blended, not a
   tiebreak. The value of this data is that it is INDEPENDENT of the
   forecast; the moment it feeds the forecast that produced it, it stops
   being independent and stops being worth anything to a researcher.
4. CONSENT IS MANDATORY, not a default. Where someone fishes is their
   livelihood, and a boat track is personally identifying and
   commercially sensitive. An upload without explicit consent is rejected.
5. POSITION IS COARSENED unless precision is explicitly requested, and how
   the position was obtained is recorded alongside it. An SST reading with
   an unknown position is not a measurement.

No LLM. No network. Deterministic and unit-testable, like the rest of the
non-shell code.
"""
from __future__ import annotations

import hashlib
import json
import logging
from datetime import datetime, timezone
from pathlib import Path

logger = logging.getLogger("orca.observations")

# NEVER data/cache/. This separation is the whole design.
OBSERVATIONS_DIR = Path(__file__).resolve().parent.parent / "data" / "observations"

# The one source string these records ever carry. "unverified" is in the
# name so it survives being copied into a spreadsheet, a plot axis, or a
# slide -- the places where a caveat kept somewhere else gets lost.
FLEET_SOURCE = "ORCA Fleet (unverified)"

# What a boat can actually measure with equipment it already owns. Kept
# deliberately short: a variable nobody can measure is a variable whose
# uploads are guesses.
#
#   sst_c        $SDMTW -- mean temperature of water
#   depth_m      $SDDBT / $SDDPT -- depth below transducer
#   wave_height_m  observed by eye, hence method="manual" in practice
ACCEPTED_VARIABLES = {
    "sst_c": "degC",
    "depth_m": "m",
    "wave_height_m": "m",
}

# Plausibility bounds. NOT quality control -- a reading inside these is
# not thereby good. They exist to reject the obviously impossible (a typo,
# a Fahrenheit value in a Celsius field, a stuck sensor) before it reaches
# a human reviewer, and everything that passes is still quarantined.
PLAUSIBLE_RANGE = {
    "sst_c": (15.0, 40.0),      # Bay of Bengal surface, generously bracketed
    "depth_m": (0.0, 5000.0),
    "wave_height_m": (0.0, 20.0),
}

METHODS = ("instrument", "manual")
POSITION_SOURCES = ("gps", "manual")

# Confidence is CAPPED, and the cap depends on how the number was
# obtained. These are not measurements of accuracy -- nobody has measured
# the accuracy of an uncalibrated fleet transducer -- they are an explicit
# statement that this data ranks below every source in data/cache/, whose
# confidences run 0.7-0.95.
CONFIDENCE_CAP = {
    "instrument": 0.35,
    "manual": 0.20,
}

# ~0.1 degree is roughly 11 km at this latitude: enough to place a reading
# in an oceanographic context, not enough to identify a fishing ground.
# Coarse is the DEFAULT; precision must be asked for.
COARSE_DECIMALS = 1


class ObservationRejected(ValueError):
    """An upload that must not be stored, with a reason the client can show.

    Raised, never swallowed (CLAUDE.md rule 2). Rejecting loudly is the
    point: an upload that silently vanishes teaches a crew that reporting
    does nothing, and an upload silently accepted with bad provenance is
    worse than one refused.
    """


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ObservationRejected(message)


def _coarse(value: float, precise: bool) -> float:
    return float(value) if precise else round(float(value), COARSE_DECIMALS)


def observation_record(payload: dict, *, received_at: datetime | None = None) -> dict:
    """Validate one uploaded reading and return the record to be stored.

    Pure: no I/O, no clock unless one is not supplied. Everything that can
    reject an upload happens here, so the storage function below cannot
    write something that was never checked.
    """
    received_at = received_at or datetime.now(timezone.utc)

    # --- consent, first, before anything else is even looked at ---------
    # Not a checkbox buried in settings and not defaulted on. PRD marks
    # LOCATION PRIVACY as PLANNED; this is where it stops being planned.
    _require(
        payload.get("consent") is True,
        "Upload refused: explicit consent is required for every upload. "
        "ORCA does not store boat positions without it.",
    )

    variable = str(payload.get("variable") or "")
    _require(
        variable in ACCEPTED_VARIABLES,
        f"Upload refused: {variable!r} is not something ORCA accepts from a boat. "
        f"Accepted: {', '.join(sorted(ACCEPTED_VARIABLES))}.",
    )

    raw_value = payload.get("value")
    _require(
        isinstance(raw_value, (int, float)) and not isinstance(raw_value, bool),
        "Upload refused: value must be a number.",
    )
    value = float(raw_value)  # type: ignore[arg-type]  -- guarded above
    low, high = PLAUSIBLE_RANGE[variable]
    _require(
        low <= value <= high,
        f"Upload refused: {value} is outside the plausible range for {variable} "
        f"({low}-{high}). Check the unit and the sensor.",
    )

    method = payload.get("method")
    _require(
        method in METHODS,
        f"Upload refused: method must be one of {METHODS}. "
        "Whether a human read this or an instrument did is part of the reading.",
    )

    position_source = payload.get("position_source")
    _require(
        position_source in POSITION_SOURCES,
        f"Upload refused: position_source must be one of {POSITION_SOURCES}. "
        "A reading with an unknown position is not a measurement.",
    )

    raw_lat, raw_lon = payload.get("lat"), payload.get("lon")
    _require(
        isinstance(raw_lat, (int, float)) and not isinstance(raw_lat, bool)
        and isinstance(raw_lon, (int, float)) and not isinstance(raw_lon, bool),
        "Upload refused: lat and lon are required.",
    )
    lat, lon = float(raw_lat), float(raw_lon)  # type: ignore[arg-type]  -- guarded above
    _require(-90 <= lat <= 90 and -180 <= lon <= 180,
             "Upload refused: lat/lon out of range.")

    observed_at = payload.get("observed_at")
    _require(bool(observed_at), "Upload refused: observed_at is required.")
    try:
        observed = datetime.fromisoformat(str(observed_at))
    except ValueError as exc:
        raise ObservationRejected(
            f"Upload refused: observed_at is not an ISO-8601 timestamp ({exc})."
        ) from exc
    if observed.tzinfo is None:
        observed = observed.replace(tzinfo=timezone.utc)

    precise = payload.get("precise_position") is True
    record = {
        "variable": variable,
        "value": value,
        "unit": ACCEPTED_VARIABLES[variable],
        "lat": _coarse(lat, precise),
        "lon": _coarse(lon, precise),
        "position_precision": "exact" if precise else f"rounded to {COARSE_DECIMALS} dp",
        "position_source": position_source,
        # None is a real answer here. A GPS fix without a stated accuracy
        # is less useful than one with it, and pretending to know is worse
        # than admitting we do not.
        "position_accuracy_m": payload.get("position_accuracy_m"),
        "observed_at": observed.isoformat(),
        "received_at": received_at.isoformat(),
        "method": method,
        "instrument": payload.get("instrument"),
        "source": FLEET_SOURCE,
        "confidence": CONFIDENCE_CAP[method],
        # Says out loud, inside every record, what the record is for.
        # Anyone who reads one of these rows in isolation still learns
        # that it never touched an advisory.
        "provenance": (
            "Reported by an ORCA fleet device. NOT calibrated, NOT quality "
            "controlled, and NOT used to compute any advisory. Held for "
            "research export only."
        ),
        "app_version": payload.get("app_version"),
        # A stable pseudonym, so repeated reports from one boat can be
        # grouped for QC without the boat being identifiable. The raw id
        # is never stored.
        "reporter": _pseudonym(payload.get("device_id")),
        "affects_advisory": False,
    }
    record["id"] = _record_id(record)
    return record


def _pseudonym(device_id) -> str | None:
    if not device_id:
        return None
    return "boat_" + hashlib.sha256(str(device_id).encode()).hexdigest()[:12]


def _record_id(record: dict) -> str:
    key = "|".join(str(record[k]) for k in
                   ("source", "variable", "lat", "lon", "observed_at", "value"))
    return "fleet_" + hashlib.sha1(key.encode()).hexdigest()[:12]


def store(payload: dict, *, directory: Path | None = None,
          received_at: datetime | None = None) -> dict:
    """Validate and append one reading to the quarantine store.

    Append-only JSONL, one file per UTC day. Append-only because a
    research dataset whose history can be rewritten in place is not a
    research dataset -- and because whoever eventually runs QC on this
    (docs/MOBILE_APP.md §5, open question 4) needs to see what actually
    arrived, not a tidied version of it.
    """
    record = observation_record(payload, received_at=received_at)
    directory = Path(directory) if directory else OBSERVATIONS_DIR
    directory.mkdir(parents=True, exist_ok=True)

    day = record["received_at"][:10]
    path = directory / f"fleet_{day}.jsonl"
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(record, ensure_ascii=False) + "\n")

    logger.info(
        "Fleet observation stored (quarantined, does not affect advisories): "
        "%s %s%s at %.1f,%.1f -> %s",
        record["variable"], record["value"], record["unit"],
        record["lat"], record["lon"], path.name,
    )
    return record


def load_all(directory: Path | None = None) -> list[dict]:
    """Every quarantined record, for export and QC. Nothing in orca/ that
    computes an advisory calls this, and nothing ever should."""
    directory = Path(directory) if directory else OBSERVATIONS_DIR
    if not directory.exists():
        return []
    records: list[dict] = []
    for path in sorted(directory.glob("fleet_*.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                records.append(json.loads(line))
    return records
