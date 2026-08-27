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
import os

import requests

from data.fetch import ZONES
from orca.planner import Recommendation, _zone_by_substring, build_recommendation

GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
# Both models are Groq-hosted, strict-JSON-schema-capable (verified against
# Groq's docs before use, not assumed -- see SCRATCH.md). Same family,
# split by task: extraction needs precision under a small, fixed schema;
# composition gets the bigger model because prose quality (especially in
# Tamil) is the part actually worth spending tokens on.
EXTRACTION_MODEL = "openai/gpt-oss-20b"
COMPOSITION_MODEL = "openai/gpt-oss-120b"
REQUEST_TIMEOUT_S = 8.0  # fail fast into the deterministic fallback -- never hang a live demo


class AgenticUnavailable(Exception):
    """Raised for any reason the agentic layer can't be used this request.
    Callers must catch this specifically (never a bare except -- CLAUDE.md
    rule 2) and fall back to the deterministic path."""


def is_configured() -> bool:
    return bool(os.environ.get("GROQ_API_KEY"))


def _post(payload: dict) -> dict:
    """The only function in this file that makes a network call. Returns
    the parsed JSON object the model produced (already schema-validated
    server-side by Groq's strict mode); raises AgenticUnavailable for
    every failure mode instead of letting any of them propagate as a
    generic exception the caller might mishandle."""
    api_key = os.environ.get("GROQ_API_KEY")
    if not api_key:
        raise AgenticUnavailable("GROQ_API_KEY not set")
    try:
        resp = requests.post(
            GROQ_API_URL,
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            json=payload,
            timeout=REQUEST_TIMEOUT_S,
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


def extract_query_intent(query: str, zones: list[dict] | None = None) -> dict:
    """Pick a real zone name (or null) and the query's language, under a
    strict schema so the model can only ever choose a zone that actually
    exists -- never invent a place. Only called when plain substring
    matching already found nothing (see answer_question()), so a
    zero-risk deterministic hit never gets second-guessed by this."""
    zones = zones or ZONES
    names = [z["name"] for z in zones]
    schema = {
        "type": "object",
        "properties": {
            "zone_name": {"type": ["string", "null"], "enum": names + [None]},
            "language": {"type": "string", "enum": ["en", "ta", "other"]},
        },
        "required": ["zone_name", "language"],
        "additionalProperties": False,
    }
    system = (
        "You extract structured facts from a fisherman's question for a "
        "marine safety tool. You do not answer the question or make any "
        f"safety decision. zone_name MUST be exactly one of {names} if the "
        "query clearly refers to one of those real places (by name, a "
        "well-known landmark there, or a common alternate/local name for "
        "it), else null -- never a place outside this list. language is "
        "'ta' for Tamil (including Tamil written in Latin script), 'en' "
        "for English, 'other' otherwise."
    )
    payload = {
        "model": EXTRACTION_MODEL,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": query},
        ],
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "query_intent", "strict": True, "schema": schema},
        },
        "temperature": 0,
    }
    result = _post(payload)
    if result.get("zone_name") is not None and result.get("zone_name") not in names:
        # Strict mode is supposed to make this impossible -- but a network
        # response never gets to skip validation just because it claims to
        # already be validated (CLAUDE.md rule 1's spirit: an absent/
        # rejected reading is correct, a fabricated one is not).
        result["zone_name"] = None
    return result


def compose_grounded_answer(query: str, recommendation: dict, language: str) -> dict:
    """Rephrase an already-decided Recommendation for a human, in their
    language. The schema has no field for action/risk/numbers -- only
    text and which of the *real* evidence ids it drew on -- so there is
    no field through which the model could alter the decision even if it
    tried."""
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
    system = (
        "You explain an already-made fishing-safety decision to a "
        "fisherman, the way an experienced local would explain it out "
        "loud -- not a report, not a restatement of field names. You did "
        "NOT make the decision and MUST NOT change it: every number, "
        "place, and fact you use must come EXACTLY from the decision JSON "
        "below -- never compute, round, or invent one. Do not say the "
        "words 'the decision is' or 'the reason is' or otherwise narrate "
        "the JSON's own structure -- just tell them plainly what to do "
        "and the one or two things that matter about why (e.g. the wave "
        "height and what it means for going out), in language code "
        f"'{language}'. 1-2 short sentences, spoken plainly. "
        "cited_evidence_ids must be a subset of: "
        f"{json.dumps(evidence_ids)}. Decision JSON: {json.dumps(recommendation)}"
    )
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
    result = _post(payload)
    # Same principle as extract_query_intent: verify the citations
    # ourselves rather than trust that strict mode + the prompt were
    # enough. Citation hallucination is a documented failure mode even
    # under schema constraints (SCRATCH.md cites arxiv 2606.00898) -- so
    # any id the model names that isn't in the real evidence list is
    # silently dropped, never shown as if it were real.
    result["cited_evidence_ids"] = [i for i in result.get("cited_evidence_ids", []) if i in evidence_ids]
    return result


def answer_question(
    query: str,
    lat: float,
    lon: float,
    observations,
    offline_mode: bool = False,
    zones: list[dict] | None = None,
) -> Recommendation:
    """The single entry point orca/api.py's /ask handler calls instead of
    build_recommendation() directly. The deterministic core always runs;
    the agentic layer, when configured and reachable, can only improve
    zone resolution (when substring matching found nothing) and phrasing
    on top of it. Any failure anywhere in the agentic parts of this
    function falls back silently to the exact plain
    build_recommendation() result -- GROQ_API_KEY unset reproduces
    today's behaviour byte-for-byte.
    """
    zones = zones or ZONES
    resolved_zone = None
    detected_language = "en"
    agentic_used = False

    if is_configured():
        substring_hit = _zone_by_substring(query, zones)
        if substring_hit is not None:
            # Zero-risk deterministic hit -- no need to ask the model,
            # and it must not get a chance to override this.
            resolved_zone = substring_hit
        else:
            try:
                intent = extract_query_intent(query, zones)
                detected_language = intent.get("language", "en")
                if intent.get("zone_name"):
                    resolved_zone = next(z for z in zones if z["name"] == intent["zone_name"])
                agentic_used = True
            except AgenticUnavailable:
                pass  # resolved_zone stays None -> build_recommendation's own nearest-fallback runs

    recommendation = build_recommendation(
        query,
        lat,
        lon,
        observations=observations,
        offline_mode=offline_mode,
        zones=zones,
        resolved_zone=resolved_zone,
    )
    recommendation.detected_language = detected_language
    recommendation.agentic_used = agentic_used

    if is_configured():
        try:
            composed = compose_grounded_answer(query, recommendation.to_dict(), detected_language)
            recommendation.recommendation = composed["answer_text"]
            recommendation.cited_evidence_ids = composed["cited_evidence_ids"]
            recommendation.agentic_used = True
        except AgenticUnavailable:
            pass  # keep the deterministic template text exactly as-is

    return recommendation
