# ORCA Mobile — building and running the APK

Design and rationale live in [../docs/MOBILE_APP.md](../docs/MOBILE_APP.md).
This file is only how to build it and what to expect.

---

## What this is

**A wrapper around web/ would add nothing, so it does more than wrap.**
Four capabilities here are ones a browser tab genuinely cannot have:

| Feature | Why a web app can never do it |
|---|---|
| **Maritime boundary watch** | A foreground service compares GPS against the real India-Sri Lanka IMBL and warns aloud in Tamil **while the app is closed and the screen is off**. A page that is not open cannot run, cannot hold a wakelock, and cannot post a notification. |
| **Tamil speech out** | Android's on-device TTS keeps working with the radio off. The Web Speech API in an Android WebView commonly synthesises over the network, so it is exactly what dies at sea. |
| **Tamil speech in** | A crew steering in spray, at night, with wet hands, is not typing. |
| **SMS position** | GSM reaches far further offshore than mobile data. A browser cannot compose an SMS. |

### The boundary watch, in detail

This is the feature that justifies an APK existing. Tamil Nadu fishermen
are detained, and have been shot at, for drifting across an invisible,
unmarked line in the Palk Strait. ORCA already knows exactly where it
runs. The problem was never answering a question about it -- nobody opens
an app while their net is out. The problem is a boat drifting north-east
for two hours with the phone in someone's pocket.

`BoundaryWatchService` samples GPS every 30 s / 100 m, computes distance
to the boundary, and speaks a Tamil warning when the band changes. It
announces on a band CHANGE only: a warning repeated every thirty seconds
is a warning that gets ignored.

**It owns no safety constant.** Both the geometry and the 2 / 5 / 10 km
bands arrive in `GET /bundle`, read out of `orca/agents.py`
(`IMBL_URGENT_KM` and friends). Hardcoding them in Java would be a second
copy of a safety threshold, and the day the two disagree ORCA has no
defensible answer about which was right — `tests/test_bundle.py` asserts
they match. And note what it never does: it reports a **distance and a
band**, never GO or DO NOT GO. That verdict stays `orca/policy.py`'s.

### Permissions, and what is deliberately not requested

- `ACCESS_FINE_LOCATION` — the boundary watch only, off until the crew
  turns it on. The position is compared on-device and discarded; nothing
  is uploaded.
- **No `ACCESS_BACKGROUND_LOCATION`** — a foreground service with a
  permanent notification does the job, and the crew can always see it is
  running.
- **No `SEND_SMS`** — the distress message goes out through
  `ACTION_SENDTO`, which needs no permission, and puts the crew in the
  loop before anything sends. Android 15 hard-restricts `SEND_SMS` for
  sideloaded apps anyway, so a permission-based path would not work here.

### Tamil voice: what to check on a real phone

The `ta-IN` TTS voice is **not** installed by default on every handset. On
first launch the app logs which it got:

```
ORCA: Tamil TTS ready (on-device, works with no signal)
ORCA: Tamil TTS ready (WARNING: this voice synthesises over the network and will fail offline)
ORCA: Tamil (ta-IN) TTS voice is not installed on this device...
```

The middle line matters: a network-synthesised voice is exactly as useless
at sea as no voice, and you would only find out there. Install the offline
voice via **Settings › Accessibility › Text-to-speech › Install voice
data › Tamil**.

---

## What this is (the shell)

A **2.3 MB Android app** that is one Activity hosting a WebView over `web/`
— the same client the browser serves. No Capacitor, no Cordova, no npm, no
React Native. One dependency (`androidx.webkit`).

It answers safety questions **with no network, no backend, and no prior
launch**, because everything it needs ships inside the APK:

| What | Where it comes from |
|---|---|
| App shell (HTML/JS/CSS, three.js, MapLibre) | `web/`, referenced as an asset source |
| Fonts including Noto Sans Tamil UI | `web/vendor/fonts/`, 31 faces |
| An advisory for all 10 zones | `mobile/seed/bundle.json` |

Verified on an **OPPO CPH2591 (Android 15)** with both host servers killed:
*"Is it safe at Mandapam?"* → **SAFER ALTERNATIVE**, logcat reading
`ORCA offline: served the stored bundle`.

---

## Build

Needs the Android SDK (platform 34+, build-tools) and JDK 17.

