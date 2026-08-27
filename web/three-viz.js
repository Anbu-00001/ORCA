// three.js visualizations for ORCA (SIH 2026): a 3D reasoning graph and a
// geospatial ocean diorama. Both are additive, presentation-layer views on
// data the backend already computes and validates -- neither one invents a
// number. See TEAM_STATUS.md for what each draws from.
//
// This file is a plain ES module loaded via <script type="module">, no
// build step, matching the rest of web/ (CLAUDE.md stack rule). three.js
// itself is loaded from an import map in index.html, pinned the same way
// maplibre-gl is (a fixed version, not "latest").
import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";

// Reuses the app's existing palette (see index.html's :root custom
// properties) so the 3D views read as part of the same product, not a
// bolted-on demo.

const COLOR_LOW = new THREE.Color(0x0f6e5c); // --accent
const COLOR_MID = new THREE.Color(0xc98a12); // --amber-border
const COLOR_HIGH = new THREE.Color(0xa4321d); // --danger

function riskColor(risk) {
  const r = Math.min(Math.max(risk, 0), 1);
  const c = new THREE.Color();
  if (r < 0.5) c.lerpColors(COLOR_LOW, COLOR_MID, r / 0.5);
  else c.lerpColors(COLOR_MID, COLOR_HIGH, (r - 0.5) / 0.5);
  return c;
}

const ACTION_COLOR = {
  "GO": COLOR_LOW,
  "DO NOT GO": COLOR_HIGH,
  "SAFER ALTERNATIVE": COLOR_MID,
};

// Draws text onto a canvas and uses it as a sprite texture -- the boring,
// dependency-free way to label a three.js scene (no font-loader / addon
// needed, works fully offline).
function makeTextSprite(text, { fontSize = 32, color = "#10241f", bg = "rgba(255,255,255,0.85)" } = {}) {
  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d");
  const font = `600 ${fontSize}px -apple-system, "Segoe UI", Roboto, Arial, sans-serif`;
  ctx.font = font;
  const paddingX = 14;
  const width = Math.ceil(ctx.measureText(text).width) + paddingX * 2;
  const height = Math.ceil(fontSize * 1.7);
  canvas.width = width;
  canvas.height = height;
  ctx.font = font; // canvas resize resets context state -- must re-apply
  ctx.fillStyle = bg;
  ctx.fillRect(0, 0, width, height);
  ctx.fillStyle = color;
  ctx.textBaseline = "middle";
  ctx.fillText(text, paddingX, height / 2);

  const texture = new THREE.CanvasTexture(canvas);
  const material = new THREE.SpriteMaterial({ map: texture, transparent: true, depthTest: false });
  const sprite = new THREE.Sprite(material);
  const spriteHeight = 0.45;
  sprite.scale.set((spriteHeight * width) / height, spriteHeight, 1);
  return sprite;
}

// Absolute-positioned children (the tooltip, the canvas) need a
// non-static positioned ancestor to anchor to. index.html sometimes
// already sets that via CSS (e.g. #ocean3d-container is `position:
// absolute` so it can fill #map-wrap) -- blindly writing an inline
// `position: relative` here would win specificity and silently break
// that layout, so only touch it when the *computed* position is static.
function ensurePositioned(el) {
  if (getComputedStyle(el).position === "static") el.style.position = "relative";
}

function nearestIndex(sortedArr, value) {
  let best = 0;
  let bestDiff = Infinity;
  for (let i = 0; i < sortedArr.length; i++) {
    const diff = Math.abs(sortedArr[i] - value);
    if (diff < bestDiff) {
      bestDiff = diff;
      best = i;
    }
  }
  return best;
}

// GET /bathymetry returns a flat list of {lat, lon, elevation_m}. This
// turns it into a regular 2D grid keyed by the real, distinct lat/lon
// values present in the data -- no interpolation invented, just indexing
// what ERDDAP actually returned.
function buildElevationGrid(points) {
  const lats = [...new Set(points.map((p) => p.lat))].sort((a, b) => a - b);
  const lons = [...new Set(points.map((p) => p.lon))].sort((a, b) => a - b);
  const byKey = new Map(points.map((p) => [`${p.lat}|${p.lon}`, p.elevation_m]));
  const grid = lats.map((lat) => lons.map((lon) => byKey.get(`${lat}|${lon}`) ?? 0));
  return { lats, lons, grid };
}

