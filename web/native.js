/* ORCA native bridge — the half of the mobile app that is NOT a web page.
 *
 * WHAT THIS FILE IS FOR
 * ---------------------
 * Wrapping a working web app in a WebView adds nothing; the criticism is
 * fair and this file is the answer to it. Every control it adds is one a
 * browser tab genuinely cannot offer:
 *
 *   MIC        Tamil speech recognition through the system recogniser.
 *              A fisherman steering in spray, at night, with wet hands,
 *              is not typing a question.
 *   SPEAKER    Tamil speech synthesis from the on-device TTS engine,
 *              which keeps working with the radio off. The Web Speech
 *              API in an Android WebView commonly routes through a
 *              network service, so it is exactly the thing that dies at
 *              sea.
 *   BOUNDARY   A background service watching GPS against the real
 *              India-Sri Lanka maritime boundary and warning aloud while
 *              the app is CLOSED. No page that is not open can do this.
 *   SMS        A pre-filled distress message with the boat's position.
 *              GSM reaches much further offshore than mobile data.
 *
 * EVERY control here is hidden in a browser. window.OrcaNative is
 * injected only by the Android shell (see OrcaBridge.java), so the web
 * client is byte-for-byte the page it always was -- this file adds
 * nothing to it and removes nothing from it.
 *
 * WHAT THIS FILE DOES NOT DO
 * --------------------------
 * It decides nothing. It speaks strings the server already produced and
 * forwards positions the device already measured. The boundary geometry
 * AND the 2/5/10 km bands both arrive from GET /bundle, read out of
 * orca/agents.py -- the app owns no safety constant of its own
 * (docs/MOBILE_APP.md §2).
 */
