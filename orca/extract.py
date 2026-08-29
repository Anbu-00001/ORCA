"""Deterministic question understanding. No LLM, no network, no clock.

WHY THIS EXISTS
---------------
The agentic layer was supposed to be an enhancement: with GROQ_API_KEY
unset, ORCA was meant to reproduce its offline behaviour exactly. It did
-- but that behaviour turned out to be almost nothing. Measured with the
key removed, every one of these returned `answer_kind=verdict`,
`time_frame=now`, `language=en`:

    "How high are the waves at Chennai?"   -> a verdict about SST
    "Which place has the worst waves?"     -> a verdict about Rameswaram
    "இன்று மீன்பிடிக்க பாதுகாப்பானதா?"            -> answered in ENGLISH
    "What about tomorrow?"                 -> answered for today

So the deterministic path could answer exactly ONE question ("is it safe
here?") and answered everything else with that same sentence. The model
was not enhancing a capability, it WAS the capability -- which is why a
Groq rate limit during a live demo looked like a chatbot repeating
itself, and why the fix for that looked like it had to be a second API
key.

It does not. Every field the extraction schema produces is a CLOSED SET
-- two intents, three time frames, two scopes, eleven variables, six
unsupported kinds -- and a closed set is a lookup, not an inference. This
module does that lookup in plain Python.

WHAT THIS BUYS
--------------
  * The capability survives a rate limit, an expired key, a bad venue
    wifi, and a provider outage. None of those are demo risks any more.
  * It is genuinely offline, which is what CLAUDE.md rule 8 always said
    and what the agentic layer quietly was not.
  * It halves LLM usage when a key IS present: extraction no longer needs
    a call, so the same quota buys twice the questions.
  * It is instant (microseconds, not ~1 s) and unit-testable, which the
    model's output never was.

WHAT IT DOES NOT DO
-------------------
It does not phrase answers, and it does not resolve a zone from a
description ("the southernmost tip of India"). Those stay with the model,
which is good at both. This is the floor, not the ceiling: when a key is
present orca/agentic.py still calls the model and lets it OVERRIDE these
fields -- see _merge() there. The difference is that the floor is now a
usable product rather than a single sentence.

Keyword matching is a blunt instrument and will mis-read some phrasings.
That is acceptable here in a way it would not be in orca/policy.py: a
wrong `intent` produces a less useful ANSWER, never a wrong VERDICT. The
verdict is computed by the deterministic core from cached observations
and is completely unaffected by anything in this file.
"""
from __future__ import annotations

import re
import unicodedata

from orca import memory

# Tamil occupies one contiguous Unicode block, so language detection is
# exact rather than probabilistic -- no model, no library, no guessing.
# U+0B80..U+0BFF is the whole Tamil block (Unicode 15.0, ch. 12.6).
_TAMIL = re.compile(r"[஀-௿]")


def detect_language(query: str) -> str:
    """'ta' if the question contains any Tamil character, else 'en'.

    A single Tamil character is enough on purpose: a question mixing
    English place names with Tamil words ("Rameswaram-ல பாதுகாப்பானதா?") is a
    Tamil question and must be answered in Tamil. The reverse mistake --
    answering a Tamil speaker in English, which is what shipped -- is the
    worse one for this product's actual users.
    """
    return "ta" if _TAMIL.search(query or "") else "en"


# --- keyword tables -----------------------------------------------------
# Tamil terms are limited to ones ORCA's own Tamil output already uses
# (காற்று, இன்று) or that are unambiguous. The list is deliberately short:
# a missing Tamil keyword degrades to the English-matched default, which
# is the same behaviour as before this module existed. An INVENTED one
# would silently mis-answer, which is worse. Extend from real user
# questions, not from a dictionary.

_VARIABLE_TERMS: list[tuple[str, tuple[str, ...]]] = [
    # Order matters: the most specific phrasing wins. "wave period" must
    # be tested before "wave", "wind gust" before "wind".
    ("wave_period_s", ("wave period", "period of the wave", "swell period")),
    ("wave_direction_deg", ("wave direction", "which way are the waves", "swell direction")),
    ("wave_height_m", ("wave", "waves", "swell", "sea state", "how rough", "அலை")),
    ("wind_gusts_kmh", ("gust", "gusts", "gusting")),
    ("wind_speed_kmh", ("wind", "breeze", "காற்று")),
    ("sst_c", ("sea temperature", "water temperature", "sst", "how warm", "வெப்பநிலை")),
    ("ocean_current_velocity_kmh", ("current speed", "how fast is the current", "current")),
    ("ocean_current_direction_deg", ("current direction",)),
    ("rain_mm", ("rain", "raining", "மழை")),
    ("precipitation_mm", ("precipitation",)),
    ("chlorophyll_mg_m3", ("chlorophyll", "plankton", "fish aggregation", "productivity")),
]

# Phrasings that ask for a NUMBER rather than a decision. Paired with a
# variable term, these make it a data_lookup.
_LOOKUP_PHRASES = (
    "how high", "how strong", "how fast", "how warm", "how cold", "how rough",
    "how much", "how many", "what is", "what's", "whats", "what are",
    "tell me the", "give me the", "show me the", "reading", "value",
    "எவ்வளவு", "என்ன",
)

# Phrasings that want a JUDGEMENT, not a number. These win over a bare
# variable mention.
_VERDICT_PHRASES = (
    "safe", "safety", "should i", "should we", "can i", "can we", "may i",
    "is it ok", "ok to", "alright to", "advisable", "risky", "danger",
    "dangerous", "go out", "head out", "set out", "take my boat",
    "பாதுகாப்ப", "போகலாமா",
)

