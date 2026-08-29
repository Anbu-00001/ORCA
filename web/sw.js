/* ORCA service worker — makes the app shell survive having no network.
 *
 * WHY THIS EXISTS, precisely
 * --------------------------
 * On 2026-08-28 the demo failed offline for two independent reasons. One
 * was Groq rate limits. The other was that three.js and MapLibre loaded
 * from unpkg: nine resources blocked, the 3D canvas never created, the 2D
 * map dead. The e2e "wifi off" test passed throughout, because it only
 * asserted the text answer.
 *
 * Vendoring into web/vendor/ fixed the origin of those files. This fixes
 * the other half: without a service worker, a browser with no network
 * cannot load index.html AT ALL, so nothing downstream matters.
 *
 * WHAT IT CACHES AND WHAT IT DOES NOT
 * -----------------------------------
 *   PRECACHE   the app shell — HTML, JS, vendored libraries, fonts.
 *              Everything needed to render the page with zero network.
 *   RUNTIME    GET /bundle and GET /bathymetry, network-first: a fresh
 *              answer when there is a link, the last good one when not.
 *   NEVER      POST /ask. A stale POST replayed as if it were live would
 *              be exactly the fabrication CLAUDE.md rule 1 forbids. When
 *              /ask cannot be reached the app falls back to the stored
 *              bundle EXPLICITLY, labelled, via web/offline.js — it does
 *              not get handed a cached response pretending to be fresh.
 *
 * Basemap raster tiles are also not precached: they are third-party, many,
 * and large. Offline, index.html already falls back to its own SVG zone
 * sketch, which is drawn from the real ZONES coordinates.
 */

// Bump on any change to SHELL or to the fetch strategy. activate below
// deletes every cache whose name does not start with the current VERSION,
// so a bump is what actually evicts a stale shell from a phone.
var VERSION = "orca-v5";
var SHELL_CACHE = VERSION + "-shell";
var RUNTIME_CACHE = VERSION + "-runtime";

/* Kept explicit rather than globbed. A build step could generate this,
 * but there is no build step (CLAUDE.md: "No build step"), and a list you
 * can read is a list you can check against what the page actually loads.
 */
var SHELL = [
  "./",
  "./index.html",
  "./manifest.json",
  "./offline.js",
  "./colormaps.js",
  "./three-viz.js",
  "./three-viz-app.js",
  "./vendor/fonts.css",
  "./vendor/three.module.js",
  "./vendor/three.core.js",
  "./vendor/maplibre-gl.js",
  "./vendor/maplibre-gl.css",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/icon-maskable-512.png",
];

/* Every font FILE fonts.css references, read out of the stylesheet at
 * install time rather than listed above.
 *
 * This is not tidiness, it is the Tamil font. Google's stylesheet is
 * subsetted by unicode-range, so a browser downloads the Tamil faces only
 * when it actually renders Tamil. A crew who used ORCA in English in
 * harbour and asked their first Tamil question at sea would have had no
 * Tamil face cached — and docs/MOBILE_APP.md is explicit that device
 * Tamil fonts vary badly across Android OEMs. Precaching every face costs
 * ~501 KB once and removes the whole class of problem.
 *
 * Parsed rather than hardcoded because there is no build step (CLAUDE.md)
 * and a 31-entry list in this file would silently rot the next time
 * scripts/vendor_web_deps.py runs.
 */
function fontFaceUrls() {
  return fetch("./vendor/fonts.css")
    .then(function (r) { return r.ok ? r.text() : ""; })
    .then(function (css) {
      var urls = [];
      var re = /url\(\.\/fonts\/([^)]+)\)/g;
      var m;
      while ((m = re.exec(css)) !== null) {
        // "./fonts/x.woff2" inside fonts.css resolves relative to the
        // STYLESHEET (web/vendor/), but cache.add() here resolves
        // relative to sw.js (web/) -- so the bare path 404s and every
        // face is silently skipped. Measured: 15 cached entries instead
        // of 46, and no Tamil face offline. Rebuild the path explicitly.
        var url = "./vendor/fonts/" + m[1];
        if (urls.indexOf(url) === -1) urls.push(url);
      }
      return urls;
    })
    .catch(function () { return []; });
}

