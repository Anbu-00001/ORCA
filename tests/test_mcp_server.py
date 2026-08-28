"""Tests for orca/mcp_server.py (war plan S6.2 stretch goal).

Two layers: the plain functions (same style as the rest of the test
suite) for logic correctness, and a real call through the registered
MCPServer's call_tool() for actual protocol-level verification -- proving
this isn't just two functions sitting next to an unused decorator.
"""
import asyncio
import json

import pytest
try:
    from mcp.server.mcpserver.exceptions import ToolError
except ImportError:
    from mcp.server.fastmcp.exceptions import ToolError

from orca.mcp_server import ask_marine_advisory, get_evidence, mcp


def test_ask_marine_advisory_returns_contract_shaped_dict():
    result = ask_marine_advisory("Nagapattinam", 10.76, 79.84)
    for key in ("id", "action", "reason", "recommendation", "chosen_zone", "overridden", "evidence", "offline_mode"):
        assert key in result
    assert result["action"] in {"GO", "DO NOT GO", "SAFER ALTERNATIVE", "CANNOT ASSESS"}


def test_get_evidence_resolves_a_real_id_from_ask():
    rec = ask_marine_advisory("Nagapattinam", 10.76, 79.84)
    obs_id = rec["evidence"][0]["id"]
    result = get_evidence(obs_id)
    assert "error" not in result
    assert result["id"] == obs_id
    assert result["variable"] == rec["evidence"][0]["variable"]


def test_get_evidence_unknown_id_returns_error_not_exception():
    result = get_evidence("obs_does_not_exist")
    assert "error" in result


def test_advisory_passes_cannot_assess_through_unchanged(monkeypatch):
    class CannotAssessRecommendation:
        @staticmethod
        def to_dict():
            return {"action": "CANNOT ASSESS", "evidence": []}

    monkeypatch.setattr(
        "orca.mcp_server.build_recommendation",
        lambda query, lat, lon: CannotAssessRecommendation(),
    )

    assert ask_marine_advisory("uncovered coordinate", 0.0, 0.0) == {
        "action": "CANNOT ASSESS",
        "evidence": [],
    }


def test_tools_are_registered_on_the_mcp_server():
    tools = asyncio.run(mcp.list_tools())
    names = {t.name for t in tools}
    assert "ask_marine_advisory" in names
    assert "get_evidence" in names


def test_call_tool_end_to_end_through_the_real_mcp_protocol_layer():
    result = asyncio.run(
        mcp.call_tool("ask_marine_advisory", {"query": "Nagapattinam", "lat": 10.76, "lon": 79.84})
    )
    assert getattr(result, "is_error", False) is False
    content = result[0].text if isinstance(result, list) else result.content[0].text
    payload = json.loads(content)
    assert payload["action"] in {"GO", "DO NOT GO", "SAFER ALTERNATIVE", "CANNOT ASSESS"}
    assert payload["evidence"]


def test_call_tool_rejects_unknown_tool_name():
    with pytest.raises(ToolError, match="Unknown tool"):
        asyncio.run(mcp.call_tool("tool_does_not_exist", {}))


def test_call_tool_rejects_malformed_arguments_before_running_tool():
    with pytest.raises(ToolError, match="validation error"):
        asyncio.run(
            mcp.call_tool(
                "ask_marine_advisory",
                {"query": "Nagapattinam", "lat": "not-a-coordinate", "lon": 79.84},
            )
        )


def test_get_evidence_unknown_id_round_trips_through_protocol():
    result = asyncio.run(mcp.call_tool("get_evidence", {"observation_id_": "obs_does_not_exist"}))
    assert getattr(result, "is_error", False) is False
    content = result[0].text if isinstance(result, list) else result.content[0].text
    payload = json.loads(content)
    assert payload == {"error": "no observation with id 'obs_does_not_exist'"}
