# What fishermen actually need, and what nobody has built

Research 2026-08-29. Every claim here has a source. Nothing in this file
is my opinion about what would be nice — it is what the literature and
the deployed systems say is missing.

---

## 1. The one number that explains everything

**Terrestrial coverage dies 10–15 km offshore.**

- INCOIS reaches ~7 lakh fishermen with PFZ and Ocean State Forecast
  advisories **by SMS**. ([INCOIS OSF](https://incois.gov.in/site/services/osf.jsp))
- "Due to limited range of mobile networks and VHFs, the erstwhile
  information system was not able to communicate disaster warnings to
  fishermen when they moved away from the coast beyond 10-12 km."
- 4G/5G reach "a maximum of 15 km from the coast."
  ([Maritime mesh, arXiv 2502.13559](https://arxiv.org/pdf/2502.13559))

**ISRO's answer was hardware.** GEMINI, then DAT-SG — dedicated satellite
receivers, one per boat, bought and distributed.
([Drishti](https://www.drishtiias.com/daily-updates/daily-news-analysis/distress-alert-transmitter-for-fishermen))

That is the gap. Every advisory system in India is excellent up to 15 km
and then stops, and the fix so far has been to buy every boat a radio.

**A phone in a pocket is the asset nobody is using past 15 km.**

---

## 2. Three things that are not built, ranked

### ① Boat-to-boat advisory relay — store, carry, forward

**The idea.** Boat A downloads the advisory bundle in harbour. It sails.
Twenty km out it comes within a few hundred metres of Boat B, which left
two days ago and has nothing. Their phones see each other over BLE /
Wi-Fi Direct and Boat B gets the advisory. No tower, no satellite, no
hardware, no subscription.

**Why it works, quantified.** "Any boat within range of the land base
station can serve as a range extender or relay, with each additional
level of range extension adding about 15-20 km to the achievable range."
Delay-tolerant networking at intermediate nodes "may improve message
delivery rates, albeit with a delay."
([arXiv 2502.13559](https://arxiv.org/pdf/2502.13559),
[Maritime comms survey, arXiv 2204.12824](https://arxiv.org/pdf/2204.12824))

**Why nobody has done it.** Existing maritime mesh work assumes
*infrastructure* — buoys as relay nodes, uninhabited islands, LoRa
hardware (Meshtastic). Nobody has used **the phones already on the
boats** as the mesh. The Signal K / Meshtastic work is the closest, and
it still needs a LoRa radio per boat.

**Why ORCA specifically can.** The advisory bundle is **52 KB, measured**.
That is trivially transferable over BLE in a few seconds of proximity.
And it is *signed by construction* — every reading carries source,
valid_time and provenance, so a relayed bundle can be verified rather than
trusted. A relay network is only safe if the payload is self-describing,
and ORCA's already is.

**Status: NOT BUILT.** This is the single most defensible "revolutionary"
claim available, and it is a protocol plus a BLE service.

---

### ② The phone as a wave sensor — every boat becomes a buoy

**The idea.** A phone's accelerometer measures the boat's vertical motion.
Integrate it and you get significant wave height where the boat is,
right now — a real in-situ measurement, from hardware already on board.

**Why it works.** This is established, not speculative:

- **WaveDroid** (TU Delft) measures wave height, direction and period from
  a smartphone IMU "at ten times less cost" than a conventional buoy, with
  commercial pilots in the Netherlands, France, Myanmar and Brazil.
  ([TU Delft](https://www.tudelft.nl/en/ceg/research/stories-of-science/measuring-the-waves-with-a-smartphone))
- Accelerometer vs GPS wave buoy comparison shows "excellent correlation
  for significant wave height" — and the study was run **in shallow water
  off Cuddalore, east coast of India**, which is one of ORCA's ten zones.
  ([ResearchGate](https://www.researchgate.net/publication/260041266))
- Smartphone arrays work as distributed IMU networks for wave propagation.
  ([arXiv 2501.04886](https://arxiv.org/pdf/2501.04886))

**Why it matters more than it sounds.** The Bay of Bengal is chronically
under-sampled in situ. Satellite SST is skin temperature and cloud-limited
— six of ORCA's ten zones had **no cloud-free chlorophyll pixel in 15
days**. A fleet of a few hundred boats reporting real wave height with
timestamps and positions is a validation dataset that does not currently
exist, and cal/val data is something ISRO and INCOIS presently pay moored
buoys to gather.

**It also closes the loop.** Right now fishermen only consume. This makes
them contributors — and it is the honest answer to "what does ORCA give
back".

**The rule-1 danger, and the design that survives it.** A phone-derived
wave height is NOT a `MarineObservation` from a trusted source, and it must
never reach `orca/policy.py`. `docs/MOBILE_APP.md` §5 already specifies the
quarantine: a separate store (`data/observations/`, never `data/cache/`),
a separate source name (`"ORCA Fleet (unverified)"`), capped confidence,
and **no path into the advisory in v1**. Break that and the project's
central claim dies.

**Status: NOT BUILT.** Demonstrable on a single phone.

---

### ③ Pair with ISRO's own NavIC receiver over Bluetooth

**The idea.** ORCA becomes the phone app for the hardware ISRO is already
distributing.

**This is not speculation — ISRO designed for it.** DAT-SG "can be
connected to mobile phones using Bluetooth interfaces, and messages can be
read in native languages using a mobile application." NavIC's messaging
service already broadcasts PFZ, cyclone and high-wave alerts on L5, and
ISRO has published a
[Signal-in-Space ICD for INCOIS messages](https://www.isro.gov.in/media_isro/pdf/SateliteNavigation/1a_messgingicd_receiver_incois_approved_ver_1.2.pdf).
Tamil Nadu fishermen were given NavIC gadgets as far back as 2018.
([Business Standard](https://www.business-standard.com/article/pti-stories/tn-fishermen-get-navic-powered-isro-gadgets-for-timely-alerts-118121700868_1.html))

**Why it is strong for THIS problem statement.** SIH26176 is an **ISRO**
problem statement. "We are the Tamil-language phone interface for your own
NavIC messaging service, and here is the ICD we read" is a far better
answer than another weather app.

**Status: NOT BUILT, and needs hardware we do not have.** Design it,
document the ICD mapping, and say plainly that it is unverified without a
receiver. Do not claim it works.

---

## 3. What existing systems do that ORCA must also do

Parity matters — a judge will ask "what about X". Current state:

| Capability | INCOIS / existing | ORCA |
|---|---|---|
| PFZ advisory | SMS + static map | **`GET /pfz`**, conversational, with evidence |
| Ocean State Forecast | SMS | **`/ask`**, offline, Tamil |
| Cyclone / high-wave alert | NavIC + SMS | **missing** — no source yet |
| Distress alert | DAT-SG hardware | SMS position (no hardware) |
| Language | Tamil SMS | **Tamil in and out, offline** |
| Beyond 15 km | GEMINI / DAT hardware | **offline bundle, no hardware** |
| Boundary warning | none on a phone | **background GPS watch** |
| Fish quantity | not provided | not provided (same honest limit) |

**Two things ORCA is missing that they have: cyclone alerts, and a
hardware-grade distress path.** Both are in `docs/HANDOFF.md` as package B.

---

## 4. The prompt you asked for

Paste this into ChatGPT / Gemini / Grok. It is written to get *specifics*
back rather than a list of buzzwords — the failure mode of asking these
models about a domain is a confident, generic answer.

```
You are advising a team building a marine safety and fishing advisory
system for small-scale fishermen on the Tamil Nadu coast of India. It is
an entry for ISRO problem statement SIH26176, "ORCA - Marine EcOsystem
Reasoning with Collaborative Agents".

WHAT ALREADY EXISTS IN OUR SYSTEM (do not propose these):
- Conversational advisory in Tamil and English, working fully offline
- Deterministic safety verdict (GO / DO NOT GO / SAFER ALTERNATIVE /
  CANNOT ASSESS) with every number traceable to a cited observation
- 10 coastal zones, real data: Open-Meteo marine + weather, NOAA VIIRS
  chlorophyll, NOAA ETOPO bathymetry, Marine Regions IMBL geometry
- Potential Fishing Zone ranking from chlorophyll + SST
- Background GPS watch against the India-Sri Lanka maritime boundary that
  warns aloud in Tamil with the app closed
- Offline advisory bundle (52 KB) carried on the phone
- SMS position/distress via the phone's own messaging app

WHAT WE KNOW IS MISSING:
- Route planning and operational planning
- Cyclone and lightning alerts (no data source identified yet)
- Any use of NavIC messaging

CONSTRAINTS THAT ARE NOT NEGOTIABLE:
- No fabricated data, ever. An absent reading must be shown as absent.
- Must work with zero network connectivity for days at a time.
- Users are fishermen with mid-range Android phones, variable literacy,
  Tamil-speaking, working on a moving deck with wet hands.
- No new hardware they have to buy.

WHAT I WANT FROM YOU:
1. Name capabilities that would materially change safety or income for
   these users and that are NOT deployed anywhere in Indian fisheries
   today. For each: what it does, why it is not already done, and what
   would make it hard.
2. For each, cite a specific paper, deployed system, standard or dataset
   I can verify. If you cannot cite something real, say so explicitly
   rather than describing it anyway.
3. Tell me which existing Indian systems (INCOIS PFZ/OSF, SAMUDRA app,
   Fisher Friendly Mobile App, GEMINI, DAT-SG, NavIC messaging, state
   fisheries departments) already do each thing, so I do not rebuild
   something that exists.
4. Rank your suggestions by (impact on a fisherman's actual day) divided
   by (engineering effort), and be blunt about which are not worth doing.
5. Separately: name the things about this problem that teams usually get
   WRONG - failure modes, false assumptions about connectivity or
   literacy, or safety mistakes that look fine in a demo.

Do not give me a generic list of app features. If you are not confident
something is real and undeployed, say so.
```

---

## 4b. Split the three models so they don't overlap

Sending the same prompt to all three wastes two of them — they converge on
the same generic list. Give each the job it is actually better at, then
cross-check the answers against each other.

**Paste the §4 prompt as the setup for all three, then append ONE of these.**

### → ChatGPT: engineering feasibility and failure modes

```
Focus ONLY on engineering feasibility. For each capability you name:
- What exactly breaks first at sea (battery, GPS lock, thermal, storage,
  Android background limits, BLE range in salt spray)?
- What is the realistic engineering effort in person-days for a team of 4
  students with 30 hours left?
- What would you cut if you had to ship in 30 hours?
Do NOT discuss policy, funding, or user research. Be concrete about
Android platform limits and cite the specific API restriction.
```

### → Gemini: Indian government data, standards and prior deployments

```
Focus ONLY on what already exists in India. Google's index is strongest
here, so use it:
- Every Indian government dataset, API or feed relevant to marine safety:
  INCOIS, IMD, ISRO/NRSC/Bhuvan, MoES, state fisheries departments. Give
  me the actual endpoint or portal URL and whether it needs registration.
- Every deployed Indian fisherman-facing system and what it costs the user.
- Relevant Indian standards or regulations: NavIC ICDs, DAT-SG specs,
  registration requirements for boats, any legal limit on giving
  navigation advice.
If a link is dead or you are unsure it is current, say so.
```

### → Grok: what fishermen actually say, and what critics would attack

```
Focus ONLY on the human reality and the criticism.
- What do Tamil Nadu fishermen actually complain about, in their own
  words? Pull from forums, news comments, X/Twitter, union statements,
  local Tamil press. Quote them.
- What would a hostile judge say about a student team building this? Name
  the weakest claims and the ways this could be dismissed as a toy.
- What has been tried in Indian fisheries tech and FAILED, and why?
  Abandoned apps, unused hardware, schemes that did not get adopted.
Prioritise real quotes and real failures over analysis. Be blunt.
```

**Then:** put the three answers side by side and keep only what at least
two agree on, or what one backs with a source you can open yourself.

---

## 5. Recommendation

Build **① the boat-to-boat relay** and **② the phone wave sensor**.

They are the only two on this list that are (a) genuinely undeployed,
(b) need no hardware anyone has to buy, (c) demonstrable, and (d) directly
answer the 15 km wall that every existing Indian system hits.

③ is the strongest *pitch* for an ISRO audience but cannot be verified
without a receiver, so it should be presented as a designed integration
with the ICD cited — never as something that works.


Grok:

Tamil Nadu fishermen’s actual complaints, in their words and from unions/local reports
Core recurring themes from Tamil press, union statements, survivors, and coverage (not filtered through tech optimism):

Border arrests, boats seized, mid-sea attacks, Katchatheevu: “Our livelihood has come under a big threat.” (T. Maruthu, Meenavar Visai Padagu Sangam, Jegathapattinam). Justin, President, Rameswaram Fishermen’s Association: a mechanised boat costs ₹50 lakh–1 crore; a 24-hour trip costs ₹60,000–80,000; “Often, the returns barely cover the expenses.” CM Stalin’s letters and assembly references note hundreds still in Sri Lankan custody, boats nationalised, mid-sea attacks and theft by unidentified persons. Fishers describe being treated “not even as human beings,” locked in restrooms, heads forcibly shaved. Traditional grounds feel inaccessible; bottom-trawling is both their survival tactic and the reason Sri Lankan fishers (and some Indian small-scale ones) hate them.
No usable warning when already at sea (Ockhi trauma still live): Amaladasan (Kanyakumari/Thoothoor area, survivor, son-in-law lost): “We have been offshore for six days and six nights. Nobody told us there was going to be a storm. There is no system (of early warning).” Fr. Churchill / survivors: some got only a generic “rough seas 40-50 kmph” that they ignored because they live with that. Multiple accounts: wireless sets failed, no response to DAT distress calls, bodies found days later by fellow fishers who organised their own searches. “If they are still alive, they would have drifted beyond 600 nautical miles.” Families still waiting years later.
Declining catch, higher fuel, pollution, industrial kill events: Kavitha (fisherwoman context): “We’re spending ₹5,000 to ₹7,000 more per month, but the catch is much less.” Ennore/Kosasthalaiyar mass fish kills: “How can we make a livelihood by fishing if this is the case?” “We have taken loans from moneylenders and banks. Without selling fish, we cannot survive.” Oil spills: catch unsellable, bottoms still marked years later, compensation ₹6–10k against monthly expenses of ₹20k+. Harbour complaints: dirty, no toilets, driven out by development, Blue Flag beaches “won’t put food on our plates.” Ban-period relief: “Will Rs 8000 for two months be enough for our families? … No fisherman has ever run his family with Rs 8,000.”
Gear damage by ships/trawlers, net costs: Tuticorin fishers: merchant vessels enter exclusive zones, destroy nets (major capital for small-scale), near-misses, compensation process “cumbersome… many claims remain unsettled.” Small-scale vs mechanised conflicts, purse-seine bans that wiped investments, caste/access issues in some villages.
Political exclusion: Union leaders: “decisions continue to be taken without us at the table.” Limited tickets, fisheries minister historically not from community, etc.

These are not abstract “pain points.” They are daily: fuel vs catch arithmetic that often loses, invisible line that ends in jail or death, weather that kills when the warning never reaches the boat, and pollution that makes the catch worthless.
What existing Indian systems already do (so you do not rebuild them)

INCOIS PFZ/OSF + SAMUDRA app: Daily PFZ (chlorophyll + SST), 5-day ocean state (waves, winds, currents, etc.), high-wave/swell/tsunami/storm-surge alerts, multi-language including Tamil. SMS historically reached ~7 lakh. GEMINI (GAGAN) device for at-sea delivery of OSF/PFZ.
Fisher Friend Mobile App (MSSRF/FFMA): PFZ offline save + GPS navigation to it, OSF, danger-zone marking (rocks, wrecks, reefs), disaster alerts, local languages, geo-fence no-fishing (e.g. Olive Ridley). ~1.2 lakh registered, active feedback loop from fishers.
Thoondil (TN Fisheries + NCCR): Tamil/English, compass, IMD weather/cyclone/tide, INCOIS PFZ, offline rescue routes to nearest ports, SOS, trip declaration, live tracking for admins, incidence reporting. Aimed at TN coast; claimed 15k–18k+ downloads early, part of TN’s post-Ockhi tiered system (app within ~12 nm).
NavIC messaging + DAT-SG / GEMINI: One-way short messages (PFZ, cyclone/high-wave/tsunami alerts) broadcast via NavIC satellites to receivers on boats; works beyond mobile range. DAT-SG adds two-way distress + acknowledgement + position. Boundary alerts (IMBL). Technology transferred; tens of thousands of vessels equipped in various states; messaging still works even when full NavIC positioning is degraded. Language support including Tamil via Bluetooth phone app.
Odaku (Kanyakumari startup): Phone/tablet marine GPS, waypoints, routes, PFZ, cloud history, geo-fences (rocks, boundaries, restricted zones), weather alerts, used on thousands of mechanised boats in TN/Kerala/Karnataka. Offline-capable with their hardware option.
State fisheries + Coast Guard/VHF layers, token systems, some harbour boards.

Your existing stack (offline Tamil conversational + deterministic GO/NO-GO with citations + chlorophyll/SST PFZ ranking + GPS IMBL watch + SMS distress) already overlaps heavily with the above on the advisory and boundary pieces. Route/operational planning and reliable at-sea cyclone/lightning delivery are the clearer gaps, but NavIC messaging + DAT-SG/GEMINI already target the latter.
What has been tried and failed or under-adopted (real cases)

Sagara (Kerala NIC/Fisheries): Post-Ockhi vessel/crew registration + exit/entry for alerts. Literacy wall: “Forget English, I can’t even manage Malayalam using phone keys… wasting a lot of time.” Base-model phones, temporary crew, elderly refusal. Hundreds registered, far fewer generating OTPs or using daily. Officials admitted low uptake.
Satellite phones (post-Ockhi, Kerala/TN): Distributed, then recharges not renewed (₹30k/year range unaffordable), spectrum issues, recalled unused. Fishers: “without recharge, it is useless… shifted back to mobile phones or walkie-talkies.”
DAT first-generation: Many distress signals during Ockhi unanswered or delayed; none of the callers rescued in some accounts. Hardware cost, maintenance, battery, training.
Suchitwa Sagaram (Kerala clean-sea plastic collection by trawlers): Initial enthusiasm, then funds dried, no incentives, wages delayed → fishers stopped and threw plastic back. “Great initiative gone astray.”
Broader pattern: apps that require daily data entry, English/complex UI, always-on connectivity, or new paid hardware die. Training camps produce downloads that do not stick. Relief schemes (ban compensation) widely called insufficient in the same breath as “we support the parties but get nothing.”

Hostile judge / student-team dismissal vectors (blunt)
A sceptical SIH/ISRO judge or experienced fisheries officer will say:

“You rebuilt INCOIS PFZ + SAMUDRA + Thoondil + FFMA with prettier Tamil chat. Show me the incremental catch or lives saved beyond what already exists on 18k–1.2 lakh devices.”
“Offline 52 KB bundle is cute until the cyclone track updates or the chlorophyll map is three days old. Your ‘CANNOT ASSESS’ is honest; it is also the most common real-world answer, and fishers already know that from looking at the sky and the radio.”
“Boundary GPS watch is already in NavIC receivers and Odaku geo-fences. Phone-closed Tamil audio is nice; it does not solve the 128 boats still in Sri Lankan custody or the political reality that the line is not going away.”
“No new hardware” is correct constraint; it also means you cannot guarantee delivery beyond the phone’s own radio when the boat is 50–150 nm out. NavIC messaging exists precisely because phones die. You have no credible path to it without partnering or becoming a software skin on existing receivers.
“Deterministic, every number traceable” looks good in a demo. At sea, with wet hands, variable literacy, and a moving deck, the actual interface that survives is loud Tamil audio + one big button, not a chat log of citations. Literacy and UI assumptions kill more apps than missing features.
Student timeline + zero-network-for-days + no fabricated data is admirable; it also means your system will frequently say “I don’t know” on the exact days (cyclone approach, sudden lightning, rapid boundary drift) when a wrong or late answer costs lives or a boat. That is not a toy feature; it is the failure mode that will be used against you.
Income claim is the weakest: PFZ already claims 30–70 % search-time reduction. Without rigorous before/after on the same boats under the same fuel prices and ban days, it is marketing.

Capabilities that could matter and are not fully deployed today (ranked roughly by fisherman-day impact / engineering cost under your hard constraints)
Only items that survive the “already exists or has failed” filter and the no-new-hardware / zero-network / no-fabrication rules. Confidence is explicit.

True offline operational route + fuel/time planning that incorporates the fisherman’s own past tracks + local hazards he marks (rocks, wrecks, currents he knows). High impact on fuel (the real daily cost) and avoiding gear loss. Why not done: requires persistent local storage of user-generated waypoints that survive phone resets and wet conditions, and a UI that does not demand typing. Hard: data quality from the user, battery, map tile size. Citation: Odaku already does pieces of this commercially on thousands of boats; FFMA marks danger zones. Not fully zero-network collaborative across a landing centre without some opportunistic sync. Worth doing if you can make the marking voice/gesture-only and the plan pure audio. Effort medium; impact high if fuel savings are real.
Lightning + rapid-onset local storm audio that does not depend on a full cyclone bulletin. Lightning is a real deck killer; existing OSF is coarser. Data source problem is real (no free, reliable, coastal Tamil Nadu lightning feed that works offline for days). If you cannot cite a verifiable, non-fabricated source that can be pre-bundled or opportunistically updated, do not claim it. Existing systems already push high-wave/cyclone via NavIC/INCOIS. Rank: high impact if solved, currently low confidence / high effort because of the data gap you already noted.
Voice-first, one-button “where is the nearest safe harbour given current wind/wave and my fuel” that works with the phone’s own GPS and a pre-loaded bathymetry + harbour geometry set. Thoondil has offline rescue routes. Incremental value is the fuel-aware, real-time wind integration and pure audio. Medium impact, lower effort if you already have the bathymetry.

Not worth the engineering under your constraints:

Anything that requires reliable two-way beyond the phone’s SMS/radio or assumes NavIC receiver ownership (hardware).
Market-price or buyer-matching features (connectivity + trust problems).
Complex multi-agent “reasoning” that produces long text the user cannot consume with wet hands.
Claims of new PFZ accuracy without independent validation against the same boats’ catch logs.

Usual failure modes teams get wrong

Assuming “offline” means the phone is always on and the user will open an app. Reality: phone is in a plastic bag or pocket, wet, low battery, screen unreadable in glare. Audio that interrupts or a physical button wins.
Literacy and language: even Tamil UI fails if it requires precise taps or reading long text. Voice + big icons or pure audio.
Connectivity optimism: “opportunistic sync when near harbour” becomes “never synced” for multi-day trips. Pre-bundled stale data must be labelled as stale; otherwise you re-create the Ockhi “nobody told us” failure.
Safety theatre: a GO/NO-GO that is correct 80 % of the time still kills on the 20 %. Absent data must stay absent; any smoothing or interpolation will be the first thing a hostile review attacks.
Adoption: training camps produce screenshots, not daily use. Fishers adopt what saves fuel or nets this week, not what looks good in a SIH video. If it is not better than looking at the sky + the VHF + the neighbour’s word, it dies.
Ignoring the political economy: border, trawler vs traditional, industrial pollution, and compensation fights dwarf any app. Tech that pretends those are solved by better information will be dismissed as a toy.

Focus the remaining engineering on the few things that change the arithmetic of a single trip (fuel, gear survival, one clear audio warning) while staying strictly inside the no-fabrication / zero-network / existing-phone constraints. Everything else has already been built, distributed, and partially abandoned for the reasons above.


Gemini:

Operational and Architectural Strategy for Marine Safety and Fishing Advisory Systems on the Tamil Nadu Coast
Undeployed Marine System Capabilities for Tamil Nadu Small-Scale Fisheries
The marine digital advisory landscape along the Tamil Nadu coast and the Palk Bay remains dominated by centralized, server-side broadcast architectures. While centralized platforms effectively disseminate coarse-grid weather updates and oceanographic coordinates when cellular connectivity is available, they fail to utilize edge-computing mechanisms during multi-day offline operations. Three high-impact software capabilities currently remain undeployed across Indian marine fisheries systems. These capabilities could transform safety and financial outcomes for artisanal and small-scale mechanized fishermen without requiring external hardware.
Offline On-Device Engine Failure Drift Trajectory Engine
When a small motor vessel (vallam) or motorized fiber-reinforced plastic (FRP) boat suffers propulsion failure in offshore waters, it immediately becomes subject to surface current forces and atmospheric wind drag. Existing Indian marine safety systems rely entirely on server-side search and rescue modeling, which requires active internet connectivity to submit queries and return drift coordinates. In an offline environment beyond terrestrial cell range, fishermen must manually estimate their drift or wait for shore-side search and rescue (SAR) operators to initiate tracking.
An offline engine failure drift trajectory engine embedded directly within the mobile application processes pre-cached surface velocity fields (zonal  and meridional  ocean currents) and surface wind vectors downloaded prior to departure within a 52 KB bundle. Upon a user triggering an engine failure event, the edge engine executes a localized stochastic Monte Carlo trajectory simulation. By combining the vessel's unpowered hydrodynamic drag profile with cached environmental force vectors, the application projects a time-stamped spatial probability distribution box for the vessel over a 6-to-36-hour horizon. This capability enables the crew to broadcast their predicted future coordinates via short-message service (SMS) before drifting completely out of range of terrestrial cell towers, while simultaneously providing neighboring vessels with precise interception vectors.
The primary barrier to deployment has been the computational overhead of running hydrodynamic vector calculus and Monte Carlo ensemble iterations on mid-range Android hardware. Furthermore, defining simplified mathematical abstractions that account for variable hull shapes (such as fiber boats versus traditional wooden vattal craft) without overwhelming smartphone memory or battery reserves requires specialized algorithmic optimization.
Hydrodynamically Weighted Offline Route Optimization
Existing fishery advisory platforms in India direct fishermen to Potential Fishing Zones (PFZs) using straight-line rhumb line tracks or static compass bearings. These calculations ignore ocean currents, wave resistance, and sea surface height anomalies. Traveling along a straight line against strong head-currents or steep wave profiles significantly increases diesel consumption—often representing 60% to 70% of a trip's operational cost—and increases vessel capsize risk under heavy sea states.
An offline hydrodynamic route planner uses a compressed spatial grid overlay pre-loaded with forecast vectors for sea surface currents, significant wave height, and swell direction. Using an edge-optimized graph traversal algorithm (such as an isochrone pathfinding search), the application calculates an optimal voyage track from the home port to target fishing coordinates and back. The algorithm optimizes for either minimum fuel consumption or maximum hydrodynamic stability. By adjusting route waypoints to ride tail-currents or avoid cross-swells, vessels can achieve fuel savings of 12% to 18% per trip while avoiding dangerous sea states.
This capability has not been implemented in Indian artisanal fisheries due to the complexity of compressing multidimensional oceanographic forecast grids into light offline data bundles (<52 KB). Additionally, generating hydrodynamic routing paths on low-to-mid-tier smartphone processors requires stripped-down graph traversal algorithms that operate efficiently without active cloud computing services.
Micro-Climate Squall Watch via Edge Barometric Sensor Fusion
Offshore weather hazards in the Bay of Bengal and Gulf of Mannar frequently manifest as localized squalls, micro-bursts, and rapid convective storms that develop faster than standard 6-hour or 12-hour global forecast model updates. Centralized alerts delivered via SMS or satellite broadcast often arrive after these localized phenomena have already formed.
An edge barometric monitoring service fuses real-time ambient pressure readings from the smartphone's built-in hardware barometer with pre-cached spatial Common Alerting Protocol (CAP) storm track cones. By running a continuous background filter (such as an exponential moving average to filter out deck movement and vessel heave), the system monitors for rapid local pressure drops (e.g., a drop exceeding 1.5 to 2.0 hPa over a 30-minute window). When such a drop is detected in conjunction with cached marine instability indexes, the application triggers a high-priority, localized storm warning audio alert in Tamil, instructing the crew to secure gear or head toward sheltered waters immediately.
This capability remains undeployed because most fisheries software design focuses on cloud-to-client messaging rather than utilizing hardware sensors embedded directly in modern mobile devices. The primary engineering obstacle is filtering out physical noise: movement across waves, changing vessel elevation, and cabin temperature swings can corrupt raw barometric readings, leading to false alerts if not properly smoothed by software algorithms.
Literature Verification, Validated Standards, and Datasets
To ensure technical feasibility, these proposed capabilities derive from established mathematical frameworks, validated standards, and operational marine platforms globally.
Search and Rescue Drift Modeling
The stochastic drift methodology is derived from the Search and Rescue Aid Tool (SARAT) developed by the Indian National Centre for Ocean Information Services (INCOIS)1. INCOIS SARAT utilizes the Monte Carlo method applied to the Leeway drift model formulation established by Allen and Plourde (1999) for the United States Coast Guard (USCG)2. This model categorizes drifting objects into specific hydrodynamic classes, calculating drift velocity  as a vector sum of ambient sea surface current  and wind-induced leeway :

where  and  represent empirical leeway coefficients, and  represents the leeway angle crosswind divergence component2. The underlying algorithms are validated in the INCOIS Technical Report ESSO-INCOIS-OMARS-TR-04 (2022), which details search object drift simulation across the Indian Ocean2.
Ship Hydrodynamics and Route Optimization
The fuel-optimal routing capability relies on the empirical ship resistance formulations outlined in ISO 15016:2015 (Guidelines for the assessment of speed and power performance by analysis of speed trial data). The algorithm models added resistance in waves  alongside calm-water hull drag . The pathfinding component uses an offline modified Dijkstra or Isochrone grid algorithm, adapted from operational open-source marine routing engines like OpenCPN Weather Routing and validated marine emergency response models detailed in WMO-No. 1348 (Guide to Marine Meteorological Services)3.
Common Alerting Protocol and Meteorological Standards
Atmospheric alert parsing adheres strictly to the OASIS Common Alerting Protocol (CAP) v1.2 standard, which is the baseline message format utilized by the India Meteorological Department (IMD) for disaster warning feeds4. The barometric pressure differential alert mechanism follows the standard marine meteorological criteria for tropical squall detection defined by the World Meteorological Organization (WMO), where sudden pressure drops () serve as an operational indicator of localized squalls.
NavIC Signal Architecture and Hardware ICDs
NavIC (Navigation with Indian Constellation) messaging integrations and positioning frameworks are governed by ISRO's published interface standards:
NavIC Signal-in-Space Interface Control Document for Standard Positioning Service (SPS) - L5 & S Band (V1.1)5.
NavIC Signal-in-Space Interface Control Document for SPS - L1 Band (V1.0)5.
Signal-in-Space ICD for Safety-of-Life Alert Dissemination Service including Second-Generation Distress Alert Transmitter (SG-DAT)5.
These specifications confirm that while NavIC satellites broadcast messaging frames via S-band and L5/L1 carriers, standard consumer mobile phones lack the physical hardware subframe decoders required to read raw satellite message packets without external hardware5.
Comparative Analysis of Deployed Indian Fisheries Technology Infrastructure
To prevent duplicating existing systems, any new application must be situated within India's current maritime technology landscape. Multiple government agencies and state departments maintain deployed tools, each serving distinct functions with specific channel constraints.

System Name
Operating Agency
Primary Communication Channel
Cost to User
Core Capabilities
Operational Limitations
INCOIS OSF & PFZ Web Services
INCOIS (Ministry of Earth Sciences)9
Web API, Web Portal, Text Broadcasts9
Free
Ocean State Forecasts (swells, currents, waves) and Potential Fishing Zone (PFZ) polygon mapping9.
Server-side only; requires active cellular data or internet connection to query maps9.
SAMUDRA App
INCOIS10
Cellular Data (Online)
Free
Visualizing PFZ lines, ocean state advisories, wave heights, and emergency port directions.
Functionality degrades significantly offline; lacks custom route optimization or on-device drift calculations.
Fisher Friendly Mobile App (FFMA)
M. S. Swaminathan Research Foundation (MSSRF) / INCOIS
Cellular Data, Offline Caching
Free
Tamil-language advisory, PFZ coordinates, IMBL proximity alerts, weather forecasts.
Dynamic routing is limited to straight-line navigation; barometric hardware sensors are not utilized.
GEMINI (GAGAN Enabled Mariner's Instrument)
INCOIS / Airport Authority of India (AAI)
Satellite Broadcast (GAGAN) to Bluetooth Receiver
Hardware unit purchase (~₹9,000–₹12,000)
Receiving disaster alerts, cyclone warnings, and PFZ data beyond cellular range via satellite broadcast.
Requires purchasing, charging, and maintaining an external hardware dongle paired via Bluetooth.
DAT-SG (Second-Generation Distress Alert Transmitter)
ISRO / Indian Coast Guard12
UHF Satellite Uplink to INSAT12
Hardware unit purchase (~₹15,000–₹20,000; subsidized)12
Two-way distress messaging, acknowledgment receipt, transmitting emergency SOS directly to Coast Guard MRCC5.
Dedicated emergency transponder; no advisory, fishing intelligence, or route planning software capabilities.
NavIC Smartphone Receiver Integration
ISRO / Smartphone OEMs5
Satellite L5 / L1 PNT Receive-Only5
Integrated into phone SoC
Standard GPS-like positioning fix using Indian constellation satellites5.
No messaging access. Consumer Android SoCs do not expose raw satellite broadcast subframe payloads to mobile software applications7.
Tamil Nadu Fisheries Portal / SAGAR System
Department of Fisheries, Govt. of Tamil Nadu
Web Portal / Cellular SMS
Free
Boat registration, biometrics, token issuance for harbor clearance, fuel subsidies.
Administrative portal; lacks offshore operational safety features or real-time navigation advisories.

Impact-to-Effort Prioritization Matrix and Feasibility Assessment
Engineering resources must be focused on features that maximize offline utility while minimizing battery, memory, and computational burdens on mid-range Android devices. Priorities are calculated by evaluating the actual impact on a fisherman's operational day against the engineering effort required for edge implementation:

Proposed Feature / Capability
Safety / Income Impact (1-10)
Engineering Effort (1-10)
Priority Index
Recommendation
Offline CAP Squall & Barometric Sensor Early Warning
9 (Prevents sudden capsizing in offshore squalls)
3 (Low complexity signal filtering & local XML parsing)
3.00
Highest Priority. Implement immediately as an offline background service.
Isochrone Hydrodynamic Fuel-Optimal Route Optimization
8 (Directly cuts fuel expenses by 12–18%)
4 (Medium complexity graph search using small vector grid)
2.00
High Priority. Core differentiator for user income optimization.
On-Device Engine Failure Drift Box Projection
7 (Saves lives during propulsion failure offshore)
6 (Requires spatial Monte Carlo loop and leeway coefficient lookup)
1.17
Medium Priority. Valuable offline safety fallback; keep sample count low for performance.
Direct NavIC Satellite Emergency Messaging Decoding on Smartphone
9 (Receives alerts anywhere without cell signal)
10 (Hardware/OS layer impossible on unmodified Android)
0.90
Do Not Attempt. Structurally impossible on consumer phone SoCs without external hardware receivers.
3D Underwater Bathymetric Contour Rendering
3 (Minor interest for bottom trawling)
8 (High storage overhead and GPU rendering demands)
0.38
Not Worth Doing. High storage footprint exceeds offline bundle limits without meaningful safety gains.

Operational Failure Modes and Common Architectural Misconceptions
Software designed for marine environments often fails when assumptions built in lab settings are tested on open water. Developers building for small-scale coastal fisheries frequently introduce design flaws that look convincing in indoor demonstrations but fail at sea.
Operational Dimension
Lab / Demo Assumption
Open Ocean Operational Reality
Correct Architectural Mitigations
Offline Horizon
Brief network gaps (<6 hours) between cellular reconnects.
Extended 3 to 7 day multi-day voyages with zero cell coverage.
Pre-cache 7-day compressed vector bundles; flag expired data explicitly; never crash or present stale forecasts as current.
UI & Touch Mechanics
Fine touch precision, complex menus, multi-touch pinch gestures.
Wet/salty hands, violent deck vibrations, intense solar glare.
Single-tap touch targets (>64dp), ultra-high contrast color schemes, hands-free Tamil audio cues.
NavIC Satellite Capabilities
Smartphone apps can directly parse text broadcasts from NavIC satellites.
Phone baseband chips strip broadcast subframe payloads and only expose position fixes (LocationManager).
Do not attempt native satellite message parsing; rely on cell-cached bundles, SMS fallback, or external Bluetooth transponders.
Navigational Guidance Liability
App provides binding turn-by-turn routes around ocean hazards.
High legal liability; strictly prohibited by IMO/hydrographic standards.
Present generated tracks as advisory vectors only; require master navigation decisions; display explicit legal disclaimers.
Boundary Warning Sensitivity
Static distance geofencing triggers alerts whenever approaching IMBL.
Constant false alarms during parallel fishing lead to alarm fatigue and app muting.
Calculate dynamic velocity vectors ( and ); trigger voice alerts only when the projected vector intersects the boundary.

Misconceptions Regarding Smartphone NavIC Satellite Messaging Capabilities
A common misconception among development teams in Indian technical competitions is that an Android application can natively receive and parse emergency broadcast messages sent by ISRO over the NavIC satellite constellation.
While modern Android smartphones include GNSS baseband chips that support NavIC for positioning, consumer smartphone SoCs only support the NavIC L5/L1 signal frequencies for Position, Navigation, and Timing (PNT) calculation5. The baseband processor decodes pseudorange measurements internally and exposes location fixes through the standard Android Location API (LocationManager)7. The raw navigation message subframes containing emergency text alerts, early warning broadcasts, or S-band messaging payloads are completely inaccessible to high-level Android applications5.
Attempting to build a software feature that decodes satellite broadcast alerts directly on an unmodified smartphone will fail. Receiving satellite text advisories beyond cellular range requires an external hardware receiver—such as a GEMINI device or an ISRO DAT-SG unit—that decodes the satellite signal and forwards the data to the phone via Bluetooth or serial link12.
Legal and Operational Limits of Navigation Advice
Providing binding navigation routes or turn-by-turn hazard avoidance advice creates legal exposure. Official nautical charting and formal hazard clearance are legally restricted to certified Electronic Chart Display and Information Systems (ECDIS) managed under International Maritime Organization (IMO) standards.
If an advisory application provides a generated route that leads a vessel into an uncharted reef, shallow shoals near Pamban Bridge, or restricted military zones, the developers face legal liability. The application must explicitly present all route outputs as advisory vectors rather than binding navigational instructions, accompanied by mandatory disclaimers that pilotage responsibility rests entirely with the vessel master.
Indian Marine Data Feeds, Endpoints, Regulations, and System Cost Structures
Building a robust marine application for the Tamil Nadu coast requires integration with official Indian meteorological, satellite, and oceanographic data services.
India Meteorological Department Data Feeds and API Infrastructure
The India Meteorological Department maintains a centralized API management portal that provides structured meteorological data, warnings, and marine advisories4.



https://api.imd.gov.in/public/api_reference.html


Authentication and Access Control
Public access to production endpoints requires static IP Whitelisting submitted through the IMD API Management Portal4. Developers must register an account, submit an application detailing the deployment scope, and obtain API key credentials from the Information System & Services Division (ISSD)4.



https://api.imd.gov.in/public/index.php


Specific Marine and Meteorological Endpoints
District Nowcast Endpoint: Returns short-term severe weather warnings, localized rainfall, and thunder squall warnings updated every 3 hours4.
https://api.imd.gov.in/api/v1/districtnowcast


Current Weather Endpoint: Provides real-time surface observations including barometric pressure, wind speed, wind direction, and relative humidity for coastal weather stations (e.g., Pamban, Tuticorin, Nagapattinam, Chennai)4.
https://api.imd.gov.in/api/v1/current_wx


Marine & Coastal Bulletin Endpoints: Delivers port warning bulletins, sea area bulletins for the Bay of Bengal and Gulf of Mannar, and specialized coastal warnings for fishermen4.
CAP Warning RSS Stream: Provides real-time alert feeds formatted according to the OASIS Common Alerting Protocol XML schema4.
https://cap-sources.s3.amazonaws.com/in-imd-en/rss.xml


Technical Escalation Contact Structure
If API endpoints become unreachable or return invalid payloads during operational integration, technical issues should be escalated through IMD's support matrix4:
Level 1 Support: ISSD Technical Support (rthnewdelhi4@gmail.com | +91-11-24344325)4.
Level 2 Escalation: Dr. Sankar Nath, Scientist-F (sankar.nath@imd.gov.in)4.
Level 3 Escalation: Dr. Kuldeep Shrivastav, Scientist-F (kuldeep.srivastava@imd.gov.in)4.
ISRO Space Applications Centre, MOSDAC, and NRSC Bhuvan Portals
ISRO operates two main portals for oceanographic and satellite meteorological data:
Meteorological & Oceanographic Satellite Data Archival Centre (MOSDAC)
MOSDAC serves as the primary repository for ISRO's satellite data (INSAT-3D, INSAT-3DR, INSAT-3DS) and ocean surface model forecasts16.



https://www.mosdac.gov.in


MOSDAC provides RESTful web services that expose raw and processed satellite observations18:
INSAT-3DS Sea Surface Temperature (SST): High-resolution thermal channel imagery used to identify ocean fronts and thermal boundaries.
Ocean Surface Wind Vectors: Scatterometer-derived wind magnitude and direction grids over the Indian Ocean EEZ.
Wave and Current Model Forecasts: Gridded netCDF files containing predicted wave heights, swell periods, and surface current velocities.
NRSC Bhuvan Open Data Archive
The National Remote Sensing Centre (NRSC) hosts the Bhuvan Geoportal, which provides access to thematic spatial datasets, coastal land-use maps, and disaster monitoring layers20.



https://bhuvan-app3.nrsc.gov.in/data/


Developers can extract bathymetric boundaries, shoreline change vector layers, and spatial coastal mapping datasets for offline tiling20.
INCOIS Potential Fishing Zone and Ocean State Forecast Feeds
INCOIS generates daily Potential Fishing Zone (PFZ) advisories derived from combined satellite sea surface temperature (SST) and ocean color (chlorophyll) measurements9. Ocean State Forecasts (OSF) provide 5-day predictions of wave height, swell structure, surface currents, and sea surface temperature10.



https://incois.gov.in/portal/dataguidance.jsp


INCOIS distributes these advisories via JSON web services and text feeds. When internet access is unavailable, these updates can be ingested by shore station servers and compressed into low-bandwidth SMS data packets for delivery to offshore vessels.
Lightning Early Warning Services (Damini Framework)
Lightning warning infrastructure in India is managed by the Indian Institute of Tropical Meteorology (IITM), Pune, under the Ministry of Earth Sciences (MoES)24. The Damini system utilizes a ground-based Lightning Location Network (LLN) to track cloud-to-ground lightning strikes and predict convective activity 30 to 45 minutes in advance24.
While the standalone Damini mobile app is designed for land-based public safety, lightning warning vector data is integrated into IMD's regional alert streams and can be queried via IMD's Radar & Lightning API endpoints4.
Hardware Specifications, Subsidies, and User Costs
Understanding the cost structure of deployed equipment is essential when designing software for small-scale fishing fleets.
Standard Android Smartphone: Mid-range devices (costing ₹8,000 to ₹14,000) are purchased directly by fishermen. Software must be optimized to run efficiently on these existing consumer devices without requiring additional hardware purchases.
ISRO DAT-SG Unit: Second-Generation Distress Alert Transponders cost approximately ₹15,000 to ₹20,000 per unit12. Under the Pradhan Mantri Matsya Sampada Yojana (PMMSY) scheme, the Central and State governments provide subsidies covering 60% to 90% of the cost for registered traditional and mechanized craft, reducing the out-of-pocket cost for fishermen to ₹2,000–₹6,00012.
GEMINI Receiver: Dongles cost between ₹9,000 and ₹12,000. State fisheries departments periodically distribute these devices with heavy subsidies to recognized fishing societies operating deep-sea vessels.
Fisheries Software Applications: Government applications (e.g., SAMUDRA, FFMA) are distributed free of charge via the Google Play Store.
Regulatory Guidelines and Interface Control Standards
Developers building software for marine safety and navigation must adhere to relevant national standards and regulatory frameworks:
NavIC ICD Compliance: NavIC signal integration must strictly conform to ISRO's Signal-in-Space Interface Control Document for SPS (L5/S Band V1.1 / L1 Band V1.0) to ensure accurate processing of GNSS positional data5.
Distress Alert Receiver Standards: Hardware integration with distress alert systems must comply with ISRO's Signal-in-Space ICD for Safety-of-Life Alert Dissemination Service5.
Maritime Boundary Regulations: Vessel tracking and IMBL alert features must align with the official territorial waters definitions issued by the Hydrographic Office of the Indian Navy and the Ministry of External Affairs.
Jurisdictional Port Clearances: Vessel tracking and status logging must comply with registration rules established under the Merchant Shipping Act and local state fisheries regulations (e.g., Tamil Nadu Marine Fishing Regulation Act), which require registered vessels to maintain operational identification transponders while at sea.
Works cited
High-Resolution Operational Ocean Forecast and Reanalysis, https://journals.ametsoc.org/view/journals/bams/101/8/bamsD190083.xml
Development of Search and Rescue Aid Tool-Integrated (SARAT-I, https://www.researchgate.net/publication/366289031_Development_of_Search_and_Rescue_Aid_Tool-Integrated_SARAT-I_to_simulate_the_probable_drift_area_of_a_missing_aircraft_at_sea
Guide to Marine Emergency and Response (WMO-No. 1348), https://community.wmo.int/site/knowledge-hub/programmes-and-initiatives/marine-services/guide-marine-emergency-and-response-wmo-no-1348-dynamic-part
IMD APIs | India Meteorological Department, https://mausam.imd.gov.in/responsive/apis.php
Satellite Navigation Services - ISRO, https://www.isro.gov.in/SatelliteNavigationServices.html
Draft Navic Sps Icd l1 Oct 2022 | PDF | Electrical Engineering - Scribd, https://www.scribd.com/document/665150736/Draft-Navic-Sps-Icd-l1-Oct-2022
USER TECHNOLOGY REPORT - GALILEO, https://galileognss.eu/wp-content/uploads/2020/08/gnss-user-tech-report-issue2-2018.pdf
An analysis of multi-GNSS observations tracked by recent ... - IRIS, https://iris.uniroma1.it/retrieve/e383532a-c2dd-15e8-e053-a505fe0a3de9/Paziewski_An-analysis-of_2021.pdf
Development and deployment of a pilot multilingual citizen- science, https://www.frontiersin.org/journals/marine-science/articles/10.3389/fmars.2026.1918530/full
CURRENT AFFAIRS, https://www.insightsonindia.com/wp-content/uploads/2019/11/Insights-October-2019-Current-Affairs-Compilation.pdf
Advances in Ocean State Forecasting and Marine Fishery Advisory, https://www.researchgate.net/publication/366384762_Advances_in_Ocean_State_Forecasting_and_Marine_Fishery_Advisory_Services_for_the_Indian_Ocean_Region
Current Affairs Quiz (January 2024) Part-II (Date - Drishti IAS, https://www.drishtiias.com/print-quiz/4237
NMSAR Manual 2020: Maritime SAR Guide | PDF - Scribd, https://www.scribd.com/document/675215553/202112240654491871725SAR
Precise Positioning Using Raw GPS Measurements from Android, https://www.researchgate.net/publication/309645977_Precise_GNSS_for_Everyone_Precise_Positioning_Using_Raw_GPS_Measurements_from_Android_Smartphones
Is there an API for Indian weather forecast provided by the ... - Quora, https://www.quora.com/Is-there-an-API-for-Indian-weather-forecast-provided-by-the-Indian-Meteorological-Department-IMD-Or-are-there-any-other-sources-that-provide-an-API
WGISS-59 Minutes and Actions v1.0, https://ceos.org/document_management/Working_Groups/WGISS/Meetings/WGISS-59/WGISS-59%20Minutes%20and%20Actions%20v1.0.pdf
Problem Statements (226) - Amity Internal Hackathon - SIH, https://amityhackathon.vercel.app/problems
ISRO Research Proposal Guidelines | PDF | Antenna (Radio) - Scribd, https://www.scribd.com/document/660702733/Research-Areas-in-Space-for-Web2023
Remote Sensing Fundamentals Overview | PDF - Scribd, https://www.scribd.com/document/719896215/IIRS-RS-GIS-Complete-Material
Applications | Bhuvan | NRSC Open EO Data Archive | NOEDA | Ortho, https://bhuvan-app3.nrsc.gov.in/data/
Annual Report 2022 - IMD - India Meteorological Department, https://mausam.imd.gov.in/imd_latest/contents/ar2022.pdf
inform inspire engage educate innovate index from the directors's desk, https://www.nrsc.gov.in/nrscnew/assets/pdf/newsletters/P2P_Jul_2020.pdf
Climate-Driven Flood Risk Mapping and Adaptive Strategy, https://knowledgeconnect.sastra.edu/cgi/viewcontent.cgi?article=1193&context=theses
Polymath - Facts and Data Book For Mains 2025-26 | PDF - Scribd, https://www.scribd.com/document/1006899058/Polymath-Facts-and-Data-Book-for-Mains-2025-26
Turkey's wildfires hit hopes for tourism rebound - Devdiscourse, https://www.devdiscourse.com/article/science-environment/1683390-turkeys-wildfires-hit-hopes-for-tourism-rebound?amp


Chatgpt:

Novel Advisory Capabilities
Offline Nautical Navigation & Routing: Equip the app with full nautical charts and offline routing (e.g. vector S-57 charts or bathymetric maps) so fishers can plot safe courses to fishing grounds or harbors. This goes beyond simply showing a compass bearing: it would compute turn-by-turn guidance or arrow pointers that account for hazards, currents and fuel. (Commercial boating apps like Navionics and Savvy Navvy offer tide-aware route planning and depth charts, but no Indian fishing app currently provides these features offline.) Why not done already? Building a chart engine is hard – global nautical charts are proprietary, and offline maps consume storage. Computing routes that avoid shallow reefs (using NOAA ETOPO bathymetry) is also nontrivial. A big engineering hurdle is Android’s background limits and rendering performance on low-end phones: multi-megabyte maps will strain storage and GPU. Battery use is also a concern if GPS is on continuously (a boat IoT study found GPS+radio draws ~150‑200 mA, giving only ~10–13 h on a 2000 mAh battery). In practice, one could simplify this in 30 h by skipping full charts: for example, just voice-navigating to a PFZ or port by computing bearing and distance (Thoondil does this for nearest port). Existing systems: PFZ/OSF (INCOIS, FFMA) do not give turn-by-turn routes, and Thoondil’s app already does nearest-port bearings. Developing a full map & router is ~15–30 person-days; in 30 h you might only implement a simple compass-to-target (cut offline map tiles, detailed routing).

Hazard and Traffic Awareness: Overlay “danger zones” and marine traffic info on the map. For example, highlight busy shipping lanes, known submerged rocks or nets, and Indian EEZ boundaries. Fishermen often have no AIS receivers, so the app could use a static database of major shipping routes or broadcast areas (from IMO or Marine Regions datasets) to warn of collision risk. Such features are not present in current Indian apps. Why not done? Curated hazard data is hard to compile for Indian waters, and processing it on-device is heavy. It also requires accurate positioning; as one study notes, GPS can degrade near structures and waves. Failure modes: if the phone is wet or tilted, compass bearings could be unreliable. Effort: moderate (10+ days) to find/open data and draw it. In 30 h you’d likely only mark a couple static “no-go” polygons. Existing systems: FFMA and Thoondil do warn of international boundary crossings, but neither shows real-time shipping or hazards.

Tide and Current Forecasts: Provide predicted tidal heights and currents for each coastal zone. Fishermen rely on tides for launch/landing and catch behavior, but no Indian app currently computes local tide times offline. One could embed a small harmonic tide model (e.g. FES or TPXO data) for local ports. Why not done? Accurate tide modeling requires either connecting to IMD or NOAA, or bundling heavy harmonic constants. It’s complex and often skipped. A simpler fallback is to use Open-Meteo forecasts (if any tide output) or a table of high/low tide times. Engineering issues: tide computation is light, but showing it in Tamil UI needs care. In 30 h you might pre-load a few ports’ tide tables (cuttable if only target zones). Impact: moderate safety/income (avoiding grounding and optimizing fishing times). Existing Indian advisories do not mention local tides (PFZ uses depth but not tide schedule).

Storm & Lightning Alerts: Build a lightweight storm-warning feature using on-device sensors or embedded forecasts. For example, the app could monitor barometric pressure (many phones have pressure sensors) and accelerometer for sudden changes that often precede squalls. It could also store recent cyclone track data for the Bay of Bengal (e.g. IBTrACS or IMD bulletins) and warn if a major storm enters a zone. Lightning detection is especially valuable but hard: there is no free lightning-feed for India. (One could tap local radio static patterns or NOAA satellite data, but that exceeds 30 h.) Why not done? Because of no connectivity and lack of robust lightning data, this is largely unsolved. Note that special satellite systems (GAGAN/GEMINI, NavIC) are used for storm alerts, but smartphones can’t directly receive those messages. In practice you could at best embed recent IMD cyclone warnings (cutting it if 30 h deadline) or sound an alarm if pressure falls fast. Failure modes: pressure sensors drift and phones can heat up (thermal drift). Effort: moderate (10–15 days to integrate a mini forecasting library). Existing systems: DAT-SG/GEMINI provide cyclone/tornado alerts but require extra hardware, and no app offline does lightning today.

Other ideas (lower priority): Onboard motion sensing (using the phone’s accelerometer/gyroscope to detect hazardous wave motion) could be explored, but is hard to calibrate and noisy. Peer‐to‐peer mesh messaging (via Bluetooth/Wi‑Fi Direct) among nearby boats is another novel concept, but range is very short on rough seas and development is complex. Most Indian apps don’t attempt this. Fuel-optimization routing (minimize engine usage using wind/current data) could boost income, but it’s essentially an extension of the routing above and likely too much for 30 h.

Impact/Effort Ranking
Navigation & Charts. Impact: Very high (directly saves time/fuel, avoids hazards). Effort: Very high to fully implement (charts/licensing, routing algorithm). Score: ~0.6. Worth doing in part (e.g. compass guidance); full ENC support is infeasible in 30 h.
Storm Alerts. Impact: High (avoids life-threatening situations). Effort: Moderate (pressure sensor code, static cyclone data). Score: ~0.7. Worth doing (at least passive warnings); full lightning detection is low priority.
Hazard/Traffic Warnings. Impact: Medium (avoids ships/reefs). Effort: High (collecting data, rendering). Score: ~0.3. Low effort version (marking a few zones) could be done; full AIS integration is not worth it (no hardware and no data feed).
Tides/Currents Forecast. Impact: Medium (prevents grounding, improves catch timing). Effort: Moderate (model data needed). Score: ~0.5. A simple tide table is doable; full current modeling is too much.
Suggestions not worth doing in 30 h: full mesh networking (long-range comm or crowdsourcing is too complex), smartphone-based AIS (impractical without radio hardware), and AI forecasting of fish beyond PFZ (no data/capability offline). In each case, the gain is small compared to the huge engineering hurdles.

Common Pitfalls and Failure Modes
Connectivity assumptions: Many teams assume some cellular or Wi-Fi will be available. In reality Tamil Nadu fishing zones have days without service. All features must degrade gracefully when offline (e.g. show “data unavailable” rather than fictitious values). Don’t rely on Google/Firebase services.
Literacy and UI: Tamil fishermen vary widely in literacy. An app must work primarily by voice prompts and simple visuals. Overloading screens (e.g. dense charts or English text) fails in wet, windy conditions. We saw Thoondil succeed by using large icons, Tamil text, and text-to-speech. Teams often underestimate the need for voice interaction on a rocking boat.
Battery life: Constant GPS, screen on, or heavy sensor use will kill the battery. Phones typically have ~3000 mAh, which can be drained in hours by GPS+maps (as lab tests show). Most fishing trips last 8–12h, so features like continuous route tracking or accelerometer must run in bursts or low-power mode. Teams often neglect battery budgets.
Android restrictions: Modern Android aggressively throttles background tasks and sensors. An advisor service must likely run as a foreground service (with a notification) to keep GPS updates. If the app is idle or locked, location updates may pause. Prototype teams often find their background GPS stops after 10 min unless properly configured (BackgroundLocation permission, foreground service).
Hardware limits: Coastal decks are hot and wet. A phone in direct sun may thermal-throttle or shut down. Touchscreens fail with wet hands. No-touch controls (shake, buttons, big compass) are safer. Also, magnetometers (compass) can be distorted by the boat’s engine or rigging – test with the phone strapped in place.
False alarms: Triggering a “danger” voice message too often will make fishers ignore it. For example, spurious tilt readings or minor pressure wiggles shouldn’t blare warnings. Calibrate thresholds (e.g. only shout after sustained 5 m/s² tilt or rapid 5 hPa pressure drop) to avoid nuisance.
Data freshness: If you preload weather or cyclone tracks, note that a single-day trip may see a storm move. Clearly label forecast “as of [time]” and have the app lock out expired data rather than give a false safe indication. Teams often skip timestamping offline data.
Sources: We drew on existing systems and literature. INCOIS PFZ/OSF (via FFMA/SAMUDRA apps) already supply basic advisories. The Tamil Nadu “Thoondil” app includes offline port-routing and compass. NavIC/GAGAN systems like DAT-SG and GEMINI broadcast storm warnings. Western boating apps like Navionics/SavvyNavvy demonstrate offline charts and routing. We have used these references to judge novelty and feasibility. In summary, advanced routing and onboard hazard sensing would be impactful but are engineering-heavy; lightning/danger-zone alerts have high safety value but limited data availability; other ideas (mesh comm, AIS) are low-impact or require new hardware.
---

# 6. Fact-check of the three model answers (2026-08-30)

Every checkable claim below was tested against a primary source or against
the actual target hardware. Where I did not check something, it says so
rather than being left to look verified.

## 6.1 The one that fails on hardware

**Gemini's top-ranked feature cannot be built on the target phone.**

It ranked *"Offline CAP Squall & Barometric Sensor Early Warning"* as
**Highest Priority — implement immediately**, effort 3/10, on the strength
of "hardware sensors embedded directly in modern mobile devices".

The test device is an OPPO CPH2591 on Android 15. It reports 25 hardware
sensors. `android.sensor.pressure` is not among them:

```
$ adb shell dumpsys sensorservice | grep -i pressure
(no output)
```

There is no barometer, so there is no pressure trend, so the feature does
not exist. This is not bad luck with one handset: barometers are largely
absent from the ₹8,000–₹14,000 class, and Gemini's OWN cost section names
that class as what fishermen buy. Its highest-priority recommendation
contradicts its own user model two sections later.

A second finding from the same dump, worth knowing before anyone builds
anything sensor-based here:

```
0x00000004) oem-pseudo-gyro | virtual_gyro | type: android.sensor.gyroscope
```

The gyroscope is **synthesised in software**, not a physical rate gyro.
`WaveSensor` uses the accelerometer (`sc7a20`, real hardware), so it is
unaffected — but anything that leans on gyro data on this class of phone
is leaning on an estimate.

**What was built instead.** The *goal* — a storm warning that arrives
offline — is the one thing all three models and the entire Ockhi record
agree on. The barometer was only ever one route to it. IMD already
publishes its warnings continuously in a signed public feed, so the
warnings ride out in `/bundle` and the phone tests their polygons against
its own GPS fix. Same goal, a route that works on hardware people own.

## 6.2 Verified TRUE — including the parts that looked invented

I expected Gemini's specific endpoints and contacts to be its weakest
point. They are its strongest. Every one checked out:

| Claim | Verdict |
|---|---|
| IMD API portal at `api.imd.gov.in`, access by static-IP whitelisting | **True** — confirmed on `mausam.imd.gov.in/responsive/apis.php` |
| `/api/v1/districtnowcast`, `/api/v1/current_wx` | **True** — both documented |
| L1 support `rthnewdelhi4@gmail.com`, `+91-11-24344325` | **True**, verbatim, gmail address and all |
| L2 Dr. Sankar Nath, L3 Dr. Kuldeep Shrivastav | **True**, names and addresses correct |
| CAP feed at `cap-sources.s3.amazonaws.com/in-imd-en/rss.xml` | **True** — fetched live, 6 real alerts, XML-DSig signed |
| NavIC messaging inaccessible to consumer Android | **True**, and it kills idea ③ in §2 of this document |
| SARAT / Allen & Plourde leeway model | **True** — USCG R&D CG-D-08-99, underpins IAMSAR |
| ISO 15016:2015 | **True** |

Gemini also **under-sold** IMD's API. It listed the two nowcast endpoints
and described the marine ones vaguely. The portal actually documents
`/api/v1/seabulletin` (issued by the Area Cyclone Warning Centre),
`/api/v1/portwarning`, `/api/v1/coastalbulletin`, `/api/v1/cyclone_track`
and `/api/v1/cyclone_wind` — all more directly marine than anything it
named. Those are the production path once someone holds a whitelisted IP.

## 6.3 Verified FALSE

| Claim | Reality |
|---|---|
| Gemini: PMMSY subsidy "60% to 90%" | **40% general, 60% SC/ST/women.** Not close. |
| Gemini: DAT-SG unit "₹15,000–₹20,000" | PMMSY unit cost for VHF/DAT/NavIC/transponder is **₹35,000** |
| Gemini: out-of-pocket "₹2,000–₹6,00" | Not a number. Follows from the two errors above. |
| Gemini: "WMO-No. 1348 (Guide to Marine Meteorological Services)" | 1348 is the **Guide to Marine Emergency Response**. *Marine Meteorological Services* is **WMO-No. 471**. Its own works-cited list has this right — the body text contradicts its own reference. |

**Gemini's citation list is substantially padding.** Attached to claims
about ISRO ICDs and WMO standards are: a Devdiscourse story about Turkish
wildfires, a Drishti IAS current-affairs quiz, a UPSC "Facts and Data
Book", a Quora thread, and several Scribd re-uploads. The claims are
mostly true; these sources are not why. Treat the reference list as
decoration and check the claim itself — which is what §6.2 did.

## 6.4 Where the models contradict each other

**Boat-to-boat relay.** ChatGPT: *"range is very short on rough seas and
development is complex… Most Indian apps don't attempt this."* That is a
direct hit on `FleetRelay`, which is already built. It is not wrong about
the risk, and the relay remains **untested on two devices** — the one
claim in this project I cannot make. Two things soften it: BLE over open
water is line-of-sight with no clutter, which is its best case rather than
its worst; and the relay is opportunistic, so a failed meeting costs
nothing but a little battery. It should be presented as designed and
unit-tested (17 tests), never as field-proven.

**Lightning.** ChatGPT: no free lightning feed for India. Gemini: Damini
(IITM Pune) exists. Both are right about different things — Damini is a
real ground network with a real app, and has no free public API. Per the
convergence rule this is a **do not attempt**, and it stays unbuilt.

**Route planning.** All three rank it high, and it is the one SIH26176
capability ORCA still lacks. It survives the convergence test more
cleanly than anything else in these answers. Still unbuilt — Package A in
`docs/HANDOFF.md`, with Gemini's legal caveat (advisory vectors only,
never binding routes; pilotage stays the master's) now folded into it.

## 6.5 NOT checked

Stated plainly so nobody mistakes silence for verification:

- **Grok's fisherman quotes and attributions** — T. Maruthu, Justin of the
  Rameswaram Fishermen's Association, Amaladasan, Fr. Churchill. Not
  individually verified. They are consistent with the published Ockhi
  reporting, and they are used in this project only as motivation, never
  as a number in an advisory.
- **Adoption figures** — FFMA ~1.2 lakh registered, Thoondil 15–18k
  downloads, INCOIS SMS ~7 lakh. Plausible, unverified. Do not put them on
  a slide as fact.
- **Grok's trip economics** — ₹50 lakh–1 crore per mechanised boat,
  ₹60,000–80,000 per 24-hour trip. Unverified.

## 6.6 What this changed in the code

| Change | Driven by |
|---|---|
| `data/fetch.py` — `IMDCapAlertFetcher`, IMD's signed CAP feed | Gemini's endpoint, verified; Grok's Ockhi record |
| `orca/alerts.py` — deterministic point-in-polygon matching, three buckets | The goal Gemini's barometer was a broken route to |
| `orca/drift.py` + `DriftModel.kt` — Leeway engine-failure drift box | Gemini's idea, Allen & Plourde verified |
| `data/fetch.py` — wind **direction** added to the forecast fetcher | Drift is uncomputable without it |
| `BoundaryAlarm.kt` — closing-speed alerting replaces distance-only | Gemini AND Grok, independently, on alarm fatigue |
| `NewScreens.kt` — strip U+F000–U+F8FF from IMD text | Found on hardware: IMD ships Wingdings bullets that render as tofu |

## 6.7 The "it looks barebones" diagnosis (31 Aug)

A teammate said the mobile app felt lightweight next to the web client.
That was correct, and the reason was specific and checkable:

```
$ grep -rl 'Canvas\|drawLine\|drawPath' mobile/android/.../org/orca/advisory/
(nothing)
```

**Every mobile screen was text and cards. Zero graphics.** The web client
has MapLibre and a 3D ocean view. Side by side the phone looked thin even
though it did strictly more.

The fix was not to copy the web client's map. It was to notice that the
web client's map is the weaker one where it matters: MapLibre fetches
tiles over the network, so at sea it is a grey rectangle. ORCA already
ships the geometry — 4,760 NOAA ETOPO soundings, the four real IMBL
segments, IMD's CAP polygons — so the phone can draw a chart that is
exactly as good 60 km offshore as it is alongside.

Three features were added on that reasoning, all verified on an OPPO
CPH2591:

| Feature | Verified how |
|---|---|
| `OfflineMap.kt` — Canvas sea chart | Coast, shelf and deep water render from real soundings; white ring is the GPS fix; IMBL dashed. 15 `MapProjectionTest` cases cover the maths |
| `TorchSos.kt` — Morse SOS from the camera light | `dumpsys media.camera` shows the torch cycling on/off, and off cleanly on stop |
| `VerdictWidget.kt` — home-screen widget | Registered provider confirmed via `cmd package query-receivers` |

Two real bugs surfaced by running it rather than reading it:

1. **Compose does not clip a Canvas to its own layout.** Zone markers and
   the IMBL were painting straight over the layer toggles and the key
   below. Fixed with `clipToBounds()`.
2. **The release build failed only on a clean build**, with what looked
   like a broken NDK (`llvm-strip … Daemon startup failed`). It was
   memory: stripping forks a process per ABI and there was 600 MB free
   with five Gradle daemons up. Stripping is now skipped — ORCA ships no
   native code of its own, so it removes nothing, and the demo laptop is
   exactly the machine that cannot spare the fork.