// Hover tooltip + click-to-select-zone, shared by both visualizations.
function attachInteraction(viz) {
  const tooltip = document.createElement("div");
  tooltip.className = "three-viz-tooltip";
  tooltip.setAttribute("data-testid", "three-viz-tooltip");
  tooltip.style.cssText =
    "position:absolute;pointer-events:none;display:none;background:#10241f;color:#fff;" +
    "font:12px -apple-system,sans-serif;padding:5px 9px;border-radius:6px;white-space:pre-line;" +
    "z-index:5;max-width:220px;line-height:1.4;";
  ensurePositioned(viz.container);
  viz.container.appendChild(tooltip);

  const raycaster = new THREE.Raycaster();
  const pointer = new THREE.Vector2();

  function hitTest(event) {
    const rect = viz.container.getBoundingClientRect();
    pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
    raycaster.setFromCamera(pointer, viz.camera);
    return raycaster.intersectObjects(viz._raycastTargets, false);
  }

  viz.renderer.domElement.addEventListener("pointermove", (event) => {
    const hits = hitTest(event);
    const rect = viz.container.getBoundingClientRect();
    if (hits.length && hits[0].object.userData.tooltip) {
      tooltip.textContent = hits[0].object.userData.tooltip;
      tooltip.style.left = `${event.clientX - rect.left + 12}px`;
      tooltip.style.top = `${event.clientY - rect.top + 12}px`;
      tooltip.style.display = "block";
      viz.renderer.domElement.style.cursor = "pointer";
    } else {
      tooltip.style.display = "none";
      viz.renderer.domElement.style.cursor = "";
    }
  });
  viz.renderer.domElement.addEventListener("pointerleave", () => {
    tooltip.style.display = "none";
  });
  viz.renderer.domElement.addEventListener("click", (event) => {
    const hits = hitTest(event);
    const zone = hits.length && hits[0].object.userData.zone;
    if (zone && typeof window.__ORCA_SELECT_ZONE__ === "function") {
      window.__ORCA_SELECT_ZONE__(zone.name, zone.lat, zone.lon);
    }
  });
}

// Shared scene/camera/renderer/controls lifecycle. Rendering is paused
// (start/stop) whenever a view is hidden behind a toggle, so a scene the
// user never opens costs no GPU time.
class ThreeVizBase {
  constructor(container, { cameraPosition = [0, 5, 10], autoRotateSpeed = 0.5 } = {}) {
    this.container = container;
    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(50, 1, 0.1, 200);
    this.camera.position.set(...cameraPosition);

    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    ensurePositioned(container);
    container.appendChild(this.renderer.domElement);

    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true;
    this.controls.autoRotate = true;
    this.controls.autoRotateSpeed = autoRotateSpeed;

    this._pulseTargets = [];
    this._raycastTargets = [];
    this._active = false;
    this._clock = new THREE.Clock();
    this._tick = this._tick.bind(this);

    this._resizeObserver = new ResizeObserver(() => this._onResize());
    this._resizeObserver.observe(container);

    attachInteraction(this);
  }

  _onResize() {
    const w = this.container.clientWidth || 1;
    const h = this.container.clientHeight || 1;
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(w, h);
  }

  start() {
    if (this._active) return;
    this._active = true;
    this._onResize();
    this._clock.start();
    requestAnimationFrame(this._tick);
  }

  stop() {
    this._active = false;
  }

  dispose() {
    this.stop();
    this._resizeObserver.disconnect();
    this.renderer.dispose();
  }

  _tick() {
    if (!this._active) return;
    const t = this._clock.getElapsedTime();
    const pulse = 1 + Math.sin(t * 2.2) * 0.08;
    this._pulseTargets.forEach((obj) => obj.scale.setScalar(pulse));
    if (this._onTick) this._onTick(t);
    this.controls.update();
    this.renderer.render(this.scene, this.camera);
    requestAnimationFrame(this._tick);
  }
}

const AGENT_SHORT_NAMES = {
  eo_satellite_agent: "EO satellite",
  ocean_state_agent: "ocean state",
  weather_agent: "weather",
  hazard_agent: "hazard",
  geofence_agent: "geofence",
};

