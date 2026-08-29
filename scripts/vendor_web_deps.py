"""Download web/'s front-end dependencies into web/vendor/ so the demo runs
with no network at all (CLAUDE.md rule 8).

    python scripts/vendor_web_deps.py            # download / refresh
    python scripts/vendor_web_deps.py --check    # verify, download nothing

WHY THIS EXISTS
---------------
index.html loaded three.js, its addons, MapLibre and a Google font from
unpkg and fonts.googleapis.com at page load. Rule 8 says the demo must
run with no network access, and the answer path genuinely does -- but the
MAP and the 3D VIEW did not. Measured 2026-08-29 with every non-localhost
request blocked: nine resources failed and `#ocean3d-container canvas`
was never created, so the 3D Ocean tab was empty.

The existing wifi-off e2e test passed throughout, because it asserts that
a QUESTION still gets an ANSWER -- which was true. Nothing asserted that
the things a judge actually looks at still draw.

This is a build-time generator for committed assets, like
scripts/build_docx.py and scripts/build_colormaps.py. It is not imported
by anything in orca/ or web/, and it is the only reason those files are
allowed to touch the network.
"""
from __future__ import annotations

import argparse
import sys
import urllib.request
from pathlib import Path

VENDOR = Path(__file__).resolve().parent.parent / "web" / "vendor"

THREE = "0.180.0"
MAPLIBRE = "4.7.1"
UNPKG = "https://unpkg.com"

# three's addons import each other by relative path, so the transitive
# closure has to come down too or the import map resolves into a 404.
THREE_ADDONS = [
    "controls/OrbitControls.js",
    "postprocessing/EffectComposer.js",
    "postprocessing/Pass.js",
    "postprocessing/RenderPass.js",
    "postprocessing/ShaderPass.js",
    "postprocessing/MaskPass.js",
    "postprocessing/UnrealBloomPass.js",
    "postprocessing/OutputPass.js",
    "shaders/CopyShader.js",
    "shaders/LuminosityHighPassShader.js",
    "shaders/OutputShader.js",
]

FILES: dict[str, str] = {
    "three.module.js": f"{UNPKG}/three@{THREE}/build/three.module.js",
    # r150+ splits the library: three.module.js is a thin re-export shell
    # that does `from './three.core.js'`. Vendoring only the shell yields
    # a 404 at page load and no 3D view at all.
    "three.core.js": f"{UNPKG}/three@{THREE}/build/three.core.js",
    "maplibre-gl.js": f"{UNPKG}/maplibre-gl@{MAPLIBRE}/dist/maplibre-gl.js",
    "maplibre-gl.css": f"{UNPKG}/maplibre-gl@{MAPLIBRE}/dist/maplibre-gl.css",
}
for addon in THREE_ADDONS:
    FILES[f"three/addons/{addon}"] = f"{UNPKG}/three@{THREE}/examples/jsm/{addon}"


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "orca-vendor/1.0"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="verify every vendored file is present; download nothing")
    args = ap.parse_args()

    if args.check:
        missing = [name for name in FILES if not (VENDOR / name).is_file()]
        empty = [name for name in FILES
                 if (VENDOR / name).is_file() and (VENDOR / name).stat().st_size == 0]
        if missing or empty:
            for name in missing:
                print(f"MISSING: web/vendor/{name}", file=sys.stderr)
            for name in empty:
                print(f"EMPTY:   web/vendor/{name}", file=sys.stderr)
            print("\nRun: python scripts/vendor_web_deps.py", file=sys.stderr)
            return 1
        total = sum((VENDOR / n).stat().st_size for n in FILES)
        print(f"OK: {len(FILES)} vendored files present ({total/1024/1024:.1f} MB)")
        return 0

    total = 0
    for name, url in FILES.items():
        dest = VENDOR / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        try:
            body = fetch(url)
        except Exception as exc:  # noqa: BLE001 -- reported, never swallowed
            print(f"FAIL {name}: {exc}", file=sys.stderr)
            return 1
        dest.write_bytes(body)
        total += len(body)
        print(f"  {len(body)/1024:8.1f} KB  web/vendor/{name}")
    print(f"\n{len(FILES)} files, {total/1024/1024:.1f} MB total")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
