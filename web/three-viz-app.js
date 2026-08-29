// Wires web/three-viz.js's two visualization classes into index.html:
// toggle buttons, lazy construction (a WebGL context is only created once
// a view is actually opened), and feeding them real data from the same
// /ask response and GET /bathymetry the rest of the page already uses.
//
// "Check-then-subscribe": the classic <script> at the bottom of
// index.html buffers the latest /ask response on
// window.__ORCA_LAST_RECOMMENDATION__ *and* dispatches an
// "orca:recommendation" event. Module scripts execute after the document
// has parsed, so by the time this file runs, mock mode may have already
// rendered once and fired the event before we were listening -- reading
// the buffered property first closes that race without depending on
// script load order.
import { ReasoningGraph, OceanDiorama } from "./three-viz.js";
import { colormapCss, CMOCEAN_META } from "./colormaps.js";

const params = new URLSearchParams(window.location.search);
const MOCK_MODE = params.get("mock") === "1";
const API_BASE = params.get("api") || "http://localhost:8000";

let latestRecommendation = window.__ORCA_LAST_RECOMMENDATION__ || null;
let latestBathymetry = null;
let reasoningGraph = null;
let oceanDiorama = null;

window.addEventListener("orca:recommendation", (event) => {
  latestRecommendation = event.detail;
  if (reasoningGraph) reasoningGraph.render(latestRecommendation);
  if (oceanDiorama) {
    oceanDiorama.setZoneSummaries(latestRecommendation.zone_summaries || []);
    // A new answer is new evidence, so the sandbox re-seeds onto it and
    // any held hypothesis is dropped. Silently keeping a stale
    // counterfactual on top of a fresh reading is exactly the confusion
    // P8 exists to prevent.
    seedSandbox();
  }
});


// Draw the depth colour bar from the same cmocean table and the same
// grid range the terrain shader samples. Reading both from one place is
// the point: a legend that is maintained separately from the picture
// eventually disagrees with it, and a colour bar that disagrees with its
// data is worse than no colour bar -- it is a wrong measurement rendered
// authoritatively.
function renderDepthColorbar(diorama) {
  const ramp = document.getElementById("depth-colorbar-ramp");
  if (!ramp) return;
  const range = diorama?.depthRange;
  ramp.style.background = colormapCss("deep");
  const meta = CMOCEAN_META.deep;
  const unitEl = document.getElementById("depth-colorbar-unit");
  if (unitEl) unitEl.textContent = meta.unit + " below sea level";
  const minEl = document.getElementById("depth-colorbar-min");
  const maxEl = document.getElementById("depth-colorbar-max");
  if (!range) {
    // No grid loaded: say so rather than printing a plausible 0-3000.
    if (minEl) minEl.textContent = "–";
    if (maxEl) maxEl.textContent = "–";
    return;
  }
  if (minEl) minEl.textContent = Math.round(range.min).toLocaleString();
  if (maxEl) maxEl.textContent = Math.round(range.max).toLocaleString();
}

async function loadBathymetry() {
  const container = document.getElementById("ocean3d-container");
  try {
    const resp = await fetch(MOCK_MODE ? "mock_bathymetry.json" : `${API_BASE}/bathymetry`);
    if (!resp.ok) throw new Error(`GET /bathymetry -> ${resp.status}`);
    latestBathymetry = await resp.json();
    container?.classList.remove("awaiting");
    if (oceanDiorama) {
      oceanDiorama.setBathymetry(latestBathymetry);
      renderDepthColorbar(oceanDiorama);
      if (latestRecommendation) oceanDiorama.setZoneSummaries(latestRecommendation.zone_summaries || []);
    }
  } catch (err) {
    // A missing/unreachable bathymetry layer is decorative context, not
    // advisory data -- it must never take down the rest of the app
    // (CLAUDE.md rule 8 spirit). Surface it loudly in the console instead
    // of silently showing an empty ocean.
    console.error("ORCA: bathymetry unavailable —", err.message);
    if (container) container.textContent = "3D ocean relief unavailable (bathymetry cache not loaded).";
  }
}

function ensureReasoningGraph() {
  if (!reasoningGraph) {
    reasoningGraph = new ReasoningGraph(document.getElementById("reasoning3d-container"));
    if (latestRecommendation) reasoningGraph.render(latestRecommendation);
  } else {
    reasoningGraph.start();
  }
  return reasoningGraph;
}

