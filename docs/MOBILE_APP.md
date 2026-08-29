# ORCA Mobile — the connector between shore and boat

**Status:** phase 1 BUILT and verified on hardware. Written 2026-08-29,
updated the same day once steps 1-3 of §8 shipped.
**Audience:** whoever builds this, and any agent reading the repo cold.

> **What exists now.** ORCA is an installable PWA that answers safety
> questions with no network and no backend. Verified on an **OPPO CPH2591
> (Android 15)** over `adb reverse` with both host servers killed: the
> shell loaded from the service worker, the badge read OFFLINE, and
> "Is it safe at Mandapam?" returned **SAFER ALTERNATIVE / SEVERITY
> ADVISORY** from the stored bundle. Covered by `e2e/offline.spec.js`
> (8 tests) and `tests/test_bundle.py` (16 tests).
>
> Files: `orca/api.py` (`GET /bundle`), `web/offline.js`, `web/sw.js`,
> `web/manifest.json`, `web/icons/`, `web/vendor/fonts.css`.
>
> **Not yet built:** §5's uplink (steps 6-7), and Tamil offline text --
> see §10.

---

## 0. TL;DR

| Question | Answer |
|---|---|
| What is it? | A **client**, not a second brain. It renders verdicts; it never computes them. |
| Why "connector"? | It is the only component that crosses the shore↔sea boundary, in **both** directions: it carries the advisory *out* to a boat with no signal, and carries **observations** *back*. |
| Same repo? | **Yes — `mobile/` in this repo.** Reasoning in §7. |
| Build it in Flutter? | **Not first.** Ship a PWA from the existing `web/` in days; go native only when you need the radio, the GPS background service, or the fish-finder. §6. |
| Biggest risk | The uplink. Boat-reported readings entering the advisory path would destroy CLAUDE.md rule 1. §5 is the whole design for preventing that. |

---

## 1. Where it sits in the existing workflow

Today ORCA is three nested trust zones. The mobile app adds a fourth ring **outside** all of them, and adds one new inbound path:

```
                            ┌─────────────────────────────────────────┐
                            │  CORE      schema.py · agents.py ·      │
                            │            policy.py                    │
                            │  pure functions, frozen (N-5)           │
                            ├─────────────────────────────────────────┤
                            │  ORCHESTRATION   planner.py             │
                            │  no model, no network                   │
                            ├─────────────────────────────────────────┤
                            │  SHELL     agentic.py · memory.py       │
                            │  may call a model, fails closed         │
                            ├─────────────────────────────────────────┤
   data/fetch.py ─────────► │  API       api.py                       │
   (ingest, ahead of time)  │  /ask /evidence /bathymetry /health     │
                            └───────────────▲─────────────┬───────────┘
                                            │             │
                             ③ uplink       │             │  ① downlink
                             (quarantined)  │             ▼
                            ┌───────────────┴─────────────────────────┐
                            │  MOBILE  ← NEW, outermost ring          │
                            │  renders · caches · observes            │
                            │  decides NOTHING                        │
                            └─────────────────────────────────────────┘
                                       ② carried to sea, offline
```

**① Downlink (day one).** In harbour wifi the app pulls one advisory bundle and stores it. This is the same `POST /ask` the browser calls — no new reasoning, no new thresholds.

**② Offline at sea (the actual point).** Signal ends a few km out. Everything the crew needs is already on the phone. This is CLAUDE.md rule 8 restated for a device that is *genuinely* offline rather than simulating it.

**③ Uplink (the differentiator, and the dangerous part).** Every boat with a fish-finder is already measuring sea surface temperature and depth over a standard NMEA 0183 port, and nobody collects it. PRD F-11 names this as both a data asset and the second revenue path — cal/val data that ISRO and INCOIS currently pay moored buoys to gather. §5 is how to accept it without lying.

---

## 2. What the app must never do

These are inherited, not negotiable. They are the reason ORCA is defensible at all.

