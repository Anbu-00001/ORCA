# ORCA — handoff for the team

**Written 2026-08-29, late. Read this before you write any code.**

One person built the current state in a single night. You are picking it
up tomorrow. This file exists so you do not have to guess what is done,
what is deliberately not done, and what will break the project if you
change it.

There are **four work packages** below (§5). Take one each. They are
written so that two people never need to touch the same file.

---

## 1. Read these five rules first. They are not style preferences.

They are in `CLAUDE.md` and they are why ORCA is defensible at all. A
judge will ask about every one.

1. **NO SYNTHETIC DATA.** Never generate, mock, simulate or fall back to
   placeholder marine data. If a source fails, raise loudly. An absent
   reading is a correct answer; a fabricated one destroys the whole
   claim. If you are ever about to write a plausible-looking number so a
   screen is not empty — stop, and make the screen say "no data" instead.
2. **No `except: pass`.** Ever. Log it or raise it.
3. **Every number a user sees is a `MarineObservation`** carrying source,
   valid_time, confidence and provenance. Bare floats get dropped.
4. **`orca/policy.py` contains no LLM calls.** Deterministic Python only.
   It is the safety guarantee.
5. **Do not modify `orca/schema.py` or `orca/policy.py`.** If you think
   you need to, you are solving the wrong problem — ask first.

Plus: **no new Python dependencies without asking**, and **the demo must
run with no network** (only `data/fetch.py` and `orca/agentic.py` may
touch the network).

**The single most important design rule for anything client-side:** the
phone and the web page NEVER compute a verdict. They render one that
`orca/policy.py` already decided. Two implementations of a safety rule is
two things that can disagree, and the day they disagree we have no
defensible answer about which was right.

---

## 2. What ORCA is, in one screen

```
data/fetch.py  ──►  data/cache/*.json  ──►  orca/planner.py  ──►  orca/policy.py
 (the only                (on disk,          (5 agents, no      (the verdict.
  fetcher)              never invented)       model, no net)     pure Python)
                                                    │
                                                    ▼
                                              orca/api.py
                              /ask  /bundle  /pfz  /evidence  /health  /bathymetry
                                        │                    │
                              ┌─────────┘                    └──────────┐
                              ▼                                          ▼
                         web/  (browser)                    mobile/android  (Kotlin)
```

- **`orca/agentic.py`** wraps `/ask` with an optional LLM for zone
  resolution and phrasing. With no API key it still works — that is what
  `orca/extract.py` (understanding) and `orca/phrase.py` / `phrase_ta.py`
  (answering) are for. Read `docs/CHATBOT.md`.
- **`web/`** is the browser client. Plain HTML/JS, no build step.
- **`mobile/android/`** is a real Kotlin + Jetpack Compose app. It is
  **not** a WebView any more — v1 was, and it was rightly called out for
  looking identical to the website.

---

## 3. Exactly where we stand against SIH26176

The problem statement's *Expected Solution* list, item by item. **Do not
take my word for any of these — verify before you claim one in a demo.**

| # | Asked for | State | Where |
|---|---|---|---|
| 1 | Understand user intent in natural language | **Done** | `orca/extract.py`, `orca/agentic.py` |
| 2 | Auto-detect language, reply in same language | **Done** (en + ta) | `extract.detect_language`, `orca/phrase_ta.py` |
| 3 | Contextual multi-turn conversation | **Done** (3 turns) | `orca/memory.py` |
| 4 | Autonomous discovery + integration of satellite/marine/met datasets | **Partial** | `data/fetch.py` — 4 real sources, but they are configured, not discovered |
| 5 | Spatial, temporal, contextual reasoning | **Done** | `orca/agents.py`, `orca/planner.py` |
| 6 | Explainable, evidence-based recs with maps/charts/geospatial viz | **Done** | evidence panel, 2D map, 3D ocean, Douglas ruler |
| 7 | Proactive safety alerts — weather, high waves, **lightning, cyclones** | **Done, except lightning** | waves/wind + IMD's own signed CAP warnings, matched to your GPS fix offline (`orca/alerts.py`, `StormAlerts.kt`). **Lightning stays unbuilt** — no free Indian feed exists; see `docs/RESEARCH.md` §6.4 |
| 8 | Geofencing for maritime boundaries and restricted waters | **Done** | `orca/agents.py` geofence_agent + `BoundaryWatchService.java` |
| 9 | **Route optimisation and operational planning** | **NOT DONE** | we currently *decline* route questions |
| 10 | Reliable recommendations with supporting evidence and reasoning | **Done** | `/evidence/{id}` on every number |
| 11 | Modular multi-agent architecture | **Done** | 5 agents in `orca/agents.py` |

