"""Regenerate demo/scenarios.json from the REAL, currently running API.

Run this the morning of the actual presentation (and any time you want
to know what the four zones currently look like): sea state changes day
to day, so which zone shows the "safety overrides opportunity" conflict
can shift. This script never invents anything — it just calls the real
/ask endpoint and records exactly what it says.

Usage:
    source .venv/bin/activate
    uvicorn orca.api:app --host 127.0.0.1 --port 8010 &
    python scripts/generate_demo_scenarios.py --base-url http://127.0.0.1:8010
"""
import argparse
import json
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ZONES = [
    ("Zone A", 10.76, 79.84),
    ("Zone B", 10.85, 79.95),
    ("Zone C", 11.50, 80.20),
    ("Zone D", 12.80, 80.50),
]

OUT_PATH = Path(__file__).resolve().parent.parent / "demo" / "scenarios.json"

NOTE = (
    "This file is a REAL, live-verified transcript of the actual system's output, "
    "captured by querying the running API (not hand-written). Conditions are dynamic: "
    "re-run scripts/generate_demo_scenarios.py before the real presentation to refresh "
    "these against current sea state. Never hardcode a response path in orca/api.py or "
    "orca/planner.py keyed off these query strings -- the API must always compute live "
    "from data/cache/, or this file becomes exactly the 'hardcoded string' failure the "
    "war plan warns about (S8.4)."
)


def _post(base_url: str, path: str, body: dict) -> dict:
    req = urllib.request.Request(
        base_url + path, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"}, method="POST",
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())


def _get(base_url: str, path: str) -> dict:
    with urllib.request.urlopen(base_url + path) as resp:
        return json.loads(resp.read())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8010")
    args = parser.parse_args()

    scenarios = []
    for name, lat, lon in ZONES:
        response = _post(args.base_url, "/ask", {
            "query": f"Should I go fishing in {name} from Nagapattinam?",
            "lat": lat, "lon": lon,
        })
        scenarios.append({"query_zone": name, "lat": lat, "lon": lon, "response": response})
        print(f"  {name}: {response['action']} -> {response['recommendation']}")

    health = _get(args.base_url, "/health")

    out = {
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "note": NOTE,
        "health_at_capture": health,
        "scenarios": scenarios,
    }
    OUT_PATH.write_text(json.dumps(out, indent=2))
    print(f"\nWrote {OUT_PATH} with {len(scenarios)} scenarios.")


if __name__ == "__main__":
    main()
