# SimulationR — research and verdicts on the ORCA simulation layer

**Date:** 2026-08-28 · **Scope:** PRD §10B (environment sandbox) and the 3D visualisation stack
**Method:** four research agents (3 Sonnet, 1 Opus) plus independent verification against ORCA's own code and cache.

> **How to read the confidence tags.** Every claim below is marked:
> **[V]** = I verified it myself by running ORCA's code or fetching the primary source.
> **[A]** = an agent reported it and I did *not* independently confirm it.
> **[U]** = unverified / could not be resolved. Treat **[A]** and **[U]** as leads, not facts.
>
> Agents got things wrong. One cited `AGENTS` as living in `orca/agents.py`; it is in
> `orca/planner.py:46`. The substance of that report was right and the location was wrong —
> which is exactly why nothing here is relayed unchecked.

---

## 0. The verdict, up front

**Do not build the environment sandbox as specified in §10B. Build roughly one-fifth of it, and fix two safety defects first — one of which this research found, and which is live right now.**

Three findings drive this, in order of importance:

1. **A live safety hole in `policy.py` that no document records.** Two of ten zones today return `GO — "No hazards found; conditions acceptable"` while carrying risk **above** the 0.6 override threshold. **[V]**
2. **The wind→wave coupling the sandbox needs cannot be made honest.** Every candidate formula fails a null-perturbation test against ORCA's own data, and errs in the fatal direction. **[V]**
3. **INCOIS already ships this product**, with a better safety index than ORCA's. Competing on index quality is a losing move; ORCA's real edge is provenance and offline operation. **[V]**

---

## 1. THE LIVE DEFECT — read this first

Found while cross-checking an agent's claim. Not in the PRD's Open list, not in §8, not in `TEAM_STATUS.md`.

Real cache, `2026-08-27`, **unperturbed** — every zone run through `run_agents()` → `policy.resolve()`:

```
zone            maxrisk  any_go   ACTION             REASON
Point Calimere     0.70    True   SAFER ALTERNATIVE  Wind 27.9 km/h, rain 0.0mm
Mandapam           0.74    True   SAFER ALTERNATIVE  Wind 29.8 km/h, rain 0.0mm
Rameswaram         0.95    True   SAFER ALTERNATIVE  Wind 38.0 km/h, rain 0.0mm
Thoothukudi        0.66    True   SAFER ALTERNATIVE  Wind 26.4 km/h, rain 0.0mm
Kanyakumari        0.67   False   GO                 No hazards found; conditions acceptable   <-- !!
Colachel           0.63   False   GO                 No hazards found; conditions acceptable   <-- !!
```

**Kanyakumari carries higher risk (0.67) than Thoothukudi (0.66), and is told there are no hazards.** **[V]**

### Mechanism

`orca/policy.py:64` — rule 2 is `if opportunity and danger:`. At Kanyakumari and Colachel **no agent sets `suggests_go`** (SST outside the 27–31 °C productive band; chlorophyll cloud-masked). With `opportunity` empty, rule 2 **cannot fire**, `hard_deny` is False, and execution falls through to rule 3 → `GO`, whose reason string is the literal text *"No hazards found; conditions acceptable."*

Forcing waves to 2.4 m — 0.1 m under the hard deny — makes it worse: **[V]**

```
Kanyakumari — wave forced to 2.4 m
   eo_satellite_agent   suggests_go=False risk=0.00
   ocean_state_agent    suggests_go=False risk=0.15
   weather_agent        suggests_go=False risk=0.67
   hazard_agent         suggests_go=False risk=0.96      <-- 0.96
   geofence_agent       suggests_go=False risk=0.00
   --> ACTION: GO   REASON: No hazards found; conditions acceptable
```

### Why this matters more than anything else in this document

P4 states *"Safety outranks opportunity, always."* Structurally, **safety only speaks when opportunity does.** The override is conditioned on there being something to override. A zone that is merely dangerous, with nothing attractive about it, is reported as fine.

This is **not** R-39 (which is about a zone with *no evidence*). Here there is ample evidence and an explicitly elevated risk. It is a distinct, undocumented defect.

