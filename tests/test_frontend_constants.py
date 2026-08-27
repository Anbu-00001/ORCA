"""Drift guards for constants that necessarily exist twice.

web/ is plain HTML/JS with no build step (CLAUDE.md's stack rule), so it
cannot import a Python constant. A handful of real values therefore live
in both places, and nothing but discipline keeps them equal -- which is
exactly the kind of thing that silently rots and then misleads someone on
stage:

  - ZONES: if the two lists drift, map markers point at the wrong water.
  - WAVE_HARD_DENY_M: the Douglas ruler would draw its "ORCA stops here"
    line somewhere the policy engine does not actually stop.
  - the conversation-history cap: the browser would send more turns than
    orca/memory.py will honour, so the extra ones vanish silently.

These tests parse the real values out of web/index.html and compare them
to the real Python ones. Deliberately a test rather than a new /zones
endpoint or a generated file: the page must keep rendering with the API
down (see e2e/live.spec.js's wifi-off test), and a build step is ruled
out. A drift guard costs nothing at runtime and fails loudly at CI time,
which is where this problem should be caught.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from data.fetch import ZONES
from orca.agents import WAVE_HARD_DENY_M
from orca.memory import MAX_TURNS

INDEX_HTML = Path(__file__).resolve().parent.parent / "web" / "index.html"


@pytest.fixture(scope="module")
def html() -> str:
    return INDEX_HTML.read_text()


def _js_number(html: str, name: str) -> float:
    match = re.search(rf"const\s+{re.escape(name)}\s*=\s*([0-9.]+)\s*;", html)
    assert match, f"could not find `const {name} = ...` in web/index.html"
    return float(match.group(1))


def test_frontend_zones_match_the_real_backend_zones(html):
    match = re.search(r"const ZONES = \[(.*?)\];", html, re.S)
    assert match, "could not find `const ZONES = [...]` in web/index.html"

    # The JS literal uses unquoted keys; normalize to JSON before parsing
    # rather than eval'ing it.
    body = match.group(1)
    body = re.sub(r"(\w+):", r'"\1":', body)
    body = re.sub(r",\s*$", "", body.strip())
    frontend_zones = json.loads(f"[{body}]")

    assert [z["name"] for z in frontend_zones] == [z["name"] for z in ZONES]
    for fe, be in zip(frontend_zones, ZONES):
        assert fe["lat"] == pytest.approx(be["lat"]), f"{fe['name']} latitude drifted"
        assert fe["lon"] == pytest.approx(be["lon"]), f"{fe['name']} longitude drifted"


def test_frontend_svg_fallback_covers_exactly_the_real_zones(html):
    """The no-network fallback map is hand-placed SVG; its labels must
    still name the real zones and nothing else."""
    svg_zones = set(re.findall(r'class="zone-dot" data-zone="([^"]+)"', html))
    assert svg_zones == {z["name"] for z in ZONES}


def test_frontend_wave_hard_deny_matches_the_policy_threshold(html):
    assert _js_number(html, "WAVE_HARD_DENY_M") == WAVE_HARD_DENY_M


def test_frontend_history_cap_does_not_exceed_the_backend_cap(html):
    """The browser may send fewer turns than the server keeps, never more
    -- anything above MAX_TURNS is silently dropped by sanitize(), which
    would make the UI's memory quietly shorter than it looks."""
    assert _js_number(html, "HISTORY_MAX") <= MAX_TURNS
