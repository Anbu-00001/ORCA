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
import { EffectComposer } from "three/addons/postprocessing/EffectComposer.js";
import { RenderPass } from "three/addons/postprocessing/RenderPass.js";
import { UnrealBloomPass } from "three/addons/postprocessing/UnrealBloomPass.js";
import { OutputPass } from "three/addons/postprocessing/OutputPass.js";

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

// index.html's --unknown. CANNOT ASSESS is a real verdict (R-39), not a
// missing key, so it gets its own colour rather than a fallback.
const COLOR_UNKNOWN = new THREE.Color(0x6b7a82);

const ACTION_COLOR = {
  "GO": COLOR_LOW,
  "DO NOT GO": COLOR_HIGH,
  "SAFER ALTERNATIVE": COLOR_MID,
  "CANNOT ASSESS": COLOR_UNKNOWN,
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
  // Four passes, measured against the real cache rather than guessed:
  // the steepest inland gradient falls from 4.38 to 1.26 rise/run and
  // the highest peak from 2.50 to 2.00 scene units, with every one of
  // the 370 land cells still land. The shards go, the relief stays.
  return { lats, lons, grid: smoothGrid(grid, 4) };
}

// A 3x3 box blur over the relief, twice.
//
// ETOPO 2022 at 60 arc-seconds gives postings ~7.4 km apart. Joining
// those with hard facets draws cliffs and spikes that assert detail the
// data does not contain -- two adjacent postings 100 m apart in height
// become a 3:1 rock face on screen, which is why the coast rendered as
// black shards. Blurring is the more honest reading of a coarse grid,
// not a prettier lie about it, and it is applied to the ONE grid every
// consumer shares (terrain, skirt, water depth, zone bases) so the
// coastline they each derive stays identical.
// SIGN-AWARE: land only ever averages with land, seabed only with
// seabed. A plain blur is wrong here and measurably so -- the shelf next
// to this coast drops to -2,700 m, so averaging a +20 m coastal cell
// against its offshore neighbours drowns it. Measured on the real cache:
// a naive 4-pass blur destroyed 37 of 370 land cells outright. Skipping
// across-shoreline neighbours keeps all 370 and leaves the coastline
// crisp, while still smoothing each side internally.
function smoothGrid(grid, passes) {
  const rows = grid.length;
  const cols = grid[0]?.length ?? 0;
  let src = grid;
  for (let p = 0; p < passes; p++) {
    const out = [];
    for (let i = 0; i < rows; i++) {
      const row = new Array(cols);
      for (let j = 0; j < cols; j++) {
        const isLand = src[i][j] > 0;
        let sum = 0;
        let n = 0;
        for (let di = -1; di <= 1; di++) {
          for (let dj = -1; dj <= 1; dj++) {
            const a = i + di;
            const b = j + dj;
            if (a < 0 || a >= rows || b < 0 || b >= cols) continue;
            if (src[a][b] > 0 !== isLand) continue;
            sum += src[a][b];
            n++;
          }
        }
        row[j] = n ? sum / n : src[i][j];
      }
      out.push(row);
    }
    src = out;
  }
  return src;
}

// ---------------------------------------------------------------------
// Gerstner ocean surface (the 3D environment sandbox's water)
// ---------------------------------------------------------------------
// A sum of four trochoidal (Gerstner) waves evaluated in the vertex
// shader. Chosen over a Tessendorf/FFT ocean deliberately: FFT needs a
// per-frame inverse transform (WebGPU compute or a fragment-shader FFT)
// for a scene that is ten points and a seabed, and Firefox still ships
// WebGPU disabled by default. Gerstner is closed-form, runs on plain
// WebGL2, and needs no assets -- which also keeps the view offline-clean
// (CLAUDE.md rule 8) rather than adding a texture fetch.
//
// WHAT IS REAL AND WHAT IS EXAGGERATED -- this matters, because ORCA's
// whole claim is that its numbers are traceable:
//
//   * TIME is real. uOmega is 2*PI / wave_period_s straight off the
//     cached observation, so the surface heaves at the actual period
//     Open-Meteo reports. Longer swell visibly rolls slower.
//   * DIRECTION is real. Taken from wave_direction_deg.
//   * RELATIVE height is real. Amplitude is linear in wave_height_m, so
//     doubling the reading doubles the crest.
//   * ABSOLUTE height and wavelength are EXAGGERATED, and have to be:
//     one scene unit spans ~22 km of coast, so a true-to-scale 2.5 m sea
//     is 0.0001 units tall -- literally sub-pixel. The exaggeration
//     factor is stated in the UI rather than hidden here.
//
// Nothing in this file decides anything. It draws wave_height_m; the
// verdict still comes from orca/policy.py.

// Scene units per metre of significant wave height. Tuned so the 2.5 m
// Douglas 4/5 hard-deny boundary (orca/agents.py WAVE_HARD_DENY_M) lands
// at a legible ~0.34 units against a 10 x 15 unit seascape.
const WAVE_UNITS_PER_M = 0.075;

// Mirrors orca/agents.py WAVE_HARD_DENY_M and index.html's Douglas ruler.
// Drawing it is all this file does with it -- hazard_agent still owns the
// denial (CLAUDE.md rule 4).
const WAVE_HARD_DENY_M = 2.5;

// Deep-water wavelength L0 = g*Tp^2 / 2*PI is 25 m at Tp=4 s and 190 m at
// Tp=11 s. Both are invisible at true scale, so L0 is mapped monotonically
// into a legible band: the ORDERING is honest (longer period always draws
// longer crests), the magnitude is not.
// Height and wavelength are exaggerated by DIFFERENT factors, which is
// the one liberty this view takes that needs stating plainly: matching
// them would preserve true steepness (~0.02) and render the sea flat, so
// the wavelengths are stretched far less than the heights. Even so the
// ratio has to stay in a plausible band -- at a visual steepness much
// past ~0.1 the surface stops reading as water and starts reading as
// corrugated iron.
const WAVE_LAMBDA_MIN_UNITS = 2.0;
const WAVE_LAMBDA_MAX_UNITS = 4.4;
const L0_MIN_M = 20.0;
const L0_MAX_M = 200.0;

// The water plane runs well past the bathymetry block so the sea reaches
// the horizon instead of stopping at a visible rectangular edge. Outside
// the ETOPO bbox there is no relief data, so those vertices are simply
// told they are in deep water -- no seabed is invented out there, it is
// drawn as the open ocean it is.
// Land is drawn as a CHART LANDMASS, not as terrain: one flat plate at a
// fixed height, hard coastline, no relief and no hypsometric ramp.
//
// A deliberate choice, forced by the data. ETOPO 2022 at 60 arc-seconds
// posts samples 7.4 km apart -- one value per ~55 km2 -- and it is the
// only elevation source in the project. Everything that makes rendered
// terrain read as terrain (ridgelines, drainage, valleys, vegetation
// edges) lives well below that scale and is simply absent from the file.
// Worse, one scene unit spans ~22 km, so showing 50 m of coastal plain
// needs ~3,000x vertical exaggeration -- and exaggerating smooth coarse
// data yields bigger smooth domes: scale without detail. Inventing the
// missing relief would be fabricated topography drawn as if surveyed.
//
// So ORCA does what every marine chart does, for the same reason: the
// sea is the subject and land is a boundary. IHO S-52 draws land as flat
// buff with a hard coastline and no shading, which is already the visual
// language of index.html's Day/Dusk/Night palette switch.
const LAND_PLATE_UNITS = 0.11;

// Isobaths, in metres of depth. Real values read off the cached ETOPO
// grid -- the effort goes here, where there IS data, instead of into
// land relief where there is none. This is also the seabed's own chart
// convention.
const DEPTH_CONTOURS_M = [20, 50, 100, 200, 500, 1000, 2000];

