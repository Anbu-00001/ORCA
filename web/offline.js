/* ORCA offline store — the advisory the boat carries out of harbour.
 *
 * WHAT THIS IS
 * ------------
 * A client-side cache for one `GET /bundle` response, plus the two things
 * a phone at sea genuinely needs on top of it: an honest age, and an
 * honest statement of which zone the stored answer is actually about.
 *
 * WHAT THIS IS NOT
 * ----------------
 * It does not compute a verdict, and it must never learn how. Every
 * `action` / `severity` / number it renders was decided by orca/policy.py
 * on shore and travelled here inside the bundle. A second implementation
 * of the safety rules is a second thing that can disagree with the first,
 * and the day they disagree ORCA has no defensible answer about which was
 * right. See docs/MOBILE_APP.md §2.
 *
 * A reviewer should be able to grep this file for a threshold — 2.5, 0.6,
 * "risk", "GO" as a decision rather than a label — and find nothing.
 *
 * WHY localStorage AND NOT IndexedDB
 * ----------------------------------
 * Measured on the live endpoint: the whole ten-zone advisory is 52 KB,
 * far inside every browser's ~5 MB localStorage budget, and it is one
 * blob read and written whole. IndexedDB buys asynchrony and schema we do not need
 * (CLAUDE.md rule 7). Bathymetry (~496 KB) is NOT stored here — it is a
 * plain GET and the service worker caches it as a normal response.
 */
