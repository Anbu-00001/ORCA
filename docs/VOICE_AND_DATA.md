# What is live, what is fixed, and what we could not make real

**Written 2026-09-01.** This is the honest inventory. If a judge asks
"is that number real?", the answer for every screen is in here, including
the places where the answer is "no, and here is exactly why."

ORCA's first rule is that it never invents a marine number. That rule is
only worth anything if we can say precisely where each number came from —
so this file exists to be read out loud, not to be filed away.

---

## 1. Voice control — the five commands

### What is hardcoded

**The five phrases, and only the phrases.** `VoiceDemo.PHRASES` is a
closed set of Tamil/English stems:

| Intent | Tamil stems | English |
|---|---|---|
| SEA | `கடல`, `அலை` | sea, wave, how is the sea |
| FISH | `மீன`, `மீன்பிடி` | fish |
| STORM | `புயல`, `எச்சரிக்க` | storm, cyclone, warning |
| POSITION | `இடம`, `நான் எங்க`, `என் இடம` | position, where am i, location |
| HELP | `உதவ`, `அவசர` | help, sos, emergency, rescue |

This is a deliberate design choice, not a shortcut:

- **No model, no inference, no network of its own.** Whatever speech engine
  the phone has hands back a string; from there the behaviour is pure
  string matching and is fully unit-tested (`VoiceDemoTest`, 10 tests).
  "Voice navigation works" therefore does not rest on a claim about
  anyone's acoustic model.
- **Stems, not whole words,** because Tamil is agglutinative — a speaker
  may say `கடல்`, `கடலில்` or `கடல்நிலை`, and matching `கடல` catches all
  three.
- **An unrecognised phrase returns `null`, never the nearest match.** One
  of the five is a distress call. A misheard word must not be able to
  raise one. The screen says "NOT UNDERSTOOD" rather than guessing.

One real ambiguity was found by a test and fixed: `மீன் எங்கே?`
("where are the **fish**?") contains both `மீன` and `எங்க`, and the longer
stem won, resolving it to POSITION. A bare question word is not an intent —
the subject is. So POSITION now requires the self-reference `நான் எங்க`
("where am **I**").

### What is NOT hardcoded — every answer

| Command | Number shown | Where it comes from |
|---|---|---|
| கடல் எப்படி? | wave height, wind | `wave_height_m` / `wind_speed_kmh` in the advisory bundle, computed by `orca/policy.py` from Open-Meteo Marine + Forecast |
| புயல் இருக்கா? | count of warnings | IMD's CAP v1.2 public feed, with its own `fetched_at` printed under the number |
| நான் எங்கே? | coordinates | the GNSS receiver, with accuracy and fix age |
| மீன் எங்கே? | — | deliberately sends you to the live Fish zones screen (see §3) |
| உதவி! | — | opens SOS. **Speaking never sends a distress message** |

Measured on device, 2026-09-01: sea `0.7 m` / wind `4.9 kn`, storm `1`
warning sourced to IMD fetched `00:36Z`, position `12 54.75N 080 08.41E ±34 m`.
Every one of those came off the wire, not out of the source code.

Where ORCA has no value it prints an em-dash and a sentence explaining
why. It never prints a comforting default.

### Why "or tap it"

Each of the five is also tappable. A deck is loud, the recogniser wants a
network the boat may not have, and a wet screen still has to work. The tap
produces exactly the same card, so the voice path is a convenience layered
over a working app rather than the only way in — and a demo does not fail
because a room is noisy.

---

## 2. Data that is genuinely live

Fetched 2026-09-01T00:35Z, **24 minutes** before the build:

| Source | What it feeds | Status |
|---|---|---|
| Open-Meteo Marine | wave height, SST, currents | ✅ 60 observations |
| Open-Meteo Forecast | wind speed and direction, rain | ✅ 50 observations |
| IMD CAP v1.2 feed | storm warnings | ✅ 8 alerts fetched, 1 relevant |
| NOAA NCEI ETOPO 2022 | sea chart bathymetry | ✅ 4,760 grid points |
| Marine Regions (VLIZ / IOC-UNESCO) | India–Sri Lanka IMBL | ✅ 29 boundary points |
| Device GNSS | position, drift origin, geofences | ✅ live |
| Device accelerometer | "measure sea" | ✅ live |

Before this refresh the app shipped a bundle that was **53 hours old**. It
is now **0.4 hours old**. Anything stale is visible in the app: the home
card shows the reading age and turns red past 24 h.

---

## 3. What we could NOT make real — chlorophyll / fish zones

**This is the one honest gap, and it is worth explaining properly rather
than hiding.**

Fish zones need satellite ocean-colour (chlorophyll-a) from NOAA
CoastWatch ERDDAP, dataset `noaacwNPPVIIRSchlaDaily`. On 2026-09-01 it
could not be fetched. Diagnosed, not assumed:

1. **The server is failing.** Nine retries, every one a read timeout. A
   direct `curl` with 150 s of patience returned **HTTP 502 Bad Gateway
   after 62 s**. That is NOAA's gateway, not our timeout.
