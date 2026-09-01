#!/usr/bin/env bash
#
# Refresh ORCA's marine cache, once an hour, from cron.
#
# WHY THIS EXISTS. The APK ships a seed bundle, and that bundle is only as
# fresh as the last time somebody remembered to run the fetcher. It was
# found 53 HOURS old on a build that was about to be demonstrated, which is
# the kind of number a judge asks about. An hourly cron makes staleness a
# machine's problem instead of a person's.
#
# WHAT IT DOES NOT DO. It never invents a reading. data/fetch.py raises on
# a failed source and this script records that failure rather than papering
# over it -- a source that is down leaves the PREVIOUS cache in place,
# which is correct: an old real number beats a fresh fake one, and the app
# shows the true age either way.
#
# Install with:  scripts/install_cron.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="$ROOT/.venv/bin/python"
LOG="$ROOT/data/cache/refresh.log"
STAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

mkdir -p "$(dirname "$LOG")"
echo "=== $STAMP refresh starting ===" >> "$LOG"

if [ ! -x "$PY" ]; then
    echo "$STAMP FATAL: no interpreter at $PY" >> "$LOG"
    exit 1
fi

# 1. Pull every source. Exit code reflects the advisory-critical fetchers.
"$PY" "$ROOT/data/fetch.py" >> "$LOG" 2>&1
FETCH_RC=$?
echo "$STAMP fetch.py exit=$FETCH_RC" >> "$LOG"

# 2. Regenerate the seed bundle the APK ships, but ONLY if the API is up.
#    A half-written bundle.json is worse than a stale one, so it is written
#    to a temp file and moved into place atomically.
if curl -sf --max-time 20 http://127.0.0.1:8000/bundle -o "$ROOT/mobile/seed/.bundle.tmp"; then
    if "$PY" -c "import json,sys; d=json.load(open('$ROOT/mobile/seed/.bundle.tmp')); sys.exit(0 if d.get('zones') else 1)"; then
        mv "$ROOT/mobile/seed/.bundle.tmp" "$ROOT/mobile/seed/bundle.json"
        AGE=$("$PY" - <<PYEOF
import json, datetime
d = json.load(open("$ROOT/mobile/seed/bundle.json"))
t = datetime.datetime.fromisoformat(d["cache_fetched_at"])
now = datetime.datetime.now(datetime.timezone.utc)
print(f"{(now - t).total_seconds() / 60:.0f} min")
PYEOF
)
        echo "$STAMP seed bundle updated, readings $AGE old" >> "$LOG"
    else
        rm -f "$ROOT/mobile/seed/.bundle.tmp"
        echo "$STAMP WARN: /bundle returned no zones; keeping previous seed" >> "$LOG"
    fi
else
    rm -f "$ROOT/mobile/seed/.bundle.tmp"
    echo "$STAMP WARN: API not reachable on :8000; cache refreshed, seed unchanged" >> "$LOG"
fi

# Keep the log from growing without bound on a demo laptop.
tail -n 2000 "$LOG" > "$LOG.trim" && mv "$LOG.trim" "$LOG"
exit $FETCH_RC