**Two real gaps: #9 and #7.** They are work packages A and B below. #9 is
the more damaging one, because the PS names it explicitly and we
currently answer "ORCA has no route or navigation planning."

### What we have that the PS did not ask for (say this in the pitch)

Research on existing systems ([INCOIS PFZ](https://incois.gov.in/MarineFisheries/PfzAdvisory),
Ocean State Forecast, the SAMUDRA and Fisher Friendly apps, ISRO's
GEMINI receiver and DAT-SG distress transmitter) found real gaps we
happen to fill:

- **INCOIS reaches ~7 lakh fishermen by SMS**, but coverage dies 10–12 km
  offshore. That is precisely why ISRO built GEMINI — a *dedicated
  hardware receiver*. **ORCA works offline on a phone they already own.**
- **PFZ advisories are static maps/SMS.** ORCA answers PFZ questions
  conversationally, in Tamil, with the evidence attached — and marks a
  cloudy zone **unseen** rather than unproductive, which the existing
  advisories do not distinguish.
- **17 separate arrest incidents of Tamil Nadu fishermen in 2025** for
  crossing the IMBL. Nothing in the existing stack warns you *before* you
  cross, on a normal phone, with the app closed. Our boundary watch does.

---

- **Engine-failure drift box.** When the engine dies, the phone computes
  where the hull will be in 6/12/24 h using the Leeway model that
  underpins IAMSAR and INCOIS SARAT (Allen & Plourde, USCG CG-D-08-99),
  and hands the crew an SMS with the search box in it — offline. No
  Indian fishing app does this; SAR drift modelling is server-side
  everywhere.
- **IMD warnings matched to YOUR position, offline.** Not "there is a
  cyclone somewhere" — the phone runs point-in-polygon against IMD's own
  signed CAP geometry using its own GPS fix, with no network.
- **A sea chart that works with the radio off.** Every web map — ours
  included — fetches tiles from a server and is a grey rectangle out of
  coverage. The phone draws 4,760 NOAA ETOPO soundings, the India–Sri
  Lanka treaty line and IMD's warning outlines from geometry shipped
  inside the APK. Every pixel is a number with a source; nothing is a
  basemap picture. This is the single clearest answer to "why not just
  use the website".
- **The camera light flashing Morse SOS.** COLREGS lists flashes among
  the recognised signals of distress. A browser cannot drive the torch
  without HTTPS, a camera permission and an open MediaStream, and stops
  the moment the tab is backgrounded; Safari cannot at all. ORCA's keeps
  flashing with the screen off.
- **Today's verdict on the home screen.** A widget is zero actions at
  4 a.m. on a dark quay. No browser API can render live content on the
  Android launcher — a PWA shortcut is an icon.
- **Boundary alerts that use closing speed, not distance.** A boat
  working a net parallel to the IMBL at 4.9 km is not warned. A boat
  running at the line from 11 km is. This is the difference between an
  alarm crews keep on and one they mute.

## 4. How to run everything

```bash
# backend (needs the venv)
.venv/bin/python -m uvicorn orca.api:app --host 0.0.0.0 --port 8000

# web client
python3 -m http.server 8080 --directory web
# then open http://127.0.0.1:8080/index.html?api=http://127.0.0.1:8000

# refresh the marine data (the ONLY thing that touches the network)
.venv/bin/python -m data.fetch

# tests — run these before and after every change
GROQ_API_KEY= .venv/bin/python -m pytest tests/ -q     # 438 pass, 1 skip
npx playwright test                                     # 65 pass
```

**Android:**

```bash
cd mobile/android
ANDROID_HOME=$HOME/Android/Sdk gradle assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# point the phone at your laptop's backend over USB
adb reverse tcp:8000 tcp:8000
adb logcat -s ORCA:I        # every fallback is logged, never swallowed
```

> **If `adb` says `unauthorized`:** accept the USB debugging prompt on the
> phone and tick **"Always allow from this computer"**. Without that box
> it lapses every time the cable moves, and `adb install` then *hangs*
> rather than erroring. This cost an hour tonight.

---

## 5. Work packages — one each

Each is self-contained. The "do not touch" line is there so you do not
collide with each other.

---

### PACKAGE A — Route planning (SIH gap #9). **Highest value.**

**Why:** the problem statement explicitly lists "route optimisation and
operational planning". We currently answer *"ORCA has no route or
navigation planning."* A judge who reads the PS will ask about this.

**Do:**
1. Add `orca/route.py` — a **new module**, pure Python, no LLM. Given a
   start position and a destination zone, return a path as a list of
   waypoints plus the reason for each deviation.
2. It must avoid: the IMBL warning bands (geometry already in
   `data/cache/imbl/`), the Gulf of Mannar Marine National Park polygon
   (`PROHIBITED_ZONE` in `orca/agents.py`), and any zone whose verdict is
   `DO NOT GO`.
3. Expose `GET /route?from_lat=&from_lon=&to=<zone>` in `orca/api.py`.
4. Remove `"route"` from `_UNSUPPORTED_TERMS` in `orca/extract.py` and
   from `_UNSUPPORTED_NOTES` in `orca/agentic.py` **only once the
   endpoint actually works**.
5. Tests in `tests/test_route.py`: a route must never pass inside
   `IMBL_URGENT_KM` of the boundary, and never through the MPA polygon.

**Do not touch:** `orca/policy.py`, `orca/schema.py`, `orca/agents.py`.
Reuse `_haversine_km` and `_point_to_segment_km` from `agents.py` by
importing them; do not copy them.

**Honest scope note:** a great-circle path with obstacle avoidance is
enough. Do not attempt fuel optimisation — we have no fuel data and
inventing one breaks rule 1.

**Current is now available if you want it.** `data/fetch.py` caches
`ocean_current_velocity_kmh` and `ocean_current_direction_deg`, and
`/bundle` exposes both per zone under `drift_inputs`. `orca/drift.py`
shows how to use a current vector correctly, including the convention
trap: Open-Meteo gives **current** as the direction it flows TOWARD and
**wind** as the direction it blows FROM. Getting those the wrong way
round is a silent 180-degree error.

**LEGAL — put this on the screen, not just in a comment.** Formal route
guidance and hazard clearance are restricted to certified ECDIS under IMO
rules. If ORCA hands out a track and a boat hits an uncharted shoal off
Pamban, the wording on that screen matters. Present every output as an
**advisory vector**, never as a binding instruction, and state plainly
that pilotage stays the master's responsibility. `orca/drift.py` and
`DriftScreen` already carry that framing — copy it rather than inventing
new wording.

---

### PACKAGE B — ~~cyclone alerts~~ **DONE 30 Aug.** What is left is lightning.

**The cyclone/storm half is built and tested on hardware.** IMD publishes
its warnings as signed OASIS CAP v1.2 documents in a public,
unauthenticated feed. They ride out inside `/bundle`, polygons and all,
and the phone tests them against its own GPS fix with no network:

- `data/fetch.py` — `IMDCapAlertFetcher`
- `orca/alerts.py` — deterministic point-in-polygon, 17 tests
- `StormAlerts.kt` — the same rules on the phone, 20 tests
- `NewScreens.kt` — `StormScreen`

Read `orca/alerts.py`'s module docstring before you change any of it. The
three-bucket split (covering / ungeolocated / elsewhere) and the fourth
state (**not checked**, which is NOT "all clear") are the whole point.

**Lightning is deliberately NOT built, and should stay that way** unless
you find something the research did not. Both a review of the literature
and a review of what Indian fishermen actually use agree: IITM Pune's
Damini network is real, and has **no free public API**. Open-Meteo's CAPE
field is a convective-instability proxy, not a strike feed — labelling it
"lightning" would be exactly the fabrication rule 1 forbids.

**If you want to work on this, the honest jobs are:**

1. **Get an IMD API key.** `api.imd.gov.in` documents
   `/api/v1/seabulletin` (issued by the Area Cyclone Warning Centre),
   `/api/v1/portwarning`, `/api/v1/coastalbulletin`, `/api/v1/cyclone_track`
   and `/api/v1/cyclone_wind` — all more marine-specific than the public
   CAP feed. Access is by **static-IP whitelisting**, applied for through
   the portal. That is a form and a wait, not code, and it is the single
   highest-value non-coding task on this list. Contacts are in
   `docs/RESEARCH.md` §6.2 and are verified real.
2. **A `WeatherWatchService`**, modelled on `BoundaryWatchService.java`,
   so a warning that arrives while the app is closed still speaks in
   Tamil. The boundary watch is a working example of the whole pattern.
3. **Re-check the CAP feed during a live cyclone.** Everything here was
   tested in a quiet week: the feed carried six inland rainfall warnings
   and nothing over Tamil Nadu, which is the correct and boring answer.
   Nobody has yet watched it during an actual Bay of Bengal cyclone. Do
   that before anyone claims cyclone coverage on a slide.

**Do not touch:** `orca/policy.py`, `orca/schema.py`, `orca/alerts.py`'s
bucket logic.

---

### PACKAGE E — Field-test the boat-to-boat relay. **Needs two phones.**

The only claim in this project that cannot be made. `FleetRelay.kt` has 17
unit tests over its accept/reject rules and the BLE code runs, but no
bundle has ever crossed from one phone to another.

**Do:** install the APK on two phones, put a fresh advisory on one and an
old one on the other, open "Share with nearby boats" on both, and record
what happens — at 1 m, at 10 m, and with a wall between. Then write down
the honest range. One review of this idea said flatly that BLE range at
sea is "very short" and not worth building; that criticism is on the
record in `docs/RESEARCH.md` §6.4 and this test is how it gets answered.

**Do not touch:** the `isBetter` / `validateReceived` rules without
reading `FleetRelayTest.kt` first. A relayed verdict must stay
byte-identical to the one the server issued.

---

### PACKAGE C — Tamil audio + review. **No coding needed for most of it.**

**Why:** ORCA now answers in Tamil offline, but nobody who speaks Tamil
has checked the wording, and the app cannot speak yet.

**Do:**
1. Open `docs/TAMIL_REVIEW.md`. Every Tamil sentence the app can say,
   with its English meaning. **Get a native Tamil speaker from the coast**
   — Nagapattinam, Rameswaram, Thoothukudi — to read it and sign the
   table at the bottom. Check the negations twice: if a "do not go" can be
   read as "you may go", someone could take a boat out in a 2.5 m sea.
2. Open `docs/TAMIL_AUDIO_SCRIPT.md`. **19 clips, about 15 minutes of
   recording.** Same person records them into `web/audio/ta/` with the
   exact filenames given. Then run
   `python scripts/tamil_audio_plan.py` to rebuild the manifest.
3. If any sentence is wrong, fix it in `orca/phrase_ta.py`, regenerate
   both docs, and re-record. Never fix it only in the recording — the
   screen text and the audio must say the same thing.

**Why recorded and not text-to-speech:** the `ta-IN` voice is not on every
phone, and where it is it often synthesises over the network — useless at
sea. A recording is offline, in the right accent, and reviewed by
definition because a person said it.

**Do not touch:** anything in `orca/` except `phrase_ta.py`.

---

### PACKAGE D — Mobile app: finish and test on hardware

**Why:** the Kotlin app builds and its screens are written, but **it has
never run on a phone.** That is the biggest untested surface we have.

**Do:**
1. Install it and work through all six screens. Fix what is broken —
   expect layout bugs, this has not been seen on a real device.
2. **Verify the boundary watch actually fires.** You cannot sail to the
   IMBL, so use `adb emu geo fix <lon> <lat>` on an emulator, or
   temporarily widen the bands in `orca/agents.py` on your local machine
   only (never commit that).
3. Check `adb logcat -s ORCA:I` on launch. It tells you whether the Tamil
   TTS voice is on-device or network-synthesised. **A network voice is
   useless at sea and this is the line that tells you.**
4. Wire `web/voice.js`'s recorded clips into the Kotlin app once package C
   has produced them (`MediaPlayer` over `assets/audio/ta/`).

**Do not touch:** `orca/`, `web/`, `data/`. Everything you need is in
`mobile/android/`.

---

## 6. Things that are deliberately NOT bugs

Do not "fix" these. Each one is a decision with a reason.

| Looks wrong | Why it is right |
|---|---|
| Cloudy zones show "not seen", not "no fish" | VIIRS cannot see through cloud. 6 of 10 zones had no usable pixel in 15 days. Conflating them is rule 1. |
| `/pfz` has no offline fallback | A PFZ is a claim about *today's* satellite pass. A stale one is worse than none. The safety verdict always has a fallback; this deliberately does not. |
| `/bundle` never calls the LLM | 10 zones would be up to 20 calls and exhaust the free tier in one tap. The verdict is identical either way. |
| The seed advisory shows "shipped with the app", not an age | It was never downloaded to that device. Stamping install time would invent a moment nothing knows. |
| Zone names stay in Latin script inside Tamil answers | That is how they appear on charts, boat registrations and every sign at the landing centre. |
| The app asks for no `ACCESS_BACKGROUND_LOCATION` and no `SEND_SMS` | A foreground service with a visible notification does the job; SMS goes through `ACTION_SENDTO`, which needs no permission and puts the crew in the loop. |

---

## 7. Known bugs, with the honest severity

| Severity | Bug | Where |
|---|---|---|
| **High** | `_zone_by_substring` returns the FIRST zone in `ZONES` order that appears in the query. *"From Chennai down to Thoothukudi"* answers for Chennai and shows a green GO for a voyage ending at the roughest zone in the fleet. | `orca/planner.py:188` |
| Medium | Comparison questions are not repeatable — *"Which is safer, Mandapam or Rameswaram?"* answered "Karaikal" once and correctly the next time. | `orca/agentic.py` `_rank_zones` |
| Medium | A ranking question still returns an action badge, which is meaningless without a subject zone. | `orca/agentic.py` |
| Low | Misspellings (`karaikkal`) only resolve when the LLM is up; with no key they fall to nearest-by-GPS. | `orca/planner.py` |
| **Unknown** | The boat-to-boat relay has never run between two phones. Only one device was available. 17 unit tests cover the accept/reject rules; nothing covers the radio. | `mobile/android/.../FleetRelay.kt` |
| Low | The Tamil throughout has not been checked by a native speaker. | `orca/phrase_ta.py`, `docs/TAMIL_REVIEW.md` |

`docs/CHATBOT.md` §8 has the full write-up with reproduction steps.

**If you have spare time after your package, fix the High one.** It is
about ten lines: score every zone named in the query instead of taking
`ZONES[0]`, and flag multi-zone questions rather than silently picking.

---

## 8. Git

- **Never** add a `Co-Authored-By` trailer.
- Do not run `git add -A`. Stage only the files your package touches.
- Branch off `Dev-B`. One branch per package.
- Run both test suites before you push. If you break a test, fix the code
  or explain in the PR why the test was wrong — do not delete it.

---

## 9. Where the documentation lives

| File | What it covers |
|---|---|
| `docs/CHATBOT.md` | How `/ask` works, the deterministic floor, every failure mode, known defects |
| `docs/MOBILE_APP.md` | Mobile design, the offline model, the staleness trap, the uplink plan |
| `mobile/README.md` | Building and running the APK, native features |
| `docs/TAMIL_REVIEW.md` | Every Tamil sentence, for native-speaker sign-off |
| `docs/TAMIL_AUDIO_SCRIPT.md` | The 19 clips to record |
| `CLAUDE.md` | The hard rules |
| `API_CONTRACT.md` | Response shape both clients depend on |

---

## 10. If you only do one thing

Get **Package C** done — the Tamil review and the 19 recordings. It needs
no engineering, it takes under an hour of one person's time, and it turns
"we support Tamil" from a claim into something a judge can hear.
