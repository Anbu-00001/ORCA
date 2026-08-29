# ORCA Mobile — building and running the APK

Design and rationale live in [../docs/MOBILE_APP.md](../docs/MOBILE_APP.md).
This file is only how to build it and what to expect.

---

## What this is

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
