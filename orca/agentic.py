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

import json
import logging
import os
import time

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
    "second_zone": (
        "You asked about more than one place. ORCA answers one place at a "
        "time, so this covers only the first one."
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
    try:
        resp = requests.post(
            GROQ_API_URL,
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            json=payload,
            timeout=REQUEST_TIMEOUT_S if timeout is None else timeout,
        )
        resp.raise_for_status()
    except requests.RequestException as exc:
        raise AgenticUnavailable(f"Groq request failed: {exc}") from exc
    try:
        body = resp.json()
        content = body["choices"][0]["message"]["content"]
        return json.loads(content)
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
        raise AgenticUnavailable(f"Groq response malformed: {exc}") from exc


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
        if recommendation.get("action") == "DO NOT GO":
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
                "comparison."
            )
        else:
            parts.append(
                "You have readings for ONE place only. Never say or imply "
                "that a place is the worst, best, safest, calmest or "
                "roughest compared with anywhere else, and never rank or "
                "order places -- you have not been shown any other place's "
                "readings. If they asked for a comparison, say you can "
                "only speak for this one place."
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
    if on_topic and unsupported in _UNSUPPORTED_NOTES:
        notes.append(_UNSUPPORTED_NOTES[unsupported])
    if stale_forecast_note:
        notes.append(stale_forecast_note)

    coverage_note = " ".join(notes) if notes else None
    recommendation.coverage_note = coverage_note

    # A comparison question gets the real ordering or nothing at all.
    ranking = None
    if on_topic and scope == "all_zones":
        ranking = _rank_zones(variable, verdict_observations, zones, recommendation)
    recommendation.ranking = ranking

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