It is also **exactly what the sandbox would have advertised to a judge.** The flagship demo — drag the wave slider at a zone — produces "No hazards found" at 2.4 m in two zones. Shipping the sandbox before fixing this would hand an evaluator the bug on a slider.

**Recommended fix (needs sign-off — `policy.py` is frozen under N-5 / CLAUDE.md rule 5):** rule 2's condition should not require `opportunity`. A `danger` finding at or above threshold should downgrade the verdict regardless of whether anything suggested going. The minimal change is to split rule 2 into "danger with opportunity → SAFER ALTERNATIVE (recording the override)" and "danger without opportunity → SAFER ALTERNATIVE / CAUTION with an empty `overridden`", which is precisely the shape R-11 already describes for hard denials.

---

## 2. THE SECOND DEFECT — R-37, confirmed and quantified

The PRD records R-37 as Open ("the decision names the first danger, not the worst"). It understates the consequence.

`orca/planner.py:46` — `AGENTS = [eo_satellite_agent, ocean_state_agent, weather_agent, hazard_agent, geofence_agent]`. `orca/policy.py:65` — `primary_danger = danger[0]`, i.e. **first in registration order**, and `weather_agent` precedes `hazard_agent`.

Raising **only** wave height, and watching whether the stated reason follows it: **[V]**

```
zone            wind kmh |   0.5m     2.0m     2.4m     2.6m
Chennai             14.3 | SST 30.    WAVE     WAVE     WAVE
Cuddalore           12.6 | SST 30.    WAVE     WAVE     WAVE
Karaikal            22.5 | SST 29.    WAVE     WAVE     WAVE
Nagapattinam        18.5 | Elevate    WAVE     WAVE     WAVE
Point Calimere      27.9 |    wind    wind     wind     WAVE
Mandapam            29.8 |    wind    wind     wind     WAVE
Rameswaram          38.0 |    wind    wind     wind     WAVE
Thoothukudi         26.4 |    wind    wind     wind     WAVE
Kanyakumari         26.7 | No haza  No haza  No haza    WAVE
Colachel            25.2 | No haza  No haza  No haza    WAVE

zones where 2.4 m waves are MASKED by another reason: 6/10
```

**At six of ten zones, dragging the wave from 0.5 m to 2.4 m changes nothing a user can see.** The feature whose entire purpose is to prove the policy is real would, on first contact, appear to do nothing. R-37 is a **hard prerequisite** for the sandbox, not an alternative use of the time.

---

## 3. Can the simulation be made REAL? The physics verdict

**Answer: partly. Wave height can be perturbed honestly. Wind cannot be coupled to waves honestly. Do not try.**

### 3.1 The null-perturbation test

The decisive test is not accuracy — it is identity. *With zero user edits, does the coupling reproduce what ORCA already observes?* If not, switching it on changes answers with no user input, which disqualifies it regardless of any accuracy argument.