const WATER_OVERSCAN = 3.0;
const WATER_SEGMENTS_X = 220;
const WATER_SEGMENTS_Z = 280;

function deepWaterWavelengthM(periodS) {
  return (9.81 * periodS * periodS) / (2 * Math.PI);
}

// Low sun. Ocean renders live or die on grazing light: it is what makes
// a specular track across the water and what lets light scatter through
// a wave crest from behind.
const SUN_DIRECTION = new THREE.Vector3(-0.62, 0.19, -0.76).normalize();

// One analytic sky, compiled into BOTH the sky dome and the water's
// reflection term. Sharing the function is the whole trick: the sea
// reflects exactly the sky that is actually drawn behind it, including
// the sun disc, so the specular track falls where the sun really is
// without a single texture, cubemap or PMREM pass.
const SKY_GLSL = /* glsl */ `
  vec3 orcaSky(vec3 dir, vec3 sunDir) {
    float y = dir.y;

    vec3 zenith  = vec3(0.026, 0.105, 0.300);
    vec3 horizon = vec3(0.300, 0.470, 0.640);
    vec3 nadir   = vec3(0.010, 0.026, 0.046);

    vec3 col = mix(horizon, zenith, pow(clamp(y, 0.0, 1.0), 0.36));
    col = mix(nadir, col, smoothstep(-0.26, 0.015, y));

    float mu = max(dot(dir, sunDir), 0.0);
    // A tight disc and two Mie-ish glow lobes. The exponents are high
    // and the multipliers low on purpose: a broad, bright sun turns the
    // whole dome white, and because the sea reflects this exact
    // function, a white sky makes the water grey too.
    col += vec3(1.00, 0.94, 0.82) * pow(mu, 4000.0) * 8.0;
    col += vec3(1.00, 0.68, 0.38) * pow(mu, 110.0) * 0.26;
    col += vec3(0.95, 0.50, 0.26) * pow(mu, 14.0) * 0.09;

    // Warm haze along the horizon -- but only around the sun's own
    // bearing, so the opposite horizon stays cool and the sky keeps a
    // direction instead of glowing uniformly.
    // Tight around the sun's own bearing (mu^4, not mu^1.5) and weak.
    // A broad warm band reflects off deep water -- which is nearly navy
    // -- and the sum reads as mauve across the entire sea.
    float band = pow(1.0 - clamp(abs(y) * 4.5, 0.0, 1.0), 4.0);
    col += vec3(0.30, 0.15, 0.06) * band * pow(clamp(mu, 0.0, 1.0), 4.0) * 0.30;
    return col;
  }
`;

// The relief gets its own shader rather than a MeshStandardMaterial.
//
// Standard Lambert falls to exactly zero wherever a face turns away from
// the light, and with one low sun that is most of a coastline for most
// of the orbit -- which is why the land kept rendering as a black mass
// no matter how much ambient was thrown at it. Wrap ("half-Lambert")
// lighting cannot reach zero by construction, so the hypsometric colour
// is always legible from every angle.
//
// Elevation arrives as an attribute in real metres and the colour ramp
// is evaluated per fragment, so the coastline is a sharp band rather
// than a smear between two 7.4 km-apart vertices.
const TERRAIN_VERTEX_SHADER = /* glsl */ `
  attribute float aElev;
  varying float vElev;
  varying vec3  vN;
  varying vec3  vW;
  void main() {
    vElev = aElev;
    // WORLD space, not normalMatrix. normalMatrix is the inverse
    // transpose of the modelVIEW matrix, so it yields view-space
    // normals -- and this shader treats N.y as "up" and dots N against a
    // world-space sun. Under normalMatrix both of those were measured in
    // the camera's frame, so flat ground read as a steep face whenever
    // the camera was pitched, and the whole relief darkened or lit as
    // you orbited. That is what kept the landmass black.
    vN = normalize(mat3(modelMatrix) * normal);
    vec4 world = modelMatrix * vec4(position, 1.0);
    vW = world.xyz;
    gl_Position = projectionMatrix * viewMatrix * world;
  }
`;

const TERRAIN_FRAGMENT_SHADER = /* glsl */ `
  uniform vec3 uSunDir;
  varying float vElev;
  varying vec3  vN;
  varying vec3  vW;

  ${SKY_GLSL}

  // Seabed tint. sqrt so the shelf, where every zone sits, gets most of
  // the colour range instead of it all being spent on the abyssal plain.
  vec3 seabedTint(float e) {
    return mix(vec3(0.247, 0.612, 0.604), vec3(0.039, 0.125, 0.212),
               clamp(sqrt(-e / 3500.0), 0.0, 1.0));
  }

  // One isobath. fwidth() keeps the line a constant width on screen
  // however steep the slope is, so a contour on the shelf break is not a
  // hairline while one on the flat abyssal plain floods a whole region.
  // The clamp is load-bearing, not defensive dressing. fwidth() is a
  // per-pixel derivative: on a near-edge-on triangle it explodes, and if
  // derivatives are unavailable it collapses to 0 -- and smoothstep with
  // edge0 == edge1 is undefined, which in practice returns 0 and makes
  // the "line" cover everything. Bounding the band to 1..60 m of
  // elevation keeps it a line under every one of those conditions.
  float isobath(float depth, float level) {
    float w = clamp(fwidth(depth) * 1.4, 1.0, 60.0);
    return 1.0 - smoothstep(0.0, w, abs(depth - level));
  }

  void main() {
    vec3 N = normalize(vN);
    vec3 L = normalize(uSunDir);
    vec3 col;

    if (vElev > 0.0) {
      // --- Chart landmass: flat buff, no relief shading ---
      // The only modulation is top-vs-bevel, which describes the plate's
      // own edge rather than pretending to describe topography.
      vec3 landTop  = vec3(0.859, 0.788, 0.639);
      vec3 landEdge = vec3(0.596, 0.529, 0.404);
      float up = smoothstep(0.45, 0.92, N.y);
      col = mix(landEdge, landTop, up);
    } else {
      float depth = -vElev;
      col = seabedTint(vElev);

      // --- Isobaths ---
      float line = 0.0;
      ${DEPTH_CONTOURS_M.map((m) => `line = max(line, isobath(depth, ${m.toFixed(1)}));`).join("\n      ")}
      col = mix(col, vec3(0.760, 0.906, 0.937), line * 0.45);

      // Wrap lighting on the seabed only: dot() remapped to 0..1 rather
      // than clamped at 0, so a slope facing away from the low sun still
      // returns light instead of going black.
      float wrap = dot(N, L) * 0.5 + 0.5;
      float upN = N.y * 0.5 + 0.5;
      vec3 sun = vec3(1.0, 0.87, 0.70) * pow(wrap, 1.5) * 1.05;
      vec3 amb = mix(vec3(0.20, 0.18, 0.15), vec3(0.44, 0.54, 0.64), upN);
      col *= (sun + amb);
    }

    // A hard coastline stroke. Drawn in elevation space against the
    // interpolated value, so it lands exactly on the 0 m isoline rather
    // than on the nearest vertex 7.4 km away. Clamped for the same
    // reason as isobath(): unbounded, this one stroke painted the entire
    // landmass slate instead of outlining it.
    float cw = clamp(fwidth(vElev) * 1.6, 1.0, 45.0);
    float coast = 1.0 - smoothstep(0.0, cw, abs(vElev));
    col = mix(col, vec3(0.129, 0.180, 0.204), coast * 0.8);

    // Same aerial perspective as the water, so land and sea share one
    // atmosphere instead of looking like two pasted layers.
    //
    // The haze direction is clamped to the upper hemisphere. Airlight
    // between you and a distant object comes from the SKY; sampling
    // orcaSky() straight down the view vector meant that looking down at
    // the seabed sampled below the horizon, where the function returns
    // its near-black nadir -- so distance made the relief darker instead
    // of hazier.
    vec3 V = normalize(cameraPosition - vW);
    vec3 hazeDir = normalize(vec3(-V.x, max(-V.y, 0.03), -V.z));
    float haze = 1.0 - exp(-length(cameraPosition - vW) * 0.026);
    col = mix(col, orcaSky(hazeDir, L), haze * 0.45);

    gl_FragColor = vec4(col, 1.0);
  }
`;

