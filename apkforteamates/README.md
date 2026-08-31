# ORCA for your phone — read this before you install

**File:** `orca-4.0-chart-signal.apk` (10.8 MB, versionCode 4)
**Built:** 31 August 2026 · **Tested on:** OPPO CPH2591, Android 15

---

## Install

1. Copy the APK to the phone (USB, or any file transfer).
2. Open it. Android will say the app is from an unknown source — allow it
   for your file manager. It is signed with a debug key on purpose: this
   is a prototype for sideloading, not a Play Store build.
3. Open **ORCA**. It works immediately, with no network and no login.

If an older ORCA is already installed, uninstall it first. Version 3 adds
fields to the stored advisory and a clean install avoids any confusion
about what you are looking at.

### If you see the website instead of this app

Then you are opening a **PWA shortcut**, not the APK. It has the same
icon and the same name and it is a browser in a costume. Delete that
shortcut. The real app has no address bar, ever.

---

## What it does with no signal at all

Everything below was tested in **airplane mode**, on a fresh install,
with the backend switched off:

| Screen | Works offline | Notes |
|---|---|---|
| Can I go out today? | yes | Verdict from `orca/policy.py`, computed on shore |
| Where are the fish? | yes | PFZ from satellite chlorophyll |
| Sea boundary warning | yes | GPS vs the India–Sri Lanka IMBL, app closed |
| **Is a storm coming here?** | yes | IMD warnings, tested against your GPS fix |
| **Engine dead — where will I drift?** | yes | Leeway drift box + SMS |
| Emergency — send my position | yes | SMS, needs no data |
| Warn another boat by SMS | yes | |
| Measure the sea | yes | Accelerometer |
| Share with nearby boats | **untested** | Needs two phones. See below. |
| **Sea chart** | yes | Real soundings + boundary + warnings, drawn with no tiles |
| **Distress light** | yes | Camera flash blinks Morse SOS — needs no signal at all |
| Ask in Tamil, by voice | partly | Recogniser may want a language pack |

Plus a **home-screen widget**: long-press the home screen → Widgets → ORCA.
Today's verdict, its zone and its real age, without opening anything.

The advisory shipped inside the APK was collected **30 Aug 2026, 00:45**.
The app always shows its real age on the home screen. It never hides it.

---

## The five things a web app cannot do

If anyone says the mobile app is thin, this is the answer. None of these
are possible in a browser, at any effort:

| Feature | Why a browser cannot |
|---|---|
| **Sea chart offline** | Every web map fetches tiles from a server. Out of coverage MapLibre is a grey rectangle. This draws 4,760 NOAA soundings and the treaty boundary that live *in the APK* — as good 60 km out as alongside |
| **Home-screen widget** | No browser API renders live content on the Android launcher. A PWA shortcut is an icon that opens a browser |
| **Distress light** | Torch needs HTTPS, a camera permission, an open MediaStream, and stops dead when the tab is backgrounded. Safari has no support at all. Here it keeps flashing with the screen off |
| **Boundary watch with the app closed** | A killed tab gets no GPS. A foreground service does |
| **SMS** | A browser cannot pre-fill and hand off an SMS |

## The two things you must not misread

**1. "NOT CHECKED" is not "all clear".**
On the storm screen, three states are deliberately different:

- green *"NO IMD WARNING OVER THIS POSITION"* — the feed was checked, IMD
  has published nothing covering you.
- grey *"NOT CHECKED"* — ORCA never fetched the feed onto this phone. It
  knows nothing. This is **not** reassurance.
- amber/red — a real IMD warning covers you.

If you ever see those rendered the same way, that is a bug and it is the
most serious kind this app can have.

**2. The drift box is an estimate, not a fix.**
It holds wind and current at the last downloaded values. At 6 hours near
the coast that is defensible. At 24 hours the screen says, in as many
words, that it is a direction and not a forecast. Always give the Coast
Guard your **current** position as well.

---

## What is not proven

**The boat-to-boat relay has never run between two phones.** Only one
device was available. The accept/reject rules have 17 unit tests and the
radio code compiles and runs, but nobody has watched a bundle cross from
one boat to another. Say "designed and unit-tested", never "works".

**The Tamil has not been reviewed by a native speaker.** See
`docs/TAMIL_REVIEW.md` — it is a checklist waiting for a person.

---

## Rebuilding it yourself

```bash
# refresh the advisory the APK ships with (needs network, once)
python -m data.fetch
python - <<'PY'
import json
from fastapi.testclient import TestClient
from orca.api import app
json.dump(TestClient(app).get('/bundle').json(),
          open('mobile/seed/bundle.json','w'), ensure_ascii=False, indent=1)
PY

# build
ANDROID_HOME=$HOME/Android/Sdk gradle -p mobile/android assembleRelease

# the tests that matter, none of which need a phone
ANDROID_HOME=$HOME/Android/Sdk gradle -p mobile/android testDebugUnitTest   # 88 tests
python -m pytest -q                                                          # 477 tests
```

Output lands at
`mobile/android/app/build/outputs/apk/release/app-release.apk`.

---

## Pick up a task

Three work packages, hardest first. Take one, do not split one.

| File | What | Effort |
|---|---|---|
| `TASK-1.md` | Route planning (the last SIH capability we lack) **and** a live High-severity safety bug where a two-zone question answers for one zone and shows green | 2–3 days |
| `TASK-2.md` | Make the storm warning speak with the app closed; apply for an IMD API key; field-test the boat-to-boat relay | 1–2 days |
| `TASK-3.md` | Get the Tamil reviewed by a native speaker, record the 18 audio clips, verify the demo end to end, fix three small real bugs | half a day |

**If you only do one thing, do TASK-3 Part 3A.** Every Tamil sentence in
this app — including the ones that say *do not go out* — was written
without a native speaker checking it. A negation that reads the wrong way
is the only bug here that could actually hurt somebody.

---

## Where to read more

- `docs/HANDOFF.md` — what is left to do, split into work packages
- `docs/RESEARCH.md` §6 — the fact-check of the ChatGPT/Gemini/Grok
  answers, including which recommendations turned out to be impossible
- `docs/MOBILE_APP.md` — architecture, and why the client owns no
  thresholds
- `docs/CHATBOT.md` — the agentic layer and its failure modes