1. **Never compute a verdict.** No thresholds, no risk arithmetic, no "if wave > 2.5 show red" in Dart or JS. `GO / DO NOT GO / SAFER ALTERNATIVE / CANNOT ASSESS` arrive from the server, already decided. A second implementation of the safety rules is a second thing that can disagree with `policy.py`, and the day they disagree ORCA has no defensible answer about which was right.
2. **Never display a bare number.** Every value on screen is a `MarineObservation` field and must be tappable through to its `source`, `valid_time`, `confidence` and `provenance`. Same as rule 3, same as the web evidence panel.
3. **Never fabricate on failure.** No last-known-good silently redisplayed as current, no interpolation, no "approximately". Absent is a correct answer; invented is not.
4. **Never treat an unknown `action` as permission.** The web client had exactly this bug — an unrecognised verdict fell through to the GO colour. Default branch is the neutral/unknown state, always. (PRD R-25, amended.)
5. **Never ship a key.** The Groq key lives on the server. The app talks to ORCA's API and nothing else.

A reviewer should be able to `grep -r "2.5\|0.6\|RISK\|threshold" mobile/` and find nothing but tests.

---

## 3. Screens (minimum viable, in build order)

| # | Screen | Content | Notes |
|---|---|---|---|
| 1 | **Verdict** | The action word, huge. The one-sentence reason. The zone. | Colour from action, with the non-permissive default. Readable in direct sunlight on a boat — high contrast, large type. |
| 2 | **Evidence** | Every reading behind the verdict: value, unit, source, age, confidence. | Tap-through. This is the screen that makes ORCA ORCA; it is not optional polish. |
| 3 | **Cache status** | When the bundle was fetched. How old the readings are. What is missing. | Must show *real* age, computed from `fetched_at` against the device clock — see §4.3, this is a known trap. |
| 4 | **Ask** | Free-text / voice-to-text question in Tamil or English. | Requires signal. Offline, it degrades to the cached bundle's verdict and says so. |
| 5 | **Observe** | Log a reading (§5). | Phase 2. |

Deliberately **not** in v1: routing, navigation, tide tables, catch logging, species ID. ORCA has no data for any of them and the chatbot already declines them by name (`UNSUPPORTED_KINDS`). The app must decline them identically, or the two clients disagree about what the product is.

---

## 4. The offline model

### 4.1 What gets carried

One **advisory bundle**, fetched in harbour:

```jsonc
{
  "fetched_at":  "2026-08-29T05:12:00Z",
  "valid_until": "2026-08-29T17:12:00Z",   // server-stated, not client-guessed
  "zones": [ /* one /ask response per zone the crew selected */ ],
  "bathymetry": { /* optional, only if the 3D view ships on mobile */ }
}
```

Size, measured on the current cache: the advisory working set is **126.5 KB** for all ten zones; bathymetry is a further **496 KB**. A whole day's bundle fits comfortably in under 1 MB — small enough to pull over a marginal 2G link in harbour.

### 4.2 A new endpoint is needed

The app should not fire ten separate `POST /ask` calls over a bad link. Add:

```
GET /bundle?zones=Rameswaram,Mandapam,Thoothukudi
  -> { fetched_at, valid_until, zones: [ <full /ask response>, ... ] }
```

It must be a **pure fan-out over the existing `build_recommendation()`** — no new reasoning, no new thresholds, no second code path. If `/bundle` can ever produce a verdict `/ask` would not have produced for the same zone, it is wrong. That property is worth an explicit test.

### 4.3 The staleness trap — read this before writing the age display

`freshness_min` on every `MarineObservation` is computed **at fetch time** as `fetched_at − valid_time`. It means *"how old was this reading when we collected it"*. **It does not grow while the bundle sits on the phone.**

This is not hypothetical. Measured on the web client on 2026-08-29: with a cache 2 days old, the evidence panel still displayed *"14 h old"*, because it rendered `freshness_min` directly. A phone at sea for three days would confidently show minutes-old ages for three-day-old data.

**The app must display two distinct things:**

| Field | Meaning | Source |
|---|---|---|
| *"measured 14 h before download"* | `freshness_min` | server, static |
| *"downloaded 3 days ago"* | `now − fetched_at` | device clock |

