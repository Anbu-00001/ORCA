"""Tests for orca/mcp_server.py (war plan S6.2 stretch goal).

Two layers: the plain functions (same style as the rest of the test
suite) for logic correctness, and a real call through the registered
MCPServer's call_tool() for actual protocol-level verification -- proving
this isn't just two functions sitting next to an unused decorator.
"""
import asyncio

from orca.mcp_server import ask_marine_advisory, get_evidence, mcp


def test_ask_marine_advisory_returns_contract_shaped_dict():
    result = ask_marine_advisory("Nagapattinam", 10.76, 79.84)
    for key in ("id", "action", "reason", "recommendation", "chosen_zone", "overridden", "evidence", "offline_mode"):
        assert key in result
    assert result["action"] in {"GO", "DO NOT GO", "SAFER ALTERNATIVE"}


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


def test_tools_are_registered_on_the_mcp_server():
    tools = asyncio.run(mcp.list_tools())
    names = {t.name for t in tools}
    assert "ask_marine_advisory" in names
    assert "get_evidence" in names


def test_call_tool_end_to_end_through_the_real_mcp_protocol_layer():
    import json

    result = asyncio.run(
        mcp.call_tool("ask_marine_advisory", {"query": "Nagapattinam", "lat": 10.76, "lon": 79.84})
    )
    assert isinstance(result, list) and len(result) > 0
    payload = json.loads(result[0].text)
    assert payload["action"] in {"GO", "DO NOT GO", "SAFER ALTERNATIVE"}
    assert payload["evidence"]
