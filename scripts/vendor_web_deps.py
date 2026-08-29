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
import re
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


# --- webfonts ---------------------------------------------------------
#
# The last two remote requests index.html made were the basemap tiles and
# this stylesheet. Tiles degrade gracefully -- offline, index.html already
# falls back to its own SVG sketch drawn from the real ZONES coordinates.
# Fonts do not degrade gracefully, and one of them is load-bearing:
#
#   Noto Sans Tamil UI renders the Tamil answers. docs/MOBILE_APP.md is
#   explicit that device Tamil fonts vary badly across Android OEMs, and
#   ORCA's actual users read Tamil on mid-range Android phones. A missing
#   Tamil face is not a styling regression, it is an unreadable answer.
#
# Google serves a different stylesheet per User-Agent (woff2 to modern
# browsers, ttf to old ones). We ask as a modern browser, then rewrite
# every gstatic URL in the returned CSS to a local path.
FONT_CSS_URL = (
    "https://fonts.googleapis.com/css2"
    "?family=Archivo:ital,wdth,wght@0,75,400;0,75,600;0,75,700"
    "&family=Public+Sans:wght@400;500;600"
    "&family=IBM+Plex+Mono:wght@400;500;600"
    "&family=Hind+Madurai:wght@400;500;600"
    "&family=Noto+Sans+Tamil+UI:wght@400;500"
    "&display=swap"
)
# Without this UA, fonts.googleapis.com returns ttf (several times larger)
# and a stylesheet with no unicode-range subsetting.
_MODERN_UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/126.0.0.0 Safari/537.36"
)
FONT_CSS_PATH = "fonts.css"
FONT_DIR = "fonts"


def fetch(url: str, user_agent: str = "orca-vendor/1.0") -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": user_agent})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read()


def vendor_fonts() -> int:
    """Download the font CSS and every face it references. Returns bytes."""
    css = fetch(FONT_CSS_URL, _MODERN_UA).decode("utf-8")
    urls = sorted(set(re.findall(r"url\((https://fonts\.gstatic\.com/[^)]+)\)", css)))
    if not urls:
        raise RuntimeError("font stylesheet referenced no gstatic files -- refusing to write a CSS with no faces")

    (VENDOR / FONT_DIR).mkdir(parents=True, exist_ok=True)
    total = 0
    for url in urls:
        # gstatic paths are already unique per family/weight/subset; the
        # last two segments keep that uniqueness without the full path.
        parts = url.rstrip("/").split("/")
        name = f"{parts[-2]}-{parts[-1]}" if len(parts) >= 2 else parts[-1]
        body = fetch(url, _MODERN_UA)
        (VENDOR / FONT_DIR / name).write_bytes(body)
        total += len(body)
        css = css.replace(url, f"./{FONT_DIR}/{name}")

    (VENDOR / FONT_CSS_PATH).write_text(css, encoding="utf-8")
    total += len(css.encode("utf-8"))
    print(f"  {total/1024:8.1f} KB  web/vendor/{FONT_CSS_PATH} + {len(urls)} faces")
    return total


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="verify every vendored file is present; download nothing")
    args = ap.parse_args()

    if args.check:
        missing = [name for name in FILES if not (VENDOR / name).is_file()]
        empty = [name for name in FILES
                 if (VENDOR / name).is_file() and (VENDOR / name).stat().st_size == 0]
        # The font CSS is generated, not listed in FILES, and a stylesheet
        # whose faces never downloaded is worse than none at all -- it
        # silently falls back to a device font, which for Tamil is the
        # failure this vendoring exists to prevent.
        css_path = VENDOR / FONT_CSS_PATH
        if not css_path.is_file():
            missing.append(FONT_CSS_PATH)
        else:
            faces = re.findall(r"url\(\./fonts/([^)]+)\)", css_path.read_text(encoding="utf-8"))
            if not faces:
                missing.append(FONT_CSS_PATH + " (references no local faces)")
            missing.extend(
                f"{FONT_DIR}/{f}" for f in faces if not (VENDOR / FONT_DIR / f).is_file()
            )
        if missing or empty:
            for name in missing:
                print(f"MISSING: web/vendor/{name}", file=sys.stderr)
            for name in empty:
                print(f"EMPTY:   web/vendor/{name}", file=sys.stderr)
            print("\nRun: python scripts/vendor_web_deps.py", file=sys.stderr)
            return 1
        n_faces = len(faces)
        total = sum((VENDOR / n).stat().st_size for n in FILES)
        total += css_path.stat().st_size
        total += sum((VENDOR / FONT_DIR / f).stat().st_size for f in faces)
        print(f"OK: {len(FILES)} vendored files + {n_faces} font faces present "
              f"({total/1024/1024:.1f} MB)")
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

    try:
        total += vendor_fonts()
    except Exception as exc:  # noqa: BLE001 -- reported, never swallowed
        print(f"FAIL fonts: {exc}", file=sys.stderr)
        return 1

    print(f"\n{len(FILES)} files + fonts, {total/1024/1024:.1f} MB total")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