The second is the one that matters at sea, and the server cannot compute it. Show both, label both. If the device clock is unset or absurd (before the bundle's `fetched_at`), say *"age unknown"* rather than print a negative or a guess — the web client's `formatAge()` already takes exactly this position and the mobile app should copy it.

### 4.4 Expiry

A bundle does not silently expire, and it does not silently persist either. Past `valid_until` the verdict stays visible — an old answer beats no answer on a boat — but it is visibly marked stale and the age is stated in the largest type on the card. ORCA's position throughout is that an old reading is usable if and only if it is *labelled* old.

---

## 5. The uplink — how to accept boat data without destroying rule 1

This is the part that turns ORCA from a data consumer into a data producer, and it is the part most likely to be got wrong.

**The rule that must survive:** an observation that reaches `policy.py` carries a source, a validated provenance and a confidence that means something. A reading typed in by a fisherman, or read off an uncalibrated transducer, does not meet that bar — and quietly mixing it into `data/cache/` would be exactly the fabrication rule 1 forbids, only with extra steps.

**The design:**

```
phone ──POST /observations──►  observations/inbox/   (quarantine, on disk)
                                       │
                                       │  NOT read by planner.py
                                       │  NOT in load_cached_observations()
                                       ▼
                               human / automated QC
                                       │
                                       ▼
                          research export  ·  cal-val dataset
```

1. **A separate store.** `data/observations/`, never `data/cache/`. `load_cached_observations()` globs `data/cache/*.json` and must continue to see nothing from boats.
2. **A separate source name.** `"ORCA Fleet (unverified)"`, with `confidence` capped low and provenance recording device, app version and whether the value was typed or instrument-read.
3. **It does not feed the advisory. At all. In v1.** Not weighted, not blended, not used as a tiebreak. The value of this data is as an *independent* record — the moment it feeds the forecast that produced it, it stops being independent and stops being worth anything to a researcher.
4. **Position provenance matters.** GPS fix accuracy, and whether the position was fixed or entered by hand, are part of the observation. An SST reading with an unknown position is not a measurement.
5. **Consent and privacy are load-bearing, not paperwork.** Boat tracks are commercially sensitive and personally identifying — where someone fishes is their livelihood. Opt-in per upload, coarse position by default, and an explicit statement of what is shared. PRD marks LOCATION PRIVACY as PLANNED; this is where it becomes real.

**Why a researcher would want it:** the Bay of Bengal is chronically under-sampled in situ. Satellite SST is skin temperature and cloud-limited — the current cache has six zones with no cloud-free chlorophyll pixel in 15 days. A fleet of a few hundred boats reporting bulk SST at depth, with timestamps and positions, is a validation dataset that does not currently exist. That is a real contribution, and it is only a contribution if the provenance is clean enough to trust.

---

## 6. Technology — and why not Flutter first

### Phase 1: PWA (recommended start, days not weeks)

`web/` is already a complete, working, offline-capable client with no build step. Making it installable is:

- a `manifest.json` (name, icons, `display: standalone`)
- a service worker caching `web/vendor/`, the app shell and the last bundle
- touch-target and sunlight-contrast passes on the existing CSS

**What this buys immediately:** installable from a link with no Play Store review, one codebase, and the entire evidence panel, Tamil rendering, Douglas ruler and 3D view for free. `web/vendor/` was just vendored (2.8 MB, §8), so the app shell is already fully self-contained — which is precisely what a service worker needs.

**What it cannot do:** background location, NMEA/Bluetooth to a fish-finder, NavIC raw messaging, reliable background sync, push alerts.

### Phase 2: Flutter (when, and only when, phase 1's ceiling is hit)

Go native at the first hard requirement from that list — realistically the fish-finder link (§5) or NavIC (PRD F-10). Flutter over React Native for one specific reason: a single high-performance canvas surface, which matters if the 3D view or the Douglas ruler come along.

**Do not** start here. A Flutter rewrite before there is a validated uplink spends the team's scarcest resource on a UI that already exists in a working form.

### Fixed decisions either way

| Concern | Decision |
|---|---|
| Language | Tamil + English at parity. The backend already detects and answers in Tamil. |
| Fonts | Bundle Noto Sans Tamil UI. Do not rely on device fonts for Tamil — rendering varies badly across Android OEMs. |
| Storage | SQLite (or IndexedDB in the PWA). One table per bundle, one per queued observation. |
| Clock | Never trust it for anything but the *display* of relative age, and handle it being wrong (§4.3). |
| Auth | None in v1. There are no accounts. Do not add them before there is a reason. |

---

## 7. Same repo or separate? — **same repo**

Put it in `mobile/` here. The deciding argument is the contract.

**For one repo:**

- **The API contract is the whole coupling, and it changes.** `API_CONTRACT.md` and `orca/planner.py`'s `to_dict()` define 22 response fields. In one repo a contract test runs across both sides and a field rename fails CI. In two repos it fails silently, and you find out on stage. This team has already lost a demo to a failure that was invisible until it was live; do not build a second one.
- **Atomic changes.** Adding `/bundle` (§4.2) touches `orca/api.py`, `API_CONTRACT.md` and the client together. That is one commit or three repos' worth of coordination.
- **Six people, one deadline.** Cross-repo version skew is an operational cost this team has no capacity for. The branch history here already shows merge strain — `Dev-B`, `Dev-c`, `web`, `ui-polish-ocean`, and a root commit with unrelated history. More repos makes that worse, not better.
- **The app is meaningless alone.** It has no independent release, no independent users, and no reason to be versioned separately.

**The real cost, stated honestly:** native build artifacts are large and noisy. Gradle caches, `.dart_tool/`, Pods, `build/` — these must be gitignored **before** the first mobile commit, not after. Add to `.gitignore`:

```gitignore
# Mobile build artifacts
mobile/build/
mobile/.dart_tool/
mobile/.gradle/
mobile/android/.gradle/
mobile/android/app/build/
mobile/ios/Pods/
mobile/**/*.iml
*.apk
*.aab
*.ipa
```

Revisit only if the app gets its own release cadence and its own users. It has neither now.

### Layout

```
ORCA/
├── orca/            # unchanged — the safety core
├── data/            # unchanged — ingest + cache
├── web/             # unchanged — becomes the PWA in phase 1
│   └── vendor/      # already self-contained, 2.8 MB
├── mobile/          # NEW
│   ├── README.md    # how to build and run
│   └── ...
├── docs/
│   └── MOBILE_APP.md   # this file
└── API_CONTRACT.md  # the shared spec — the reason this is one repo
```

---

## 8. Build order

| Step | Deliverable | Gate |
|---|---|---|
| 1 | `GET /bundle` + contract test | For every zone, `/bundle`'s verdict is byte-identical to `/ask`'s |
| 2 | PWA manifest + service worker | Airplane mode: app opens, shows last bundle, states its real age |
| 3 | Dual-age display (§4.3) | A 3-day-old bundle reads "downloaded 3 days ago", not "14 h old" |
| 4 | Sunlight/thumb pass | Legible at arm's length in direct sun; every control reachable one-handed |
| 5 | Tamil parity | Every screen renders in Tamil with the bundled font, no clipping |
| 6 | `POST /observations` → quarantine | `load_cached_observations()` provably cannot see an uploaded reading |
| 7 | Consent + coarse position | Nothing uploads without explicit per-upload opt-in |
| 8 | Native shell | Only on a hard requirement from §6 |

Steps 1–5 are the demo. 6–7 are the research contribution. 8 is next year.

---

## 9. Open questions for a human

1. **Which zones does a crew subscribe to?** All ten (simplest, ~127 KB) or a chosen few? All ten is probably right and makes the "safer alternative" answer meaningful offline.
2. **How long is a bundle valid?** The server must state `valid_until`. What sets it — the forecast horizon, or a fixed 12 h?
3. **Does the 3D view ship on mobile?** It is 496 KB of bathymetry plus a WebGL surface on a mid-range Android. It is also the most persuasive screen. Suggest: yes in the PWA, behind an explicit "load 3D view" tap.
4. **Who runs QC on uploaded observations (§5)?** Without an answer, step 6 produces a growing pile of data nobody can vouch for — which is worse than not collecting it.
5. **NavIC.** PRD F-10 wants it and it needs hardware. Is anyone sourcing a receiver, or does this stay a roadmap line?
