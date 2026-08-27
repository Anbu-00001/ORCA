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
  if (oceanDiorama) oceanDiorama.setZoneSummaries(latestRecommendation.zone_summaries || []);
});

async function loadBathymetry() {
  const container = document.getElementById("ocean3d-container");
  try {
    const resp = await fetch(MOCK_MODE ? "mock_bathymetry.json" : `${API_BASE}/bathymetry`);
    if (!resp.ok) throw new Error(`GET /bathymetry -> ${resp.status}`);
    latestBathymetry = await resp.json();
    container?.classList.remove("awaiting");
    if (oceanDiorama) {
      oceanDiorama.setBathymetry(latestBathymetry);
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
    oceanDiorama = new OceanDiorama(document.getElementById("ocean3d-container"));
    if (latestBathymetry) {
      oceanDiorama.setBathymetry(latestBathymetry);
      if (latestRecommendation) oceanDiorama.setZoneSummaries(latestRecommendation.zone_summaries || []);
    }
  } else {
    oceanDiorama.start();
  }
  return oceanDiorama;
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
    ensureOceanDiorama();
  });

  view2dBtn.addEventListener("click", () => {
    ocean3dEl.classList.remove("visible");
    map2dLayer.classList.remove("hidden-view");
    view2dBtn.classList.add("active");
    view3dBtn.classList.remove("active");
    if (oceanDiorama) oceanDiorama.stop();
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

wireViewToggle();
wireReasoningToggle();
loadBathymetry();
