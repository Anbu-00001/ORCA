"""Where an engine-failed boat will be in six hours.

Deterministic Python. No LLM, no network, no random numbers -- the same
inputs give the same box every time, which is what makes it testable and
what makes it safe to read out over SMS.

THE MODEL
---------
This is the Leeway model that underlies IAMSAR and every operational SAR
drift system, including INCOIS SARAT:

    drift = ambient surface current  +  wind-induced leeway

Leeway is decomposed relative to the wind, not the compass: a downwind
component (DWL) parallel to the wind, and a crosswind component (CWL)
perpendicular to it. Both are linear in the 10 m wind speed:

    DWL [cm/s] = dwl_slope * W10[m/s] + dwl_offset
    CWL [cm/s] = cwl_slope * W10[m/s] + cwl_offset

Coefficients are empirical, per object type, from Allen & Plourde (1999),
"Review of Leeway; Field Experiments and Implementation", USCG R&D Center
Technical Report CG-D-08-99. The exact numbers below are the
FISHING-VESSEL-1 row ("Fishing vessel, general (mean values)") of
OpenDrift's OBJECTPROP.DAT, which is that report's table transcribed. They
are not ORCA's numbers and are not tuned.

WHY A BOX AND NOT A DOT
-----------------------
Operational SAR runs a Monte Carlo ensemble. We do not, on purpose:

  - A random ensemble gives a different answer each run. A fisherman
    reading a position to the Coast Guard over a failing phone needs the
    same answer twice.
  - The published coefficients already carry their own standard
    deviations (12.0 cm/s downwind, 9.4 cm/s crosswind for this hull).
    Sweeping +/-1 sigma analytically gives the same envelope the ensemble
    converges to, for no CPU and no RNG.
  - Which side of the wind a hull crabs to is genuinely unknown -- Allen &
    Plourde give it as +/- the same magnitude -- so BOTH sides are always
    in the envelope. There is nothing to sample.

The output is therefore a quadrilateral: downwind extent from the -1 sigma
to the +1 sigma leeway, crosswind extent to +1 sigma either side, all
translated by the current, which is treated as known (it is a measured
MarineObservation, not a guess).

WHAT IT IS NOT
--------------
It is a single-point forecast held constant over the horizon. Real drift
runs through a moving current and wind field; SARAT does that with the
full ocean model. Over 6 hours near the coast this is defensible; at 24
hours it is a sketch, and `confidence_note` says so at every horizon. It
is an aid to telling someone where to look. It is not a rescue plan.
"""
from __future__ import annotations

import math

# Allen & Plourde (1999), CG-D-08-99 -- FISHING-VESSEL-1, "Fishing vessel,
# general (mean values)". Slopes are percent of the 10 m wind speed;
# offsets and standard deviations are cm/s. Transcribed verbatim from
# OpenDrift's OBJECTPROP.DAT line:
#     2.47  0.00  12.00   2.76  0.00  9.40   -2.76  0.00  9.40
FISHING_VESSEL_GENERAL = {
    "object_type": "FISHING-VESSEL-1",
    "description": "Fishing vessel, general (mean values)",
    "dwl_slope": 2.47,
    "dwl_offset": 0.0,
    "dwl_std": 12.0,
    "cwl_slope": 2.76,
    "cwl_offset": 0.0,
    "cwl_std": 9.4,
    "source": (
        "Allen & Plourde (1999), USCG R&D Center Technical Report CG-D-08-99, "
        "table FISHING-VESSEL-1, via OpenDrift OBJECTPROP.DAT"
    ),
    "provenance": (
        "https://raw.githubusercontent.com/OpenDrift/opendrift/master/"
        "opendrift/models/OBJECTPROP.DAT"
    ),
}

_M_PER_DEG_LAT = 111_320.0


def _leeway_components(wind_ms: float, coef: dict) -> dict:
    """Downwind and crosswind leeway in m/s, as mean and +/-1 sigma.

    Follows OpenDrift's leeway.py update() exactly: the epsilon term
    enters as `eps/20 * windspeed + eps/2`, and the whole bracket is
    multiplied by 0.01 to turn cm/s into m/s.
    """
    def _apply(slope: float, offset: float, eps: float) -> float:
        return ((slope + eps / 20.0) * wind_ms + offset + eps / 2.0) * 0.01

    dw = coef["dwl_slope"], coef["dwl_offset"], coef["dwl_std"]
    cw = coef["cwl_slope"], coef["cwl_offset"], coef["cwl_std"]

    return {
        # A hull always drifts downwind; a negative mean would be
        # unphysical, so the low bound is floored at zero rather than
        # allowed to reverse.
        "downwind_mean": _apply(dw[0], dw[1], 0.0),
        "downwind_low": max(0.0, _apply(dw[0], dw[1], -dw[2])),
        "downwind_high": _apply(dw[0], dw[1], +dw[2]),
        # Sign is unknown, so the magnitude is what matters; the box uses
        # it on both sides of the wind axis.
        "crosswind_mean": abs(_apply(cw[0], cw[1], 0.0)),
        "crosswind_high": abs(_apply(cw[0], cw[1], +cw[2])),
    }


