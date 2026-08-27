# DEV A — frontend

**Branch:** `web` · **Kill time:** T+4:30
**Start:** as soon as Dev D pushes the frozen `API_CONTRACT.md` (~T+0:45).

**You own, exclusively:**
`web/index.html` · `web/mock_response.json` · `e2e/*.spec.js` ·
`tests/test_frontend_constants.py`

Nobody else touches these, and you touch nothing else. If a fix would take you into
`orca/`, it stops being your task and becomes a message to Dev D.

---

## Task 1 is the highest-leverage 20 minutes on the board

The backend is about to gain a fourth `action` value, `CANNOT ASSESS`, for a zone where
ORCA has no evidence at all. Right now `web/index.html:587` sends every unrecognised
value to the **green GO badge**:

```js
function actionClass(action) {
  if (action === "DO NOT GO")         return "action-do-not-go";
  if (action === "SAFER ALTERNATIVE") return "action-safer-alternative";
  return "action-go";   // ← every unrecognised value lands here
}
```

If the backend ships first, a zone ORCA *cannot assess* renders as a confident green
**GO**. That is strictly worse than the bug the backend is fixing.

**Your fix must merge before or with Dev D's R-39. Never after.** Do it first, push
it on its own, and tell Dev D the moment it's in — they are gated on you. Don't hold
it back to ship alongside tasks 2–4.

---

## Tasks

### 1. [P0 — gates the backend] Make the unknown verdict non-permissive

`web/index.html:587`.

- Add a `CANNOT ASSESS` branch.
- Change the fallback so **any** unrecognised value is non-permissive, not just the one
  you know about today. The next enum widening should not be able to do this again.

Style it with the **`--unknown`** token, which the page already defines at `:73` for
exactly this meaning — the comment above it reads *"a missing observation is never shown
as a guessed colour."* You are implementing the design system's own stated intent, not
inventing a colour. It's defined in all three palettes (Day `#6b7a82`, Dusk `#5c6a72`,
Night) so it works in every mode without extra work.

Add the matching badge rule beside the two at `:224–225`.

### 2. [P1] R-55 — mark the mock render

`MOCK_MODE` is read at `:478` and renders `web/mock_response.json` at `:840` — through
the **same `renderRecommendation()` a real advisory uses.** The result is
screenshot-identical to a live safety verdict.

It isn't reachable from the UI and it's never a fallback, so it isn't a live-path bug.
The problem is that a fabricated advisory can end up in a deck, a screenshot, or a
judge's photo with nothing on it saying so. PRD §10B.1 will forbid exactly this for
hypotheticals — v0.2 shouldn't ship the thing it's about to prohibit.

**Build:** a persistent banner, visible whenever `MOCK_MODE` is on.

- **Not** a tooltip, not a subtle border, not a console warning. It has to survive being
  screenshotted and cropped.
- Wire it off the existing `MOCK_MODE` constant — don't add a second source of truth.
- Use `--unknown` or the `--amber-*` family. **Not `--accent`** — that's starboard green
  and means GO.
- It must **not** appear on the live path. Check by loading without `?mock=1`.

`e2e/mock.spec.js` will need to expect it.

### 3. [P2] R-33 — reading age, and one false claim

R-33 is currently **Partial** in the PRD, and it's two separate things. The second takes
a minute and is worth doing even if you run out of time on the first.

**3a. Delete a claim the system doesn't honour.**
`web/index.html:583` renders the offline tooltip as
`"Offline — cached evidence, confidence adjusted"`. **Nothing anywhere reduces confidence
at read time.** Chlorophyll confidence does decay with staleness, but at *fetch* time,
per-source — a different thing. The tooltip states something false about the running
system. Delete the phrase. Making it true is a backend change and is out of this window.

**3b. Show each reading's age.**
The evidence panel shows each observation's `valid_time` and the source's own
`confidence`, but not `freshness_min` and no computed age. In degraded mode the user
can't tell a five-minute-old reading from a nine-hour-old one.

Every observation in the `evidence` array carries `freshness_min`. Render it as a human
age next to each reading — the panel already has `formatNumber()` and the `.mono` class
for figures; follow what's there rather than inventing a style.

Two things to watch: the panel is built per-observation, so whatever you add runs
ten-plus times per answer — keep it cheap. And `freshness_min` can be large after a
stale cache, which is correct behaviour and must render sensibly, not overflow the row.

### 4. [P1] Widen the e2e action assertions

`e2e/live.spec.js` lines **29, 66, 119, 176, 189** match
`/GO|DO NOT GO|SAFER ALTERNATIVE/`; lines **90** and **131** do equality checks.
`e2e/mock.spec.js:15` asserts exact text. All need to account for `CANNOT ASSESS`.

Dev B's consumer sweep gives you the full inventory with a verdict on which lines are
load-bearing. Don't wait for it to start on task 1.

### 5. [cut] Render `severity` and `blind_agents`

Only once Dev D pushes them — they're the bottom of the cut line and may not land.
Absent fields render nothing, so write it defensively and it's safe either way.

- `severity` (R-38) — lets a rerouted hard deny be told apart from a mild wind override,
  which currently differ only in prose.
- `blind_agents` (R-40) — which agents had no evidence. A `GO` resting on SST alone
  currently reads identically to one backed by every source.

---

## Done when

```bash
npx playwright test
```

Plus four checks by eye, because these are visual guarantees a test can't fully carry:

- A `CANNOT ASSESS` payload → grey badge, **never green**. Fake one by hand-editing a
  copy of `web/mock_response.json` if the backend hasn't landed yet — don't commit that
  edit.
- `?mock=1` → banner **visible in a screenshot**.
- No `?mock=1` → banner **absent**.
- Evidence panel → every reading shows an age; the offline tooltip no longer claims
  confidence was adjusted.

---

## Notes

- Dev D is removing `_is_reachable()` from `/ask` (R-54). The offline badge must keep
  working from `/health` — **confirm that with them before they merge.**
- Your three palettes are Day / Dusk / Night, driven by `data-palette`. Anything you add
  needs a token in all three, not a hardcoded colour in one.