function ensureOceanDiorama() {
  if (!oceanDiorama) {
    const container = document.getElementById("ocean3d-container");
    try {
      oceanDiorama = new OceanDiorama(container);
      oceanDiorama.onWaveChange(renderSandbox);
      if (latestBathymetry) {
        oceanDiorama.setBathymetry(latestBathymetry);
        renderDepthColorbar(oceanDiorama);
        if (latestRecommendation) oceanDiorama.setZoneSummaries(latestRecommendation.zone_summaries || []);
      }
      seedSandbox();
    } catch (err) {
      // Anything thrown while building the scene -- a bad shader, a
      // malformed grid -- used to leave start() uncalled and the canvas
      // simply black, which is indistinguishable from a slow load. Say
      // so instead. Not swallowed: rethrown to the console with its
      // stack intact (CLAUDE.md rule 2).
      oceanDiorama = null;
      if (container) {
        container.classList.remove("awaiting");
        container.textContent = "3D ocean failed to build — see the browser console.";
      }
      console.error("ORCA: 3D ocean build failed —", err);
      throw err;
    }
  } else {
    oceanDiorama.start();
  }
  return oceanDiorama;
}

// ---------------------------------------------------------------------
// Environment sandbox
// ---------------------------------------------------------------------
// The panel is a thin skin over OceanDiorama's wave state. It reads the
// measured wave_height_m out of the current /ask response's evidence and
// lets the user push it somewhere else; the sea redraws, the 2.5 m plane
// stays put, and everything the user sees says which of the two they are
// looking at.
//
// What this deliberately does NOT do: call /ask, mutate the verdict, or
// touch the Douglas ruler. The advisory on the rail stays the advisory
// for the measured sea state, so a hypothesis can never be mistaken for
// one ORCA actually issued (CLAUDE.md rules 1 and 3).

// Real WMO Douglas sea-state bands, the same scale index.html's ruler
// draws. Labelling the slider position with the standard name is the
// honest way to say "this is what you just asked for" without inventing
// a number to describe it.
const DOUGLAS_BANDS = [
  { max: 0.1, name: "Calm (glassy)" },
  { max: 0.5, name: "Smooth" },
  { max: 1.25, name: "Slight" },
  { max: 2.5, name: "Moderate" },
  { max: 4.0, name: "Rough" },
  { max: 6.0, name: "Very rough" },
  { max: Infinity, name: "High" },
];

function douglasName(waveM) {
  return DOUGLAS_BANDS.find((b) => waveM <= b.max).name;
}

function seedSandbox() {
  const panel = document.getElementById("sandbox-panel");
  if (!oceanDiorama || !panel) return;
  const seeded = oceanDiorama.setWaveFromEvidence(
    (latestRecommendation && latestRecommendation.evidence) || []
  );
  const slider = document.getElementById("sandbox-wave");

  // The panel always shows, but it only becomes operable once a real
  // wave_height_m has arrived to depart FROM. Hiding it outright just
  // read as a broken feature; seeding it with a made-up default would
  // be worse -- a hypothesis has to be a departure from a measurement,
  // and an absent reading has to look absent (CLAUDE.md rule 1).
  panel.classList.add("visible");
  panel.classList.toggle("is-idle", !seeded);
  if (slider) slider.disabled = !seeded;

  if (!seeded) {
    document.getElementById("sandbox-value").textContent = "—";
    document.getElementById("sandbox-band").textContent = "";
    document.getElementById("sandbox-flag").className = "observed";
    document.getElementById("sandbox-flag").textContent = "NO READING";
    document.getElementById("sandbox-note").textContent =
      "Ask ORCA about a zone to load its measured sea state, then drag to explore another.";
    document.getElementById("sandbox-reset").classList.add("hidden");
    return;
  }
  if (slider) slider.value = String(oceanDiorama.waveState.heightM);
}

function renderSandbox(state) {
  const panel = document.getElementById("sandbox-panel");
  if (!panel) return;
  const flag = document.getElementById("sandbox-flag");
  const note = document.getElementById("sandbox-note");
  const resetBtn = document.getElementById("sandbox-reset");

  document.getElementById("sandbox-value").textContent = state.heightM.toFixed(1);
  document.getElementById("sandbox-band").textContent = douglasName(state.heightM);

  panel.classList.toggle("is-hypothetical", state.hypothetical);
  flag.className = state.hypothetical ? "hypothetical" : "observed";
  flag.textContent = state.hypothetical ? "HYPOTHETICAL" : "OBSERVED";
  resetBtn.classList.toggle("hidden", !state.hypothetical);

  if (!state.hypothetical) {
    note.textContent =
      `Measured sea state — ${state.periodS.toFixed(1)} s period, ` +
      `from ${Math.round(state.directionDeg)}°. Drag to explore another.`;
    return;
  }

  const base = state.baseline;
  const crossed =
    state.heightM > 2.5 !== base.heightM > 2.5
      ? state.heightM > 2.5
        ? " Crosses the 2.5 m hard-deny line."
        : " Falls back under the 2.5 m hard-deny line."
      : "";
  note.textContent =
    `Not measured. ${base.heightM.toFixed(1)} m → ${state.heightM.toFixed(1)} m.` + crossed;
}

