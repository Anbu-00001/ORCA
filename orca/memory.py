"""Conversation memory for ORCA's chatbot layer.

The single design rule here, and the reason this is its own module rather
than a dict passed around inside orca/agentic.py:

    NOTHING THE USER TYPED IS EVER STORED OR REPLAYED. EVER.

A turn is reduced, on the way in, to at most three values drawn from
fixed, closed sets that this project already defines elsewhere -- a real
zone name from data/fetch.py's ZONES, a real variable name from the
observations orca/schema.py validates, and one of two time frames. Free
text has no channel through this module. That is what makes the two
documented failure modes of multi-turn chat structurally impossible here
rather than merely unlikely:

1. Hallucination compounding. The naive pattern -- concatenate the whole
   raw transcript into every subsequent prompt -- is exactly what causes
   a model to drift, contradict itself, and reinforce its own earlier
   mistakes over a session (research summarized in SCRATCH.md). If turn 3
   said something wrong, the wrong text is right there shaping turn 4.
   Here, a bad turn can only ever leave behind a zone name that really
   exists, a variable that really exists, and "now" or "tomorrow" -- so
   there is no wrong *text* to carry forward, and every answer is
   re-derived from live cached observations, never from an earlier answer.
2. Prompt injection through history. Because the injected instruction
   would have to survive being reduced to one of those enum values, and
   the enums are re-validated against the real sets on every single
   ingest (see sanitize()), there is no path for "ignore your
   instructions..." to reach the model as history. This is architectural
   prevention, which the literature is clear is the only defense that
   actually holds -- delimiters and role markers demonstrably do not.

The other half of the guarantee lives in orca/agentic.py: history is
given ONLY to the extraction step (so "what about tomorrow" can resolve
its missing subject), and NEVER to the composition step. Composition sees
one thing: the decision that was just computed, this request, from real
cached data. It therefore cannot repeat or compound an earlier answer,
because it has never seen one.
"""
from __future__ import annotations

from dataclasses import dataclass

# The two closed sets a turn may reference, plus the time frames. Kept
# here (not inlined at the call site) so sanitize() and orca/agentic.py's
# extraction schema provably agree on what "a real value" means.
TIME_FRAMES = ("now", "tomorrow")
DEFAULT_TIME_FRAME = "now"

# Real MarineObservation.variable values, confirmed present in the actual
# cache (data/cache/) before being listed here, not guessed from the
# fetcher source. chlorophyll_mg_m3 is deliberately included even though
# it has no tomorrow-forecast equivalent (it is satellite observation,
# not a forecast) -- orca/agentic.py answers "no data for that" in that
# case rather than silently substituting today's value.
LOOKUP_VARIABLES = (
    "wave_height_m",
    "wave_period_s",
    "wave_direction_deg",
    "sst_c",
    "wind_speed_kmh",
    "wind_gusts_kmh",
    "precipitation_mm",
    "rain_mm",
    "ocean_current_velocity_kmh",
    "ocean_current_direction_deg",
    "chlorophyll_mg_m3",
)

# How many prior turns are kept. Small on purpose: this exists to resolve
# "what about tomorrow" / "and Karaikal?" against the immediately
# preceding subject, not to be a transcript. Every extra turn is more
# context for the model to misweigh for no added ability.
MAX_TURNS = 3


@dataclass(frozen=True)
class ConversationTurn:
    """One prior turn, reduced to validated facts. Frozen: once sanitized,
    a turn cannot be mutated into carrying something unvalidated."""

    zone_name: str | None
    variable: str | None
    time_frame: str

    def to_dict(self) -> dict:
        return {"zone_name": self.zone_name, "variable": self.variable, "time_frame": self.time_frame}


def sanitize(raw_history, zones: list[dict]) -> list[ConversationTurn]:
    """Reduce whatever the client sent to at most MAX_TURNS validated
    turns. Anything unrecognized becomes None rather than an error: a
    malformed or hostile history must degrade to "no memory", never to a
    rejected request or an exception on the /ask path.

    This is the only way a ConversationTurn is ever constructed from
    outside input.
    """
    if not isinstance(raw_history, list):
        return []

    real_zone_names = {z["name"] for z in zones}
    turns: list[ConversationTurn] = []

    # Only the most recent MAX_TURNS are considered at all.
    for item in raw_history[-MAX_TURNS:]:
        if not isinstance(item, dict):
            continue

        zone_name = item.get("zone_name")
        if zone_name not in real_zone_names:  # covers None, wrong type, invented places
            zone_name = None

        variable = item.get("variable")
        if variable not in LOOKUP_VARIABLES:
            variable = None

        time_frame = item.get("time_frame")
        if time_frame not in TIME_FRAMES:
            time_frame = DEFAULT_TIME_FRAME

        # A turn carrying nothing usable is not worth a slot in the
        # model's context.
        if zone_name is None and variable is None:
            continue

        turns.append(ConversationTurn(zone_name=zone_name, variable=variable, time_frame=time_frame))

    return turns


def last_zone(turns: list[ConversationTurn]) -> str | None:
    """The most recent real zone the conversation was about, if any.
    Used to resolve a follow-up that names no place at all."""
    for turn in reversed(turns):
        if turn.zone_name is not None:
            return turn.zone_name
    return None


def to_prompt_facts(turns: list[ConversationTurn]) -> list[dict]:
    """The exact, minimal structure handed to the extraction model. Plain
    dicts of validated enum values -- reading this function is the whole
    audit of what history can possibly reach an LLM."""
    return [t.to_dict() for t in turns]
