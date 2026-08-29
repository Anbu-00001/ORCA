"""Which IMD warnings actually cover a zone, right now.

Deterministic Python, in the spirit of orca/policy.py: no LLM, no network,
no inference. It answers one question -- does this CAP polygon contain this
point, and has the alert expired -- and it answers it the same way every
time.

The honest-answer rule matters more here than anywhere else in ORCA. IMD's
public CAP feed is a NATIONAL feed. On most days it carries inland rainfall
warnings and nothing at all over the Tamil Nadu coast. "No active IMD
warning covering this zone" is then the correct output, and it means
exactly that -- not "the weather is fine", and not "we checked the sea".
The UI must never round it up to reassurance.

Three buckets come back, and they are kept apart on purpose:

  covering    -- unexpired, has a polygon, and the polygon contains the
                 point. This is the only bucket that may raise an alarm.
  ungeolocated-- unexpired, but the alert named no polygon. ORCA cannot
                 tell whether it covers you, and says so.
  elsewhere   -- unexpired, has a polygon, does not contain the point.
                 Carried with a real distance so the app can say where the
                 nearest live warning actually is instead of looking dead.
"""
from __future__ import annotations

import math
from datetime import datetime


def _parse_cap_time(raw: str | None) -> datetime | None:
    """CAP times are ISO 8601 with a real offset, e.g. 2026-08-31T09:00:00+05:30."""
    if not raw:
        return None
    return datetime.fromisoformat(raw)


def point_in_polygon(lat: float, lon: float, polygon: list) -> bool:
    """Ray casting, counting crossings of the horizontal line y = lat.

    Polygon vertices are (lat, lon) pairs, matching MarineObservation's
    field order. CAP polygons are closed (last vertex repeats the first),
    which this handles without special-casing.

    Caveat stated rather than hidden: this is planar. Over a warning
    polygon a few hundred km across, at ~10 degrees N, the error from
    ignoring the earth's curvature is far smaller than the polygon's own
    resolution -- IMD draws these to state boundaries, not to the metre.
    """
    if not polygon or len(polygon) < 3:
        return False

    inside = False
    n = len(polygon)
    j = n - 1
    for i in range(n):
        lat_i, lon_i = polygon[i][0], polygon[i][1]
        lat_j, lon_j = polygon[j][0], polygon[j][1]
        # Does edge j->i straddle the horizontal line through `lat`?
        if (lat_i > lat) != (lat_j > lat):
            # Longitude where that edge crosses the line.
            crossing_lon = lon_i + (lat - lat_i) * (lon_j - lon_i) / (lat_j - lat_i)
            if lon < crossing_lon:
                inside = not inside
        j = i
    return inside


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def distance_to_polygon_km(lat: float, lon: float, polygon: list) -> float:
    """Distance to the nearest VERTEX, not the nearest edge.

    Deliberate: it is an over-estimate of how far the warning area is, and
    an over-estimate is the safe direction to be wrong in when the number
    is only ever used to say "the nearest live warning is far away". It is
    labelled as approximate everywhere it surfaces.
    """
    return min(haversine_km(lat, lon, pt[0], pt[1]) for pt in polygon)


def active_alerts_for(
    lat: float,
    lon: float,
    payload: dict | None,
    now: datetime,
) -> dict:
    """Split a cached CAP payload into the three buckets above.

    `payload` is what data.fetch.IMDCapAlertFetcher.fetch() produced, read
    back from data/cache/alerts/. None (never fetched) is distinguished
    from an empty feed: one means ORCA does not know, the other means IMD
    has published nothing.
    """
    if payload is None:
        return {
            "checked": False,
            "reason": "No IMD CAP feed has been fetched into the cache.",
            "source": None,
            "fetched_at": None,
            "covering": [],
            "ungeolocated": [],
            "elsewhere": [],
        }

    covering: list[dict] = []
    ungeolocated: list[dict] = []
    elsewhere: list[dict] = []

    for alert in payload.get("alerts", []):
        expires = _parse_cap_time(alert.get("expires"))
        if expires is not None and expires <= now:
            continue  # over; not a warning any more

        polygon = alert.get("polygon")
        if not polygon:
            ungeolocated.append(dict(alert, expired=False))
            continue

        if point_in_polygon(lat, lon, polygon):
            covering.append(dict(alert, distance_km=0.0))
        else:
            elsewhere.append(
                dict(alert, distance_km=round(distance_to_polygon_km(lat, lon, polygon), 1))
            )

    elsewhere.sort(key=lambda a: a["distance_km"])

    return {
        "checked": True,
        "reason": None,
        "source": payload.get("source"),
        "fetched_at": payload.get("fetched_at"),
        "provenance": payload.get("provenance"),
        "covering": covering,
        "ungeolocated": ungeolocated,
        "elsewhere": elsewhere,
    }


# Severity ordering as CAP defines it, most severe first. Used only to sort
# and to colour; never to invent a severity IMD did not state.
CAP_SEVERITY_RANK = {
    "Extreme": 0,
    "Severe": 1,
    "Moderate": 2,
    "Minor": 3,
    "Unknown": 4,
}


def worst_severity(alerts: list[dict]) -> str | None:
    """The most severe CAP severity among `alerts`, or None if empty."""
    if not alerts:
        return None
    return min(
        (a.get("severity") or "Unknown" for a in alerts),
        key=lambda s: CAP_SEVERITY_RANK.get(s, 99),
    )