// A per-query 3D render of the actual reasoning trace: the final decision
// at the center, the 5 agents that fed it on a ring around it, and each
// agent's own supporting MarineObservation(s) one ring further out. Every
// node/edge here comes from a real /ask response's agent_findings +
// evidence (see orca/planner.py Recommendation) -- this is a picture of
// computation that already happened, not a generated illustration of it.
//
// Layout is a fixed radial placement, not a physics simulation: with a
// small, constant-shaped graph (1 decision + 5 agents + ~5-10
// observations) a force simulation buys nothing but instability, so we
// don't use one (CLAUDE.md: boring beats clever).
export class ReasoningGraph extends ThreeVizBase {
  constructor(container) {
    super(container, { cameraPosition: [0, 4, 9], autoRotateSpeed: 0.6 });
    this.scene.add(new THREE.AmbientLight(0xffffff, 0.75));
    const point = new THREE.PointLight(0xffffff, 0.9);
    point.position.set(5, 8, 6);
    this.scene.add(point);

    this._group = new THREE.Group();
    this.scene.add(this._group);
  }

  render(recommendation) {
    while (this._group.children.length) {
      const obj = this._group.children.pop();
      obj.geometry?.dispose?.();
      obj.material?.map?.dispose?.();
      obj.material?.dispose?.();
    }
    this._pulseTargets = [];
    this._raycastTargets = [];

    const findings = recommendation.agent_findings || [];
    const evidenceById = new Map((recommendation.evidence || []).map((o) => [o.id, o]));

    const coreColor = ACTION_COLOR[recommendation.action] || COLOR_LOW;
    const core = new THREE.Mesh(
      new THREE.SphereGeometry(0.5, 32, 32),
      new THREE.MeshStandardMaterial({ color: coreColor, emissive: coreColor, emissiveIntensity: 0.35 })
    );
    core.userData.tooltip = `${recommendation.action}\n${recommendation.reason}`;
    this._group.add(core);
    this._pulseTargets.push(core);
    this._raycastTargets.push(core);

    const coreLabel = makeTextSprite(recommendation.action, { fontSize: 36 });
    coreLabel.position.set(0, 0.95, 0);
    this._group.add(coreLabel);

    const agentRadius = 3.0;
    const obsRadius = 5.0;
    const n = Math.max(findings.length, 1);

    findings.forEach((f, i) => {
      const angle = (i / n) * Math.PI * 2;
      const ax = Math.cos(angle) * agentRadius;
      const az = Math.sin(angle) * agentRadius;
      const color = riskColor(f.risk_level);
      const size = 0.16 + f.risk_level * 0.2;

      const agentMesh = new THREE.Mesh(
        new THREE.SphereGeometry(size, 24, 24),
        new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: f.hard_deny ? 0.6 : 0.15 })
      );
      agentMesh.position.set(ax, 0, az);
      agentMesh.userData.tooltip =
        `${AGENT_SHORT_NAMES[f.agent] || f.agent}\nrisk ${f.risk_level.toFixed(2)}` +
        `${f.hard_deny ? " (hard deny)" : ""}\n${f.reason}`;
      this._group.add(agentMesh);
      this._raycastTargets.push(agentMesh);

      if (f.hard_deny) {
        const ring = new THREE.Mesh(
          new THREE.TorusGeometry(size + 0.16, 0.025, 8, 32),
          new THREE.MeshBasicMaterial({ color: 0xa4321d })
        );
        ring.position.copy(agentMesh.position);
        ring.rotation.x = Math.PI / 2;
        this._group.add(ring);
        this._pulseTargets.push(ring);
      }

      const label = makeTextSprite(AGENT_SHORT_NAMES[f.agent] || f.agent, { fontSize: 30 });
      label.position.set(ax, 0.4, az);
      this._group.add(label);

      const edgeColor = f.hard_deny ? 0xa4321d : f.suggests_go ? 0x0f6e5c : 0x9a9484;
      const edgeGeom = new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(0, 0, 0), agentMesh.position.clone()]);
      const edge = new THREE.Line(
        edgeGeom,
        new THREE.LineBasicMaterial({ color: edgeColor, transparent: true, opacity: f.hard_deny ? 0.95 : 0.5 })
      );
      this._group.add(edge);

      const obsIds = f.observation_ids || [];
      obsIds.forEach((id, j) => {
        const obs = evidenceById.get(id);
        if (!obs) return;
        const spread = obsIds.length > 1 ? (j - (obsIds.length - 1) / 2) * 0.35 : 0;
        const oAngle = angle + spread;
        const ox = Math.cos(oAngle) * obsRadius;
        const oz = Math.sin(oAngle) * obsRadius;

        const obsMesh = new THREE.Mesh(
          new THREE.BoxGeometry(0.2, 0.2, 0.2),
          new THREE.MeshStandardMaterial({ color: 0x2f6b53 })
        );
        obsMesh.position.set(ox, 0, oz);
        obsMesh.userData.tooltip = `${obs.variable}: ${obs.value} ${obs.unit || ""}\n${obs.source}\nconfidence ${obs.confidence}`;
        this._group.add(obsMesh);
        this._raycastTargets.push(obsMesh);

        const obsLabel = makeTextSprite(`${obs.variable} ${obs.value}${obs.unit ? " " + obs.unit : ""}`, {
          fontSize: 24,
        });
        obsLabel.position.set(ox, 0.32, oz);
        this._group.add(obsLabel);

        const obsEdgeGeom = new THREE.BufferGeometry().setFromPoints([agentMesh.position.clone(), obsMesh.position.clone()]);
        const obsEdge = new THREE.Line(
          obsEdgeGeom,
          new THREE.LineBasicMaterial({ color: 0xbdb6a2, transparent: true, opacity: 0.6 })
        );
        this._group.add(obsEdge);
      });
    });

    this.start();
  }
}

