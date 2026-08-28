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

try:
    from mcp.server.mcpserver import MCPServer
except ImportError:
    from mcp.server.fastmcp import FastMCP as MCPServer

from orca.planner import build_recommendation, load_cached_observations, observation_id

mcp = MCPServer("orca")


def ask_marine_advisory(query: str, lat: float, lon: float) -> dict:
    """Get a fishing-safety recommendation for a location on the
    Nagapattinam/Chennai coast, Bay of Bengal. Returns action (GO /
    DO NOT GO / SAFER ALTERNATIVE / CANNOT ASSESS), the reason, the chosen zone, any
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


@mcp.tool(name="ask_marine_advisory")
async def _ask_marine_advisory_tool(query: str, lat: float, lon: float) -> dict:
    """Protocol adapter for :func:`ask_marine_advisory`.

    MCPServer 2.x dispatches synchronous tools through AnyIO's worker-thread
    pool.  Keeping the protocol adapters async avoids making this small,
    cache-only operation depend on thread-pool availability while preserving
    the plain synchronous functions used by Python callers and unit tests.
    """
    return ask_marine_advisory(query, lat, lon)


@mcp.tool(name="get_evidence")
async def _get_evidence_tool(observation_id_: str) -> dict:
    """Protocol adapter for :func:`get_evidence`; reads committed cache only."""
    return get_evidence(observation_id_)


if __name__ == "__main__":
    mcp.run()
