# ORCA for your phone — read this before you install

**File:** `orca-3.0-storm-drift.apk` (10.7 MB, versionCode 3)
**Built:** 30 August 2026 · **Tested on:** OPPO CPH2591, Android 15

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
| Ask in Tamil, by voice | partly | Recogniser may want a language pack |

The advisory shipped inside the APK was collected **30 Aug 2026, 00:45**.
The app always shows its real age on the home screen. It never hides it.

---

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
ANDROID_HOME=$HOME/Android/Sdk gradle -p mobile/android testDebugUnitTest   # 73 tests
python -m pytest -q                                                          # 477 tests
```

Output lands at
`mobile/android/app/build/outputs/apk/release/app-release.apk`.

---

## Where to read more

- `docs/HANDOFF.md` — what is left to do, split into work packages
- `docs/RESEARCH.md` §6 — the fact-check of the ChatGPT/Gemini/Grok
  answers, including which recommendations turned out to be impossible
- `docs/MOBILE_APP.md` — architecture, and why the client owns no
  thresholds
- `docs/CHATBOT.md` — the agentic layer and its failure modes