// A 3D geospatial view: real seafloor/land relief (GET /bathymetry, NOAA
// NCEI ETOPO 2022) with per-zone risk columns from the last /ask
// response's zone_summaries. Vertical scale is exaggerated for visual
// clarity (see ELEVATION_SCALE) -- horizontal position and the sign of
// elevation are not.
export class OceanDiorama extends ThreeVizBase {
  constructor(container) {
    super(container, { cameraPosition: [0, 9, 13], autoRotateSpeed: 0.35 });
    this.controls.maxPolarAngle = Math.PI * 0.49; // stay above the "seabed"
    this.controls.minDistance = 5;
    this.controls.maxDistance = 30;

    this.scene.background = new THREE.Color(0x0d2233);
    this.scene.add(new THREE.AmbientLight(0xffffff, 0.55));
    const sun = new THREE.DirectionalLight(0xfff2d9, 1.0);
    sun.position.set(8, 12, 4);
    this.scene.add(sun);

    this._width = 10;
    this._depth = 15;
    // Real elevation_m values compressed for a legible diorama -- land
    // rarely exceeds a few hundred m here but the offshore shelf drops
    // several km, so an unscaled mesh would be almost all cliff.
    this._elevationScale = 1 / 1200;
    this._bbox = null;
    this._grid = null;

    this._terrain = new THREE.Group();
    this._columns = new THREE.Group();
    this.scene.add(this._terrain, this._columns);
  }

  _lonToX(lon) {
    return ((lon - this._bbox.min_lon) / (this._bbox.max_lon - this._bbox.min_lon)) * this._width - this._width / 2;
  }

  _latToZ(lat) {
    return ((this._bbox.max_lat - lat) / (this._bbox.max_lat - this._bbox.min_lat)) * this._depth - this._depth / 2;
  }

  _elevToY(elev) {
    return elev * this._elevationScale;
  }

  _heightAt(lat, lon) {
    if (!this._grid) return 0;
    const { lats, lons, grid } = this._grid;
    return grid[nearestIndex(lats, lat)][nearestIndex(lons, lon)];
  }

  setBathymetry(bathymetryResponse) {
    this._bbox = bathymetryResponse.bbox;
    this._grid = buildElevationGrid(bathymetryResponse.points);
    this._buildTerrainMesh();
    this.start();
  }

