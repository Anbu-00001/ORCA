# DEV A — frontend

**Branch:** `web` · **Kill time:** T+4:30
**Start:** as soon as Dev D pushes the frozen `API_CONTRACT.md` (~T+0:45).

**You own, exclusively:**
`web/index.html` · `web/mock_response.json` · `e2e/*.spec.js` ·
`tests/test_frontend_constants.py`

Nobody else touches these, and you touch nothing else. If a fix would take you into
`orca/`, it stops being your task and becomes a message to Dev D.

---

## Start here

```bash
cd ~/cloon/or/ORCA
git fetch origin
git checkout -b web origin/main        # AFTER Dev D pushes the frozen contract

python3 -m venv .venv                  # once, if you don't have one
.venv/bin/pip install -r requirements.txt
npm install                            # once, for Playwright
```

## Run it and see your change

Two servers, two terminals, both left running:

```bash
# terminal 1 — backend
.venv/bin/uvicorn orca.api:app --reload --port 8000

# terminal 2 — the page
python3 -m http.server 8080 --directory web
```

Open **http://localhost:8080**. `index.html:479` defaults `API_BASE` to
`http://localhost:8000`, so they connect with no config (`?api=` overrides it).

- `http://localhost:8080/?mock=1` — the mock render you're marking in task 2
- `http://localhost:8000/health` — where the offline badge gets its data
- `http://localhost:8000/docs` — Swagger, to see a real `/ask` response shape

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

**Three edits, all in `web/index.html`.**

**a. The function at `:587`.** Note the last line: green stops being the fallback and
becomes an explicit case, so anything unrecognised lands on the non-permissive branch.

```js
function actionClass(action) {
  if (action === "DO NOT GO")         return "action-do-not-go";
  if (action === "SAFER ALTERNATIVE") return "action-safer-alternative";
  if (action === "GO")                return "action-go";
  return "action-unknown";   // CANNOT ASSESS — and anything we don't know yet
}
```

**b. The badge rule**, beside the two existing ones at `:224–225`:

```css
#answer-action.action-unknown { background: var(--unknown-bg); color: var(--unknown); }
```

**c. Add the `--unknown-bg` token to all three palettes.** `--unknown` already exists
(`:73` Day, `:89` Dusk, `:107` Night) and the comment above it states the intent you're
implementing — *"a missing observation is never shown as a guessed colour."* But there
is **no `--unknown-bg`**; you have to add it next to each `--unknown`:

```css
:root, :root[data-palette="day"] { --unknown-bg: #eceff0; }   /* near :73  */
:root[data-palette="dusk"]       { --unknown-bg: #232e33; }   /* near :89  */
:root[data-palette="night"]      { --unknown-bg: #1a0f0e; }   /* near :107 */
```

Those values are suggestions that match each palette's existing `*-bg` pairs — check
them against the real backgrounds and adjust if the contrast is off.

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

---

## Ship it

Push task 1 **on its own, immediately** — Dev D is gated on it. Don't batch it with
tasks 2–4.

```bash
git add web/index.html                 # check `git status` first
git commit -m "fix: unrecognised action values render non-permissive (R-25)"
git push -u origin web
```

Then message Dev D that `web` is pushed and R-39 is unblocked. Tasks 2–4 go in later
commits on the same branch.

`git add .` is fine here — `.gitignore` covers `.venv/` and the caches. Just run
`git status` first, because two things in your task list must **not** be committed: the
screenshots you take to verify R-55, and the hand-edited `mock_response.json` you use to
fake a `CANNOT ASSESS` payload before the backend lands.