def _offset_position(lat: float, lon: float, east_m: float, north_m: float) -> list:
    """Local flat-earth offset. Good to well under a kilometre over the
    tens of km these drifts cover at ~10 degrees N."""
    dlat = north_m / _M_PER_DEG_LAT
    dlon = east_m / (_M_PER_DEG_LAT * math.cos(math.radians(lat)))
    return [round(lat + dlat, 5), round(lon + dlon, 5)]


def drift_forecast(
    lat: float,
    lon: float,
    wind_speed_kmh: float | None,
    wind_direction_deg: float | None,
    current_speed_kmh: float | None,
    current_direction_deg: float | None,
    hours: float,
    coef: dict | None = None,
) -> dict:
    """Drift box for a disabled hull, `hours` after the engine stops.

    Directions follow the conventions of the sources they come from, and
    getting either backwards would send a search the wrong way, so both
    are converted explicitly here:

      wind_direction_deg    meteorological -- the direction the wind blows
                            FROM. Open-Meteo `wind_direction_10m`.
      current_direction_deg oceanographic -- the direction the current
                            flows TOWARD. Open-Meteo
                            `ocean_current_direction`, documented as
                            "Direction following the flow of the current.
                            E.g. where the current is heading towards."

    Any missing input is refused rather than defaulted. A drift computed
    with an assumed wind direction is a fabricated position, and this
    number gets read to the Coast Guard.
    """
    coef = coef or FISHING_VESSEL_GENERAL

    missing = [
        name
        for name, value in (
            ("wind speed", wind_speed_kmh),
            ("wind direction", wind_direction_deg),
            ("current speed", current_speed_kmh),
            ("current direction", current_direction_deg),
        )
        if value is None
    ]
    if missing:
        return {
            "ok": False,
            "reason": (
                "Cannot compute drift: no reading for " + ", ".join(missing) + ". "
                "ORCA will not guess a direction for a position that gets "
                "passed to a rescue."
            ),
            "missing": missing,
        }

    wind_ms = wind_speed_kmh / 3.6
    current_ms = current_speed_kmh / 3.6
    seconds = hours * 3600.0

    # Wind blows TOWARD (from + 180).
    wind_toward = math.radians((wind_direction_deg + 180.0) % 360.0)
    # Unit vector along the wind, and 90 degrees right of it.
    dw_east, dw_north = math.sin(wind_toward), math.cos(wind_toward)
    cw_east, cw_north = math.cos(wind_toward), -math.sin(wind_toward)

    # Current already points where it is going.
    cur = math.radians(current_direction_deg % 360.0)
    cur_east = current_ms * math.sin(cur)
    cur_north = current_ms * math.cos(cur)

    lw = _leeway_components(wind_ms, coef)

    def _corner(downwind_ms: float, crosswind_ms: float) -> list:
        east = (cur_east + downwind_ms * dw_east + crosswind_ms * cw_east) * seconds
        north = (cur_north + downwind_ms * dw_north + crosswind_ms * cw_north) * seconds
        return _offset_position(lat, lon, east, north)

    centre = _corner(lw["downwind_mean"], 0.0)
    corners = [
        _corner(lw["downwind_low"], +lw["crosswind_high"]),
        _corner(lw["downwind_high"], +lw["crosswind_high"]),
        _corner(lw["downwind_high"], -lw["crosswind_high"]),
        _corner(lw["downwind_low"], -lw["crosswind_high"]),
    ]

    # Mean displacement, for the one line a fisherman actually reads out.
    mean_east = (cur_east + lw["downwind_mean"] * dw_east) * seconds
    mean_north = (cur_north + lw["downwind_mean"] * dw_north) * seconds
    distance_km = math.hypot(mean_east, mean_north) / 1000.0
    bearing = (math.degrees(math.atan2(mean_east, mean_north)) + 360.0) % 360.0

    return {
        "ok": True,
        "hours": hours,
        "origin": [round(lat, 5), round(lon, 5)],
        "centre": centre,
        "box": corners,
        "distance_km": round(distance_km, 2),
        "bearing_deg": round(bearing, 1),
        "speed_kmh": round(distance_km / hours, 2) if hours else 0.0,
        "components": {
            "current_ms": round(current_ms, 4),
            "current_toward_deg": round(current_direction_deg % 360.0, 1),
            "wind_ms": round(wind_ms, 4),
            "wind_toward_deg": round((wind_direction_deg + 180.0) % 360.0, 1),
            "downwind_leeway_ms": round(lw["downwind_mean"], 4),
            "crosswind_leeway_ms": round(lw["crosswind_mean"], 4),
        },
        "model": coef["description"],
        "source": coef["source"],
        "provenance": coef["provenance"],
        "confidence_note": _confidence_note(hours),
    }


def _confidence_note(hours: float) -> str:
    if hours <= 6:
        return (
            "Wind and current are held at the values measured now. Over 6 hours "
            "near the coast that is a reasonable assumption."
        )
    if hours <= 12:
        return (
            "Wind and current are held at the values measured now, which they "
            "will not be for 12 hours. Treat the box as a search area, not a "
            "position."
        )
    return (
        "Over 24 hours the wind and current WILL change and this model does not "
        "know how. This is a sketch of a direction, not a forecast. Give the "
        "Coast Guard the 6-hour box and your last known position."
    )


def bearing_to_compass(bearing_deg: float) -> str:
    """16-point compass name -- what gets said out loud, in any language."""
    names = [
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    ]
    return names[int((bearing_deg % 360.0) / 22.5 + 0.5) % 16]
