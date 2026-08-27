"""Tests for orca/agentic.py — written before most of the implementation
existed, matching this project's TDD discipline. No test in this file
makes a real network call by default: every Groq response is mocked with
a `_FakeResponse`, the same pattern tests/test_fetch.py already uses for
data/fetch.py's real network fetchers (`monkeypatch.setattr("orca.agentic.requests.post", ...)`).

A real end-to-end pass against the live Groq API (once GROQ_API_KEY is
available) belongs under the "agentic" pytest marker registered in
pyproject.toml, mirroring the existing "integration" marker for
data/fetch.py's real network tests — see test_answer_question_live at the
bottom of this file.
"""
from __future__ import annotations

import json

import pytest

from data.fetch import ZONES
from orca.agentic import (
    AgenticUnavailable,
    answer_question,
    compose_grounded_answer,
    extract_query_intent,
    is_configured,
)
from orca.schema import MarineObservation
from datetime import datetime, timezone

ZONE_A = next(z for z in ZONES if z["name"] == "Nagapattinam")
ZONE_B = next(z for z in ZONES if z["name"] == "Karaikal")


def _obs(zone, source="Open-Meteo Marine"):
    return MarineObservation(
        variable="wave_height_m", value=1.4, unit="m",
        lat=zone["lat"], lon=zone["lon"],
        valid_time=datetime(2026, 8, 26, 4, 0, tzinfo=timezone.utc),
        fetched_at=datetime(2026, 8, 26, 4, 5, tzinfo=timezone.utc),
        source=source, confidence=0.85, freshness_min=5,
        provenance="https://example.test/provenance",
    )


class _FakeResponse:
    """Mirrors tests/test_fetch.py's mocking style: a stand-in for
    requests.Response carrying only what orca.agentic._post() reads."""

    def __init__(self, status_code=200, body=None):
        self.status_code = status_code
        self._body = body or {}

    def raise_for_status(self):
        if self.status_code >= 400:
            import requests
            raise requests.HTTPError(f"{self.status_code} error")

    def json(self):
        return self._body


def _groq_response(content_obj: dict) -> _FakeResponse:
    """A Groq chat-completions response shaped exactly like the real API
    (verified against console.groq.com/docs before writing this file --
    see SCRATCH.md), with the model's structured-output JSON as a string
    inside choices[0].message.content, same as the real API returns it."""
    return _FakeResponse(200, {"choices": [{"message": {"content": json.dumps(content_obj)}}]})


# ---------------------------------------------------------------------------
# is_configured / fail-closed on missing key
# ---------------------------------------------------------------------------

def test_is_configured_false_without_key(monkeypatch):
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    assert is_configured() is False