function wireSandbox() {
  const slider = document.getElementById("sandbox-wave");
  const resetBtn = document.getElementById("sandbox-reset");
  if (!slider || !resetBtn) return;

  slider.addEventListener("input", () => {
    if (oceanDiorama) oceanDiorama.setHypotheticalWaveHeight(parseFloat(slider.value));
  });
  resetBtn.addEventListener("click", () => {
    if (!oceanDiorama) return;
    oceanDiorama.resetWave();
    slider.value = String(oceanDiorama.waveState.heightM);
  });
}

function wireViewToggle() {
  const view2dBtn = document.getElementById("view-toggle-2d");
  const view3dBtn = document.getElementById("view-toggle-3d");
  const map2dLayer = document.getElementById("map-2d-layer");
  const ocean3dEl = document.getElementById("ocean3d-container");

  view3dBtn.addEventListener("click", () => {
    map2dLayer.classList.add("hidden-view");
    ocean3dEl.classList.add("visible");
    view3dBtn.classList.add("active");
    view2dBtn.classList.remove("active");
    document.getElementById("ocean3d-legend")?.classList.add("visible");
    document.getElementById("cam-bar")?.classList.add("visible");
    ensureOceanDiorama();
    seedSandbox(); // re-seed on every open, not just first construction
  });

  view2dBtn.addEventListener("click", () => {
    ocean3dEl.classList.remove("visible");
    map2dLayer.classList.remove("hidden-view");
    view2dBtn.classList.add("active");
    view3dBtn.classList.remove("active");
    // Leaving the 3D view drops any held hypothesis as well as hiding
    // the panel, so coming back always starts on the measurement.
    document.getElementById("sandbox-panel")?.classList.remove("visible");
    document.getElementById("ocean3d-legend")?.classList.remove("visible");
    document.getElementById("cam-bar")?.classList.remove("visible");
    if (oceanDiorama) {
      oceanDiorama.resetWave();
      // Back to orbit before hiding: free cam's key listeners live on
      // window, and they must not still be swallowing keystrokes once
      // the 3D view is gone.
      oceanDiorama.setCameraMode("orbit");
      oceanDiorama.setAutoRotate(true);
      document.getElementById("cam-free")?.classList.remove("active");
      document.getElementById("cam-orbit")?.classList.add("active");
      document.getElementById("cam-help")?.classList.add("hidden");
      oceanDiorama.stop();
    }
  });
}

function wireReasoningToggle() {
  const toggleBtn = document.getElementById("reasoning3d-toggle");
  const container = document.getElementById("reasoning3d-container");

  toggleBtn.addEventListener("click", () => {
    const willShow = container.classList.contains("collapsed");
    container.classList.toggle("collapsed");
    toggleBtn.textContent = willShow ? "Hide 3D reasoning graph ▴" : "View reasoning in 3D ▾";
    if (willShow) ensureReasoningGraph();
    else if (reasoningGraph) reasoningGraph.stop();
  });
}

// Orbit is the default because it demos itself -- the scene turns on its
// own with nobody touching it. Free cam is the deliberate opt-in for
// actually flying the coast.
function wireCameraBar() {
  const orbitBtn = document.getElementById("cam-orbit");
  const freeBtn = document.getElementById("cam-free");
  const resetBtn = document.getElementById("cam-reset");
  const help = document.getElementById("cam-help");
  if (!orbitBtn || !freeBtn || !resetBtn) return;

  function setMode(mode) {
    if (!oceanDiorama) return;
    oceanDiorama.setCameraMode(mode);
    const free = mode === "free";
    freeBtn.classList.toggle("active", free);
    orbitBtn.classList.toggle("active", !free);
    help.classList.toggle("hidden", !free);
    // Auto-rotate would fight a user who is orbiting by hand, and means
    // nothing in free mode.
    oceanDiorama.setAutoRotate(!free);
  }

  orbitBtn.addEventListener("click", () => setMode("orbit"));
  freeBtn.addEventListener("click", () => setMode("free"));
  resetBtn.addEventListener("click", () => {
    if (!oceanDiorama) return;
    oceanDiorama.resetCamera();
    setMode("orbit");
    oceanDiorama.setAutoRotate(true);
  });
}

function wireLegend() {
  const legend = document.getElementById("ocean3d-legend");
  const toggle = document.getElementById("legend-toggle");
  if (!legend || !toggle) return;
  toggle.addEventListener("click", () => {
    const collapsed = legend.classList.toggle("collapsed");
    toggle.setAttribute("aria-expanded", String(!collapsed));
  });
}

wireViewToggle();
wireReasoningToggle();
wireSandbox();
wireLegend();
wireCameraBar();
loadBathymetry();