const SKY_VERTEX_SHADER = /* glsl */ `
  varying vec3 vDir;
  void main() {
    vDir = position;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

const SKY_FRAGMENT_SHADER = /* glsl */ `
  uniform vec3 uSunDir;
  varying vec3 vDir;
  ${SKY_GLSL}
  void main() {
    gl_FragColor = vec4(orcaSky(normalize(vDir), uSunDir), 1.0);
  }
`;

const WATER_VERTEX_SHADER = /* glsl */ `
  uniform float uTime;
  uniform float uAmp;      // scene units, mean-to-crest of the dominant wave
  uniform float uLambda;   // scene units, dominant wavelength
  uniform float uOmega;    // rad/s -- the REAL 2*PI / wave_period_s
  uniform float uChop;     // 0..1 Gerstner horizontal pinch
  uniform vec2  uDir;      // unit vector, from the real wave_direction_deg

  attribute float aDepth;  // SIGNED metres of water: negative over land

  varying float vDepth;
  varying float vFoam;
  varying vec3  vWorld;
  varying vec3  vNrm;
  varying float vElev;   // displacement above mean sea level, scene units

  // One trochoid. Accumulates displacement, the analytic normal, and a
  // crest-sharpness term the fragment shader thresholds into foam.
  void trochoid(
    vec2 d, float amp, float lam, float om, float chop, vec2 p, float t,
    inout vec3 disp, inout vec3 nrm, inout float steep
  ) {
    float w = 6.2831853 / lam;
    float q = chop / (w * amp * 4.0);
    float phase = w * dot(d, p) - om * t;
    float c = cos(phase);
    float s = sin(phase);
    float wa = w * amp;

    disp.x += q * amp * d.x * c;
    disp.z += q * amp * d.y * c;
    disp.y += amp * s;

    nrm.x += -d.x * wa * c;
    nrm.z += -d.y * wa * c;
    nrm.y += -q * wa * s;

    steep += wa * max(s, 0.0);
  }

  void main() {
    vec2 p = vec2(position.x, position.z);
    vec3 disp = vec3(0.0);
    vec3 nrm = vec3(0.0, 1.0, 0.0);
    float steep = 0.0;

    // Four components. Wavelength ratios spread the spectrum; the
    // angular offsets give short-crestedness instead of a corrugated
    // roof. Each component's frequency follows the deep-water dispersion
    // relation om ~ sqrt(g*k), i.e. om_i = om_0 * sqrt(lam_0 / lam_i),
    // so the shorter components genuinely run faster.
    // Directional spread: +38, -58 and +14 degrees off the observed
    // bearing. Narrower than this and the sum reads as corrugated iron
    // rather than as a short-crested sea.
    vec2 d0 = uDir;
    vec2 d1 = vec2(uDir.x * 0.7880 - uDir.y * 0.6157, uDir.x * 0.6157 + uDir.y * 0.7880);
    vec2 d2 = vec2(uDir.x * 0.5299 + uDir.y * 0.8480, -uDir.x * 0.8480 + uDir.y * 0.5299);
    vec2 d3 = vec2(uDir.x * 0.9703 - uDir.y * 0.2419, uDir.x * 0.2419 + uDir.y * 0.9703);

    // Amplitude falls off faster than wavelength does, so the short
    // components ripple the surface instead of chopping it up.
    trochoid(d0, uAmp * 1.00, uLambda * 1.00, uOmega * 1.0000, uChop, p, uTime, disp, nrm, steep);
    trochoid(d1, uAmp * 0.38, uLambda * 0.62, uOmega * 1.2700, uChop, p, uTime, disp, nrm, steep);
    trochoid(d2, uAmp * 0.16, uLambda * 0.34, uOmega * 1.7150, uChop, p, uTime, disp, nrm, steep);
    trochoid(d3, uAmp * 0.44, uLambda * 1.52, uOmega * 0.8111, uChop, p, uTime, disp, nrm, steep);

    // Waves flatten as they run out of water, and stop entirely at the
    // waterline. This one IS physical in shape if not in scale: no
    // seabed, no shoaling limit.
    float shelf = smoothstep(0.0, 14.0, max(aDepth, 0.0));
    disp *= mix(0.0, 1.0, smoothstep(-0.2, 1.5, aDepth)) * mix(0.35, 1.0, shelf);

    vec3 pos = position + disp;
    vec4 world = modelMatrix * vec4(pos, 1.0);

    vDepth = aDepth;
    vFoam = steep;
    vWorld = world.xyz;
    vNrm = normalize(nrm);
    vElev = disp.y;

    gl_Position = projectionMatrix * viewMatrix * world;
  }
