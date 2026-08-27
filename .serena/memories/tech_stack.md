# ORCA — tech stack

- Python 3.12 (repo docs say 3.11; 3.12 is what's actually installed/used, no
  issue found).
- Backend: FastAPI 0.141, pydantic 2.13 (only used at the `orca/api.py`
  request boundary — the rest of the codebase uses plain `@dataclass` +
  `__post_init__` validation, not pydantic), requests, pytest 9.1, httpx (via
  FastAPI TestClient), mcp 2.1.1.
- Frontend: vanilla JS/HTML/CSS, no framework, no build step. MapLibre GL JS
  loaded from the unpkg CDN at runtime (must degrade gracefully offline — see
  the `#map-fallback` SVG in `web/index.html`).
- venv at `.venv/`; `requirements.txt` pins exact versions (no ranges).
- Test tooling: `package.json`/`node_modules` exist ONLY for Playwright
  (`@playwright/test`) — not app runtime, don't treat repo as a JS project.

## Trap: the `mcp` package's public API is a moving target
`mcp` 2.x renamed `FastMCP` -> `MCPServer`, moved to
`mcp.server.mcpserver`, and changed `call_tool()`'s return shape (no longer a
plain list of content blocks in all versions — check the actual installed
package, e.g. `python -c "import mcp; print(mcp.__version__)"` and
`inspect.signature(...)`, before writing against it). Do not trust
remembered/training-data API shapes for this package; verify against
whatever version `pip show mcp` reports in the active venv first.