  _buildTerrainMesh() {
    while (this._terrain.children.length) {
      const obj = this._terrain.children.pop();
      obj.geometry?.dispose?.();
      obj.material?.dispose?.();
    }
    const { lats, lons, grid } = this._grid;
    const rows = lats.length;
    const cols = lons.length;
    const positions = new Float32Array(rows * cols * 3);
    const colors = new Float32Array(rows * cols * 3);
    const deep = new THREE.Color(0x08243b);
    const shallow = new THREE.Color(0x1f6f8c);
    const lowland = new THREE.Color(0x4c7a4a);
    const highland = new THREE.Color(0x8a7752);

    let p = 0;
    let c = 0;
    for (let i = 0; i < rows; i++) {
      for (let j = 0; j < cols; j++) {
        const elev = grid[i][j];
        positions[p++] = this._lonToX(lons[j]);
        positions[p++] = this._elevToY(elev);
        positions[p++] = this._latToZ(lats[i]);

        const col =
          elev < 0
            ? shallow.clone().lerp(deep, Math.min(-elev / 3500, 1))
            : lowland.clone().lerp(highland, Math.min(elev / 500, 1));
        colors[c++] = col.r;
        colors[c++] = col.g;
        colors[c++] = col.b;
      }
    }

    const indices = [];
    for (let i = 0; i < rows - 1; i++) {
      for (let j = 0; j < cols - 1; j++) {
        const a = i * cols + j;
        const b = a + 1;
        const cIdx = a + cols;
        const d = cIdx + 1;
        indices.push(a, cIdx, b, b, cIdx, d);
      }
    }

    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute("position", new THREE.BufferAttribute(positions, 3));
    geometry.setAttribute("color", new THREE.BufferAttribute(colors, 3));
    geometry.setIndex(indices);
    geometry.computeVertexNormals();

    const mesh = new THREE.Mesh(
      geometry,
      new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.9, metalness: 0.05 })
    );
    mesh.userData.tooltip = null;
    this._terrain.add(mesh);

    const seaGeom = new THREE.PlaneGeometry(this._width, this._depth, 1, 1);
    seaGeom.rotateX(-Math.PI / 2);
    const sea = new THREE.Mesh(
      seaGeom,
      new THREE.MeshStandardMaterial({ color: 0x1a5f82, transparent: true, opacity: 0.35, roughness: 0.1, metalness: 0.2 })
    );
    sea.position.y = 0.02;
    this._terrain.add(sea);
    this._seaMesh = sea;
  }

  setZoneSummaries(zoneSummaries) {
    while (this._columns.children.length) {
      const obj = this._columns.children.pop();
      obj.geometry?.dispose?.();
      obj.material?.dispose?.();
    }
    this._raycastTargets = this._raycastTargets.filter((o) => !o.userData.zone);
    this._pulseTargets = this._pulseTargets.filter((o) => !o.userData.beacon);
    if (!this._bbox) return; // needs setBathymetry() first for coordinate mapping

    const maxHeight = 2.6;
    (zoneSummaries || []).forEach((zone) => {
      const x = this._lonToX(zone.lon);
      const z = this._latToZ(zone.lat);
      const baseY = Math.max(this._elevToY(this._heightAt(zone.lat, zone.lon)), 0.05);
      const height = Math.max(0.15, zone.risk_level * maxHeight);
      const color = riskColor(zone.risk_level);

      const col = new THREE.Mesh(
        new THREE.CylinderGeometry(0.18, 0.24, height, 16),
        new THREE.MeshStandardMaterial({
          color,
          emissive: color,
          emissiveIntensity: zone.hard_deny ? 0.55 : 0.2,
          transparent: true,
          opacity: 0.92,
        })
      );
      col.position.set(x, baseY + height / 2, z);
      col.userData.zone = zone;
      col.userData.tooltip = `${zone.name}\n${zone.action} — risk ${zone.risk_level.toFixed(2)}${zone.hard_deny ? " (hard deny)" : ""}`;
      this._columns.add(col);
      this._raycastTargets.push(col);

      if (zone.hard_deny) {
        const beacon = new THREE.Mesh(new THREE.SphereGeometry(0.11, 16, 16), new THREE.MeshBasicMaterial({ color: 0xa4321d }));
        beacon.position.set(x, baseY + height + 0.25, z);
        beacon.userData.beacon = true;
        this._columns.add(beacon);
        this._pulseTargets.push(beacon);
      }

      const label = makeTextSprite(zone.name, { fontSize: 28 });
      label.position.set(x, baseY + height + 0.45, z);
      this._columns.add(label);
    });
  }

  _onTick(t) {
    if (this._seaMesh) this._seaMesh.position.y = 0.02 + Math.sin(t * 1.2) * 0.03;
  }
}
