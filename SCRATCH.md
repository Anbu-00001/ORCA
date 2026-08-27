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

## 2026-08-27 — replacing the 4 mock zones with real ones: what was checked

Direct feedback: "four mock zones ... THIS IS JUST CRAP, WE NEED ACTUAL
DATA." Researched real, checkable coordinate sources for Tamil Nadu
coastal fishing locations before touching `data/fetch.py`.

- **CMFRI's GIS-based inventory of 1,278 fish landing centres (359 in
  Tamil Nadu)** — eprints.cmfri.org.in/13606/ — is the right dataset in
  principle (physically-verified GPS coordinates, purpose-built for
  exactly this), but the actual coordinate PDF is "Restricted to
  Registered users only." Not usable without emailing
  cmfrilibrary@gmail.com and waiting. A real, scriptable public dataset
  worth revisiting post-hackathon if that access comes through.
- **INCOIS Potential Fishing Zone WebGIS** (incois.gov.in/MarineFisheries/
  PfzWebGis, incois.gov.in/gisserver/PFZ/) — real, live, ~1223 advisory
  nodes along the whole Indian coast, refreshed daily. WebFetch on these
  pages returns only the page shell (client-side JS renders the actual
  map/data), so no backend API endpoint was discoverable without
  inspecting real network calls in an actual browser — not attempted.
  This is the single most valuable real-time data source ORCA could ever
  plug into (it's literally the same kind of advisory ORCA produces, from
  the government agency that does it for real) — worth a manual
  browser-network-tab investigation by a teammate, not something to
  guess at.
- **Protected Planet / WDPA API** (api.protectedplanet.net) — 401
  Unauthorized without a token; the website's search/download UI didn't
  expose a token-free path either. Used a real, specific, named feature
  instead (Krusadai Island, 9.20°N 79.17°E, sourced from its own
  Wikipedia article) rather than trying to approximate the Gulf of
  Mannar park's full ~160km boundary without real boundary data — see
  `orca/agents.py`'s `PROHIBITED_ZONE` comment.
- **What worked:** every zone's coordinate is sourced from its own
  Wikipedia article (a couple from the more specific *fishing harbour*
  article, e.g. Rameswaram: 9.2811°N 79.3151°E specifically for "Rameswaram
  Fishing Harbour and Boat Jetty", not just the town). Verifiable,
  citable, real — just not from a single authoritative GIS dataset the
  way CMFRI's would have been.
- **OpenFreeMap** (openfreemap.org) confirmed working with zero
  friction: no API key, no signup, `https://tiles.openfreemap.org/styles/
  liberty` returns a real styled vector basemap. Replaced
  `demotiles.maplibre.org` in `web/index.html`.

## 2026-08-27 — verifying the "Authenticity & Feature Upgrade Plan" doc's endpoints

A teammate-sourced research doc claimed several Indian government API
endpoints as ready to use. Verified each one directly rather than trusting
the doc (it says to do this itself) -- two of its central claims turned
out to be wrong or incomplete in ways worth recording before anyone
builds on them:

- **`erddap.incois.gov.in`** (INCOIS's own ERDDAP -- the single biggest
  win the doc proposes) is real and live -- `curl -k` confirms a genuine
  INCOIS-issued cert (`CN=*.incois.gov.in`, issued by GlobalSign) -- but
  the server has a TLS chain misconfiguration: it doesn't send the
  GlobalSign intermediate certificate, so standard verification fails
  with "unable to get local issuer certificate". This is NOT something
  to work around with `verify=False` (a real security regression, ruled
  out) -- it needs either the missing intermediate bundled explicitly
  (attempted; `secure.globalsign.com` was itself unreachable from this
  sandbox to fetch it) or for INCOIS to fix their server. Blocked, not
  abandoned -- retry from an environment that can reach GlobalSign's
  cert repository.
- **`api.imd.gov.in`** (IMD's marine/cyclone/nowcast API) -- the doc
  claims "No key required on the endpoints I checked." Every single
  endpoint tested, including the most basic (`cityforecast`), returned
  `401 {"error":"API key missing"}`. The 28KB public API reference page
  contains zero mention of "key", "register", "contact", or "apisetu" --
  no visible self-service path to get one. Real blocker, needs a human to
  find IMD's actual key-issuance process (possibly a direct request to
  IMD, not a web form) -- added to MANUAL_TASKS.md.
- **Confirmed working, no auth needed:** Marine Regions WFS
  (`geo.vliz.be`, real India-Sri Lanka IMBL treaty-line geometry -- now
  wired into `orca/agents.py`'s `geofence_agent`, see below) and
  INCOIS's PFZ text advisory page (`incois.gov.in/MarineFisheries/
  TextDataHome`, real "Forecast Date"/"Valid upto" fields confirmed in
  the HTML) -- not yet ingested into the app, just verified reachable.
- **Google Fonts family names, verified before use, one was wrong as
  written:** "Archivo Condensed" is not a real family -- Archivo is a
  variable font and "condensed" is its `wdth` axis, requested as
  `family=Archivo:ital,wdth,wght@0,75,700` and applied via CSS
  `font-stretch: condensed`, not a separate `font-family` name. "Noto
  Sans Tamil UI" (which sounded like it might be an OS-only naming
  convention) is in fact a real, distinct Google Fonts family --
  confirmed by fetching its actual `@font-face` CSS.
- **A real bug caught before shipping:** the first draft of the Douglas
  sea scale table in `web/index.html` was off by one band -- it had the
  2.5 m boundary sitting between "Slight" and "Moderate" instead of the
  real "Moderate" and "Rough", which would have directly contradicted
  the whole point of the feature (that `WAVE_HARD_DENY_M` sits exactly
  on the real Douglas 4/5 boundary). Caught by re-checking the rendered
  band name for a known input (3.1 m) against the WMO table before
  trusting the first draft.
