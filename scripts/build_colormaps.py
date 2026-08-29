"""Regenerate web/colormaps.js from cmocean's published RGB tables.

Run this only when a colormap needs adding or cmocean publishes a
revision. The output is committed, so the demo never fetches it and the
no-build-step rule still holds (CLAUDE.md stack rule).

    python scripts/build_colormaps.py            # fetch and rewrite
    python scripts/build_colormaps.py --check    # verify, change nothing

This is the ONLY place in the project that may reach the network outside
data/fetch.py and orca/agentic.py, and it is not part of the running
system -- it is a build-time generator for a committed asset, in the same
category as scripts/build_docx.py. Nothing in web/ or orca/ imports it.

Why the exact tables and not an approximation: the perceptual-uniformity
claim ORCA makes about its seabed is only true if the values ARE the
published ones. A 32-stop interpolation of them carries up to 8.7/255 of
channel error (measured), which is exactly the kind of quiet
almost-right that the rest of this codebase refuses elsewhere.

Reference: Thyng, K.M., C.A. Greene, R.D. Hetland, H.M. Zimmerle and
S.F. DiMarco (2016), "True Colors of Oceanography: Guidelines for
Effective and Accurate Colormap Selection", Oceanography 29(3):9-13,
doi:10.5670/oceanog.2016.66.
"""
from __future__ import annotations

import argparse
import sys
import urllib.request
from pathlib import Path

CMOCEAN_RGB_URL = "https://raw.githubusercontent.com/matplotlib/cmocean/main/cmocean/rgb/{name}-rgb.txt"
OUT_PATH = Path(__file__).resolve().parent.parent / "web" / "colormaps.js"

# name -> (what it encodes, unit, kind). Every map ORCA ships must be
# bound to one variable with one unit: a colormap with no stated variable,
# no range and no units is decoration, not a measurement.
MAPS = {
    "deep":    ("Depth below sea level", "m", "sequential"),
    "thermal": ("Sea surface temperature", "°C", "sequential"),
    "speed":   ("Speed (current / wind)", "km/h", "sequential, constant lightness"),
    "amp":     ("Significant wave height", "m", "sequential"),
    "balance": ("Anomaly about zero", "signed", "diverging"),
}


def fetch(name: str) -> str:
    """256 rows of 'r g b' floats -> one flat lowercase hex string."""
    with urllib.request.urlopen(CMOCEAN_RGB_URL.format(name=name), timeout=30) as resp:
        rows = [line.split() for line in resp.read().decode().splitlines() if line.strip()]
    if len(rows) != 256:
        raise SystemExit(f"{name}: expected 256 stops, got {len(rows)}")
    out = []
    for r in rows:
        out.append("%02x%02x%02x" % tuple(min(255, max(0, round(float(c) * 255))) for c in r))
    return "".join(out)


def render(tables: dict[str, str]) -> str:
    header = OUT_PATH.read_text().split("const CMOCEAN_HEX")[0] if OUT_PATH.exists() else ""
    if not header:
        raise SystemExit(
            "web/colormaps.js has no header to preserve -- refusing to write a "
            "file whose comment block explains why the values are exact."
        )
    body = ["const CMOCEAN_HEX = {"]
    for name, hexes in tables.items():
        body.append(f'  {name}: "{hexes}",')
    body.append("};\n")
    body.append("export const CMOCEAN_META = {")
    for name, (label, unit, kind) in MAPS.items():
        body.append(f'  {name}: {{ label: "{label}", unit: "{unit}", kind: "{kind}" }},')
    body.append("};")
    tail = OUT_PATH.read_text().split("};\n", 2)[-1] if OUT_PATH.exists() else ""
    return header + "\n".join(body) + "\n" + tail


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="verify the committed file matches cmocean; write nothing")
    args = ap.parse_args()

    tables = {name: fetch(name) for name in MAPS}

    if args.check:
        current = OUT_PATH.read_text()
        missing = [n for n, h in tables.items() if f'{n}: "{h}"' not in current]
        if missing:
            print(f"STALE: {', '.join(missing)} differ from cmocean upstream", file=sys.stderr)
            return 1
        print(f"OK: all {len(tables)} colormaps match cmocean's published tables")
        return 0

    OUT_PATH.write_text(render(tables))
    print(f"wrote {OUT_PATH} ({OUT_PATH.stat().st_size:,} bytes, {len(tables)} colormaps)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