`;

const WATER_FRAGMENT_SHADER = /* glsl */ `
  uniform vec3  uShallow;
  uniform vec3  uDeep;
  uniform vec3  uFoam;
  uniform vec3  uSunDir;
  uniform float uTime;
  uniform float uAmp;
  uniform float uFoamGain;     // 0..1 whitecap coverage, from real wave height
  uniform float uDenyMix;      // 0..1 -- how far into hard-deny territory
  uniform float uHypothetical; // 1.0 when the height is a user hypothesis

  varying float vDepth;
  varying float vFoam;
  varying vec3  vWorld;
  varying vec3  vNrm;
  varying float vElev;

  ${SKY_GLSL}

  // Cheap value noise. Only ever breaks up foam edges -- foam is the one
  // place a hard analytic threshold reads unmistakably as computer
  // graphics rather than as water.
  float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
  }

  float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(
      mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
      mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x),
      u.y
    );
  }

  void main() {
    // --- 0. The coastline ---
    // vDepth is signed, so land is negative and there is simply no water
    // to draw there. The narrow ramp either side of zero is the wet sand
    // the swash runs over; without it the coast aliases into a stair.
    float wet = smoothstep(-0.35, 1.1, vDepth);
    if (wet < 0.012) discard;

    vec3 N = normalize(vNrm);
    vec3 V = normalize(cameraPosition - vWorld);
    vec3 L = normalize(uSunDir);

    // --- 1. Body colour: Beer-Lambert absorption through real depth ---
    // Red is absorbed an order of magnitude faster than blue, which is
    // the entire reason deep water is blue. One exp() buys more
    // perceived realism than any texture would. Depth is clamped so the
    // continental shelf still reads as a gradient instead of saturating
    // to black a few hundred metres out.
    float d = clamp(vDepth, 0.0, 90.0);
    vec3 transmit = exp(-vec3(0.085, 0.028, 0.016) * d);
    vec3 body = mix(uDeep, uShallow, transmit);

    // --- 2. Reflection: the actual sky that is drawn behind the sea ---
    vec3 R = reflect(-V, N);
    R.y = max(R.y, 0.008); // never sample below the horizon
    vec3 reflection = orcaSky(normalize(R), L);

    // Schlick, F0 = 0.02 for a water/air interface. This is why a calm
    // sea near the horizon reads as pure sky and the water under your
    // feet reads as water.
    float fresnel = 0.02 + 0.98 * pow(1.0 - clamp(dot(N, V), 0.0, 1.0), 5.0);

    // --- 3. Subsurface scattering through the crests ---
    // Light entering the back of a wave and leaving the front is what
    // makes real swell glow green at the top. Scaled by how high this
    // vertex sits, so only crests light up -- and it therefore
    // intensifies with wave height, which is the point of the sandbox.
    float lift = clamp(vElev / max(uAmp, 0.001), 0.0, 1.4);
    float back = pow(clamp(dot(V, -L) * 0.5 + 0.5, 0.0, 1.0), 3.5);
    vec3 sss = vec3(0.10, 0.62, 0.48) * back * lift * 0.75;

    // --- 4. Sun glitter ---
    // The reflected sky already carries the sun disc, so the specular
    // track comes for free and lands in the physically right place; this
    // is just a tighter highlight on top of it.
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 220.0) * 2.4;

    // --- 5. Foam ---
    // Gated by uFoamGain, which the CPU derives from the real wave
    // height. Whitecaps are a sea-state signal, not decoration: a 0.8 m
    // slight sea has essentially none and a 5 m sea is covered in them,
    // so the slider changes the CHARACTER of the water and not just its
    // amplitude. Painting whitecaps on a calm sea would be the visual
    // equivalent of inventing a reading.
    vec2 fp = vWorld.xz * 5.5;
    float n = valueNoise(fp + uTime * 0.16) * 0.6 + valueNoise(fp * 2.7 - uTime * 0.09) * 0.4;
    float crest = smoothstep(0.66, 1.15, vFoam * (0.5 + n * 0.9)) * uFoamGain;
    // Surf, not a white shelf: a band that hugs the waterline itself,
    // fading out both seaward and onto the sand, and only where there is
    // wave energy to break.
    float shore = (1.0 - smoothstep(0.2, 2.2, vDepth))
                * smoothstep(-0.1, 0.6, vDepth)
                * smoothstep(0.42, 0.88, n)
                * (0.25 + uFoamGain * 0.5);
    float foam = clamp(max(crest, shore), 0.0, 1.0);

    vec3 col = mix(body + sss, reflection, fresnel);
    col += spec;
    col = mix(col, uFoam, foam);

    // Past the 2.5 m limit the sea darkens and cools rather than
    // reddening. Red over the whole surface fought the mauve for the
    // worst artefact in the frame, and the refusal is already carried by
    // the rail, the beacons, the panel and the verdict -- the water only
    // needs to look meaner, which is what breaking crests already do.
    col = mix(col, col * vec3(0.72, 0.80, 0.88), uDenyMix * 0.55);
    // A hypothetical sea is marked off-hue on purpose -- a fabricated
    // number must never be screenshot-able as a measured one (PRD P8) --
    // but the loud half of that job belongs to the panel and the badge.
    // Here it is a slight violet cast; any stronger and the water stops
    // looking like water, which is its own kind of dishonesty.
    col = mix(col, col * vec3(1.05, 0.95, 1.14), uHypothetical * 0.7);

    // --- 6. Aerial perspective ---
    // Distant water fades into the sky in the direction you are looking,
    // which is what gives the diorama a horizon instead of an edge.
    // Clamped to the upper hemisphere for the same reason as the terrain
    // shader: airlight comes from the sky, and sampling below the
    // horizon returns orcaSky()'s near-black nadir.
    float dist = length(cameraPosition - vWorld);
    vec3 hazeDir = normalize(vec3(-V.x, max(-V.y, 0.03), -V.z));
    float haze = 1.0 - exp(-dist * 0.026);
    col = mix(col, orcaSky(hazeDir, L), haze * 0.5);

    // Kept well under 1.0 even in deep water: the ETOPO relief beneath is
    // half the point of the view, and an opaque sea hides it. Multiplied
    // by the wet mask so the sheet thins to nothing as it reaches the
    // beach instead of ending on a hard line.
    float alpha = mix(0.42, 0.86, smoothstep(0.0, 30.0, max(vDepth, 0.0)));
    gl_FragColor = vec4(col, max(alpha, foam * 0.92) * wet);
  }
