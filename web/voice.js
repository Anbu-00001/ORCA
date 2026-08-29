/* ORCA spoken output — pre-recorded Tamil, not synthesis.
 *
 * WHY RECORDINGS AND NOT TEXT-TO-SPEECH
 * -------------------------------------
 * The first version of this spoke the verdict through Android's TTS
 * engine. That was worse on every axis that matters here:
 *
 *   - The ta-IN voice is NOT installed on every handset, and when it is,
 *     it is often flagged for NETWORK synthesis. A voice that needs a
 *     network is exactly as useless at sea as no voice at all, and the
 *     crew would only find that out at sea.
 *   - Synthesised Tamil is Modern Standard Tamil read by a machine.
 *     ORCA's users speak coastal Tamil. It is understandable and it
 *     sounds like a form being read back at you.
 *   - Nobody reviewed it. A synthesised sentence is generated at runtime,
 *     so no human ever hears it before a fisherman does.
 *
 * A recording fixes all three at once, and it is affordable BECAUSE THE
 * OUTPUT SET IS CLOSED. ORCA has four verdicts, ten zones and three
 * boundary bands. That is around twenty clips, recorded once by a native
 * speaker from the coast, and then every phone says exactly what that
 * person said -- offline, in the right dialect, reviewed by definition.
 *
 * COMPOSITION
 * -----------
 * A clip per zone plus a clip per verdict, played back to back as an
 * announcement:  "மண்டபம்."  ...  "போக வேண்டாம்."
 * Announcement order is deliberate: it is how a harbour PA or a radio
 * call is phrased, it keeps the recording count at 10 + 4 rather than
 * 10 x 4, and each fragment stays a grammatical whole.
 *
 * FAILING HONESTLY
 * ----------------
 * If a clip is missing, this NEVER plays a different one and never
 * guesses. It falls back to the native TTS if the shell offers it, and
 * otherwise stays silent and says so. Playing the wrong verdict aloud is
 * the worst thing this file could do.
 */
(function (global) {
  "use strict";

  var BASE = "audio/ta/";
  var manifest = null;          // null = not loaded yet, {} = none available
  var current = null;           // the Audio element playing right now

  function load() {
    if (manifest !== null) return Promise.resolve(manifest);
    return fetch(BASE + "manifest.json")
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) {
        manifest = (json && json.clips) || {};
        return manifest;
      })
      .catch(function () {
        // No manifest is a normal state before anything is recorded.
        manifest = {};
        return manifest;
      });
  }

  function has(key) {
    return !!(manifest && manifest[key]);
  }

  /* Play a list of clip keys in order. Resolves true only if EVERY clip
   * played; a partial announcement ("Mandapam." with no verdict) is worse
   * than none, so the caller falls back wholesale. */
  function playSequence(keys) {
    stop();
    var i = 0;
    return new Promise(function (resolve) {
      function next() {
        if (i >= keys.length) { resolve(true); return; }
        var key = keys[i++];
        if (!has(key)) { resolve(false); return; }
        var audio = new Audio(BASE + manifest[key]);
        current = audio;
        audio.onended = next;
        audio.onerror = function () { resolve(false); };
        var p = audio.play();
        if (p && p.catch) {
          // Autoplay policy: a play() not started by a user gesture is
          // rejected. Reported, never silently treated as success.
          p.catch(function (err) {
            console.warn("ORCA: audio blocked:", err && err.message);
            resolve(false);
          });
        }
      }
      next();
    });
  }

  function stop() {
    if (current) {
      try { current.pause(); } catch (e) { /* already gone */ }
      current = null;
    }
  }

  /* The keys an announcement needs, most specific first.
   *
   * `action` is policy.py's, passed through untouched -- this file never
   * decides which verdict to announce, only which recording says it. */
  function keysFor(recommendation) {
    if (!recommendation || !recommendation.action) return null;
    var zone = recommendation.primary_zone || recommendation.chosen_zone;
    var name = zone && zone.name;
    var action = "action_" + recommendation.action.replace(/ /g, "_");
    var keys = [];
    if (name && has("zone_" + name)) keys.push("zone_" + name);
    keys.push(action);
    // SAFER ALTERNATIVE names somewhere to go instead, so the alternative
    // zone is announced after the verdict. Without it the crew is told to
    // leave and not told where to.
    var chosen = recommendation.chosen_zone && recommendation.chosen_zone.name;
    if (recommendation.action === "SAFER ALTERNATIVE" && chosen && chosen !== name) {
      if (has("connector_instead")) keys.push("connector_instead");
      if (has("zone_" + chosen)) keys.push("zone_" + chosen);
    }
    return keys;
  }

  /* Speak a verdict. Returns a promise resolving to what actually
   * happened, so the UI can tell the user rather than appear to work. */
  function announce(recommendation) {
    return load().then(function () {
      var keys = keysFor(recommendation);
      if (!keys) return "nothing";
      return playSequence(keys).then(function (ok) {
        if (ok) return "recorded";
        stop();
        // Fall back to the shell's TTS only if it is really there AND
        // really has Tamil. English synthesis of a Tamil answer is not an
        // improvement on silence.
        var n = global.OrcaNative;
        if (n && n.canSpeakTamil && n.canSpeakTamil() && recommendation.recommendation) {
          n.speak(recommendation.recommendation, "ta");
          return "tts";
        }
        return "unavailable";
      });
    });
  }

  /* The boundary warnings, which are the ones that matter most and the
   * ones a page cannot deliver at all -- see BoundaryWatchService. This
   * entry point exists for the in-app banner; the background service
   * plays its own audio natively. */
  function announceBoundary(band) {
    return load().then(function () {
      var key = "boundary_" + band;
      return playSequence([key]).then(function (ok) {
        return ok ? "recorded" : "unavailable";
      });
    });
  }

  function status() {
    return load().then(function (m) {
      var keys = Object.keys(m);
      return {
        available: keys.length > 0,
        clipCount: keys.length,
        // What is MISSING is the useful half: it tells whoever is
        // recording exactly what is left to do.
        hasAllActions: ["GO", "DO_NOT_GO", "SAFER_ALTERNATIVE", "CANNOT_ASSESS"]
          .every(function (a) { return !!m["action_" + a]; }),
      };
    });
  }

  global.ORCAVoice = {
    announce: announce,
    announceBoundary: announceBoundary,
    status: status,
    stop: stop,
    has: has,
    keysFor: keysFor,
  };
})(typeof window !== "undefined" ? window : globalThis);