(function () {
  "use strict";

  var native_ = window.OrcaNative;
  if (!native_ || !native_.isNativeShell || !native_.isNativeShell()) {
    return; // a browser: the page stays exactly as it was
  }
  document.documentElement.setAttribute("data-native", "1");

  function lastRec() { return window.__ORCA_LAST_RECOMMENDATION__ || null; }

  function answerLanguage() {
    var rec = lastRec();
    return (rec && rec.detected_language === "ta") ? "ta" : "en";
  }

  /* ---------------------------------------------------------------
   * Speech OUT
   * ------------------------------------------------------------- */

  function speakAnswer() {
    var rec = lastRec();
    if (!rec || !rec.recommendation) return;

    // RECORDED TAMIL FIRST. web/voice.js plays clips a native speaker
    // from the coast actually said -- no TTS voice pack, no network
    // synthesis, right dialect. It falls back to the device TTS only if a
    // clip is missing AND the device really has a Tamil voice, and stays
    // silent otherwise. See web/voice.js for why that ordering matters.
    if (answerLanguage() === "ta" && window.ORCAVoice) {
      window.ORCAVoice.announce(rec).then(function (how) {
        if (how === "unavailable") {
          setStatus("No Tamil recordings or voice on this phone yet — "
                  + "the answer is on screen.");
        } else if (how === "tts") {
          setStatus("");
        }
      });
      return;
    }

    // English, or a browser with no recordings: the verdict text ONLY.
    // Coverage notes and evidence are not read aloud -- a spoken answer
    // competing with engine noise has to be one sentence, and the one
    // that matters is the verdict.
    native_.speak(rec.recommendation, answerLanguage());
  }

  /* Speak a new verdict automatically when the crew has asked for that.
   * Off by default -- an app that talks unprompted gets muted, and a
   * muted app cannot warn anyone. */
  var AUTO_KEY = "orca.speak.auto";
  function autoSpeakOn() {
    try { return localStorage.getItem(AUTO_KEY) === "1"; } catch (e) { return false; }
  }
  function setAutoSpeak(on) {
    try { localStorage.setItem(AUTO_KEY, on ? "1" : "0"); } catch (e) { /* reported below */ }
  }

  window.addEventListener("orca:recommendation", function () {
    if (autoSpeakOn()) speakAnswer();
  });

  /* ---------------------------------------------------------------
   * Speech IN
   * ------------------------------------------------------------- */

  // Called by OrcaBridge.deliverSpeechResult with the recognised text.
  window.__ORCA_VOICE_RESULT__ = function (text) {
    var input = document.getElementById("query-input");
    if (!input || !text) return;
    input.value = text;
    // Ask immediately. Someone who just spoke a question expects an
    // answer, not a form waiting for a second tap they cannot easily
    // make with wet hands.
    var ask = document.getElementById("ask-button");
    if (ask) ask.click();
  };

  window.__ORCA_VOICE_ERROR__ = function (reason) {
    // Never swallowed, and never turned into a guessed question.
    console.warn("ORCA: speech input unavailable:", reason);
    setStatus(reason === "no recogniser"
      ? "No speech recogniser on this phone — type instead."
      : "Could not hear that — type instead.");
  };

  /* ---------------------------------------------------------------
   * Boundary watch
   * ------------------------------------------------------------- */

  function boundaryFromBundle() {
    if (!window.ORCAOffline) return null;
    var bundle = window.ORCAOffline.loadBundle();
    return (bundle && bundle.boundary) || null;
  }

  function startWatch() {
    var boundary = boundaryFromBundle();
    if (!boundary) {
      // No geometry means no warning, NOT a guessed one.
      setStatus("No boundary data stored yet — connect once to download it.");
      return false;
    }
    if (!native_.hasLocationPermission()) {
      native_.requestLocationPermission();
      return false;
    }
    native_.startBoundaryWatch(JSON.stringify(boundary), answerLanguage());
    return true;
  }

  /* ---------------------------------------------------------------
   * SMS distress
   * ------------------------------------------------------------- */

  function distressMessage() {
    var lat = (document.getElementById("lat-input") || {}).value || "?";
    var lon = (document.getElementById("lon-input") || {}).value || "?";
    var rec = lastRec();
    var when = new Date().toISOString().replace("T", " ").slice(0, 16);
    // Position first. Whoever reads this on shore needs the coordinates
    // before anything else, and an SMS can be truncated.
    var lines = ["ORCA: " + lat + ", " + lon + " @ " + when + " UTC"];
    if (rec && rec.primary_zone && rec.primary_zone.name) {
      lines.push("Near " + rec.primary_zone.name + ".");
    }
    if (rec && rec.action) lines.push("Last advisory: " + rec.action + ".");
    lines.push("Sent from the ORCA app.");
    return lines.join(" ");
  }

  /* ---------------------------------------------------------------
   * UI
   * ------------------------------------------------------------- */

  var status;
  function setStatus(text) {
    if (status) { status.textContent = text || ""; status.hidden = !text; }
  }

  function button(id, label, title) {
    var b = document.createElement("button");
    b.id = id;
    b.className = "native-btn";
    b.type = "button";
    b.setAttribute("data-testid", id);
    b.innerHTML = label;
    b.title = title;
    b.setAttribute("aria-label", title);
    return b;
  }

  function build() {
    var host = document.querySelector("footer") || document.body;
    var bar = document.createElement("div");
    bar.id = "native-bar";
    bar.setAttribute("data-testid", "native-bar");

    var mic = button("native-mic", "&#127908;<span>Speak</span>", "Ask by voice in Tamil");
    mic.addEventListener("click", function () {
      setStatus("Listening…");
      native_.listen("ta");
    });

    var speaker = button("native-speak", "&#128266;<span>Read out</span>", "Read the answer aloud");
    speaker.addEventListener("click", speakAnswer);
    // Long-press toggles automatic reading, so the crew opts in rather
    // than being talked at.
    var pressTimer = null;
    speaker.addEventListener("touchstart", function () {
      pressTimer = setTimeout(function () {
        var on = !autoSpeakOn();
        setAutoSpeak(on);
        speaker.classList.toggle("active", on);
        setStatus(on ? "Answers will be read aloud automatically."
                     : "Automatic reading off.");
      }, 600);
    });
    speaker.addEventListener("touchend", function () { clearTimeout(pressTimer); });
    speaker.classList.toggle("active", autoSpeakOn());

    var watch = button("native-boundary", "&#128506;<span>Boundary</span>",
                       "Warn me if I approach the Sri Lanka maritime boundary");
    function paintWatch() {
      var on = native_.isBoundaryWatchRunning();
      watch.classList.toggle("active", on);
    }
    watch.addEventListener("click", function () {
      if (native_.isBoundaryWatchRunning()) {
        native_.stopBoundaryWatch();
        setStatus("Boundary watch off.");
      } else if (startWatch()) {
        setStatus("Boundary watch on — it keeps warning you with the app closed.");
      }
      setTimeout(paintWatch, 300);
    });
    paintWatch();

    var sms = button("native-sms", "&#128241;<span>SMS position</span>",
                     "Send my position by SMS (works without mobile data)");
    sms.addEventListener("click", function () {
      native_.sendSms("", distressMessage());
    });

    bar.appendChild(mic);
    bar.appendChild(speaker);
    bar.appendChild(watch);
    bar.appendChild(sms);

    status = document.createElement("p");
    status.id = "native-status";
    status.setAttribute("data-testid", "native-status");
    status.hidden = true;

    host.appendChild(bar);
    host.appendChild(status);

    if (!native_.canSpeakTamil()) {
      // Stated, not hidden. A Tamil speaker who taps "read out" and hears
      // English should know why, and it is fixable in Settings.
      setStatus("Tamil voice not installed on this phone — speech will be in English. "
              + "Install it in Settings › Text-to-speech.");
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", build);
  } else {
    build();
  }
})();