`;

// ---------------------------------------------------------------------
// Free-fly camera
// ---------------------------------------------------------------------
// Drag to look, WASD to move, Q/E for altitude. Written here rather than
// pulled from three/addons because FlyControls rolls the camera on drag
// (there is no dragToLook without roll) and FirstPersonControls steers
// from raw pointer position, which means the view drifts whenever the
// mouse is merely resting over the canvas. Neither is what you want for
// flying over a coastline in front of an audience.
//
// Yaw/pitch are held as scalars and written to the camera each frame, so
// the horizon can never tilt -- the one property that matters most for a
// scene whose subject is a sea surface.
class FreeFlyController {
  constructor(camera, domElement) {
    this.camera = camera;
    this.dom = domElement;
    this.enabled = false;

    this.speed = 6.0;         // scene units/second at normal throttle
    this.lookSensitivity = 0.0028;
    this.damping = 12.0;

    this._yaw = 0;
    this._pitch = 0;
    this._velocity = new THREE.Vector3();
    this._keys = new Set();
    this._dragging = false;
    this._last = { x: 0, y: 0 };

    this._onKeyDown = (e) => {
      if (!this.enabled) return;
      // The listener is on window, so without this the flight keys would
      // swallow every W, A, S and D the user types into the ask bar --
      // and preventDefault() would stop the characters appearing at all.
      const el = e.target;
      const tag = el && el.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || (el && el.isContentEditable)) return;

      const k = e.key.toLowerCase();
      if (FreeFlyController.KEYS.has(k)) {
        this._keys.add(k);
        e.preventDefault();
      }
    };
    this._onKeyUp = (e) => this._keys.delete(e.key.toLowerCase());
    this._onBlur = () => this._keys.clear();

    this._onPointerDown = (e) => {
      if (!this.enabled || e.button !== 0) return;
      this._dragging = true;
      this._last = { x: e.clientX, y: e.clientY };
      this.dom.setPointerCapture?.(e.pointerId);
      this.dom.style.cursor = "grabbing";
    };
    this._onPointerMove = (e) => {
      if (!this.enabled || !this._dragging) return;
      this._yaw -= (e.clientX - this._last.x) * this.lookSensitivity;
      this._pitch -= (e.clientY - this._last.y) * this.lookSensitivity;
      // Just short of straight up/down: at exactly +/-90 degrees the
      // yaw axis degenerates and the view snaps.
      this._pitch = Math.max(-1.553, Math.min(1.553, this._pitch));
      this._last = { x: e.clientX, y: e.clientY };
    };
    this._onPointerUp = (e) => {
      this._dragging = false;
      this.dom.releasePointerCapture?.(e.pointerId);
      this.dom.style.cursor = this.enabled ? "grab" : "";
    };
    this._onWheel = (e) => {
      if (!this.enabled) return;
      e.preventDefault();
      // Throttle, not dolly: scrolling changes how fast you fly, which
      // is what you actually want when crossing a 30-unit seascape.
      this.speed = Math.max(1.0, Math.min(40, this.speed * (e.deltaY > 0 ? 0.88 : 1.14)));
    };
  }

  static KEYS = new Set(["w", "a", "s", "d", "q", "e", " ", "shift"]);

  enable() {
    if (this.enabled) return;
    this.enabled = true;
    // Adopt whatever the orbit camera was looking at, so switching modes
    // never jumps the view.
    const dir = new THREE.Vector3();
    this.camera.getWorldDirection(dir);
    this._yaw = Math.atan2(-dir.x, -dir.z);
    this._pitch = Math.asin(Math.max(-1, Math.min(1, dir.y)));
    this._velocity.set(0, 0, 0);

    window.addEventListener("keydown", this._onKeyDown);
    window.addEventListener("keyup", this._onKeyUp);
    window.addEventListener("blur", this._onBlur);
    this.dom.addEventListener("pointerdown", this._onPointerDown);
    this.dom.addEventListener("pointermove", this._onPointerMove);
    this.dom.addEventListener("pointerup", this._onPointerUp);
    this.dom.addEventListener("wheel", this._onWheel, { passive: false });
    this.dom.style.cursor = "grab";
  }

  disable() {
    if (!this.enabled) return;
    this.enabled = false;
    this._keys.clear();
    this._dragging = false;
    window.removeEventListener("keydown", this._onKeyDown);
    window.removeEventListener("keyup", this._onKeyUp);
    window.removeEventListener("blur", this._onBlur);
    this.dom.removeEventListener("pointerdown", this._onPointerDown);
    this.dom.removeEventListener("pointermove", this._onPointerMove);
    this.dom.removeEventListener("pointerup", this._onPointerUp);
    this.dom.removeEventListener("wheel", this._onWheel);
    this.dom.style.cursor = "";
  }

  update(dt) {
    if (!this.enabled) return;
    const step = Math.min(dt, 0.1); // a backgrounded tab must not teleport

    this.camera.quaternion.setFromEuler(
      new THREE.Euler(this._pitch, this._yaw, 0, "YXZ")
    );

    const forward = new THREE.Vector3(0, 0, -1).applyQuaternion(this.camera.quaternion);
    const right = new THREE.Vector3(1, 0, 0).applyQuaternion(this.camera.quaternion);

    const wish = new THREE.Vector3();
    if (this._keys.has("w")) wish.add(forward);
    if (this._keys.has("s")) wish.sub(forward);
    if (this._keys.has("d")) wish.add(right);
    if (this._keys.has("a")) wish.sub(right);
    if (this._keys.has("e") || this._keys.has(" ")) wish.y += 1;
    if (this._keys.has("q")) wish.y -= 1;

    if (wish.lengthSq() > 0) {
      wish.normalize().multiplyScalar(this.speed * (this._keys.has("shift") ? 3.0 : 1.0));
    }
    // Exponential approach rather than a hard set, so starts and stops
    // glide instead of snapping.
    this._velocity.lerp(wish, 1 - Math.exp(-this.damping * step));
    this.camera.position.addScaledVector(this._velocity, step);
  }
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
  // A camera drag ends in a "click" too, so without this guard orbiting
  // or free-looking across a zone marker fires a whole new /ask. Only a
  // pointer that barely moved counts as a deliberate pick.
  let pressAt = null;
  viz.renderer.domElement.addEventListener("pointerdown", (event) => {
    pressAt = { x: event.clientX, y: event.clientY };
  });
  viz.renderer.domElement.addEventListener("click", (event) => {
    const moved = pressAt
      ? Math.hypot(event.clientX - pressAt.x, event.clientY - pressAt.y)
      : 0;
    pressAt = null;
    if (moved > 5) return;

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
    // Elapsed time is accumulated by hand instead of using
    // Clock.getElapsedTime(), because that method calls getDelta()
    // internally -- calling both in one frame double-advances the clock
    // and makes the waves run at twice speed.
    this._elapsed = 0;
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
    // Subclasses may opt into postprocessing by setting this._composer;
    // when they do it owns the final draw and has to track the canvas.
    if (this._composer) this._composer.setSize(w, h);
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
    this._freeFly?.disable(); // its listeners live on window, not the canvas
    this._resizeObserver.disconnect();
    this._composer?.dispose?.();
    this.renderer.dispose();
  }

  _tick() {
    if (!this._active) return;
    const dt = this._clock.getDelta();
    this._elapsed += dt;
    const t = this._elapsed;
    const pulse = 1 + Math.sin(t * 2.2) * 0.08;
    this._pulseTargets.forEach((obj) => obj.scale.setScalar(pulse));
    if (this._onTick) this._onTick(t, dt);
    // OrbitControls writes the camera transform every update(), so the
    // two controllers must never both run: whichever is disabled stays
    // silent rather than fighting for the camera each frame.
    if (this._freeFly?.enabled) this._freeFly.update(dt);
    else this.controls.update();
    if (this._composer) this._composer.render();
    else this.renderer.render(this.scene, this.camera);
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

    // NON-PERMISSIVE default, mirroring actionClass() in index.html.
    // COLOR_LOW is the GO green, so `|| COLOR_LOW` painted CANNOT ASSESS
    // -- and every future action the amended R-25 allows -- in the one
    // colour that means "safe to go". R-39 introduced CANNOT ASSESS
    // precisely so ignorance is never rendered as safety; this view was
    // still undoing that.
    const coreColor = ACTION_COLOR[recommendation.action] || COLOR_UNKNOWN;
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
    super(container, { cameraPosition: [-8.5, 7.0, 15.5], autoRotateSpeed: 0.22 });
    // A little under the horizon, so the camera can drop almost to sea
    // level for the dramatic angle without ever going below the seabed.
    this.controls.maxPolarAngle = Math.PI * 0.495;
    this.controls.minDistance = 4;
    this.controls.maxDistance = 40;
    this.controls.target.set(0, 0.1, 0);

    // Filmic response. The water shader writes genuine HDR values (the
    // sun disc peaks around 26.0), so without tone mapping every
    // specular highlight clips to a flat white blob.
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.05;

    this._sunDir = SUN_DIRECTION.clone();

    // Sky dome, drawn from the same orcaSky() the water reflects.
    const sky = new THREE.Mesh(
      new THREE.SphereGeometry(90, 32, 20),
      new THREE.ShaderMaterial({
        vertexShader: SKY_VERTEX_SHADER,
        fragmentShader: SKY_FRAGMENT_SHADER,
        uniforms: { uSunDir: { value: this._sunDir } },
        side: THREE.BackSide,
        depthWrite: false,
      })
    );
    sky.renderOrder = -1;
    this.scene.add(sky);

    // Sky-above / seabed-below bounce, which is what stops the terrain
    // reading as a grey lump under a blue sky.
    // A low sun leaves most of a coarse, faceted coastline facing away
    // from it, so the ambient budget has to be generous or the land
    // renders as black shards. Sky-above / ground-below bounce does most
    // of the work; the flat ambient is the floor under everything.
    this.scene.add(new THREE.HemisphereLight(0x9cc0dc, 0x4a4436, 1.5));
    this.scene.add(new THREE.AmbientLight(0xbcd4e4, 0.45));
    const sun = new THREE.DirectionalLight(0xffdcae, 1.5);
    sun.position.copy(this._sunDir).multiplyScalar(40);
    this.scene.add(sun);
    // Cool fill from the opposite side so the shadowed coast keeps shape
    // instead of going black.
    const fill = new THREE.DirectionalLight(0x8fbde0, 0.75);
    fill.position.set(12, 8, 10);
    this.scene.add(fill);
    // Headlight, repositioned onto the camera every frame. With a fixed
    // low sun the coast is backlit from roughly half the orbit, and no
    // amount of ambient stops a faceted relief going to mud there. This
    // guarantees whatever you are looking at is lit from where you are
    // looking. Kept dim so the sun still owns the modelling.
    this._headlight = new THREE.DirectionalLight(0xdCEAF6, 0.55);
    this.scene.add(this._headlight);

    // Bloom, kept tight: it exists to make the sun's specular track and
    // the hard-deny beacons glow, not to smear the whole frame.
    this._composer = new EffectComposer(this.renderer);
    this._composer.addPass(new RenderPass(this.scene, this.camera));
    // (resolution, strength, radius, threshold). The threshold sits well
    // above 1.0 on purpose: the pass runs on linear HDR before OutputPass
    // tone-maps, so anything lower would bloom ordinary lit terrain.
    this._bloom = new UnrealBloomPass(new THREE.Vector2(1, 1), 0.30, 0.50, 1.60);
    this._composer.addPass(this._bloom);
    this._composer.addPass(new OutputPass());

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

    // --- environment sandbox state -------------------------------
    // `_baselineWave` is whatever the last /ask response actually
    // observed; `_wave` is what is currently being drawn. They differ
    // only while the user is holding a hypothesis, and `hypothetical`
    // is derived from that difference rather than tracked separately,
    // so the flag cannot drift out of sync with the geometry.
    this._freeFly = new FreeFlyController(this.camera, this.renderer.domElement);

    this._baselineWave = null;
    this._wave = { heightM: 0.8, periodS: 7.0, directionDeg: 150 };
    this._water = null;
    this._denyRailMat = null;
    this._onWaveChange = null;
  }

  // "orbit"  -- OrbitControls circling the diorama, the default.
  // "free"   -- fly anywhere: drag to look, WASD to move, Q/E altitude.
  // Switching adopts the current view either way, so the camera never
  // jumps; the free controller reads the orbit camera's heading on
  // enable, and orbit re-aims its target down the free camera's own
  // sightline on return.
  setCameraMode(mode) {
    const free = mode === "free";
    if (free === this._freeFly.enabled) return;

    if (free) {
      this.controls.enabled = false;
      this.controls.autoRotate = false;
      this._freeFly.enable();
    } else {
      this._freeFly.disable();
      const dir = new THREE.Vector3();
      this.camera.getWorldDirection(dir);
      // Put the orbit pivot a sensible distance ahead of wherever the
      // free camera ended up, rather than snapping back to the origin.
      this.controls.target.copy(this.camera.position).addScaledVector(dir, 12);
      this.controls.enabled = true;
      this.controls.update();
    }
    return free;
  }

  get cameraMode() {
    return this._freeFly.enabled ? "free" : "orbit";
  }

  setAutoRotate(on) {
    if (!this._freeFly.enabled) this.controls.autoRotate = !!on;
  }

  resetCamera() {
    this._freeFly.disable();
    this.camera.position.set(-8.5, 7.0, 15.5);
    this.controls.target.set(0, 0.1, 0);
    this.controls.enabled = true;
    this.controls.update();
  }

  get waveState() {
    return {
      ...this._wave,
      baseline: this._baselineWave ? { ...this._baselineWave } : null,
      hypothetical: this.isHypothetical,
    };
  }

  get isHypothetical() {
    if (!this._baselineWave) return false;
    return Math.abs(this._wave.heightM - this._baselineWave.heightM) > 1e-6;
  }

  _lonToX(lon) {
    return ((lon - this._bbox.min_lon) / (this._bbox.max_lon - this._bbox.min_lon)) * this._width - this._width / 2;
  }

  _latToZ(lat) {
    return ((this._bbox.max_lat - lat) / (this._bbox.max_lat - this._bbox.min_lat)) * this._depth - this._depth / 2;
  }

  // Land and seabed get DIFFERENT vertical treatments, because they have
  // different jobs here. Below water the scale stays linear -- depth is
  // what the Beer-Lambert tint reads and what makes the shelf legible.
  // Above water a plain 1/1200 turns the whole Coromandel coast into a
  // 0.1-unit smear against a 2 km shelf drop, so land runs through a
  // saturating curve instead: the coastal plain every zone actually sits
  // on gets real relief, and the Western Ghats still cap out inside the
  // frame rather than spearing through the sky.
  _elevToY(elev) {
    if (elev <= 0) return elev * this._elevationScale;
    return LAND_PLATE_UNITS;
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
    const elevations = new Float32Array(rows * cols);
    // Elevation travels to the GPU in real metres and the hypsometric
    // ramp is evaluated per fragment (see TERRAIN_FRAGMENT_SHADER).
    // Interpolating colour between vertices 7.4 km apart smeared the
    // shoreline across kilometres of beach; interpolating the elevation
    // and colouring afterwards puts the band exactly at 0 m.
    let p = 0;
    for (let i = 0; i < rows; i++) {
      for (let j = 0; j < cols; j++) {
        const elev = grid[i][j];
        positions[p++] = this._lonToX(lons[j]);
        positions[p++] = this._elevToY(elev);
        positions[p++] = this._latToZ(lats[i]);
        elevations[i * cols + j] = elev;
      }
    }

    const indices = [];
    for (let i = 0; i < rows - 1; i++) {
      for (let j = 0; j < cols - 1; j++) {
        const a = i * cols + j;
        const b = a + 1;
        const cIdx = a + cols;
        const d = cIdx + 1;
        // Winding matters twice over here, and getting it backwards is
        // what made the landmass render black.
        //
        // j runs east so x increases with it, but i runs north and
        // _latToZ() maps increasing latitude to DECREASING z -- so the
        // grid's (i, j) order is already mirrored in world space. Winding
        // a-cIdx-b on top of that put every face's normal at -Y: the mesh
        // was back-facing from above, ShaderMaterial culls back faces by
        // default, and the terrain simply was not drawn. What showed
        // through the hole was _buildSkirt()'s near-black block walls,
        // which are DoubleSide -- hence "the land is a black mass".
        //
        // The same -Y normals also fed the shader, where the land branch
        // reads N.y for its top-vs-bevel mix and the seabed dots N
        // against the sun, so no amount of lighting work could have
        // fixed it. a-b-cIdx winds counter-clockwise seen from above:
        // normals +Y, faces front, land buff, seabed lit.
        indices.push(a, b, cIdx, b, d, cIdx);
      }
    }

    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute("position", new THREE.BufferAttribute(positions, 3));
    geometry.setAttribute("aElev", new THREE.BufferAttribute(elevations, 1));
    geometry.setIndex(indices);
    geometry.computeVertexNormals();

    const mesh = new THREE.Mesh(
      geometry,
      new THREE.ShaderMaterial({
        vertexShader: TERRAIN_VERTEX_SHADER,
        fragmentShader: TERRAIN_FRAGMENT_SHADER,
        uniforms: { uSunDir: { value: this._sunDir } },
      })
    );
    mesh.userData.tooltip = null;
    this._terrain.add(mesh);

    this._buildSkirt(lats, lons, grid);
    this._buildWaterMesh();
  }

  // Walls dropped from the four edges of the relief down to a flat base,
  // plus a floor. Without them the bathymetry is an infinitely thin sheet
  // and the whole view reads as a rectangle floating in the sky; with
  // them it reads as a block of seafloor lifted out of the coast, which
  // is what it actually is.
  _buildSkirt(lats, lons, grid) {
    const rows = lats.length;
    const cols = lons.length;
    let minElev = Infinity;
    for (let i = 0; i < rows; i++) {
      for (let j = 0; j < cols; j++) minElev = Math.min(minElev, grid[i][j]);
    }
    const baseY = this._elevToY(minElev) - 0.35;

    const positions = [];
    const colors = [];
    const top = new THREE.Color(0x243b4d);
    const bottom = new THREE.Color(0x0a141d);

    const push = (x, y, z, t) => {
      positions.push(x, y, z);
      const c = bottom.clone().lerp(top, t);
      colors.push(c.r, c.g, c.b);
    };

    // Walk each border, emitting a quad per segment.
    const wall = (aLat, aLon, bLat, bLon) => {
      const ax = this._lonToX(aLon), az = this._latToZ(aLat), ay = this._elevToY(this._heightAt(aLat, aLon));
      const bx = this._lonToX(bLon), bz = this._latToZ(bLat), by = this._elevToY(this._heightAt(bLat, bLon));
      push(ax, ay, az, 1); push(bx, by, bz, 1); push(bx, baseY, bz, 0);
      push(ax, ay, az, 1); push(bx, baseY, bz, 0); push(ax, baseY, az, 0);
    };

    for (let j = 0; j < cols - 1; j++) {
      wall(lats[0], lons[j], lats[0], lons[j + 1]);
      wall(lats[rows - 1], lons[j + 1], lats[rows - 1], lons[j]);
    }
    for (let i = 0; i < rows - 1; i++) {
      wall(lats[i + 1], lons[0], lats[i], lons[0]);
      wall(lats[i], lons[cols - 1], lats[i + 1], lons[cols - 1]);
    }

    // Floor.
    const x0 = this._lonToX(lons[0]), x1 = this._lonToX(lons[cols - 1]);
    const z0 = this._latToZ(lats[0]), z1 = this._latToZ(lats[rows - 1]);
    push(x0, baseY, z0, 0); push(x1, baseY, z1, 0); push(x1, baseY, z0, 0);
    push(x0, baseY, z0, 0); push(x0, baseY, z1, 0); push(x1, baseY, z1, 0);

    const geom = new THREE.BufferGeometry();
    geom.setAttribute("position", new THREE.Float32BufferAttribute(positions, 3));
    geom.setAttribute("color", new THREE.Float32BufferAttribute(colors, 3));
    geom.computeVertexNormals();
    this._terrain.add(
      new THREE.Mesh(
        geom,
        new THREE.MeshStandardMaterial({
          vertexColors: true,
          roughness: 1.0,
          metalness: 0.0,
          side: THREE.DoubleSide,
        })
      )
    );
  }

  // The sandbox surface. Rebuilt only when the bathymetry changes --
  // moving the wave slider just writes uniforms, so dragging it is a
  // uniform update per frame, not a geometry rebuild.
  _buildWaterMesh() {
    const seaW = this._width * WATER_OVERSCAN;
    const seaD = this._depth * WATER_OVERSCAN;
    const geometry = new THREE.PlaneGeometry(seaW, seaD, WATER_SEGMENTS_X, WATER_SEGMENTS_Z);
    geometry.rotateX(-Math.PI / 2);

    // Bake the real seabed depth under every water vertex, so the
    // fragment shader's Beer-Lambert tint and the shoaling falloff are
    // driven by ERDDAP's actual ETOPO relief rather than a painted
    // gradient. Same _heightAt() the risk columns stand on.
    const pos = geometry.getAttribute("position");
    const depths = new Float32Array(pos.count);
    const { min_lat, max_lat, min_lon, max_lon } = this._bbox;
    // SIGNED, not clamped: negative over land. A clamped 0 reads to the
    // shader as "zero metres of water", which is not the same statement
    // as "no water here" -- it got the shallow tint and the surf line and
    // laid a white sheet over the whole coastal plain. The sign is what
    // lets the surface end at the real coastline.
    const OPEN_OCEAN_M = 120; // past the surveyed block: deep, and drawn as such
    for (let i = 0; i < pos.count; i++) {
      const x = pos.getX(i);
      const z = pos.getZ(i);
      const insideX = Math.abs(x) <= this._width / 2;
      const insideZ = Math.abs(z) <= this._depth / 2;
      if (insideX && insideZ) {
        const lon = min_lon + ((x + this._width / 2) / this._width) * (max_lon - min_lon);
        const lat = max_lat - ((z + this._depth / 2) / this._depth) * (max_lat - min_lat);
        depths[i] = -this._heightAt(lat, lon);
      } else {
        // Ease outwards over one block-width so the surveyed area does
        // not end in a visible ring.
        const outX = Math.max(0, Math.abs(x) - this._width / 2) / (this._width / 2);
        const outZ = Math.max(0, Math.abs(z) - this._depth / 2) / (this._depth / 2);
        const t = Math.min(1, Math.hypot(outX, outZ));
        const edgeLon = Math.min(Math.max(min_lon + ((x + this._width / 2) / this._width) * (max_lon - min_lon), min_lon), max_lon);
        const edgeLat = Math.min(Math.max(max_lat - ((z + this._depth / 2) / this._depth) * (max_lat - min_lat), min_lat), max_lat);
        const edgeDepth = -this._heightAt(edgeLat, edgeLon);
        // Only WATER may deepen outwards. Where the surveyed block ends
        // on land, the land is what continues past it -- so hold the
        // edge value and let the fragment shader's `wet` test discard,
        // exactly as it does for land inside the block.
        //
        // Blending unconditionally drew the sea over inland India. The
        // cached ETOPO bbox spans 76.9-80.6E / 7.8-13.4N because Colachel
        // sits on the Arabian Sea side, which drags the western edge
        // across the Western Ghats: 87% of the west edge and 93% of the
        // north edge are land, up to 1,929 m. Easing those toward
        // OPEN_OCEAN_M put a waterline 94% of the way out into the ring
        // and full-amplitude waves over the Ghats beyond it -- geography
        // ORCA does not have data for, drawn as though it did.
        depths[i] = edgeDepth > 0
          ? edgeDepth + (OPEN_OCEAN_M - edgeDepth) * t
          : edgeDepth;
      }
    }
    geometry.setAttribute("aDepth", new THREE.BufferAttribute(depths, 1));

    const material = new THREE.ShaderMaterial({
      vertexShader: WATER_VERTEX_SHADER,
      fragmentShader: WATER_FRAGMENT_SHADER,
      transparent: true,
      side: THREE.DoubleSide,
      uniforms: {
        uTime: { value: 0 },
        uAmp: { value: 0.05 },
        uLambda: { value: 1.4 },
        uOmega: { value: 0.9 },
        uChop: { value: 0.75 },
        uDir: { value: new THREE.Vector2(0, 1) },
        uSunDir: { value: this._sunDir },
        uShallow: { value: new THREE.Color(0x36c6b4) },
        // Blue-green rather than navy: a navy body under a warm sky
        // reflection is precisely what mixed to mauve.
        uDeep: { value: new THREE.Color(0x052c42) },
        uFoam: { value: new THREE.Color(0xf2fafd) },
        uFoamGain: { value: 0 },
        uDenyMix: { value: 0 },
        uHypothetical: { value: 0 },
      },
    });

    const water = new THREE.Mesh(geometry, material);
    water.position.y = 0.02;
    water.renderOrder = 2;
    this._terrain.add(water);
    this._water = water;

    // The 2.5 m line, drawn in the world rather than only on the Douglas
    // ruler: a plane the sea visibly rises through. Same constant as
    // orca/agents.py WAVE_HARD_DENY_M -- see that file's comment for why
    // 2.5 m is the real Douglas 4/5 boundary and not an invented cutoff.
    // A FRAME at the survey block's edge, not a sheet over the sea.
    //
    // Drawn as a filled plane this washed the entire view red: it is a
    // horizontal surface, the camera looks across it at a grazing angle,
    // and every pixel of ocean ends up behind it. A level is better
    // stated by an edge you can sight along than by a tint over the
    // thing you are trying to look at.
    const denyY = 0.02 + WAVE_HARD_DENY_M * WAVE_UNITS_PER_M;
    const hw = this._width / 2;
    const hd = this._depth / 2;
    const rail = 0.045;
    const deny = new THREE.Group();
    const railMat = new THREE.MeshBasicMaterial({
      color: 0xd4441f,
      transparent: true,
      opacity: 0.55,
      depthWrite: false,
    });
    [
      [this._width, rail, 0, -hd],
      [this._width, rail, 0, hd],
      [rail, this._depth, -hw, 0],
      [rail, this._depth, hw, 0],
    ].forEach(([w, d, x, z]) => {
      const bar = new THREE.Mesh(new THREE.BoxGeometry(w, 0.012, d), railMat);
      bar.position.set(x, denyY, z);
      deny.add(bar);
    });
    deny.renderOrder = 3;
    this._terrain.add(deny);
    this._denyRailMat = railMat;

    const denyLabel = makeTextSprite("2.5 m — ORCA stops here", {
      fontSize: 26,
      color: "#ffffff",
      bg: "rgba(164,50,29,0.9)",
    });
    denyLabel.scale.multiplyScalar(0.7);
    denyLabel.position.set(-this._width / 2 + 1.2, denyY + 0.12, this._depth / 2 - 0.7);
    this._terrain.add(denyLabel);
    this._denyGroup = deny;

    this._applyWaveUniforms();
  }

  // Push `this._wave` into the shader. Pure presentation: no rounding,
  // no clamping of the underlying value, and no decision made here.
  _applyWaveUniforms() {
    if (!this._water) return;
    const u = this._water.material.uniforms;
    const { heightM, periodS, directionDeg } = this._wave;

    // Gerstner amplitude is mean-to-crest, so half the significant
    // height, which is by definition crest-to-trough.
    u.uAmp.value = Math.max(0.004, (heightM * WAVE_UNITS_PER_M) / 2);

    const l0 = deepWaterWavelengthM(Math.max(periodS, 0.5));
    const t = Math.min(Math.max((l0 - L0_MIN_M) / (L0_MAX_M - L0_MIN_M), 0), 1);
    u.uLambda.value = WAVE_LAMBDA_MIN_UNITS + t * (WAVE_LAMBDA_MAX_UNITS - WAVE_LAMBDA_MIN_UNITS);

    // The one quantity drawn at true scale.
    u.uOmega.value = (2 * Math.PI) / Math.max(periodS, 0.5);

    // Steeper seas pinch their crests; flat swell barely does. Deep-water
    // steepness Hs/L0 runs ~0.005-0.04 in ORCA's cache, so this maps that
    // real range onto the visual chop rather than picking a constant.
    const steepness = heightM / Math.max(l0, 1);
    u.uChop.value = Math.min(0.35 + steepness * 14, 0.95);

    // Meteorological convention: direction waves come FROM, degrees
    // clockwise from north.
    const rad = (directionDeg * Math.PI) / 180;
    u.uDir.value.set(Math.sin(rad), Math.cos(rad));

    // Whitecap coverage. Douglas 3 "Slight" (up to 1.25 m) is scattered
    // whitecaps at most; by Douglas 5 "Rough" the sea is covered. Ramping
    // between them keeps the surface an honest read of the number.
    u.uFoamGain.value = Math.min(Math.max((heightM - 1.1) / 3.0, 0), 1);

    u.uDenyMix.value = Math.min(Math.max((heightM - WAVE_HARD_DENY_M) / 1.5, 0), 1);
    const hyp = this.isHypothetical;
    u.uHypothetical.value = hyp ? 1 : 0;
    // The whitecaps carry the marker too. Foam is the brightest thing on
    // the water, so tinting it is the cheapest way to make a hypothetical
    // sea unmistakable at a glance without muddying the water colour.
    u.uFoam.value.set(hyp ? 0xf0e2ff : 0xf2fafd);

    // The limit rail earns its prominence only when it is nearly in
    // play. On a calm sea it is a faint reference edge; as the slider
    // climbs towards 2.5 m it brightens, which is the moment worth
    // looking at.
    if (this._denyRailMat) {
      const near = Math.min(Math.max((heightM - 1.0) / 1.5, 0), 1);
      this._denyRailMat.opacity = 0.3 + near * 0.4 + (heightM > WAVE_HARD_DENY_M ? 0.3 : 0);
    }
  }

  // Seed the sandbox from a real /ask response. This is the "ingest"
  // half: the slider always starts on the measured sea state, so a
  // hypothesis is explicitly a departure from evidence, never a value
  // conjured from nothing.
  setWaveFromEvidence(evidence) {
    const pick = (variable) => {
      const hit = (evidence || []).find((o) => o && o.variable === variable);
      return hit && Number.isFinite(hit.value) ? hit.value : null;
    };
    const heightM = pick("wave_height_m");
    if (heightM === null) return false; // absent, never invented (CLAUDE.md rule 1)

    this._baselineWave = {
      heightM,
      periodS: pick("wave_period_s") ?? 7.0,
      directionDeg: pick("wave_direction_deg") ?? 150,
    };
    this._wave = { ...this._baselineWave };
    this._applyWaveUniforms();
    this._emitWaveChange();
    return true;
  }

  // The "apply it in the sandbox" half. Height only: it is the hard-deny
  // variable and is causally downstream of everything else, so "suppose
  // the sea were 3 m, however it got there" is a complete hypothesis that
  // makes no claim about the world. Wind is deliberately NOT wired to
  // waves -- see SimulationR.md section 3 for why that coupling cannot be
  // made honest with the data ORCA holds.
  setHypotheticalWaveHeight(heightM) {
    if (!Number.isFinite(heightM)) return;
    this._wave = { ...this._wave, heightM };
    this._applyWaveUniforms();
    this._emitWaveChange();
  }

  resetWave() {
    if (!this._baselineWave) return;
    this._wave = { ...this._baselineWave };
    this._applyWaveUniforms();
    this._emitWaveChange();
  }

  onWaveChange(callback) {
    this._onWaveChange = callback;
    this._emitWaveChange();
  }

  _emitWaveChange() {
    if (this._onWaveChange) this._onWaveChange(this.waveState);
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
        new THREE.CylinderGeometry(0.075, 0.14, height, 20),
        new THREE.MeshStandardMaterial({
          color,
          emissive: color,
          // Bloom picks these up, so the risk ranking reads at a glance
          // from any camera angle -- the taller and hotter the beam, the
          // worse the zone.
          emissiveIntensity: zone.hard_deny ? 2.6 : 0.55 + zone.risk_level * 1.5,
          roughness: 0.35,
          metalness: 0.1,
          transparent: true,
          opacity: 0.95,
        })
      );
      col.position.set(x, baseY + height / 2, z);

      // Soft halo around the base: reads as a footprint on the water and
      // keeps a low-risk (short) beam from disappearing entirely.
      const halo = new THREE.Mesh(
        new THREE.RingGeometry(0.16, 0.34, 28),
        new THREE.MeshBasicMaterial({
          color,
          transparent: true,
          opacity: 0.22 + zone.risk_level * 0.3,
          side: THREE.DoubleSide,
          depthWrite: false,
        })
      );
      halo.rotation.x = -Math.PI / 2;
      halo.position.set(x, baseY + 0.012, z);
      this._columns.add(halo);
      col.userData.zone = zone;
      col.userData.tooltip = `${zone.name}\n${zone.action} — risk ${zone.risk_level.toFixed(2)}${zone.hard_deny ? " (hard deny)" : ""}`;
      this._columns.add(col);
      this._raycastTargets.push(col);

      if (zone.hard_deny) {
        const beacon = new THREE.Mesh(
          new THREE.SphereGeometry(0.1, 20, 20),
          // Deliberately over 1.0: this is the one thing in the scene
          // that should bloom hard.
          new THREE.MeshBasicMaterial({ color: new THREE.Color(0xff5a34).multiplyScalar(2.4) })
        );
        beacon.position.set(x, baseY + height + 0.25, z);
        beacon.userData.beacon = true;
        this._columns.add(beacon);
        this._pulseTargets.push(beacon);
      }

      // Dark chrome here, not the default light sprite: against a bright
      // sky with bloom on, a white label bleeds into a glowing smear.
      const label = makeTextSprite(zone.name, {
        fontSize: 26,
        color: "#eaf5fa",
        bg: "rgba(4,16,26,0.72)",
      });
      label.scale.multiplyScalar(0.62); // ten of these crowd the frame fast
      label.position.set(x, baseY + height + 0.3, z);
      this._columns.add(label);
    });
  }

  _onTick(t) {
    if (this._water) this._water.material.uniforms.uTime.value = t;
    if (this._headlight) this._headlight.position.copy(this.camera.position);
  }
}