```bash
cd mobile/android
ANDROID_HOME=$HOME/Android/Sdk gradle assembleRelease
```

Output: `mobile/android/app/build/outputs/apk/release/app-release.apk`

```bash
adb install -r mobile/android/app/build/outputs/apk/release/app-release.apk
adb shell am start -n org.orca.advisory/.MainActivity
```

**The APK is signed with the debug key.** It is a prototype for sideloading
and demo, not a Play Store artifact. Do not describe it as a release build.

## Watch what it is actually doing

Every fallback is logged, never swallowed (CLAUDE.md rule 2). This is how
you tell whether an answer on screen came from the network or from cache:

```bash
adb logcat -s ORCA:I
```

```
ORCA: could not refresh offline bundle: Failed to fetch     <- no backend, expected
ORCA offline: served the stored bundle — Failed to fetch    <- answered from cache
```

## Point it at a live backend

The app looks for `http://127.0.0.1:8000`. On a real handset that is the
phone's own loopback, so tunnel it to your laptop over USB:

```bash
python -m uvicorn orca.api:app --port 8000     # on the laptop
adb reverse tcp:8000 tcp:8000                  # phone's :8000 -> laptop's :8000
```

With that up, the app refreshes its advisory on launch (`GET /bundle`) and
`ASK ORCA` reaches the live `/ask`. Remove it (`adb reverse --remove-all`)
to test the offline path.

To change the address permanently, edit `API_BASE` in
[MainActivity.java](android/app/src/main/java/org/orca/advisory/MainActivity.java).

## Refresh the seed advisory

`mobile/seed/bundle.json` is what the app answers with on a phone that has
never had signal. It is real data with real timestamps, and the UI labels
it *"shipped with this app"* rather than claiming it was downloaded.

```bash
python -m data.fetch                                   # refresh the cache
python -m uvicorn orca.api:app --port 8000 &           # serve it
curl -s http://127.0.0.1:8000/bundle -o mobile/seed/bundle.json
```

Regenerate this before a demo. An APK built today ships today's readings;
one built last week ships last week's, says so, and is still honest — but
"collected 8 days ago" is not what you want on stage.

---

## Two decisions worth knowing before you change anything

### The assets are referenced, not copied

`app/build.gradle` adds `../../../web` as an asset source directory. There
is deliberately no copy of the frontend under `mobile/`. A copy would be a
second client that drifts from the first, and the two would eventually
disagree about what ORCA says — the same argument
[MOBILE_APP.md §2](../docs/MOBILE_APP.md) makes for never re-implementing
the verdict.

### `https://appassets.androidplatform.net`, never `file://`

The obvious way to load bundled HTML is
`webView.loadUrl("file:///android_asset/index.html")`. It would have
silently broken both halves of the offline story:

- `file://` has an **opaque origin**, and `web/offline.js` keeps the entire
  cached advisory in `localStorage`
- **service workers do not exist on `file://` at all**, so `web/sw.js`
  would never register

`WebViewAssetLoader` serves the same assets over an origin WebView treats
as a secure context, which restores both. A `file://` build would have
launched, looked perfect, and been useless at sea.

**Also:** a service worker's own script fetch does **not** go through
`WebViewClient.shouldInterceptRequest`. It consults `ServiceWorkerController`.
Wiring the asset loader into only the first one produced
`Failed to register a ServiceWorker ... An unknown error occurred when
fetching the script` on the device. Both are wired now.

---

## Known limitations

| Limitation | Detail |
|---|---|
| **Offline answers are English only** | Tamil comes from the LLM composer; `orca/phrase.py` has no Tamil templates. A Tamil question at sea gets an English verdict. Biggest remaining gap for the actual users. |
| `targetSdk 34`, not 35 | Android 15 forces edge-to-edge at 35 and two inset approaches both left the wordmark behind the status bar. Fine for sideloading; must be raised (and done properly) before any Play Store release. |
| Debug-signed | See above. |
| No basemap tiles offline | Third-party, many, large. The page falls back to its own SVG zone sketch drawn from the real `ZONES` coordinates. |
| Service worker is an optimisation, not the foundation | WebView is documented to sometimes drop registrations across restarts. The shell is inside the APK, so ORCA does not depend on it. |
| No location permission | Not requested at all. `MOBILE_APP.md` §5 treats boat tracks as personally identifying; a permission that is not requested cannot be misused. |
