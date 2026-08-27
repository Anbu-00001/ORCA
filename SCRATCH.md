# SCRATCH.md — ORCA Build Log & Surprises

- Project initialized on 2026-08-26.
- Skeleton structure created following ORCA 32-Hour War Plan (v3).
- Verification complete: Clean commit history with single author.

## 2026-08-27 — 3D visualization build: real bathymetry source found; MOSDAC checked, blocked on auth

- Real seafloor relief source confirmed live and working:
  `oceanwatch.pifsc.noaa.gov/erddap/griddap/ETOPO_2022_v1_60s` (NOAA NCEI
  ETOPO 2022, 60 arc-second, "positive up" sign convention). Two other
  NOAA ERDDAP mirrors (`coastwatch.pfeg.noaa.gov`, `upwell.pfeg.noaa.gov`)
  timed out from this sandbox — don't assume any given NOAA ERDDAP host is
  reachable, check the specific one. Now wired up as
  `ERDDAPBathymetryFetcher` in `data/fetch.py`, cached to
  `data/cache/bathymetry/` (a *subdirectory*, deliberately — see that
  file's docstring for why it can't sit next to the point-observation
  cache files).
- Checked MOSDAC (ISRO's own ocean data portal) as a more India-specific,
  on-theme alternative/supplement, per teammate pointer:
  - `mosdac.gov.in`'s download API requires an authenticated account
    (`user_credentials` in a config file) for every dataset — no
    anonymous/public REST endpoint exists. Confirms what
    `MANUAL_TASKS.md` already flags: MOSDAC registration is a human task,
    not something scriptable in this session without credentials.
  - Their **Indian Mainland Coastal Product** (SARAL/AltiKa altimetry:
    sea surface height, significant wave height, wind speed, 5°N-24°N /
    68°E-90°E, ~180 m along-track) would be a genuinely better,
    India-specific replacement for some of what we currently pull from
    NOAA/Open-Meteo — but it's also login-gated, and its coverage is
    March 2013 - July 2016 (historical, not live), so it wouldn't replace
    the live wave-height feed used for GO/DO NOT GO decisions anyway.
    **Worth registering for post-hackathon**, not blocking anything now.
  - Also checked a teammate-suggested GitHub repo
    (`divya-m984/Oceanographic-Coral-reefs-preservation-and-prediction`):
    it's a coral-reef ML classifier trained entirely on **synthetic**
    sonar/sensor data (the authors say so themselves) and has no 3D/
    three.js visualization. Not usable here — would violate CLAUDE.md
    rule 1 if any of its data touched this repo, and there's no
    visualization technique to borrow either.