self.addEventListener("install", function (event) {
  event.waitUntil(
    caches.open(SHELL_CACHE).then(function (cache) {
      // addAll() is atomic — one 404 rejects the whole install and the
      // old worker stays active. That is the behaviour we want: a
      // half-cached shell is worse than the previous one, because it
      // fails at sea rather than here.
      return cache.addAll(SHELL).then(function () {
        // Fonts are added SEPARATELY and non-atomically on purpose: a
        // single missing face must degrade one script's rendering, not
        // block the whole worker from installing and leave the app with
        // no offline shell at all.
        return fontFaceUrls().then(function (urls) {
          return Promise.all(urls.map(function (u) {
            return cache.add(u).catch(function (err) {
              console.warn("ORCA sw: font not cached:", u, err && err.message);
            });
          }));
        });
      });
    }).then(function () {
      return self.skipWaiting();
    })
  );
});

self.addEventListener("activate", function (event) {
  event.waitUntil(
    caches.keys().then(function (names) {
      return Promise.all(
        names
          .filter(function (n) { return n.indexOf(VERSION) !== 0; })
          .map(function (n) { return caches.delete(n); })
      );
    }).then(function () {
      return self.clients.claim();
    })
  );
});

function isRuntimeCacheable(url) {
  return url.pathname.endsWith("/bundle") || url.pathname.endsWith("/bathymetry");
}

self.addEventListener("fetch", function (event) {
  var request = event.request;

  // Only GET is ever served from a cache. POST /ask falls through to the
  // network and is allowed to fail — see the header comment.
  if (request.method !== "GET") return;

  var url = new URL(request.url);

  if (isRuntimeCacheable(url)) {
    // Network-first: at sea this rejects fast and we serve the last good
    // copy. In harbour it refreshes. Never the other way round — a stale
    // advisory must never win over a reachable live one.
    event.respondWith(
      fetch(request)
        .then(function (response) {
          if (response && response.ok) {
            var copy = response.clone();
            caches.open(RUNTIME_CACHE).then(function (c) { c.put(request, copy); });
          }
          return response;
        })
        .catch(function () {
          return caches.match(request).then(function (hit) {
            if (hit) return hit;
            // Nothing stored. Say so in a shape the caller can read,
            // rather than returning an opaque failure.
            return new Response(
              JSON.stringify({ detail: "offline and nothing cached for this resource" }),
              { status: 504, headers: { "Content-Type": "application/json" } }
            );
          });
        })
    );
    return;
  }

  // App shell: cache-first.
  //
  // ignoreSearch is LOAD-BEARING, not a tidy-up. caches.match() keys on
  // the FULL url including the query string, and the page is opened as
  // index.html?api=http://... -- so the precached "./index.html" was a
  // miss, the request fell through to the network, and an offline reload
  // died with ERR_FAILED. Measured 2026-08-29 with the network cut: the
  // bundle was stored, the worker was active, and the page still would
  // not load. Every ?api=, ?mock=, or cache-busting parameter would have
  // reproduced it on the boat.
  event.respondWith(
    caches.match(request, { ignoreSearch: true }).then(function (hit) {
      if (hit) return hit;
      return fetch(request).then(function (response) {
        // Opportunistically keep same-origin GETs that were not in the
        // precache list (an icon size, a font variant).
        if (response && response.ok && url.origin === self.location.origin) {
          var copy = response.clone();
          caches.open(SHELL_CACHE).then(function (c) { c.put(request, copy); });
        }
        return response;
      }).catch(function (err) {
        // A navigation that reaches here has no cached match AND no
        // network. Serving the app shell is right: index.html renders
        // from the stored bundle, so the crew gets the advisory rather
        // than a browser error page.
        if (request.mode === "navigate") {
          return caches.match("./index.html", { ignoreSearch: true }).then(function (shell) {
            if (shell) return shell;
            throw err;
          });
        }
        throw err;
      });
    })
  );
});
