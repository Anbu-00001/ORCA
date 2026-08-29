"""Deterministic answer phrasing. No LLM, no network.

orca/extract.py made ORCA UNDERSTAND a question without a model. This
module makes it ANSWER one. Without both halves the first is invisible:
with the key removed, "How high are the waves at Chennai?" was correctly
classified as a data_lookup for wave_height_m and then answered "Go to
Chennai. SST 30.2 degC within productive range" -- the right reading of
the question, rendered as the wrong sentence.

The division of labour with the model is deliberate and unchanged:

    this module   states WHAT IS TRUE, from values already computed
    the model     says it more naturally, and says it in Tamil

So the model is now a fluency layer over a correct answer, rather than
the thing that decides whether there is an answer at all. When it is
present the text below is replaced by its phrasing; when it is absent,
rate-limited, or the venue wifi is down, what remains is still a real
answer to the question that was asked.

Every number here comes from a MarineObservation that the planner already
resolved (CLAUDE.md rule 3). Nothing in this file computes, rounds into
significance, or infers a value -- it only chooses a sentence. The verdict
is orca/policy.py's and is passed through untouched.
"""
from __future__ import annotations

# Fisherman-readable names for the variables a data_lookup can ask for.
# Kept here rather than derived from the identifier so the wording is a
# deliberate choice ("sea temperature", not "sst c").
_READABLE = {
    "wave_height_m": "wave height",
    "wave_period_s": "wave period",
    "wave_direction_deg": "wave direction",
    "wind_speed_kmh": "wind speed",
    "wind_gusts_kmh": "wind gusts",
    "sst_c": "sea temperature",
    "ocean_current_velocity_kmh": "current speed",
    "ocean_current_direction_deg": "current direction",
    "rain_mm": "rain",
    "precipitation_mm": "precipitation",
    "chlorophyll_mg_m3": "chlorophyll",
}

# Which end of a ranking answers which question. _rank_zones() returns
# WORST/highest first, so "safest" and "calmest" are the LAST entry.
_SUPERLATIVE_IS_FIRST = ("worst", "highest", "roughest", "strongest", "most", "windiest")


def readable(variable: str | None) -> str:
    return _READABLE.get(variable or "", variable or "that reading")


def _format(value: float, unit: str) -> str:
    """Trim a trailing .0 so "2 m" does not read as "2.0 m", without ever
    changing the value. Display only -- /evidence/{id} still serves the
    full precision the source published."""
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return f"{text} {unit}"


def data_lookup_sentence(lookup: dict | None, zone_name: str | None) -> str | None:
    """The number they asked for, or an honest statement that ORCA has no
    such reading. Never a substituted one (rule 1)."""
    if not lookup:
        return None
    where = f" at {zone_name}" if zone_name else ""
    if lookup.get("missing"):
        when = " for tomorrow" if lookup.get("time_frame") == "tomorrow" else ""
        return (
            f"ORCA has no {readable(lookup.get('variable'))} reading{where}{when}."
        )
    return (
        f"The {readable(lookup.get('variable'))}{where} is "
        f"{_format(lookup['value'], lookup['unit'])}."
    )


def ranking_sentence(ranking: list[dict] | None, query: str, variable: str | None) -> str | None:
    """The comparison answered from the real, Python-computed ordering.

    Which END of the list to quote depends on what was asked, so the
    superlative in the question decides it. Asked for the calmest and
    handed the worst, a confident sentence would be exactly backwards --
    and pointed at danger rather than away from it.
    """
    if not ranking:
        return None
    wants_first = any(w in query.lower() for w in _SUPERLATIVE_IS_FIRST)
    entry = ranking[0] if wants_first else ranking[-1]
    zone = entry.get("zone")

    if "value" in entry and "unit" in entry:
        descriptor = "highest" if wants_first else "lowest"
        return (
            f"{zone} has the {descriptor} {readable(variable)} of the "
            f"{len(ranking)} zones ORCA covers, at "
            f"{_format(entry['value'], entry['unit'])}."
        )
    # Risk-ordered: policy.py's verdict word only. The raw risk_level is
    # deliberately not exposed -- it is a policy output, not a
    # MarineObservation, and rule 3 governs every number a user sees.
    descriptor = "most hazardous" if wants_first else "least hazardous"
    return (
        f"Of the {len(ranking)} zones ORCA covers, {zone} is currently the "
        f"{descriptor} — ORCA's verdict there is {entry.get('action')}."
    )


def render(
    recommendation,
    *,
    query: str,
    intent: str,
    variable: str | None,
    lookup: dict | None,
    ranking: list[dict] | None,
    coverage_note: str | None,
    on_topic: bool,
) -> str:
    """The deterministic answer text, assembled from values the planner
    already computed. Returns the recommendation's existing verdict text
    unchanged when there is nothing more specific to say."""
    if not on_topic:
        return (
            "ORCA only answers questions about sea conditions and whether it is "
            "safe to go fishing off the Tamil Nadu coast. Ask about that and it "
            "will tell you what the readings say."
        )

    zone = recommendation.chosen_zone or recommendation.primary_zone or {}
    zone_name = zone.get("name") if isinstance(zone, dict) else None

    parts: list[str] = []

    comparison = ranking_sentence(ranking, query, variable)

    # A comparison answers the question by itself. Leading with the
    # anchor zone's own reading ("The wave height at Rameswaram is
    # 1.38 m. Thoothukudi has the highest...") is noise: Rameswaram is
    # merely where the question was asked from, not what was asked about.
    if intent == "data_lookup" and not comparison:
        sentence = data_lookup_sentence(lookup, zone_name)
        if sentence:
            parts.append(sentence)

    if comparison:
        parts.append(comparison)

    # The verdict always comes last and always comes. A narrower question
    # must never bury it -- someone who asked only for a wave height still
    # needs to be told the sea is over the limit.
    parts.append(recommendation.recommendation)

    if coverage_note:
        parts.append(coverage_note)

    return " ".join(p for p in parts if p)
