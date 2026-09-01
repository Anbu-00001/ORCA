#!/usr/bin/env bash
#
# Install (or refresh) the hourly ORCA cache cron entry for THIS user.
# Idempotent: running it twice leaves exactly one entry.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MARK="# ORCA hourly marine cache refresh"
LINE="17 * * * * $ROOT/scripts/hourly_refresh.sh >/dev/null 2>&1  $MARK"

# Minute 17 rather than 0: every other cron on the machine fires on the
# hour, and Open-Meteo/IMD are likewise busiest then.
TMP="$(mktemp)"
crontab -l 2>/dev/null | grep -v "$MARK" > "$TMP" || true
echo "$LINE" >> "$TMP"
crontab "$TMP"
rm -f "$TMP"
echo "installed:"
crontab -l | grep "ORCA hourly"