2. **A separate local problem:** `coastwatch.noaa.gov` and
   `coastwatch.pfeg.noaa.gov` resolve to IPv6 on this machine, and IPv6 is
   broken here — they fail instantly, while the IPv4-resolving mirrors
   (`upwell.pfeg.noaa.gov`, `oceanwatch.pifsc.noaa.gov`) answer `302`.
   Forcing IPv4 gets past that but still hits the 502 above.
3. **The dataset itself is a month behind.** A smaller 2-day query *does*
   succeed, and what comes back is dated **2026-07-31** — and every pixel
   over Chennai is `NaN`, i.e. cloud. So even a perfectly healthy fetch
   would currently yield month-old, fully cloud-masked data.

**What ORCA does about it:** nothing dishonest. The Fish zones screen is
network-only *by design* — there is deliberately no cached fallback,
because a PFZ is a claim about *today's* satellite pass and a stale one is
worse than none. With no data it says so. Zones the satellite could not
see through cloud render as **"Cloud — not seen"**, which is a third state,
distinct from "no fish". Six of ten zones had no cloud-free pixel in a
15-day window even when the service was healthy.

**Say this out loud in the demo.** "Six of ten zones were invisible to the
satellite, so we show that rather than guessing" is a stronger claim than
a screen full of confident green ticks, and it is the reason INCOIS's own
PFZ bulletins carry the same limitation.

**Not yet tried:** mirroring the query to `upwell.pfeg.noaa.gov` or
`oceanwatch.pifsc.noaa.gov` (both reachable), or Copernicus Marine as a
second source. Worth doing; it is a fetcher change, not an app change.

---

## 4. The trap that cost us a whole evening

**Installing the APK force-stops the app, and Android never auto-restarts
a force-stopped app.**

```
ActivityManager: Force stopping org.orca.advisory ... due to installPackageLI
Killing 6082:org.orca.advisory (adj 0)
```

Not `START_STICKY`, not `BOOT_COMPLETED`, not a foreground service — none
of them bring it back. It stays dead until a human opens it.

This is why the SOS trigger appeared broken for hours: every new build was
installed and then tested *without launching the app*, so the panic watch
had no process to run in. Verified directly — install, do not launch,
press the button: **zero log lines.**

It also silently **disables the accessibility service** on every install.

**Operational rule for teammates and judges: after installing ORCA, open
it once.** The SOS screen now shows a red "PANIC WATCH IS NOT RUNNING"
card when this has happened, and reads Android's own
`ApplicationExitInfo` to say *why* it stopped.

---

## 5. Screen-off SOS — what is possible and what is not

Researched and measured, because three separate approaches failed:

| Mechanism | Screen off? | Verdict |
|---|---|---|
| AccessibilityService `onKeyEvent` | ❌ | Android stops dispatching key events while the display sleeps. Measured: 5-second hold, zero callbacks |
| MediaSession `VolumeProvider` | ❌ | Known framework defect, Android 12–15. ORCA owned the media button session and still received nothing. **It also swallowed the volume keys**, and has been removed |
| `VOLUME_CHANGED_ACTION` broadcast | ❌ | Receiver confirmed registered; ColorOS never delivered a single one |
| Accessibility **shortcut** (hold both volume keys) | ⚠️ | Works screen-off, but it is a **toggle** — the second trigger disables the service, and re-enabling needs `WRITE_SECURE_SETTINGS`. Disqualifying |
| **Power button ×5** (screen-state broadcasts) | ✅ | **Works.** We do not intercept the key — that is impossible for any app — we count the `ACTION_SCREEN_ON/OFF` broadcasts the press causes |
| Lock-screen notification action | ✅ | Always available, two taps, no unlock |

Note for India: the 2017 DoT mandate means most phones sold here dial 112
on **3 rapid power presses**, implemented in OEM firmware. That is a real
safety net that already exists, and it may collide with a 5-press gesture
on some handsets — worth knowing before a demo.

The industry converges elsewhere: Noonlight ships a Canary BLE button,
Life360 uses Tile's physical button, and **ISRO's own answer for fishermen
(DAT-SG) is a dedicated external hardware transmitter, not a phone app.**
Reaching the same architectural conclusion ISRO did is a point in ORCA's
favour, not against it.

---

## 6. Nothing in the mobile app is mocked

Checked by grep across all Kotlin sources: no `Random`, no `Math.random`,
no `dummy`, no `mockData`, no `placeholder`, no `TODO`/`FIXME` stubs
feeding any displayed value.

Two things that *look* like fixed data and are not:

- **The Gulf of Mannar park box** (`Geofence.MARINE_PARK`) is an
  approximate box around the published island coordinate, not the park's
  full 560 km² boundary — that geometry is not publicly downloadable
  without a WDPA account. The screen says so in those words, because a
  crew told "you are clear of the park" by a box smaller than the park
  would be told a dangerous lie.
- **Boundary warning distances** default to 2/5/10 km in the app only so a
  malformed payload cannot crash it. The real values always come from the
  server.