I tested the standard shortcut myself. NOAA publishes a "30 % rule": `Hs ≈ 0.30·U10` (fetch-limited 0.33), correlation R = 0.88 — but derived from **tropical cyclone** buoy data. **[V]** ([NOAA MWL](https://www.vos.noaa.gov/MWL/201512/waveheight.shtml))

Against ORCA's own paired observations, three independent snapshots: **[V]**

| snapshot | n | mean Hs/U10 | rule predicts |
|---|---|---|---|
| 2026-08-26 | 3 | 0.164 | 0.300 |
| 2026-08-27 | 10 | 0.151 | 0.300 |
| tomorrow | 10 | 0.186 | 0.300 |

**It overpredicts by ~2×**, and the within-snapshot spread (0.084–0.536) rules out any single constant. Sheltered Bay-side zones sit near 0.10; open Arabian Sea zones near 0.21.

The Opus agent ran the fuller SPM and CEM fetch-limited formulas and reported errors of **−41 % to +109 %**, with SPM at Rameswaram predicting **2.59 m against 1.24 m observed — crossing the 2.5 m hard deny with no user edit at all.** **[A]** — I did not re-derive its table, but it is directionally consistent with my own 2× result, which I did verify.

### 3.2 Why it fails: this is a swell coast, not a wind-sea coast

Wave age `c_p/U = gT_p/(2πU)`; above ~1.2 means waves outrunning the local wind, i.e. energy generated elsewhere. The agent computed **six of ten zones swell-dominated** today. **[A]**

I verified the underlying inputs independently — steepness computed from ORCA's own cached `wave_height_m` and `wave_period_s`: **[V]**

```
zone               Hs m   Tp s     L0 m   steepness Hs/L0
Chennai            0.74    7.8     95.0            0.0078
Cuddalore          0.34    6.2     60.0            0.0057
Karaikal           0.74    4.1     26.2            0.0282
Nagapattinam       0.64    3.6     20.2            0.0316
Point Calimere     0.90    3.9     23.7            0.0379
Mandapam           1.22    6.9     74.3            0.0164
Rameswaram         1.24    5.3     44.7            0.0277
Thoothukudi        1.62    7.6     90.2            0.0180
Kanyakumari        1.50    9.6    143.9            0.0104
Colachel           1.42    9.9    154.6            0.0092
```

Long periods (9.6–9.9 s) at Kanyakumari/Colachel with modest heights is the signature of swell. Local wind carries almost no information about the wave field there.

### 3.3 The error points the wrong way

At the swell-dominated southern zones the coupling **under-predicts**. A sandbox built on it teaches *low wind ⇒ low waves* — precisely the false belief that kills small-boat fishermen on this coast: a glassy, near-windless morning with a 2–3 m southerly swell running. **The pedagogical failure is asymmetric and points toward danger.**

### 3.4 The structural argument, independent of any formula

**The sandbox has no time axis.** Waves take hours to respond to a wind change. "The wind is now 40 km/h" and "the waves are now what 40 km/h produces" are separated by 6–12+ hours of unmodelled duration-limited growth. A sandbox with no time coordinate **cannot represent wind→wave coupling correctly even in principle.** This is the cleanest single sentence to record as the answer to Open Decision 6.

### 3.5 What to allow, encode, and refuse

**ALLOW — `wave_height_m` alone.** It is the hard-deny variable, it is causally downstream of everything else, and *"suppose the sea state were 3 m, however it got there"* is a complete, coherent hypothesis that makes no causal claim. Nothing to get wrong.

**ENCODE — exactly one inequality, the deep-water steepness admissibility check:**

```
Hs  ≤  s_max · g·Tp² / (2π)        with s_max ≈ 0.06
```

This couples wave height to wave **period** — two moments of the *same spectrum*, a within-one-object constraint, not a causal-history claim. It needs no fetch, no duration, no drag coefficient, no swell partition. It uses only variables ORCA already caches. Today's values (0.006–0.038) sit comfortably inside, so it does not disturb the baseline.

It bites exactly where it should: **"3 m at Karaikal" against the cached Tp of 4.1 s gives steepness 0.115 — physically impossible, refusable with a real reason.** That is the flagship demo scenario, and the correct system response is *"3 m cannot coexist with a 4.1 s period."* **[V — I confirmed the Tp and the arithmetic; s_max = 0.06 is [U], see §7]**

**ALLOW WITH REFRAMING — `wind_speed_kmh` alone, framed as a policy probe**, not a sea state: *"what does `weather_agent`'s wind rule do at 45 km/h?"*, with the wave row rendered *"unchanged — ORCA is not modelling how this wind would change the waves."* Honest, because the user is probing a deterministic rule rather than a forecast.

**REFUSE:** deriving wave height from wind (§3.1–3.4); any Hs violating steepness; perturbing `chlorophyll_mg_m3` (the cached value is **27 days stale at confidence 0.3** — a hypothesis about it is not about today **[V]**); perturbing `lat`/`lon` or IMBL distance (that is a position, not an environment, and would let a user "demonstrate" they are outside a restricted zone).

**Considered and rejected:** depth-limited breaking (`Hs ≲ 0.5·h`) from cached bathymetry. Physically the most defensible constraint of all, but the ETOPO grid is **85 × 56 at 0.0667° ≈ 7.4 km** **[V]** against zone coordinates that are *harbour* points — it would refuse nearly everything. Wave refraction and shoaling need ~100 m resolution. **Physically real nearshore wave transformation is not possible with the data ORCA has.**

---

## 4. What researchers and agencies actually want

### 4.1 The competitive reality — INCOIS already ships this

Confirmed from INCOIS's own service page: **[V]**

| | INCOIS SVAS | ORCA today |
|---|---|---|
| safety index | Boat Safety Index: wave height + **steepness** + **directional spread** + rapid wind-sea development | **wave height only**, flat 2.5 m |
| vessel classes | beam **< 4 m, 6 m, 7 m** — boat-specific | none |
| lead time | 10 days | today + tomorrow |
| coverage | 9 coastal states/UTs | 10 Tamil Nadu points |
| validation | against real capsize incidents | none |

**The national authority's product is strictly stronger on the safety-index axis.** Competing there is a losing move.

**The actionable half of this finding:** steepness ranks ORCA's zones almost *inversely* to height. Point Calimere is 5th by height and **1st by steepness**; Thoothukudi is 1st by height and 6th by steepness. **[V]** ORCA already caches the wave period needed to compute it — the PRD's own Appendix B lists `wave_period_s` as *"no agent — lookup only."* **This is a larger safety finding than anything a sandbox would surface, and the data is already on disk.**

*Gap:* the exact BSI formula and thresholds are **not public** — the paper is paywalled (403) and the official page does not publish them. **[U]** Do not claim to implement BSI; at most, cite steepness as a known operational parameter.

### 4.2 Do researchers want a what-if sandbox?

The evidence is genuinely split, and the split matters. **[A — this section is agent-reported; I verified only the SIH item below]**

**Yes, at institutional scale.** The EU Digital Twin Ocean and Destination Earth name what-if scenario simulation as an explicit, first-class capability. But these are 90+ partner consortia running validated multi-decadal physics on dedicated HPC, aimed at scientists and policymakers. If you say "we built a what-if ocean sandbox," an oceanographer's reference point is *that*. A slider on unvalidated physics reads as a category mismatch, not as junior DestinE.

**No, at the level of the agencies you would pitch.** No INCOIS/MoES document was found naming scenario simulation as a gap. Their self-stated gap is **data** — georeferenced ground-truth catch observations, which they are addressing with a citizen-science app, not a simulator.

**No, in the adoption literature.** The barriers to advisory-tool uptake are consistently trust, connectivity, literacy, cost and last-mile delivery. The agent found **zero** direct evidence of end users demanding the ability to run counterfactuals. Explainability appears mainly in advocacy literature by authors with a professional stake in it.

**But your own problem statement asks for it.** SIH26176 contains the phrase **"explore scenarios."** **[V — but sourced from a community GitHub mirror, not `sih.gov.in`. Confirm officially before relying on it.]** Note the phrase is ambiguous: a conversational query layer over *real* data also satisfies it, more cheaply and with no synthetic-data risk. The same text also asks for **route optimization**, which ORCA does not have at all.

### 4.3 How credible scenario tools stay credible

Pattern across NOAA SLOSH, WIFIRE/BurnPro3D, Google Flood Hub and aviation Level D simulators: **validated physics, a professional audience, and unmissable labelling that the output is hypothetical.** **[A]**

SLOSH is the closest analogue and the most useful convention: it is built from up to 100,000 *hypothetical* storms and states plainly *"No single hurricane will produce the regional flooding depicted in the MOMs"* — worst-case-for-planning, explicitly not a prediction, restricted to evacuation planning.

**None of these lets an untrained end user twiddle a physics parameter and get output that looks like a real reading.** That is the bar the §10B sandbox as specified would fail.

---

## 5. The P1/P8 boundary — is it sound?

**Conceived correctly, located wrongly.** F-19a puts the label in a Python *type*. Every leak path is one where the type has already been erased. **[A, with the specific items below verified]**

The leaks that matter:

- **The composer strips exactly the labelling fields.** `_composition_context()` passes only `{id, variable, value, unit}` — `source`, `provenance` and `confidence` are deliberately dropped for token cost. So at the one place in the system that writes prose about numbers, a hypothetical is byte-identical to a measurement. **[V — this is code I wrote; the docstring states the cost rationale.]**
- **`_FORBIDDEN_SOURCE_WORDS` = `("mock", "sample", "synthetic", "dummy", "fake")`.** F-19b mandates the source string `"USER HYPOTHESIS (not measured)"`, which contains **none of those five**. So `write_cache()` would persist a hypothesis and **G-6 would return nothing while the cache was poisoned.** F-19e is unenforceable as written. **[V]**
- **Two renderers, one flag.** `web/index.html` and `web/three-viz-app.js` both render verdicts; F-19d obliges "the UI" to mark hypotheticals. The 3D view is the most screenshot-able artefact the project has.
- **Front-end turn memory.** A sandbox turn's zone is a *real* zone name, so it passes `memory.sanitize()` cleanly and silently contextualises the next real question. Sandbox turns must not call `rememberTurn()`.
- **`?mock=1` already ships the prohibited behaviour** (R-55, already Open).

### The cheapest structural fix, and a standard to cite

**Prefix hypothetical ids `hyp_` instead of `obs_`.** The id is a *string*, so it survives serialisation, logging, JSON responses, screenshots, copy-paste — and the composer prompt, which keeps `id` verbatim. It partially closes the composer leak for free, at zero schema cost.

**The deeper finding, which is not about hypotheses at all:** ORCA's "observations" are already **model output, not measurements**. Open-Meteo Marine wave data comes from numerical wave models (GFS-Wave / ICON-Wave / WAM / MFWAM). There is no buoy anywhere in ORCA's data path. **[A — plausible and consistent with Open-Meteo's documented model backing, but I did not confirm which model serves the Indian Ocean domain; see §7.]**

So the honest ontology is three-level — **measurement → model forecast → user hypothesis** — and P1 is not "only measured values" (ORCA has none) but *"only values whose lineage is external and recorded."* That is stronger and survives the sandbox intact. The relevant standards to cite rather than reinvent: **CF Conventions §2.6.2** (`source` should name the model if model-generated), **ISO 19115-2 `LI_Lineage`** (F-21's "parent + ordered edit list" is literally this), and **W3C PROV-O** (`prov:wasDerivedFrom` is exactly F-19b).

---

## 6. 3D tech stack and textures

### 6.1 The local situation

- three.js **r0.180.0** and MapLibre GL **4.7.1** load from **unpkg CDN** and are **not vendored**. The advisory answers offline; **the map and 3D views do not.** F-2 is open and this is a live contradiction with P6/G-8. **[V]**
- Current 3D code is 535 lines using only `SphereGeometry`, `TorusGeometry`, `MeshStandardMaterial`, `Line`, `Sprite`, `BufferGeometry`. **No shaders, no water, and zero texture loaders in the entire codebase.** **[V]**
- Bathymetry for terrain: 4,760 points, 85 × 56, ~7.4 km, to −2,737 m. **[V]**

**The good news, and the agents could not have known this:** `web/index.html:40-44` already declares an **import map**:

```html
<script type="importmap">
  { "imports": {
      "three":         "https://unpkg.com/three@0.180.0/build/three.module.js",
      "three/addons/": "https://unpkg.com/three@0.180.0/examples/jsm/"
  } }
</script>
```

**Vendoring is therefore a two-line change** — repoint both entries at local files. F-2 is far cheaper than its "Phase 1" placement suggests, and **it should be done before any new 3D work**, because every addition below deepens the offline hole until it is. **[V]**

### 6.2 three.js official water

`examples/jsm/objects/Water.js` at r0.180.0: **12,912 bytes, plain ES module** (`import {…}` / `export { Water }`), `waterNormals` an optional `Texture` defaulting to `null`. Vendors trivially — it needs nothing but three.js and a jpg. **[V — fetched and read directly]**

It renders one reflective plane, animating a single normal map sampled twice at offset UVs. `Water2.js` adds a flow map and a refraction pass, needing **two** normal maps. Both do an extra full-scene pass to a 512×512 render target; for a scene of spheres and lines that is negligible on integrated graphics. **[A on the perf claim — not benchmarked]**

**The licence catch, and it matters for a submission.** `waternormals.jpg` is **248,813 bytes** and fetches fine **[V]** — but the agent could find **no licence notice for the three.js example *textures*** anywhere in the repo. three.js's MIT licence covers the *code*. **[A, and the agent itself rated this medium-low — absence of evidence, not proof]**. Regardless of how it resolves, the cheap move is to avoid the question: use a confirmed-CC0 normal map instead.

### 6.3 Gerstner vs FFT/Tessendorf

Gerstner is a closed-form sum of a handful of trochoids in a vertex shader. Tessendorf/FFT generates a full Phillips spectrum via inverse FFT every frame — realistic, but effectively needs WebGPU compute or a fragment-shader FFT. **[A — standard graphics literature, high confidence]**

**Verdict: Gerstner. Skip FFT entirely.** Sean Bradley's three.js fork ships `webgl_shaders_ocean_gerstner.html` as a plain `<script type="module">` with no bundler, extending stock `Water.js` — a directly usable reference. **[A — the agent reports fetching and confirming it; I did not re-fetch]**

### 6.4 Alternative stacks — all rejected

| Library | Verdict |
|---|---|
| **CesiumJS** | **Avoid.** No build step, but offline needs 4 asset directories copied plus `CESIUM_BASE_URL`, and real terrain needs quantized-mesh tiles you'd have to generate. Correct tool, wrong week. |
| **deck.gl** | **Skip.** Solves massive-point-dataset problems you don't have. Ten points. |
| **MapLibre 3D terrain** | **The only one worth considering** — already vendored — but needs ETOPO converted to terrain-RGB tiles as an offline prep step. |
| **Babylon.js** | **Skip.** Rewriting 535 working lines in a second engine for zero payoff. |

**None of these replaces what you have. three.js + MapLibre is already the right architecture.** **[A]**

### 6.5 Textures

Use **3dtextures.me "Water 002"** (CC0, 1024×1024, includes a normal map) as primary — smaller and more confidently licensed than three.js's own bundled jpgs. **ambientCG** is confirmed CC0 site-wide but no specific ocean asset was pinned down. **OpenGameArt's "Water — Batch of 15" is CC-BY 3.0, not CC0** — usable only with an attribution line. **[A — licence pages were fetched; individual asset pages were not opened. Open the asset page before committing the file.]**

### 6.6 What to build, ranked by payoff ÷ cost

1. **Depth-based colour absorption (Beer–Lambert tint).** One fragment-shader change, no new assets, no extra pass. Turns a flat blue plane into water with depth. **Highest payoff per hour by a distance.**
2. **Foam at crests/shoreline.** Threshold on wave steepness — which Gerstner already computes — blended with a noise texture. Pairs naturally with (1).
3. **Gerstner ocean surface.** The headline upgrade; realistic in a day given the reference above.
4. **Wave-spectrum overlay at the 10 points.** The only genuinely *novel* item, and the only one that is honest data visualisation rather than decoration: it renders the real `wave_height_m` / `wave_period_s` you already hold, reinforcing rules 1 and 3 instead of straining them. Cheap — a canvas-texture sprite per point.
5. **GPGPU particle advection for currents.** **Rank last, and probably don't.** The technique is legitimate and build-step-free (`GPUComputationRenderer`), but you have current vectors at **10 sparse points** and would have to interpolate a whole velocity field across the visible ocean. **That interpolation is fabricated marine data drawn as if observed — a direct P1 problem**, not merely an effort problem.

**Skip WebGPU.** ~85.6 % global support but **Firefox has it disabled by default**, and `WebGPURenderer` is still maturing. **[A — caniuse figure not independently re-checked]** Your scene is not draw-call bound, so the upside is zero and the failure mode is a black canvas on stage.

**Build order: depth tint → foam → Gerstner → spectrum overlay.** But **vendor three.js first (§6.1)**, or every one of these makes the offline gap worse.

---

## 7. What could NOT be verified

Listed so nobody treats them as settled.

1. **Which wave model backs Open-Meteo's Indian Ocean domain, and whether `wave_height_m` is total Hs or a wind-sea partition.** This is the single most load-bearing unknown in §3. Resolvable in ~15 minutes from Open-Meteo's model table.
2. **Zone independence at the model grid scale.** Karaikal↔Nagapattinam is ~18 km and Mandapam↔Rameswaram ~22 km against a ~0.25° (~28 km) global grid, yet the cache shows materially different periods. Interpolation, or a finer nest? **This affects the cross-zone ranking claim (R-46 / G-12) as much as it affects the sandbox.**
3. **The correct `s_max` for the steepness check.** 0.06 is a judgement from a range in the literature; no single authoritative operational threshold for `Hs/Lp` in a random sea was found. Use 0.07 to refuse less.
4. **Exact SPM/CEM coefficients.** I could not extract them from a readable primary source — the CEM PDF would not decode and the search results did not print them. The *conclusion* is robust (my own 2× measurement stands independently), but **do not quote the constants** until someone reads them off the CEM directly.
5. **INCOIS BSI formula and thresholds** — paywalled.
6. **SIH26176 exact text** — community mirror only.
7. **Whether `mypy` is run in this project.** F-19a's type boundary is only as strong as static checking; no mypy config was found.
8. **Licence status of the three.js example textures.** No notice found, but that is absence of evidence. Sidestep it with a CC0 asset rather than resolve it.
9. **The specific CC0 water assets.** Licence *pages* were confirmed; individual asset pages were not opened. Open the 3dtextures.me "Water 002" page before committing the file.
10. **WebGPU support figures and Firefox's default.** Reported from caniuse, not re-checked. The verdict (avoid) does not depend on the exact number.
11. **Sean Bradley's Gerstner fork running build-free.** Agent-reported as fetched and confirmed; I did not re-fetch.

### Two agents died mid-run

The ocean-physics and 3D agents from the first launch stopped writing at 00:23 and never sent completion notifications — they were lost at an interrupt. The 3D one was refired and completed; **the dedicated ocean-physics agent was not refired**, because the Opus analysis had already covered that ground and I had verified the load-bearing numbers myself. If you want the operational-model survey (WAVEWATCH III / SWAN / MIKE 21 / Delft3D licensing and resolution, INCOIS model specifics), that research was never delivered and is a genuine hole in this document.

---

## 8. Recommendation

Ordered. Estimates are the Opus agent's **[A]** except where I measured.

| # | Do this | Why |
|---|---|---|
| 1 | **Fix the rule-2 fall-through (§1)** — needs `policy.py` sign-off under N-5 | Live. Two zones today say "No hazards found" at above-threshold risk. |
| 2 | **Fix R-37 (§2)**, via the planner, using the R-39a pattern so `policy.py` stays frozen | 6/10 zones mask the wave reason. Without it the sandbox demonstrates the opposite of its thesis. |
| 3 | **Add `"hypothes"`, `"counterfactual"` to `_FORBIDDEN_SOURCE_WORDS` and the G-6 grep** (~10 min) | Makes F-19e real *before* any sandbox code exists. |
| 4 | **Sandbox, minimal slice** (~3 h): one variable (`wave_height_m`), one zone, slider + numeric field; `hyp_`-prefixed ids; `"hypothetical": true`; **skip LLM composition entirely for hypothetical requests** | Skipping composition closes the biggest leak outright *and* makes the diff deterministic, satisfying F-27's offline requirement with no second code path. |
| 5 | **F-19d rendering in both renderers, which also closes R-55** (~1 h) | Two birds; R-55 is already Open. |
| 6 | **The steepness refusal** (~30 min) | The single highest-credibility moment in the feature: the system saying *"no, that sea state cannot exist."* |
| — | **Do NOT build** `EnvironmentState`/`perturb()` with full lineage (F-20/21), NL perturbation parsing (F-25/26 — a slider is better, offline, and unfalsifiable), or **any** wind↔wave coupling (F-22 — refuse, and record §3.4 as the reason) | |

**If forced to choose one thing:** fix §1. It is a live safety defect on a system whose entire claim is that its safety layer is real.

**The framing that makes the whole feature defensible:** *do not claim to simulate the ocean — claim to simulate the policy.* A substitution makes **no claim about the world**; it makes a claim about ORCA's own code, discharged by **verification** (execution), not **validation** (comparison against nature). That is why the wave-only slice is shippable in two days and the coupled version is not — and it is exactly what §10B.4 already says the feature is for.
