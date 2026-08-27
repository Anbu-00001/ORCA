"""Test-suite-wide environment control.

orca/api.py loads a git-ignored .env at import so the running server
actually picks up GROQ_API_KEY (it previously did not, and the agentic
layer sat silently off for a full day). That import happens in tests too,
via TestClient -- which meant the unit suite started making real, billed
Groq calls: test_api.py went from milliseconds to 2-4 seconds per test
the moment the loader landed.

Unit tests must be hermetic. This removes the key for every test except
those explicitly marked `agentic`, which are the ones that are supposed
to talk to the live API and are deselected by default.
"""
from __future__ import annotations

import pytest


@pytest.fixture(autouse=True)
def _no_live_groq_unless_marked(request, monkeypatch):
    if request.node.get_closest_marker("agentic"):
        return
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
