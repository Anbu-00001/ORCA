"""ORCA as an MCP server (war plan S6.2, stretch goal -- core was green
first). Any MCP client -- Claude, a government helpdesk bot, a district
control-room dashboard -- can call the same reasoning layer as a tool,
not just a human via the web page.

Wraps orca.planner directly. Same guarantees as orca/api.py: no network
calls, no LLM calls here, reads only data/cache/ (CLAUDE.md rules 1, 4, 8).

Run directly (stdio transport, for a local MCP client config):
    python -m orca.mcp_server
"""
from __future__ import annotations

from mcp.server.fastmcp import FastMCP

from orca.planner import build_recommendation, load_cached_observations, observation_id

mcp = FastMCP("orca")


def ask_marine_advisory(query: str, lat: float, lon: float) -> dict:
    """Get a fishing-safety recommendation for a location on the
    Nagapattinam/Chennai coast, Bay of Bengal. Returns action (GO /
    DO NOT GO / SAFER ALTERNATIVE), the reason, the chosen zone, any
    overridden findings, and evidence with source/timestamp/confidence
    for every number.
    """
    return build_recommendation(query, lat, lon).to_dict()


def get_evidence(observation_id_: str) -> dict:
    """Look up a single marine observation by id, with full provenance."""
    for obs in load_cached_observations():
        if observation_id(obs) == observation_id_:
            return {**obs.to_dict(), "id": observation_id_}
    return {"error": f"no observation with id {observation_id_!r}"}


mcp.tool()(ask_marine_advisory)
mcp.tool()(get_evidence)


if __name__ == "__main__":
    mcp.run()
