# TASK 2 — Make the storm warning wake the phone, and prove the relay works

**Difficulty: medium.** Android services, one government form, and a test
that needs a second phone.
**Estimated: 1–2 days.** Three independent pieces — 2A is the most
valuable, 2C needs no coding at all.

---

## Context you need first

On 30 August the cyclone half of SIH gap #7 was closed. IMD publishes its
warnings as **signed OASIS CAP v1.2 documents** in a public feed that
needs no key. They now ride out inside `/bundle`, polygons and all, and
the phone tests them against its own GPS fix with no network.

Built and tested:

| Piece | File | Tests |
|---|---|---|
| Fetcher | `data/fetch.py` → `IMDCapAlertFetcher` | via `/bundle` |
| Matcher (server) | `orca/alerts.py` | 17 in `tests/test_alerts.py` |
| Matcher (phone) | `StormAlerts.kt` | 20 in `StormAlertsTest.kt` |
| Screen | `NewScreens.kt` → `StormScreen` | verified on hardware |

**Read `orca/alerts.py`'s module docstring before changing any of it.**
The three-bucket split and the fourth state are the entire point:

- **covering** — unexpired, has a polygon, contains you. The only bucket
  that may raise an alarm.
- **ungeolocated** — unexpired, but IMD named no polygon. We cannot tell.
- **elsewhere** — real, live, but not over you. Carried with a distance
  so the screen never looks broken.
- **NOT CHECKED** — the feed was never fetched. **This is not "all
  clear".** If those two ever render the same way, that is the most
  serious bug this app can have.

---

# PART 2A — `WeatherWatchService`: warn with the app closed

**This is the highest-value piece in this document.**

## The problem

Today the storm warning only exists while someone is looking at the
screen. The Ockhi survivors' complaint was not that the warning was
wrong — it was that it never reached the boat. A warning nobody opens the
app to see has the same failure mode.

## What to build

A foreground service, modelled **exactly** on the one that already works:
`BoundaryWatchService.java`. Read it end to end first. It is a complete
worked example of everything you need — foreground notification, GPS
listener, Tamil text-to-speech, alarm-worthy notification channel.

1. New `WeatherWatchService.java` (or `.kt`).
2. On each location fix, run `StormAlerts.match(lat, lon, feed, now)`.
3. If `covering` is non-empty **and** contains an alert not yet
   announced → speak it in Tamil and post a high-priority notification.
4. Track announced alerts by their CAP `identifier`, so one warning
   speaks once and not every thirty seconds.
5. Re-announce **only** if the severity rises (Severe → Extreme).
   `StormAlerts.severityRank()` already orders these.

## Steal the alarm-fatigue logic — do not rewrite it

`BoundaryAlarm.kt` was written specifically to stop this app becoming one
people mute. It has 20 tests. Its rules:

- a state must hold for two consecutive fixes before it speaks
- it never repeats an announcement it has already made
- only genuine escalation re-speaks

Apply the same discipline here. **An alarm that fires forty times a day
is an alarm that gets turned off, and a muted alarm is the same as no
alarm.** Two independent research reviews named this as what kills
fishing apps.

## Tests (`WeatherWatchServiceTest.kt` — pure logic only)

Put every decision in a testable object like `BoundaryAlarm`, not inside
the Service class:

```
test_a_new_covering_alert_speaks_once
test_the_same_alert_does_not_speak_twice
test_a_severity_increase_re_speaks
test_a_severity_decrease_stays_silent
test_an_expired_alert_stops_being_announced
test_an_ungeolocated_alert_never_triggers_the_alarm
```

## Definition of done

Run it, do not just report it:

```bash
ANDROID_HOME=$HOME/Android/Sdk gradle -p mobile/android assembleRelease
adb install -r mobile/android/app/build/outputs/apk/release/app-release.apk
adb shell am start -n org.orca.advisory/.MainActivity
# start the watch, press HOME, then push a fake fix inside a CAP polygon:
adb shell appops set org.orca.advisory android:mock_location allow
```

Paste the `adb logcat | grep ORCA` output showing the announcement firing
with the app in the background.

---

# PART 2B — Get an IMD API key. **No code. Highest value per hour.**

The public CAP feed is IMD's **national** feed. On an ordinary day it
carries inland rainfall warnings and nothing over Tamil Nadu — which is
the correct and boring answer, and exactly what we saw in testing.

IMD's own API has far better endpoints. All verified real (see
`docs/RESEARCH.md` §6.2):

| Endpoint | What it gives |
|---|---|
| `/api/v1/seabulletin` | sea-area bulletins, issued by the Area Cyclone Warning Centre |
| `/api/v1/portwarning` | port warning signals |
| `/api/v1/coastalbulletin` | coastal forecasts by region |
| `/api/v1/cyclone_track` | live cyclone track |
| `/api/v1/cyclone_wind` | cyclone wind field |

**Access is by static-IP whitelisting**, applied for through
<https://api.imd.gov.in/public/index.php>. That is a form and a wait, not
code — which is why it should start **today** regardless of who does the
coding.

Contacts (verified correct, including the gmail address, which looks
wrong and is not):

- L1 · ISSD Technical Support · `rthnewdelhi4@gmail.com` · +91-11-24344325
- L2 · Dr. Sankar Nath, Sc-F · `sankar.nath@imd.gov.in`
- L3 · Dr. Kuldeep Shrivastav, Sc-F · `kuldeep.srivastava@imd.gov.in`

When the key arrives, add a fetcher to `data/fetch.py` following the
`IMDCapAlertFetcher` pattern. Every observation carries `source`,
`valid_time`, `confidence`, `provenance` — a fetcher that invents a field
will fail review.

## ⚠️ Lightning stays unbuilt. Do not "fix" this.

The PS names lightning. We do not have it, on purpose:

- IITM Pune's **Damini** network is real and has **no free public API**.
- Open-Meteo's **CAPE** field is convective instability, **not** a strike
  feed. Labelling it "lightning" is exactly the fabrication rule 1
  forbids.

If you cannot cite an endpoint you have personally fetched, **say so and
stop**. "We looked, here is why it is not possible" is a far stronger
answer to a judge than a stubbed feature they catch.

---

# PART 2C — Field-test the boat-to-boat relay. **Needs two phones.**

**This is the only claim in the whole project that cannot currently be
made.** `FleetRelay.kt` has 17 unit tests over its accept/reject rules and
the BLE code compiles and runs, but **no bundle has ever crossed from one
phone to another.** Only one device was available.

One of the three model reviews said flatly that BLE range at sea is "very
short" and the whole idea is not worth building (`docs/RESEARCH.md` §6.4).
That criticism is on the record. **This test is how it gets answered.**

## Do

1. Install the APK on two phones.
2. Put a **fresh** advisory on phone A (open it with the backend
   reachable) and an **old** one on phone B (install and never refresh —
   it will use the seed).
3. Open "Share with nearby boats" on both.
4. Record what happens at **1 m**, **10 m**, **50 m**, and **with a wall
   between**.
5. Write the honest range into `docs/HANDOFF.md`. If it is 15 m, write
   15 m. A small honest number beats a large unverified one.

## Do not touch

`isBetter` or `validateReceived` without reading `FleetRelayTest.kt`
first. A relayed verdict must stay **byte-identical** to the one the
server issued — that is the whole safety argument for the feature.

## What to check while you are there

- Does phone B actually take the newer bundle?
- Does phone A refuse the older one? (It should — a tie or an older
  bundle is rejected, because the transfer costs battery that has to last
  the trip.)
- Does the hop count increment and show on screen?