def test_is_configured_true_with_key(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    assert is_configured() is True


def test_extract_query_intent_raises_when_unconfigured(monkeypatch):
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    with pytest.raises(AgenticUnavailable):
        extract_query_intent("is it safe near the harbour jetty at rameswaram", ZONES)


# ---------------------------------------------------------------------------
# extract_query_intent — mocked Groq responses
# ---------------------------------------------------------------------------

def test_extract_query_intent_returns_real_zone_and_language(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    monkeypatch.setattr(
        "orca.agentic.requests.post",
        lambda *a, **k: _groq_response({"zone_name": "Rameswaram", "language": "en"}),
    )
    result = extract_query_intent("is it safe near the boat jetty at rameswaram", ZONES)
    assert result["zone_name"] == "Rameswaram"
    assert result["language"] == "en"


def test_extract_query_intent_rejects_hallucinated_zone_not_in_list(monkeypatch):
    # Defense-in-depth: even if strict mode somehow failed and the model
    # named a place outside the real zone list, this must not pass
    # through as a resolvable zone.
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    monkeypatch.setattr(
        "orca.agentic.requests.post",
        lambda *a, **k: _groq_response({"zone_name": "Atlantis", "language": "en"}),
    )
    result = extract_query_intent("some query", ZONES)
    assert result["zone_name"] is None


def test_extract_query_intent_raises_on_network_error(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")

    def _raise(*a, **k):
        import requests
        raise requests.ConnectionError("no route to host")

    monkeypatch.setattr("orca.agentic.requests.post", _raise)
    with pytest.raises(AgenticUnavailable):
        extract_query_intent("some query", ZONES)


def test_extract_query_intent_raises_on_timeout(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")

    def _raise(*a, **k):
        import requests
        raise requests.Timeout("timed out")

    monkeypatch.setattr("orca.agentic.requests.post", _raise)
    with pytest.raises(AgenticUnavailable):
        extract_query_intent("some query", ZONES)


def test_extract_query_intent_raises_on_malformed_response(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    monkeypatch.setattr("orca.agentic.requests.post", lambda *a, **k: _FakeResponse(200, {"unexpected": "shape"}))
    with pytest.raises(AgenticUnavailable):
        extract_query_intent("some query", ZONES)


def test_extract_query_intent_raises_on_non_json_content(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    monkeypatch.setattr(
        "orca.agentic.requests.post",
        lambda *a, **k: _FakeResponse(200, {"choices": [{"message": {"content": "not json"}}]}),
    )
    with pytest.raises(AgenticUnavailable):
        extract_query_intent("some query", ZONES)


def test_extract_query_intent_raises_on_http_error_status(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    monkeypatch.setattr("orca.agentic.requests.post", lambda *a, **k: _FakeResponse(401, {}))
    with pytest.raises(AgenticUnavailable):
        extract_query_intent("some query", ZONES)


# ---------------------------------------------------------------------------
# compose_grounded_answer — citation validation is the important part
# ---------------------------------------------------------------------------

def test_compose_grounded_answer_keeps_real_citations(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    monkeypatch.setattr(
        "orca.agentic.requests.post",
        lambda *a, **k: _groq_response({"answer_text": "Conditions look calm.", "cited_evidence_ids": ["obs_real1"]}),
    )
    rec = {"evidence": [{"id": "obs_real1"}, {"id": "obs_real2"}]}
    result = compose_grounded_answer("is it safe?", rec, "en")
    assert result["answer_text"] == "Conditions look calm."
    assert result["cited_evidence_ids"] == ["obs_real1"]


def test_compose_grounded_answer_drops_hallucinated_citations(monkeypatch):
    # The core defense against citation hallucination (SCRATCH.md cites
    # arxiv 2606.00898: LLM citations hallucinate even under schema
    # constraints) -- an id that isn't in the real evidence list must
    # never reach the user as if it were.
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    monkeypatch.setattr(
        "orca.agentic.requests.post",
        lambda *a, **k: _groq_response(
            {"answer_text": "Conditions look calm.", "cited_evidence_ids": ["obs_real1", "obs_made_up"]}
        ),
    )
    rec = {"evidence": [{"id": "obs_real1"}]}
    result = compose_grounded_answer("is it safe?", rec, "en")
    assert result["cited_evidence_ids"] == ["obs_real1"]


# ---------------------------------------------------------------------------
# answer_question — the full orchestration, and its fallback guarantee
# ---------------------------------------------------------------------------

def test_answer_question_falls_back_to_deterministic_when_unconfigured(monkeypatch):
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    rec = answer_question(
        "Should I go fishing near Nagapattinam?", ZONE_A["lat"], ZONE_A["lon"], observations=[_obs(ZONE_A)]
    )
    assert rec.agentic_used is False
    assert rec.detected_language == "en"
    assert rec.chosen_zone is not None
    assert rec.chosen_zone["name"] == "Nagapattinam"


def test_answer_question_substring_hit_never_calls_the_model_for_zone_resolution(monkeypatch):
    # Zero-risk deterministic hit must win outright for zone *resolution*
    # specifically -- proven by making the extraction schema explode if
    # it's ever requested, while composition (a separate, independent use
    # of the network for phrasing only) is still allowed to run.
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")

    def _post(url, headers=None, json=None, timeout=None):
        if "query_intent" in str(json):
            raise AssertionError("must not ask the model to resolve a zone substring matching already found")
        return _groq_response({"answer_text": "Go to Nagapattinam.", "cited_evidence_ids": []})

    monkeypatch.setattr("orca.agentic.requests.post", _post)
    rec = answer_question(
        "Should I go fishing near Nagapattinam?", ZONE_A["lat"], ZONE_A["lon"], observations=[_obs(ZONE_A)]
    )
    assert rec.chosen_zone["name"] == "Nagapattinam"


def test_answer_question_uses_llm_zone_when_substring_finds_nothing(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")

    def _post(url, headers=None, json=None, timeout=None):
        if "query_intent" in str(json):
            return _groq_response({"zone_name": "Karaikal", "language": "en"})
        return _groq_response({"answer_text": "Go ahead, conditions are calm at Karaikal.", "cited_evidence_ids": []})

    monkeypatch.setattr("orca.agentic.requests.post", _post)
    rec = answer_question(
        "is it safe to go out near the salt pans by the backwater today",
        ZONE_B["lat"], ZONE_B["lon"], observations=[_obs(ZONE_B)],
    )
    assert rec.agentic_used is True
    assert rec.chosen_zone["name"] == "Karaikal"
    assert rec.recommendation == "Go ahead, conditions are calm at Karaikal."


def test_answer_question_falls_back_when_llm_extraction_fails(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")

    def _raise(*a, **k):
        import requests
        raise requests.Timeout("timed out")

    monkeypatch.setattr("orca.agentic.requests.post", _raise)
    rec = answer_question(
        "is it safe to go out near the salt pans by the backwater today",
        ZONE_B["lat"], ZONE_B["lon"], observations=[_obs(ZONE_B)],
    )
    # No substring hit, LLM unreachable -> nearest-by-coordinates fallback,
    # exactly like plain build_recommendation() would have done.
    assert rec.agentic_used is False
    assert rec.chosen_zone["name"] == "Karaikal"


def test_answer_question_keeps_template_text_when_composition_fails(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")

    def _composition_endpoint_down(*a, **k):
        import requests
        raise requests.ConnectionError("composition endpoint down")

    monkeypatch.setattr("orca.agentic.requests.post", _composition_endpoint_down)
    rec = answer_question(
        "Should I go fishing near Nagapattinam?", ZONE_A["lat"], ZONE_A["lon"], observations=[_obs(ZONE_A)]
    )
    # Substring match resolves the zone with zero network calls; composition
    # then fails -- the deterministic template recommendation text must
    # still be present, not blank/broken.
    assert rec.recommendation.startswith("Go to Nagapattinam")
    assert rec.agentic_used is False


# ---------------------------------------------------------------------------
# Real end-to-end against the live Groq API. Skips itself unless
# GROQ_API_KEY is set -- mirrors the "integration" marker's pattern in
# data/fetch.py's tests exactly (see pyproject.toml).
# ---------------------------------------------------------------------------

import os


@pytest.mark.agentic
@pytest.mark.skipif(not os.environ.get("GROQ_API_KEY"), reason="requires a real GROQ_API_KEY")
def test_answer_question_live_end_to_end():
    rec = answer_question(
        "Is it safe to go fishing near the harbour jetty at Rameswaram today?",
        ZONE_A["lat"], ZONE_A["lon"], observations=[_obs(next(z for z in ZONES if z["name"] == "Rameswaram"))],
    )
    assert rec.agentic_used is True
    assert rec.chosen_zone is not None
    assert rec.chosen_zone["name"] == "Rameswaram"
    assert rec.recommendation  # non-empty, real model output
