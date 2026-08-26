# MANUAL_TASKS.md — things a human has to do

Everything code-shaped from the war plan is done and tested (see
[`TEAM_STATUS.md`](TEAM_STATUS.md)). These are the remaining items —
none of them are something an agent can do for you, either because they
need a human judgment call, a physical device, real-world presence, or a
native speaker.

## Before the demo, in priority order

### 1. Refresh the data cache close to presentation time
The cache in `data/cache/` was fetched 2026-08-26 ~14:39 UTC. By the
actual demo it'll be stale (the frontend will honestly show a large
`freshness_min`, which is correct behavior, just not what you want on
stage). Shortly before presenting:
```bash
source .venv/bin/activate
python data/fetch.py
python scripts/generate_demo_scenarios.py --base-url http://127.0.0.1:<port>
```
The second command also tells you **which zone currently shows a live
safety-override conflict** — this changes with real weather, so check it
rather than assuming Zone A/B like the war plan's illustrative example.

### 2. Record a real Tamil audio sample
`web/index.html`'s "Play sample Tamil query" button currently:
- fills the query box with `நாகப்பட்டினத்தில் இருந்து மீன்பிடிக்க போகலாமா?`
  (a plain-Tamil rendering of "Should I go fishing from Nagapattinam?"),
  written by the AI build — **have a native Tamil speaker confirm this
  reads naturally**, especially the fishing-specific phrasing;
- tries to play `web/assets/tamil_sample.mp3`, which **does not exist
  yet**. The button degrades gracefully (fills the text, silently skips
  the audio) so this isn't a crash risk, but it means there's currently
  no actual audio.

To do: pick/confirm the phrase, have someone record it (even a phone
voice memo is fine), save as `web/assets/tamil_sample.mp3`. No code
changes needed after that — the button already points at that path.

### 3. Deck work (PowerPoint)
Can't be done from here — needs the actual `.pptx` file and PowerPoint/
equivalent. From the war plan (S14), still open:
- Open in real PowerPoint, check every slide for overflow (slides 3 & 4
  especially).
- Slide 2's agent diagram is a flat picture and doesn't show the
  conflict — the war plan's stated innovation isn't depicted. (There's a
  Mermaid flowchart of the actual decision logic in `README.md` under
  "Deterministic Safety Policy" if you want a reference for what the
  real mechanism looks like — could be redrawn into the deck.)
- Add a real demo screenshot to slide 5 — `docs/screenshots/orca_live_demo.png`
  is ready to use (captured from the live running system, real Zone B
  wind-risk override, evidence panel expanded). Take a fresh one closer
  to presentation day if you want current conditions instead.
- Verify "Miscellaneous" is a valid SIH 2026 theme for this ISRO PS.
- Confirm team name matches registration everywhere.

### 4. Test on the actual presentation laptop
Everything here was verified on a dev machine. Before presenting:
- Fresh `git clone` on the presentation laptop, run the Quick Start in
  `TEAM_STATUS.md` end to end.
- **Note:** this dev environment had something already listening on
  port 8000 unrelated to this project — check the presentation laptop
  isn't in the same situation, or just use a different port and update
  the `?api=` param / `API_CONTRACT.md` base URL note if so.
- Run once with real wifi, once with wifi physically off — confirm the
  offline badge flips and `/ask` still answers (this is tested in code,
  but "seen it happen live on the actual hardware" is a different kind
  of confidence for stage day).
- Open every browser tab you'll need before judges enter.

### 5. Screen recording + screenshots (fallback per war plan S13)
Record a full run-through and take screenshots of every screen. Put the
recording in an already-open tab as the crash fallback. Screenshot each
screen for the deck appendix.

### 6. Backup the deck
`.pptx` and `.pdf`, on two USBs and cloud, per the war plan's T-8
checklist.

### 7. Rehearsal
War plan S10 wants 8+ run-throughs, at least twice on the real laptop.
Presenter should be able to name all seven prior-art systems (war plan
S12) and answer the Q&A prep (S11) from memory — especially "Did AI
write this code?" (yes, with review — answer it calmly, it's in S11).

### 8. Venue logistics
Projector/laptop/power check, arrive early, run once on venue wifi and
once with it off, per war plan S10's T-1 → T-0 block. Charger + both
USBs packed.

### 9. MOSDAC / INCOIS registration
War plan S8.2 says register anyway (it's a real deck line: "registration
is in progress"). This needs a human with institutional credentials —
not something to automate. Not on ORCA's critical path either way, since
the adapter layer is source-agnostic by design.

### 10. Sleep
War plan S10 stages this deliberately: presenter and QA sleep a full
block, operators stagger. Not a joke item — it's in the plan for a
reason and nobody in this chat can do it for you.

---

## Things that look manual but aren't (already automated)

- Verifying "no synthetic data anywhere" — `grep -rni "mock\|sample\|synthetic\|dummy" data/cache/*.json` returns nothing (checked; also see `tests/test_fetch.py`).
- Verifying the safety rule can't be silently removed — see the mutation
  test in `tests/test_policy.py` and the manual mutation-and-revert
  verification described in that commit's message.
- Verifying "flip the wave height, the recommendation changes" — three
  layers of this exist: `tests/test_agents.py`, `tests/test_planner.py`,
  and `tests/test_policy.py`. You don't need to re-derive this by hand.