_TOMORROW = ("tomorrow", "நாளை")
_BEYOND = (
    "day after tomorrow", "next week", "this weekend", "in three days",
    "in 3 days", "next month", "day after",
)

# Questions that need every zone, not the one they are standing in.
_COMPARISON = (
    "which place", "which zone", "which one", "which is", "which are",
    "where is the", "where should", "where can", "safest", "calmest",
    "roughest", "worst", "best", "compare", "better than", "safer than",
    "rougher than", "calmer than", "most dangerous", "least",
    "எங்கே", "எது",
)

# Things ORCA genuinely cannot do. Saying so is a feature; the composer
# turns each into an explicit caveat (_UNSUPPORTED_NOTES in agentic.py).
_UNSUPPORTED_TERMS: list[tuple[str, tuple[str, ...]]] = [
    ("route", ("route", "navigate", "navigation", "how do i get", "directions", "waypoint")),
    ("tide_or_time", ("tide", "tides", "high tide", "low tide", "what time", "sunrise", "sunset")),
    ("species", ("species", "which fish", "what fish", "catch", "prawn", "shrimp",
                 "tuna", "sardine", "mackerel", "seer fish")),
    ("unit_conversion", ("in feet", "in knots", "in miles", "in fahrenheit", "in ft", "in kt")),
]

# Anything that is not about the sea, the weather or going out on it.
_OFF_TOPIC = (
    "capital of", "who is", "what is the meaning", "translate", "joke",
    "president", "cricket", "movie", "recipe", "python", "write me",
)

_MARINE = (
    "sea", "ocean", "wave", "wind", "fish", "boat", "sail", "coast", "water",
    "weather", "rain", "storm", "safe", "go out", "current", "tide", "swell",
    "கடல்", "மீன்", "அலை", "காற்று", "படகு",
)


def _normalise(query: str) -> str:
    """Lowercase, NFC-normalised, punctuation softened to spaces.

    NFC matters for Tamil: the same word can arrive pre-composed or as a
    base plus combining marks, and those are different byte sequences that
    must not match differently.
    """
    text = unicodedata.normalize("NFC", query or "").lower()
    # Apostrophes are DELETED, not turned into spaces. Replacing them
    # split "what's" into "what s", which matched no lookup phrase and
    # silently downgraded every contraction-phrased question to a
    # verdict -- caught by test_data_lookup_resolves_the_real_observation.
    # Covers the straight quote and both curly ones, since a phone
    # keyboard produces U+2019 rather than U+0027.
    text = re.sub(r"[\u0027\u2018\u2019\u02bc]", "", text)
    return re.sub(r"[^\w\s஀-௿]+", " ", text)


def _first_match(text: str, table: list[tuple[str, tuple[str, ...]]]) -> str | None:
    for value, terms in table:
        for term in terms:
            if term in text:
                return value
    return None


def _mentions_two_zones(text: str, zones: list[dict]) -> bool:
    named = {z["name"].lower() for z in zones if z["name"].lower() in text}
    return len(named) >= 2


def extract(query: str, zones: list[dict]) -> dict:
    """Same shape orca/agentic.extract_query_intent() returns, computed
    without a model. Every value is drawn from the same closed sets, so
    this result is interchangeable with the model's.

    Conversation memory is deliberately NOT a parameter. orca/agentic.py
    resolves a remembered zone itself (tier 3), after either extractor
    has run, so threading it here would create a second place that
    decides what "it" refers to.

    `zone_name` is left None: orca/agentic.py already does the literal
    substring match itself (tier 1) before either extractor is consulted,
    and guessing a zone from anything weaker than its own name is the one
    job here genuinely worth a model.
    """
    zones = zones or []
    text = _normalise(query)

    variable = _first_match(text, _VARIABLE_TERMS)
    asks_for_a_number = any(p in text for p in _LOOKUP_PHRASES)
    asks_for_a_decision = any(p in text for p in _VERDICT_PHRASES)

    # A question naming a variable is a data_lookup unless it also asks
    # for a judgement.
    #
    # Requiring an explicit interrogative was too strict: real questions
    # are terse. "waves tomorrow at Nagapattinam?" and "chlorophyll?"
    # name a variable, ask for nothing else, and are plainly requests for
    # a number -- but they carry no "how high" or "what is", so they were
    # being answered with a safety verdict instead.
    #
    # The asymmetry is deliberate. A verdict phrase WINS over a bare
    # variable, so "is it safe with these waves?" stays a verdict. Read
    # the wrong way round, a safety question would be answered with a
    # bare measurement -- and of the two mistakes, that is the one that
    # matters.
    if variable and (asks_for_a_number or not asks_for_a_decision):
        intent = "data_lookup"
    else:
        intent = "verdict"

    if any(p in text for p in _BEYOND):
        time_frame = "beyond"
    elif any(p in text for p in _TOMORROW):
        time_frame = "tomorrow"
    else:
        time_frame = memory.DEFAULT_TIME_FRAME

    comparison = any(p in text for p in _COMPARISON) or _mentions_two_zones(text, zones)
    scope = "all_zones" if comparison else "one_zone"

    unsupported = _first_match(text, _UNSUPPORTED_TERMS) or "none"
    if unsupported == "none" and _mentions_two_zones(text, zones):
        unsupported = "second_zone"

    on_topic = not any(p in text for p in _OFF_TOPIC) or any(p in text for p in _MARINE)

    return {
        "language": detect_language(query),
        "intent": intent,
        "variable": variable if intent == "data_lookup" else None,
        "time_frame": time_frame,
        "zone_name": None,
        "on_topic": on_topic,
        "scope": scope,
        "unsupported": unsupported,
    }
