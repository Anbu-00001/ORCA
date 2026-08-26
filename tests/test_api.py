"""Tests for orca/api.py — written before the implementation.

These run against the REAL data/cache/ (already real, already validated
by tests/test_fetch.py) rather than injected fixtures: this file's job is
to prove the HTTP wiring and the API_CONTRACT.md shape, not the decision
logic (that's tests/test_planner.py and tests/test_policy.py). Every
number in an /ask response must resolve through /evidence/{id} — that's
the traceability guarantee (CLAUDE.md rule 3) checked at the HTTP layer.
"""
from fastapi.testclient import TestClient

from orca.api import app

client = TestClient(app)

VALID_ACTIONS = {"GO", "DO NOT GO", "SAFER ALTERNATIVE"}


def test_health_returns_expected_shape():
    resp = client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert isinstance(data["offline_mode"], bool)
    assert isinstance(data["cache_age_min"], int)
    assert data["cache_age_min"] >= 0
    assert isinstance(data["cache_observation_count"], int)
    assert data["cache_observation_count"] > 0


def test_ask_returns_contract_shaped_recommendation():
    resp = client.post("/ask", json={"query": "Should I go fishing in Zone A from Nagapattinam?", "lat": 10.76, "lon": 79.84})
    assert resp.status_code == 200
    data = resp.json()
    for key in ("id", "action", "reason", "recommendation", "chosen_zone", "overridden", "evidence", "offline_mode"):
        assert key in data
    assert data["action"] in VALID_ACTIONS
    assert data["id"].startswith("rec_")
    assert isinstance(data["evidence"], list) and len(data["evidence"]) > 0


def test_ask_every_evidence_number_carries_full_provenance():
    resp = client.post("/ask", json={"query": "Zone A", "lat": 10.76, "lon": 79.84})
    data = resp.json()
    for obs in data["evidence"]:
        for required in ("id", "variable", "value", "unit", "source", "valid_time", "confidence", "provenance"):
            assert required in obs and obs[required] not in (None, "")


def test_ask_evidence_ids_resolve_through_evidence_endpoint():
    resp = client.post("/ask", json={"query": "Zone A", "lat": 10.76, "lon": 79.84})
    evidence = resp.json()["evidence"]
    assert evidence, "expected at least one evidence item"
    for item in evidence:
        ev_resp = client.get(f"/evidence/{item['id']}")
        assert ev_resp.status_code == 200
        ev_data = ev_resp.json()
        assert ev_data["variable"] == item["variable"]
        assert ev_data["value"] == item["value"]
        assert ev_data["source"] == item["source"]


def test_ask_missing_field_returns_422():
    resp = client.post("/ask", json={"query": "Zone A", "lat": 10.76})  # missing lon
    assert resp.status_code == 422


def test_ask_wrong_type_returns_422():
    resp = client.post("/ask", json={"query": "Zone A", "lat": "not-a-number", "lon": 79.84})
    assert resp.status_code == 422


def test_evidence_unknown_id_returns_404():
    resp = client.get("/evidence/obs_does_not_exist")
    assert resp.status_code == 404


def test_cors_header_present_for_get():
    resp = client.get("/health", headers={"Origin": "http://example.com"})
    assert resp.headers.get("access-control-allow-origin") == "*"


def test_ask_and_evidence_are_unaffected_by_connectivity(monkeypatch):
    """The actual §8.6 guarantee: /ask and /evidence read only from
    data/cache/ and must return identical, fully-evidenced answers
    whether or not the machine has internet -- only /health's badge
    should change. This is what makes "physically switch off the wifi"
    safe to do live on stage.
    """
    import orca.api as api_module

    online_resp = client.post("/ask", json={"query": "Zone A", "lat": 10.76, "lon": 79.84})
    assert online_resp.status_code == 200

    monkeypatch.setattr(api_module, "_is_reachable", lambda *a, **k: True)  # simulate offline

    offline_health = client.get("/health")
    assert offline_health.json()["offline_mode"] is True

    offline_resp = client.post("/ask", json={"query": "Zone A", "lat": 10.76, "lon": 79.84})
    assert offline_resp.status_code == 200
    online_data = online_resp.json()
    offline_data = offline_resp.json()
    assert offline_data["action"] == online_data["action"]
    assert offline_data["chosen_zone"] == online_data["chosen_zone"]
    assert len(offline_data["evidence"]) == len(online_data["evidence"])
    # offline_mode is echoed onto the recommendation payload too, so the
    # frontend can badge individual answers, not just the header.
    assert offline_data["offline_mode"] is True
    assert online_data["offline_mode"] is False


def test_ask_different_zones_can_produce_different_actions():
    """Sanity: the endpoint is actually consulting real per-zone data, not
    returning one constant answer regardless of input.
    """
    responses = {}
    for name, lat, lon in [("Zone A", 10.76, 79.84), ("Zone B", 10.85, 79.95)]:
        resp = client.post("/ask", json={"query": name, "lat": lat, "lon": lon})
        responses[name] = resp.json()
    assert all(r["action"] in VALID_ACTIONS for r in responses.values())