(function (global) {
  "use strict";

  var BUNDLE_KEY = "orca.bundle.v1";

  /* ---------------------------------------------------------------
   * Storage. Every access is wrapped: a private window, cleared site
   * data, or a browser set to block storage all throw here rather than
   * returning null, and an advisory app must not die because of it.
   * ------------------------------------------------------------- */

  function saveBundle(bundle) {
    try {
      localStorage.setItem(BUNDLE_KEY, JSON.stringify(bundle));
      return true;
    } catch (err) {
      console.warn("ORCA: could not store bundle:", err && err.message);
      return false;
    }
  }

  function loadBundle() {
    try {
      var raw = localStorage.getItem(BUNDLE_KEY);
      if (!raw) return null;
      var parsed = JSON.parse(raw);
      // A bundle without zones is not a usable bundle. Treat a corrupt
      // one as absent rather than rendering half of it.
      if (!parsed || !Array.isArray(parsed.zones) || !parsed.zones.length) return null;
      return parsed;
    } catch (err) {
      console.warn("ORCA: stored bundle unreadable:", err && err.message);
      return null;
    }
  }

  function clearBundle() {
    try {
      localStorage.removeItem(BUNDLE_KEY);
    } catch (err) {
      console.warn("ORCA: could not clear bundle:", err && err.message);
    }
  }

  /* ---------------------------------------------------------------
   * Refresh. Called in harbour, while there is still a link.
   * ------------------------------------------------------------- */

  /* The advisory shipped INSIDE the app, for a phone that has never had
   * signal. Only used when nothing is stored -- a real download always
   * wins, because it is always newer.
   *
   * `downloaded_at` is deliberately NOT stamped. It means "when this
   * reached the device", and for a seed that is install time, which
   * nothing here knows. Leaving it unset makes downloadedAgo() return
   * null and the UI say so, rather than inventing a moment (CLAUDE.md
   * rule 1). The age that actually matters -- when ORCA collected the
   * readings -- is carried by cache_fetched_at and IS shown.
   */
  async function loadSeed(seedUrl) {
    if (loadBundle()) return null;              // a real bundle wins
    var resp = await fetch(seedUrl);
    if (!resp.ok) throw new Error("seed fetch failed: HTTP " + resp.status);
    var bundle = await resp.json();
    if (!bundle || !Array.isArray(bundle.zones) || !bundle.zones.length) {
      throw new Error("seed contained no zones");
    }
    bundle.from_seed = true;
    saveBundle(bundle);
    return bundle;
  }

  async function refreshBundle(apiBase) {
    var resp = await fetch(apiBase + "/bundle");
    if (!resp.ok) throw new Error("bundle fetch failed: HTTP " + resp.status);
    var bundle = await resp.json();
    if (!bundle || !Array.isArray(bundle.zones) || !bundle.zones.length) {
      throw new Error("bundle fetch returned no zones");
    }
    // Stamp the moment it landed on THIS device. The server cannot know
    // it, and it is the number that matters at sea (see downloadedAgo).
    bundle.downloaded_at = new Date().toISOString();
    saveBundle(bundle);
    return bundle;
  }

  /* ---------------------------------------------------------------
   * THE STALENESS TRAP (docs/MOBILE_APP.md §4.3)
   *
   * `freshness_min` on every MarineObservation is computed at FETCH time
   * as `fetched_at - valid_time`. It means "how old was this reading when
   * we collected it". IT DOES NOT GROW while the bundle sits on a phone.
   *
   * Measured on the web client 2026-08-29: with a cache two days old, the
   * evidence panel still read "14 h old", because it rendered
   * freshness_min directly. A phone three days at sea would confidently
   * show minutes-old ages for three-day-old data.
   *
   * So there are TWO ages and both must be shown:
   *   freshness_min      "measured 14 h before download"   server, static
   *   now - downloaded   "downloaded 3 days ago"           device clock
   * ------------------------------------------------------------- */

  /* A DURATION, never a moment. Callers append "ago", so returning
   * "just now" here produced "downloaded just now ago". */
  function humanDuration(minutes) {
    var mins = Math.floor(minutes);
    if (mins < 1) return "under a minute";
    if (mins < 60) return mins + " min";
    var hours = Math.floor(mins / 60);
    if (hours < 24) {
      var remMin = mins % 60;
      return remMin ? hours + " h " + remMin + " min" : hours + " h";
    }
    var days = Math.floor(hours / 24);
    var remHours = hours % 24;
    return remHours ? days + " d " + remHours + " h" : days + " d";
  }

  /* How long ago this bundle was downloaded, per the DEVICE clock.
   *
   * Returns null — never a guess — when the clock cannot support the
   * claim: unset, unparseable, or reading earlier than the download
   * itself. "age unknown" is a correct answer; an invented one is not
   * (CLAUDE.md rule 1). This mirrors formatAge()'s existing position in
   * index.html rather than inventing a second one.
   */
  function downloadedAgo(bundle, nowMs) {
    if (!bundle || !bundle.downloaded_at) return null;
    var then = Date.parse(bundle.downloaded_at);
    if (!isFinite(then)) return null;
    var now = typeof nowMs === "number" ? nowMs : Date.now();
    var deltaMin = (now - then) / 60000;
    if (deltaMin < 0) return null; // clock is behind the download: unknowable
    return humanDuration(deltaMin);
  }

  /* ---------------------------------------------------------------
   * Zone selection — selection only, never computation.
   * ------------------------------------------------------------- */

  function zoneNames(bundle) {
    return (bundle.zones || [])
      .map(function (z) { return z.primary_zone && z.primary_zone.name; })
      .filter(Boolean);
  }

  function entryForZone(bundle, name) {
    return (bundle.zones || []).find(function (z) {
      return z.primary_zone && z.primary_zone.name === name;
    }) || null;
  }

  function haversineIsh(aLat, aLon, bLat, bLon) {
    // Squared degrees. Adequate for ranking ten zones along one 700 km
    // coast, and the same comparison orca/planner.py's fallback makes.
    var dLat = aLat - bLat;
    var dLon = aLon - bLon;
    return dLat * dLat + dLon * dLon;
  }

  /* Which stored answer applies to this question.
   *
   * Returns { entry, match, named } where `match` is one of:
   *   "exact"     the query named exactly one covered zone
   *   "ambiguous" it named SEVERAL — we refuse to pick, and say so
   *   "fallback"  it named none; nearest to the supplied position
   *
   * The "ambiguous" branch is deliberate and is where this differs from
   * the server. orca/planner.py's _zone_by_substring() returns the FIRST
   * zone in declaration order whose name appears anywhere in the query,
   * so "from Chennai down to Thoothukudi" silently answers for Chennai —
   * a GO badge for a voyage ending somewhere flagged SAFER ALTERNATIVE
   * (docs/CHATBOT.md §8.1). Offline there is no model to disambiguate and
   * no way to ask, so the honest move is to name both and let the crew
   * choose. Refusing to guess is cheaper here than being wrong.
   */
  function resolveOffline(bundle, query, lat, lon) {
    var names = zoneNames(bundle);
    var lower = String(query || "").toLowerCase();
    var named = names.filter(function (n) { return lower.indexOf(n.toLowerCase()) !== -1; });

    if (named.length === 1) {
      return { entry: entryForZone(bundle, named[0]), match: "exact", named: named };
    }
    if (named.length > 1) {
      return { entry: entryForZone(bundle, named[0]), match: "ambiguous", named: named };
    }

    var best = null;
    var bestD = Infinity;
    (bundle.zones || []).forEach(function (z) {
      var zone = z.primary_zone;
      if (!zone || typeof zone.lat !== "number" || typeof zone.lon !== "number") return;
      var d = haversineIsh(lat, lon, zone.lat, zone.lon);
      if (d < bestD) { bestD = d; best = z; }
    });
    return { entry: best, match: "fallback", named: [] };
  }

  /* The answer to render offline, with its provenance as an ANSWER made
   * explicit. Returns null when there is nothing stored — in which case
   * the caller must say so, not improvise.
   */
  function offlineAnswer(query, lat, lon, nowMs) {
    var bundle = loadBundle();
    if (!bundle) return null;
    var resolved = resolveOffline(bundle, query, lat, lon);
    if (!resolved.entry) return null;

    var age = downloadedAgo(bundle, nowMs);
    var notes = [];

    if (bundle.from_seed) {
      // Never claim it was downloaded: it was not. It came with the app.
      notes.push(
        "Offline — showing the advisory that shipped with this app. It has " +
        "not been refreshed on this device."
      );
    } else {
      notes.push(
        age
          ? "Offline — showing the advisory downloaded " + age + " ago."
          : "Offline — showing a stored advisory. This device's clock cannot " +
            "confirm how old it is."
      );
    }

    if (resolved.match === "ambiguous") {
      notes.push(
        "Your question named more than one place ORCA covers (" +
        resolved.named.join(", ") + "). Offline it cannot ask which you meant, " +
        "so this is for " + resolved.named[0] + " only — check the others before you sail."
      );
    } else if (resolved.match === "fallback") {
      var name = resolved.entry.primary_zone && resolved.entry.primary_zone.name;
      notes.push(
        "You didn't name a place ORCA covers, so this is for " + name +
        ", the nearest stored zone to your position."
      );
    }

    // How old the READINGS are, which is not the same as how long the
    // bundle has been on the phone — a bundle downloaded ten minutes ago
    // can still carry day-old readings.
    //
    // There is no expiry to check against, on purpose: /bundle reports no
    // valid_until because these are nowcast observations and no source
    // publishes a shelf life for them (see orca/api.py's bundle()). So
    // the verdict STAYS VISIBLE at any age — an old answer beats no
    // answer on a boat — and the age is stated instead. ORCA's position
    // throughout: an old reading is usable if and only if it is labelled
    // old (docs/MOBILE_APP.md §4.4).
    var readingAge = null;
    if (bundle.cache_fetched_at) {
      var collected = Date.parse(bundle.cache_fetched_at);
      if (isFinite(collected)) {
        var readingMin = ((typeof nowMs === "number" ? nowMs : Date.now()) - collected) / 60000;
        if (readingMin >= 0) readingAge = humanDuration(readingMin);
      }
    }
    if (readingAge) {
      notes.push("ORCA collected these readings " + readingAge + " ago.");
    }

    // A shallow copy so the stored bundle is never mutated by rendering.
    var answer = Object.assign({}, resolved.entry);
    answer.offline_mode = true;
    answer.from_cache = true;
    answer.cache_downloaded_ago = age;
    answer.zone_match = resolved.match === "ambiguous" ? "fallback" : resolved.match;
    // Prepend, never replace: the server's own coverage note is still true.
    answer.coverage_note = notes.concat(answer.coverage_note ? [answer.coverage_note] : []).join(" ");
    return answer;
  }

  function bundleStatus(nowMs) {
    var bundle = loadBundle();
    if (!bundle) return { present: false };
    return {
      present: true,
      zoneCount: bundle.zones.length,
      zoneNames: zoneNames(bundle),
      fromSeed: !!bundle.from_seed,
      downloadedAgo: downloadedAgo(bundle, nowMs),
      downloadedAt: bundle.downloaded_at || null,
      cacheFetchedAt: bundle.cache_fetched_at || null,
      latestReadingTime: bundle.latest_reading_time || null,
    };
  }

  global.ORCAOffline = {
    BUNDLE_KEY: BUNDLE_KEY,
    loadSeed: loadSeed,
    saveBundle: saveBundle,
    loadBundle: loadBundle,
    clearBundle: clearBundle,
    refreshBundle: refreshBundle,
    downloadedAgo: downloadedAgo,
    humanDuration: humanDuration,
    resolveOffline: resolveOffline,
    offlineAnswer: offlineAnswer,
    bundleStatus: bundleStatus,
  };
})(typeof window !== "undefined" ? window : globalThis);
