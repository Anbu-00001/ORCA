"""ORCA's chatbot layer: LLM-assisted zone resolution and localized,
grounded phrasing, wrapped strictly around the unchanged deterministic
safety core in orca/planner.py and orca/policy.py.

Design (researched, not guessed -- see SCRATCH.md's "chatbot layer
research" entry for sources): a fixed-code-path *workflow*, not an
open-ended autonomous agent (Anthropic's own "Building effective agents"
guidance is explicit that a bounded, well-specified task like this one
calls for a workflow you control, not a model that picks its own next
step -- matches this project's "boring beats clever" rule directly).

Three safety properties this module exists to guarantee, not just aim
for:

1. orca/policy.py is never imported here and never will be (CLAUDE.md
   rule 4). This module only ever sees an already-decided Recommendation;
   it can change zone *selection* (from a fixed, real enum -- never a
   free-text place the model invents) and *phrasing* -- never
   risk_level, hard_deny, action, or any number.
2. A cheap, zero-risk deterministic substring match always wins over an
   LLM guess when it finds one (see answer_question() below) -- the LLM
   is consulted only for what plain substring matching genuinely cannot
   do, not as a first resort.
3. Every call here fails closed. Any problem at all (no key, network
   error, timeout, malformed response, a schema violation) raises
   AgenticUnavailable, which is caught immediately by the caller in this
   file and turns into the exact plain build_recommendation() output --
   never a 500, never a hang, never a fabricated answer standing in for
   a real one (CLAUDE.md rule 1's spirit applied to this new surface: an
   unavailable enhancement is correct behaviour, a guessed one is not).

CLAUDE.md rule 8 exception: this is the second and ONLY other file in
the whole project allowed to touch the network, alongside data/fetch.py
-- by explicit user sign-off (see CLAUDE.md's rule 8 note and
TEAM_STATUS.md). /ask keeps working with zero network access exactly as
before; this module can only ever add to that, never replace it.
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import time
from collections import OrderedDict

import requests

from data.fetch import ZONES
from orca import memory
from orca.planner import (
    Recommendation,
    _zone_by_substring,
    build_recommendation,
    observation_id,
    observations_for_zone,
)

logger = logging.getLogger("orca.agentic")

GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
# Both models are Groq-hosted, strict-JSON-schema-capable (verified against
# Groq's docs before use, not assumed -- see SCRATCH.md). Same family,
# split by task: extraction needs precision under a small, fixed schema;
# composition gets the bigger model because prose quality (especially in
# Tamil) is the part actually worth spending tokens on.
EXTRACTION_MODEL = "openai/gpt-oss-20b"
COMPOSITION_MODEL = "openai/gpt-oss-120b"
REQUEST_TIMEOUT_S = 8.0  # fail fast into the deterministic fallback -- never hang a live demo

# One wall-clock budget for the ENTIRE layer (R-49). REQUEST_TIMEOUT_S is
# per call, and answer_question() makes two sequential calls, so the
# per-call timeout on its own bounds nothing: 8 s of extraction followed
# by a fresh 8 s of composition adds ~16 s to a single request. That worst
# case is precisely the stage condition -- a key present, the network
# unreachable, wifi off, a judge watching.
#
# Enforced two ways, because a check alone is not a bound: composition is
# SKIPPED when the budget is already spent, and when it does run its
# timeout is CLAMPED to whatever is left. A check without the clamp still
# permits 8 s + 8 s, since extraction can only ever finish just inside the
# budget and would then hand a full fresh timeout to composition.
#
# The bound this buys: the agentic layer adds at most LAYER_BUDGET_S of
# network wait to one /ask, regardless of how many calls it makes.
LAYER_BUDGET_S = 10.0
# Below this much remaining, a call cannot realistically complete -- so
# starting one only spends the rest of the budget in order to fail anyway.
MIN_CALL_BUDGET_S = 0.5

# --- Groq rate-limit cool-down (the thing that broke the live demo) -----
#
# Groq's free tier allows 30 requests/min but only 8,000 TOKENS/min, per
# model per ORGANISATION -- and tokens are what binds. Measured on this
# prompt set: extraction sends ~978 tokens and composition ~718, so the
# ceiling is ~8 questions/minute before a 429, lower once completions are
# counted. A presenter asking a question every ten seconds while talking
# is already at that edge; a teammate testing on the same key pushes it
# over. What the audience then sees is not an error -- the fallback is
# correct and silent -- but every answer collapsing to the same
# deterministic sentence, which reads as a broken chatbot.
#
# Two things made it worse than it needed to be:
#   * a 429 was retried on the NEXT question, and the next, each one
#     spending a request to be told "no" again;
#   * extraction failing did not stop composition from also being tried,
#     so a rate-limited question burned TWO 429s, not one.
#
# So: remember the cool-down Groq itself asks for (Retry-After, else a
# conservative default) and skip calls to that model until it expires.
# Skipping is instant, which also removes the network wait from the
# fallback path -- the answer arrives immediately instead of after a
# timeout. Per model, because the quotas are per model.
RATE_LIMIT_STATUS = 429
DEFAULT_COOLDOWN_S = 60.0
MAX_COOLDOWN_S = 300.0
_cooldown_until: dict[str, float] = {}


def _cooldown_remaining(model: str) -> float:
    """Seconds left before `model` may be called again. 0.0 when ready."""
    return max(0.0, _cooldown_until.get(model, 0.0) - time.monotonic())


def _begin_cooldown(model: str, retry_after: str | None) -> float:
    """Record the cool-down Groq asked for. Returns its length in seconds."""
    seconds = DEFAULT_COOLDOWN_S
    if retry_after:
        try:
            seconds = float(retry_after)
        except ValueError:
            pass  # a date-form Retry-After -- the default is close enough
    seconds = min(max(seconds, 1.0), MAX_COOLDOWN_S)
    _cooldown_until[model] = time.monotonic() + seconds
    return seconds


def reset_rate_limit_state() -> None:
    """Clear all cool-downs. For tests, and for an operator who has just
    switched to a key with its own quota."""
    _cooldown_until.clear()


# --- Response cache -----------------------------------------------------
#
# The same question asked twice sends Groq a byte-identical payload, and
# a byte-identical payload has a byte-identical answer. Remembering it
# costs nothing and removes the single biggest source of demo rate-limit
# pressure: a presenter re-asking the question they just showed, a judge
# repeating it on their own laptop, a rehearsal run.
#
# Keyed on the WHOLE payload, which is what makes this safe rather than
# merely convenient. The composition payload carries the actual readings
# it is asked to phrase, so the moment data/fetch.py writes a new number
# the payload differs and the cache misses. There is no way for it to
# serve a sentence about a sea state that is no longer in the cache.
#
# It never caches a verdict. GO / DO NOT GO / SAFER ALTERNATIVE /
# CANNOT ASSESS are recomputed by orca/policy.py from live observations
# on every single request, cached or not (CLAUDE.md rule 4). What is
# remembered here is only the model's phrasing and its parse of the
# question.
RESPONSE_CACHE_MAX = 256
_response_cache: "OrderedDict[str, dict]" = OrderedDict()


def _cache_key(payload: dict) -> str:
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()


def reset_response_cache() -> None:
    """For tests, and after a cache refresh if an operator wants the
    model re-consulted even on payloads it has already seen."""
    _response_cache.clear()


# The closed sets extraction may return. Defined once and used BOTH in the
# JSON schema sent to the model and in the re-validation of its reply --
# writing them twice meant a value could be added to the schema, returned
# happily by the model, and then silently normalized away by a
# re-validation that had not been updated with it. orca/memory.py already
# does it this way for TIME_FRAMES/LOOKUP_VARIABLES; this matches it.
LANGUAGES = ("en", "ta", "other")
DEFAULT_LANGUAGE = "en"
INTENTS = ("verdict", "data_lookup")

# The escape hatches. Everything above describes a question ORCA can
# answer; without a way to say "this question isn't one of those", the
# schema silently coerces every unanswerable question into the nearest
# answerable one -- and the answer comes back sounding just as certain.
# Measured on 2026-08-27: "which zone has the worst waves today?" was
# answered "Nagapattinam has the worst waves today" while Nagapattinam
# was the second CALMEST of the ten zones (0.36 m, against Kanyakumari's
# 1.42 m). Nothing was broken; the schema simply had no way to represent
# the question, so it became a single-zone question about the fallback
# zone. Naming each gap is what lets answer_question() either fill it
# with real data (all_zones -> a computed ranking) or say plainly that
# ORCA cannot (everything else).
EXTRACTION_TIME_FRAMES = ("now", "tomorrow", "beyond")
SCOPES = ("one_zone", "all_zones")
DEFAULT_SCOPE = "one_zone"
UNSUPPORTED_KINDS = (
    "none", "unit_conversion", "second_zone", "species", "tide_or_time", "route",
)
# What each gap costs the user, in their words. Stated up front by the
# composer rather than left for them to discover by trusting a wrong answer.
_UNSUPPORTED_NOTES = {
    "unit_conversion": (
        "ORCA reports each reading in the unit its source publishes, so this "
        "is in metres and km/h rather than the unit you asked for."
    ),
    # {zone} is substituted with the real zone name. It used to read
    # "...so this covers only the first one", and that phrase was the
    # trigger for a live fabrication: asked "Is Kanyakumari safer than
    # Rameswaram?", the composer answered "Kanyakumari appears later in
    # the list than Rameswaram, so it's considered safer." There is no
    # list anywhere in its context -- it confabulated one from the words
    # "the first one", then drew a SAFETY CONCLUSION from the invented
    # ordering. Naming the zone removes the ordinal, and so the invitation.
    "second_zone": (
        "You asked about more than one place. ORCA answers one place at a "
        "time, so this covers {zone} only."
    ),
    "species": "ORCA has no fish-species or catch data, only sea and weather conditions.",
    "tide_or_time": "ORCA has no tide tables or timings.",
    "route": "ORCA has no route or navigation planning.",
}
_BEYOND_NOTE = (
    "ORCA only holds readings for today and tomorrow, so this answers for "
    "today rather than the day you asked about."
)
DEFAULT_INTENT = "verdict"


class AgenticUnavailable(Exception):
    """Raised for any reason the agentic layer can't be used this request.
    Callers must catch this specifically (never a bare except -- CLAUDE.md
    rule 2) and fall back to the deterministic path."""


def is_configured() -> bool:
    return bool(os.environ.get("GROQ_API_KEY"))


def _post(payload: dict, timeout: float | None = None) -> dict:
    """The only function in this file that makes a network call. Returns
    the parsed JSON object the model produced (already schema-validated
    server-side by Groq's strict mode); raises AgenticUnavailable for
    every failure mode instead of letting any of them propagate as a
    generic exception the caller might mishandle.

    `timeout` defaults to the per-call REQUEST_TIMEOUT_S. answer_question()
    passes a smaller value when less of the layer's LAYER_BUDGET_S remains,
    which is what turns the budget into an actual bound (R-49)."""
    api_key = os.environ.get("GROQ_API_KEY")
    if not api_key:
        raise AgenticUnavailable("GROQ_API_KEY not set")

    key = _cache_key(payload)
    hit = _response_cache.get(key)
    if hit is not None:
        _response_cache.move_to_end(key)
        logger.info("Groq response cache hit for %s", payload.get("model", "?"))
        return hit

    # Still inside a cool-down this model asked for: do not spend a
    # request to be told "no" again. Failing here is the same
    # AgenticUnavailable the caller already handles, just instantly.
    model = payload.get("model", "?")
    remaining = _cooldown_remaining(model)
    if remaining > 0:
        raise AgenticUnavailable(
            f"Groq rate limit: {model} is in cool-down for another {remaining:.0f}s"
        )

    try:
        resp = requests.post(
            GROQ_API_URL,
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            json=payload,
            timeout=REQUEST_TIMEOUT_S if timeout is None else timeout,
        )
        if resp.status_code == RATE_LIMIT_STATUS:
            held = _begin_cooldown(model, resp.headers.get("Retry-After"))
            logger.warning(
                "Groq rate limit (429) on %s -- holding off for %.0fs. Free tier is "
                "8,000 tokens/min per model; ORCA sends ~1,700 per question across two "
                "models. Answers stay correct but become deterministic-only until then.",
                model, held,
            )
            raise AgenticUnavailable(f"Groq rate limited: {model} (cooling down {held:.0f}s)")
        resp.raise_for_status()
    except requests.RequestException as exc:
        raise AgenticUnavailable(f"Groq request failed: {exc}") from exc
    try:
        body = resp.json()
        content = body["choices"][0]["message"]["content"]
        parsed = json.loads(content)
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
        raise AgenticUnavailable(f"Groq response malformed: {exc}") from exc

    _response_cache[key] = parsed
    _response_cache.move_to_end(key)
    while len(_response_cache) > RESPONSE_CACHE_MAX:
        _response_cache.popitem(last=False)
    return parsed


def extract_query_intent(
    query: str, zones: list[dict] | None = None, history: list | None = None
) -> dict:
    """Turn one free-text question into validated, structured facts, under
    a strict schema whose every field is a closed set -- so the model can
    only ever select from things that really exist, never invent a place,
    a variable, or a capability.

    `history` is a list of orca.memory.ConversationTurn (already
    sanitized by the caller -- see that module's docstring). It exists
    solely so a follow-up that omits its subject ("what about tomorrow?")
    can be resolved against what was actually being discussed. It carries
    no user text, only validated enum values.

    Every returned field is re-validated here against the real sets
    before being returned, regardless of what strict mode claims.
    """
    zones = zones or ZONES
    names = [z["name"] for z in zones]
    variables = list(memory.LOOKUP_VARIABLES)
    schema = {
        "type": "object",
        "properties": {
            "zone_name": {"type": ["string", "null"], "enum": names + [None]},
            "language": {"type": "string", "enum": list(LANGUAGES)},
            "intent": {"type": "string", "enum": list(INTENTS)},
            "variable": {"type": ["string", "null"], "enum": variables + [None]},
            "time_frame": {"type": "string", "enum": list(EXTRACTION_TIME_FRAMES)},
            "on_topic": {"type": "boolean"},
            "scope": {"type": "string", "enum": list(SCOPES)},
            "unsupported": {"type": "string", "enum": list(UNSUPPORTED_KINDS)},
        },
        "required": [
            "zone_name", "language", "intent", "variable", "time_frame",
            "on_topic", "scope", "unsupported",
        ],
        "additionalProperties": False,
    }
    system = (
        "You extract structured facts from a fisherman's question for a "
        "marine safety tool covering the Tamil Nadu coast. You do not "
        "answer the question and you make no safety decision.\n"
        f"- zone_name MUST be exactly one of {names} if the query clearly "
        "refers to one of those real places (by name, a well-known "
        "landmark there, or a common alternate/local name for it), else "
        "null -- never a place outside this list.\n"
        "- language: 'ta' for Tamil (including Tamil written in Latin "
        "script), 'en' for English, 'other' otherwise.\n"
        "- intent: 'data_lookup' if they are asking for one specific "
        "measurement (e.g. 'what is the wave height', 'how windy is it'); "
        "'verdict' if they are asking whether to go out, or anything "
        "broader. When unsure, choose 'verdict'.\n"
        f"- variable: exactly one of {variables} -- the measurement being "
        "asked for. Set it whenever the question names a measurement, "
        "INCLUDING comparisons ('which place has the worst waves' is "
        "wave_height_m). null only for a general go/don't-go question, or "
        "if no listed variable matches what they asked for.\n"
        "- time_frame: 'tomorrow' if they ask about tomorrow or the next "
        "day; 'beyond' if they ask about any day further out than that "
        "(day after tomorrow, this weekend, next week); otherwise 'now'.\n"
        "- scope: 'all_zones' ONLY when answering requires ranking or "
        "comparing places -- 'which place has the worst waves', 'where is "
        "safest', 'is it rougher at X than Y'. A question that simply "
        "names no place is NOT all_zones: 'is it safe out there right "
        "now?' is one_zone, asked about wherever the person already is. "
        "Use 'one_zone' unless the question is genuinely a comparison.\n"
        "- unsupported: name the ONE thing they asked for that this tool "
        "cannot give, if any. 'unit_conversion' if they asked for a "
        "reading in a specific unit (feet, knots, miles); 'second_zone' "
        "if they asked about two or more different places at once; "
        "'species' for fish types or catch; 'tide_or_time' for tides, "
        "high/low water or timings; 'route' for directions or navigation. "
        "'none' if the question needs none of those.\n"
        "- on_topic: false ONLY if the question has nothing to do with the "
        "sea, weather, fishing, or going out on the water. A question "
        "that is about those things is on_topic even if this tool cannot "
        "answer it."
    )
    messages = [{"role": "system", "content": system}]
    if history:
        # Structured facts only -- see orca/memory.py. This is what lets
        # "what about tomorrow?" find its missing subject without ever
        # replaying a previous answer or a previous user message.
        messages.append(
            {
                "role": "system",
                "content": (
                    "Earlier turns in this conversation, as validated facts "
                    "(not text). Use them ONLY to fill in what the current "
                    "question leaves out, e.g. an unstated place: "
                    f"{json.dumps(memory.to_prompt_facts(history))}"
                ),
            }
        )
    messages.append({"role": "user", "content": query})

    payload = {
        "model": EXTRACTION_MODEL,
        "messages": messages,
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "query_intent", "strict": True, "schema": schema},
        },
        "temperature": 0,
    }
    result = _post(payload)

    # Re-validate everything. Strict mode is supposed to make each of
    # these impossible -- but a network response never gets to skip
    # validation just because it claims to already be validated
    # (CLAUDE.md rule 1's spirit: an absent/rejected reading is correct,
    # a fabricated one is not).
    zone_name = result.get("zone_name")
    variable = result.get("variable")
    intent = result.get("intent")
    time_frame = result.get("time_frame")
    language = result.get("language")
    on_topic = result.get("on_topic")
    scope = result.get("scope")
    unsupported = result.get("unsupported")

    return {
        "zone_name": zone_name if zone_name in names else None,
        "language": language if language in LANGUAGES else DEFAULT_LANGUAGE,
        "intent": intent if intent in INTENTS else DEFAULT_INTENT,
        "variable": variable if variable in memory.LOOKUP_VARIABLES else None,
        "time_frame": (
            time_frame if time_frame in EXTRACTION_TIME_FRAMES else memory.DEFAULT_TIME_FRAME
        ),
        "scope": scope if scope in SCOPES else DEFAULT_SCOPE,
        "unsupported": unsupported if unsupported in UNSUPPORTED_KINDS else "none",
        # Default to on-topic when absent/malformed: wrongly refusing a
        # real fisherman's real question is worse than answering a stray
        # one (the abstention literature's "over-abstention" failure --
        # see SCRATCH.md).
        "on_topic": on_topic if isinstance(on_topic, bool) else True,
    }


# The fourth action Dev D is adding (R-39). Named here so this module's
# composer branch is correct the moment the planner can produce it, rather
# than discovering afterwards that an unassessable zone took the "tell them
# plainly what to do" branch -- which is the §1.3 confident gap, and was a
# real fail-open found by the R-25 consumer sweep.
CANNOT_ASSESS = "CANNOT ASSESS"

# Agent -> the reading it needs, in the words a fisherman would use.
# Presentation only, which is squarely the shell's job; WHICH agents were
# blind is read off the findings the planner already computed, never
# re-derived here.
_AGENT_READING_NAMES = {
    "eo_satellite_agent": "the satellite fish-finding pass (chlorophyll)",
    "ocean_state_agent": "sea temperature",
    "weather_agent": "wind and rain",
    "hazard_agent": "wave height",
    "geofence_agent": "the position check",
}


def _blind_agent_readings(recommendation: dict) -> list[str]:
    """Which readings ORCA did not have, from the findings themselves.

    An agent that cited no observation ids had nothing to look at. Reading
    it off `observation_ids` rather than re-deriving which variables ought
    to exist keeps the list honest even when the agents change: this cannot
    name a reading the planner did not actually find missing.
    """
    names: list[str] = []
    for finding in recommendation.get("agent_findings") or []:
        if not finding.get("observation_ids"):
            label = _AGENT_READING_NAMES.get(finding.get("agent"))
            if label and label not in names:
                names.append(label)
    return names


def _composition_context(recommendation: dict) -> dict:
    """The minimal slice of a decision the composer actually needs to
    phrase an answer.

    Handing over the whole recommendation dict was ~3,200 tokens per
    request -- all 10 zone_summaries, all 5 agent_findings, and every
    evidence item's full provenance URL and coordinates -- which blew
    past Groq's free-tier 8,000 tokens-per-minute budget after about two
    questions and made composition fail (observed live: 429s, see
    SCRATCH.md). None of that bulk was reachable in the output anyway:
    the composer states the verdict, its reason, and at most a couple of
    real readings.

    Trimming it is also a grounding win, not just a cost one -- there is
    less irrelevant material for the model to pick a stray number out of.
    Evidence ids are kept verbatim, because citation validation in the
    caller depends on them matching the real ones exactly.
    """
    return {
        "action": recommendation.get("action"),
        "reason": recommendation.get("reason"),
        "chosen_zone": (recommendation.get("chosen_zone") or {}).get("name"),
        # Which day these readings are FOR. Without it the composer sees
        # unlabelled numbers and, asked "what about tomorrow?", hedges --
        # observed live inventing "we don't have tomorrow's readings yet"
        # while holding correct forecast figures (see SCRATCH.md). Cheap
        # to include and it removes the reason to guess.
        "readings_are_for": recommendation.get("time_frame", "now"),
        "evidence": [
            {
                "id": e["id"],
                "variable": e["variable"],
                "value": e["value"],
                "unit": e["unit"],
            }
            for e in recommendation.get("evidence", [])
        ],
    }


def compose_grounded_answer(
    query: str,
    recommendation: dict,
    language: str,
    lookup: dict | None = None,
    coverage_note: str | None = None,
    off_topic: bool = False,
    ranking: list[dict] | None = None,
    timeout: float | None = None,
) -> dict:
    """Rephrase an already-decided Recommendation for a human, in their
    language. The schema has no field for action/risk/numbers -- only
    text and which of the *real* evidence ids it drew on -- so there is
    no field through which the model could alter the decision even if it
    tried.

    Deliberately receives NO conversation history: this step sees only
    the decision computed from real cached data on THIS request, so it
    cannot repeat, reinforce, or compound anything an earlier answer got
    wrong (see orca/memory.py's docstring for why that matters).

    `lookup` is a real, resolved observation for a data_lookup question
    (or a marker that ORCA has no such reading). `coverage_note` and
    `off_topic` carry the honesty caveats -- see answer_question().
    """
    evidence_ids = [e["id"] for e in recommendation.get("evidence", [])]
    schema = {
        "type": "object",
        "properties": {
            "answer_text": {"type": "string"},
            "cited_evidence_ids": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["answer_text", "cited_evidence_ids"],
        "additionalProperties": False,
    }

    if off_topic:
        system = (
            "A user asked a marine-safety assistant for the Tamil Nadu "
            "coast something outside what it does. Reply in language code "
            f"'{language}', in one short, friendly sentence: say you only "
            "help with sea conditions and whether it is safe to go "
            "fishing, and invite them to ask about that. Do not attempt "
            "to answer their actual question, and do not mention any sea "
            "conditions or numbers. cited_evidence_ids must be []."
        )
    else:
        parts = [
            "You explain an already-made fishing-safety decision to a "
            "fisherman, the way an experienced local would explain it out "
            "loud -- not a report, not a restatement of field names. You did "
            "NOT make the decision and MUST NOT change it: every number, "
            "place, and fact you use must come EXACTLY from the JSON below "
            "-- never compute, round, or invent one. Do not say the words "
            "'the decision is' or 'the reason is' or otherwise narrate the "
            "JSON's own structure.",
        ]

        if lookup is not None and lookup.get("missing"):
            parts.append(
                "They asked for a specific measurement ("
                f"{lookup.get('variable')}) that ORCA does NOT have for what "
                "they asked. Say plainly that you don't have that reading, "
                "and do not substitute a different one or estimate it. You "
                "may still tell them the overall verdict below."
            )
        elif lookup is not None:
            # Spelled out as a sentence rather than handed over as JSON:
            # given the raw object and told to reproduce it exactly, the
            # model copies the object into the answer verbatim, braces and
            # all (observed live -- see SCRATCH.md). The number and unit
            # still have to match exactly; only the framing changed.
            parts.append(
                "They asked for one specific measurement. Its real value is "
                f"{lookup['value']} {lookup['unit']} ({lookup['variable']}). "
                "Open your answer by telling them that value in plain words, "
                "using exactly that number and unit. Never output JSON, "
                "braces, quotes, or field names -- write it the way a person "
                "would say it out loud."
            )

        # The safety floor: a narrower question must never be allowed to
        # bury a hard denial. Stated as an explicit instruction rather
        # than hoped for.
        action = recommendation.get("action")
        if action == CANNOT_ASSESS:
            # "I do not know" is a defensible answer; "here is what to do"
            # from zero readings is not. Advising either way here would be
            # the confident gap the verdict exists to close.
            missing = _blind_agent_readings(recommendation)
            said = (
                "CRITICAL: ORCA has NO readings for this place, so it does "
                "not know whether it is safe. Say that plainly and first. "
                "Do NOT tell them to go, and do NOT tell them not to go -- "
                "you have no basis for either, and there is no number here "
                "to reason from. Never soften this into a recommendation."
            )
            if missing:
                said += (
                    " Name what is missing, in these words: "
                    + "; ".join(missing)
                    + "."
                )
            said += (
                " If a zone is named below as somewhere ORCA CAN speak for, "
                "offer it as an alternative -- not knowing about one place "
                "is not the same as being unable to help."
            )
            parts.append(said)
        elif action == "DO NOT GO":
            parts.append(
                "CRITICAL: the verdict is DO NOT GO. Whatever else they "
                "asked, you MUST also tell them clearly not to go out, and "
                "why. Never answer only the narrow question when the "
                "verdict is DO NOT GO."
            )
        else:
            parts.append(
                "Then tell them plainly what to do and the one or two "
                "things that matter about why."
            )

        # Either it has the true ordering, or it is told in as many words
        # that it does not have one. There is no third state in which
        # guessing is the reasonable thing to do.
        if ranking is not None:
            parts.append(
                "They asked a question that compares places. Here is the "
                "real ordering across all zones, computed from actual "
                "readings, worst/highest first: "
                f"{json.dumps(ranking)}. The list runs WORST/highest "
                "first, so the safest, calmest or best place is the LAST "
                "entry in it. Answer the comparison from THIS list only, "
                "naming places and values exactly as they appear in it. Do "
                "NOT answer with the zone named in the Decision block "
                "below unless the list itself puts it there -- that zone is "
                "just where the question was anchored, not the answer to a "
                "comparison. "
                # The ordering is now real, so conclusions drawn from it
                # are sound -- but SAYING "X appears later in the ranking"
                # is both poor advice and indistinguishable, to a reader,
                # from the fabrication this replaced. State the finding,
                # not the mechanism, exactly as the opening rule already
                # requires for the JSON's other fields.
                "USE the order to work out the answer -- of two places, "
                "the one nearer the END of the list is the calmer and "
                "safer of them, and you should say so plainly. But never "
                "DESCRIBE the list: do not say 'appears later', 'is "
                "listed after', 'in the ranking', or mention positions, "
                "ordering or the list at all. State what is true of the "
                "places the way a fisherman would say it, and where the "
                "list gives a value with a unit, use that value. Two "
                "places sharing the same verdict word are still not "
                "equally safe if the list orders them differently."
            )
        else:
            parts.append(
                "You have readings for ONE place only. Never say or imply "
                "that a place is the worst, best, safest, calmest or "
                "roughest compared with anywhere else, and never rank or "
                "order places -- you have not been shown any other place's "
                "readings. If they asked for a comparison, say you can "
                "only speak for this one place. "
                # Added after a live fabrication: the model invented a
                # "list" that appears nowhere in its context and reasoned
                # from position in it to a safety conclusion. Ordering of
                # any kind is not evidence, and saying so explicitly is
                # cheaper than hoping the general rule covers it.
                "There is no list, ranking or ordering of places anywhere "
                "in what you have been given. Do not refer to one, and "
                "never infer that a place is safer, rougher or better from "
                "the order in which anything is mentioned. Position is not "
                "a measurement."
            )

        if coverage_note:
            parts.append(
                "Before anything else, tell them this caveat, in your own "
                "words and addressed to them directly -- do not copy it "
                "back verbatim, and keep the pronouns pointing at the "
                f"person asking: {coverage_note}"
            )

        parts.append(
            "Never say ORCA lacks, is missing, or does not have a reading "
            "unless you were told that explicitly above. If something was "
            "simply not included, say only what you do have and stop -- "
            "claiming a reading is unavailable when it was merely not "
            "shown to you is as wrong as inventing one."
        )
        if action != CANNOT_ASSESS:
            # Only true when there ARE readings. Telling the composer never
            # to claim ORCA lacks data, on the one verdict that means
            # exactly that, would put the two instructions in direct
            # conflict and let the model pick.
            parts.append(
                "The readings below are already for the day the user asked "
                "about (`readings_are_for`). Never claim ORCA lacks data for "
                "that day, and never hedge about what it has -- if a reading "
                "is present, it is real and current for that day."
            )
        parts.append(
            f"Reply in language code '{language}'. 1-3 short sentences, "
            "spoken plainly. cited_evidence_ids must be a subset of: "
            f"{json.dumps(evidence_ids)}. Decision: "
            f"{json.dumps(_composition_context(recommendation))}"
        )
        system = " ".join(parts)
    payload = {
        "model": COMPOSITION_MODEL,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": query},
        ],
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "grounded_answer", "strict": True, "schema": schema},
        },
        "temperature": 0.3,
    }
    result = _post(payload, timeout=timeout)
    # Same principle as extract_query_intent: verify the citations
    # ourselves rather than trust that strict mode + the prompt were
    # enough. Citation hallucination is a documented failure mode even
    # under schema constraints (SCRATCH.md cites arxiv 2606.00898) -- so
    # any id the model names that isn't in the real evidence list is
    # silently dropped, never shown as if it were real.
    result["cited_evidence_ids"] = [i for i in result.get("cited_evidence_ids", []) if i in evidence_ids]
    return result


def _rank_zones(variable: str | None, observations, zones: list[dict], recommendation) -> list[dict]:
    """The true cross-zone ordering, computed in plain Python from the same
    cached observations every other answer uses.

    A comparison question ("which place has the worst waves?") needs ten
    zones' readings. The composer is handed one zone's evidence, by
    design -- so when it was asked one of these anyway it did the only
    thing it could and guessed, in a confident declarative sentence.
    Measured 2026-08-27: it named Nagapattinam the worst when
    Nagapattinam was the second calmest of the ten. The fix is to hand it
    the real ordering, not to ask it more firmly not to guess.

    Ranked by the named variable when they named one, otherwise by the
    deterministic risk_level orca/policy.py already computed per zone --
    which is what "worst" means here, and is not the model's to decide.
    """
    if variable is not None:
        rows = []
        for zone in zones:
            for obs in observations_for_zone(observations, zone):
                if obs.variable == variable:
                    rows.append({"zone": zone["name"], "value": obs.value, "unit": obs.unit})
                    break
        rows.sort(key=lambda r: r["value"], reverse=True)
        return rows
    summaries = sorted(
        recommendation.to_dict().get("zone_summaries", []),
        key=lambda z: z["risk_level"],
        reverse=True,
    )
    # Ordering only, and the verdict word policy.py already assigned. The
    # raw risk_level float is deliberately dropped: it is a policy output,
    # not a MarineObservation, and CLAUDE.md rule 3 governs every number
    # that reaches a user. Handed the float, the composer put "risk_level
    # 0.95" straight into an answer (measured 2026-08-27).
    return [{"zone": z["name"], "action": z["action"]} for z in summaries]


def _resolve_lookup(
    variable: str | None,
    zone: dict | None,
    observations: list,
    forecast_observations: list,
    time_frame: str,
) -> dict | None:
    """Find the one real observation a data_lookup question asked for.

    Returns None when this isn't a data_lookup at all, a real reading
    (with its own provenance id) when ORCA has it, or a `missing` marker
    when it doesn't -- never a substituted or estimated value. Asking for
    tomorrow's chlorophyll, for instance, has no answer: chlorophyll is a
    satellite observation, not a forecast, so the honest response is "I
    don't have that", not today's figure quietly relabelled.
    """
    if variable is None or zone is None:
        return None

    pool = forecast_observations if time_frame == "tomorrow" else observations
    for obs in observations_for_zone(pool, zone):
        if obs.variable == variable:
            return {
                "variable": obs.variable,
                "value": obs.value,
                "unit": obs.unit,
                "valid_time": obs.valid_time.isoformat(),
                "source": obs.source,
                "confidence": obs.confidence,
                "id": observation_id(obs),
            }
    return {"variable": variable, "time_frame": time_frame, "missing": True}


def answer_question(
    query: str,
    lat: float,
    lon: float,
    observations,
    offline_mode: bool = False,
    zones: list[dict] | None = None,
    history: list | None = None,
    forecast_observations: list | None = None,
) -> Recommendation:
    """The single entry point orca/api.py's /ask handler calls instead of
    build_recommendation() directly.

    The deterministic core always runs. The agentic layer, when
    configured and reachable, can only ever change:
      - which real zone the question resolved to (from a closed set),
      - which of four answer shapes gets composed, and
      - the wording.
    It never changes action, risk_level, hard_deny, or any number. Any
    failure anywhere in the agentic parts falls back silently to the
    exact plain build_recommendation() result -- GROQ_API_KEY unset
    reproduces the pre-agentic behaviour byte-for-byte.

    `history` is raw client-supplied conversation memory; it is sanitized
    into validated enum facts immediately (orca/memory.py) and reaches
    ONLY the extraction step, never composition.
    """
    # R-49: one clock for the whole layer, stamped before the first call
    # that could wait on a network. monotonic() because this is a duration,
    # not a time of day -- a clock adjustment mid-request must not be able
    # to hand the layer more budget than it was given.
    started_at = time.monotonic()

    zones = zones or ZONES
    forecast_observations = forecast_observations if forecast_observations is not None else []
    turns = memory.sanitize(history, zones)

    detected_language = "en"
    intent = "verdict"
    variable = None
    time_frame = memory.DEFAULT_TIME_FRAME
    scope = DEFAULT_SCOPE
    unsupported = "none"
    on_topic = True
    agentic_used = False

    # Tier 1, always first, zero network, zero risk: did they literally
    # name a real zone? A deterministic hit is never second-guessed by
    # the model.
    resolved_zone = _zone_by_substring(query, zones)
    zone_match = "exact" if resolved_zone is not None else "fallback"

    if is_configured():
        try:
            extracted = extract_query_intent(query, zones, turns)
            detected_language = extracted["language"]
            intent = extracted["intent"]
            variable = extracted["variable"]
            time_frame = extracted["time_frame"]
            on_topic = extracted["on_topic"]
            scope = extracted["scope"]
            unsupported = extracted["unsupported"]
            # Tier 2: the model mapped a landmark/description onto a real
            # zone. Only consulted because tier 1 found nothing.
            if resolved_zone is None and extracted["zone_name"]:
                resolved_zone = next(z for z in zones if z["name"] == extracted["zone_name"])
                zone_match = "inferred"
            agentic_used = True
        except AgenticUnavailable as exc:
            # Falling back is correct; falling back QUIETLY is not -- see
            # the composition fallback below, whose comment states the
            # lesson this line was the last place in the module not to
            # follow. Behaviour is unchanged: every field keeps its
            # deterministic default. Only the silence is gone.
            logger.warning(
                "Agentic extraction unavailable, using deterministic defaults: %s", exc
            )

    # Tier 3: the question named no place at all, but the conversation
    # was already about one ("what about tomorrow?"). Comes from
    # validated memory, so it can only ever be a real zone.
    if resolved_zone is None:
        remembered = memory.last_zone(turns)
        if remembered is not None:
            resolved_zone = next(z for z in zones if z["name"] == remembered)
            zone_match = "remembered"

    # A question about tomorrow must be answered from tomorrow's data. The
    # deterministic core is identical either way -- same agents, same
    # policy.resolve(), same thresholds -- only the observations differ,
    # so a forecast verdict is a real verdict, not a weaker one. Running
    # it on today's readings and presenting that as an answer about
    # tomorrow would be the exact "answers a different question than the
    # one asked" dishonesty this whole change set exists to remove.
    # If the forecast cache is empty (data/fetch.py not re-run), we fall
    # back to today's data AND say so, rather than silently pretending.
    # ORCA holds exactly two days. A question past that gets answered for
    # today -- which is fine, and dishonest only if unsaid. Left unsaid it
    # produced a confident answer about right now to a question about the
    # day after tomorrow (measured 2026-08-27).
    beyond_note = None
    if time_frame == "beyond":
        beyond_note = _BEYOND_NOTE
        time_frame = memory.DEFAULT_TIME_FRAME

    verdict_observations = observations
    stale_forecast_note = None
    if time_frame == "tomorrow" and on_topic:
        if forecast_observations:
            verdict_observations = forecast_observations
        else:
            stale_forecast_note = (
                "ORCA has no forecast cached for tomorrow, so this reflects "
                "current conditions, not tomorrow's."
            )

    recommendation = build_recommendation(
        query,
        lat,
        lon,
        observations=verdict_observations,
        offline_mode=offline_mode,
        zones=zones,
        resolved_zone=resolved_zone,
    )
    recommendation.detected_language = detected_language
    recommendation.agentic_used = agentic_used
    recommendation.zone_match = zone_match
    recommendation.time_frame = time_frame
    recommendation.answer_kind = "off_topic" if not on_topic else intent

    # A comparison question gets the real ordering or nothing at all.
    #
    # "second_zone" belongs here too, and treating it as an unsupported
    # capability was the bug. ORCA holds all ten zones' readings, so "Is
    # Kanyakumari safer than Rameswaram?" is perfectly answerable -- it
    # was being declined, and the composer then invented an answer anyway:
    # "Kanyakumari appears later in the list than Rameswaram, so it's
    # considered safer" (measured live 2026-08-29; there is no list in its
    # context). Hardening the prompt against that did NOT stop it -- the
    # model reproduced the same fabrication three times out of three.
    #
    # This is the lesson _rank_zones() already records for the identical
    # failure one scope over: hand it the real ordering, do not ask it
    # more firmly not to guess. A rule the model can talk itself out of
    # is not a rule.
    ranking = None
    comparison = scope == "all_zones" or unsupported == "second_zone"
    if on_topic and comparison:
        ranking = _rank_zones(variable, verdict_observations, zones, recommendation)
    recommendation.ranking = ranking

    # Tier 4 (zone_match still "fallback"): nothing in the question,
    # nothing in memory. build_recommendation() fell back to the
    # geographically nearest zone -- which is a reasonable default, but
    # answering as though that were what they asked is the dishonest
    # part. Say so instead.
    notes = []
    if zone_match == "fallback" and on_topic and scope != "all_zones":
        chosen = recommendation.chosen_zone or (resolved_zone or {})
        name = chosen.get("name") if isinstance(chosen, dict) else None
        if name:
            notes.append(
                f"You didn't name a place ORCA covers, so this is for {name}, "
                "the nearest of the 10 Tamil Nadu coastal zones it has real data for."
            )
    if beyond_note and on_topic:
        notes.append(beyond_note)
    # ...and once it is answered as a comparison, the "one place at a
    # time" caveat is simply false, so it must not be attached.
    if on_topic and unsupported == "second_zone" and ranking:
        unsupported = "none"
    if on_topic and unsupported in _UNSUPPORTED_NOTES:
        note = _UNSUPPORTED_NOTES[unsupported]
        if "{zone}" in note:
            covered = recommendation.chosen_zone or resolved_zone or {}
            covered_name = covered.get("name") if isinstance(covered, dict) else None
            if covered_name is None and recommendation.zone_summaries:
                covered_name = recommendation.zone_summaries[0].get("zone")
            note = note.format(zone=covered_name or "the one place it could resolve")
        notes.append(note)
    if stale_forecast_note:
        notes.append(stale_forecast_note)

    coverage_note = " ".join(notes) if notes else None
    recommendation.coverage_note = coverage_note

    lookup = None
    if on_topic and intent == "data_lookup":
        # Which zone the reading should come from, most-specific first.
        # chosen_zone is None on a DO NOT GO (there is nowhere to send
        # them), and resolved_zone is None when they named no place -- so
        # on "what's the wave height?" during dangerous conditions both
        # are None and the number they actually asked for would be
        # silently dropped. zone_summaries[0] is the primary zone
        # build_recommendation really evaluated (planner.py builds it
        # primary-first), so the reading always has a real home.
        lookup_zone = recommendation.chosen_zone or resolved_zone
        if lookup_zone is None and recommendation.zone_summaries:
            lookup_zone = recommendation.zone_summaries[0]
        lookup = _resolve_lookup(
            variable,
            lookup_zone,
            observations,
            forecast_observations,
            time_frame,
        )
        if lookup is not None:
            recommendation.lookup = lookup
        else:
            # The model said "data_lookup" but named no variable we
            # actually collect (e.g. a bare "and what about tomorrow?").
            # Reporting answer_kind="data_lookup" with nothing to look up
            # would claim a kind of answer we did not deliver -- this is
            # a verdict, and saying so is the honest label.
            recommendation.answer_kind = "verdict"

    if is_configured():
        elapsed = time.monotonic() - started_at
        remaining = LAYER_BUDGET_S - elapsed
        if remaining < MIN_CALL_BUDGET_S:
            # Extraction already spent the layer's whole budget. Starting a
            # second call here is what produced the ~16 s worst case: it
            # cannot help this request, and the deterministic text below is
            # already correct. Skipped, and -- per R-45 -- said out loud.
            logger.warning(
                "Agentic composition skipped: layer budget of %.1fs spent "
                "(%.1fs elapsed); using deterministic text",
                LAYER_BUDGET_S, elapsed,
            )
            return recommendation
        try:
            composed = compose_grounded_answer(
                query,
                recommendation.to_dict(),
                detected_language,
                lookup=lookup,
                coverage_note=coverage_note,
                off_topic=not on_topic,
                ranking=ranking,
                # Clamped, not the full per-call timeout: this is the half
                # of R-49 that makes LAYER_BUDGET_S a bound rather than a
                # suggestion.
                timeout=min(REQUEST_TIMEOUT_S, remaining),
            )
            recommendation.recommendation = composed["answer_text"]
            recommendation.cited_evidence_ids = composed["cited_evidence_ids"]
            recommendation.agentic_used = True
        except AgenticUnavailable as exc:
            # Falling back is correct; falling back QUIETLY is what let a
            # server run all day with the agentic layer off while looking
            # exactly like one that had it on. The deterministic text is
            # still kept verbatim -- the only change is that the reason is
            # now on the record (CLAUDE.md rule 2: nothing swallowed).
            logger.warning("Agentic composition unavailable, using deterministic text: %s", exc)

    return recommendation
