# TASK 3 — Tamil review, recorded audio, and proving the demo works

**Difficulty: easiest. Most of it needs no code at all.**
**Estimated: half a day for 3A + 3B if you can find a Tamil speaker.**

Do not read "easiest" as "least important". Task 3A is the only thing
standing between this project and shipping a Tamil safety sentence nobody
qualified has ever read. That is a bigger risk than anything in Task 1.

---

# PART 3A — Get the Tamil reviewed by a native speaker. **No coding.**

## Why this is urgent

Every Tamil string in ORCA was written without a native speaker checking
it. The verdicts include sentences like *"do not go out"*. **If a
negation reads the wrong way, someone takes a boat out into a 2.5 m sea.**

That is not a hypothetical class of bug — it is the single way this
project could actually hurt somebody.

## Do

1. Open `docs/TAMIL_REVIEW.md`. It is already generated and ready — every
   Tamil sentence with its English meaning beside it.
2. Find a native Tamil speaker. Ideally coastal (Nagapattinam,
   Rameswaram, Thoothukudi). A family member counts. A classmate counts.
3. Sit with them and go through it. Ask the three questions the sheet
   asks, **in its order**:
   - Is the meaning right? — **read every DO NOT GO and CANNOT ASSESS
     line twice**
   - Would a fisherman understand it immediately? (not "is it correct
     Tamil" — is it Tamil someone hears over an engine and acts on)
   - Does anything sound machine-translated?
4. Write their corrections into **`orca/phrase_ta.py`** — never into
   `docs/TAMIL_REVIEW.md`, which is generated.
5. Regenerate the sheet and re-run the tests:

```bash
.venv/bin/python scripts/tamil_review_sheet.py
.venv/bin/python -m pytest tests/test_phrase_ta.py -q
```

## Definition of done

The reviewer's **name and the date** are in the sign-off box at the bottom
of `docs/TAMIL_REVIEW.md`. Without a name it is not a review.

---

# PART 3B — Record the 18 Tamil audio clips. **15 minutes of recording.**

`docs/TAMIL_AUDIO_SCRIPT.md` is the script, already written.

## Why recorded and not text-to-speech

The spoken output is a **closed set** — four verdicts, ten harbour names,
four boundary lines, one connector. Eighteen clips. Because it is closed,
one person records all of it in about fifteen minutes and then every phone
says exactly what that person said: offline, in a real coastal accent,
and human-reviewed by definition.

TTS was tried and is worse. The `ta-IN` voice is not on every handset;
where it is, it often synthesises **over the network** — which is exactly
what fails at sea.

## Do

1. Ideally the same person who did 3A. Quiet room, phone voice recorder is
   fine.
2. Read each line in the script. One clip per line. **Leave a beat of
   silence before and after** — the clips get concatenated.
3. Export as `.m4a` or `.ogg`, named exactly as the script says.
4. Drop them into `web/audio/` and `mobile/android/app/src/main/res/raw/`.
5. Play the app back with the volume up. If a joined sentence sounds
   clipped, re-record that clip with more silence.

## Definition of done

Open the app, trigger a `DO NOT GO`, and hear a human Tamil voice say it.
Record a screen capture with audio and put it in the demo folder.

---

# PART 3C — Verify the demo end to end, and write down what you saw

This project's rule is: **a task is done when someone has RUN it and seen
correct output.** Not when a doc says so. Your job here is to be the
person who ran it.

## The full run

```bash
# 1. backend
.venv/bin/python -m uvicorn orca.api:app --port 8000

# 2. web client
python3 -m http.server 8080 --directory web
# open http://127.0.0.1:8080/index.html?api=http://127.0.0.1:8000

# 3. tests — both suites
.venv/bin/python -m pytest -q                                   # expect 477 passed, 1 skipped
ANDROID_HOME=$HOME/Android/Sdk gradle -p mobile/android testDebugUnitTest   # expect 73, 0 failures

# 4. phone
adb install -r apkforteamates/orca-3.0-storm-drift.apk
adb reverse tcp:8000 tcp:8000
```

## ⚠️ The trap that will waste your afternoon

**If you change any Python and the app still shows old behaviour, your
uvicorn is stale.** It does not auto-reload. Restart it.

This happened during testing: the storm screen correctly showed **NOT
CHECKED** because the running server had been started before the alerts
code existed and was serving a `/bundle` with no `alerts` block. The app
was right; the server was old. Restart before you debug anything.

## The offline test — the one that matters most

```bash
adb reverse --remove-all
adb shell cmd connectivity airplane-mode enable
# force-stop and reopen the app
```

Every screen must still work from the advisory shipped inside the APK.
The home banner must say **"No connection — showing what is stored on this
phone."** If any screen goes blank instead of explaining itself, that is
a bug — file it.

Turn airplane mode back off when you are finished.

## Write down

Fill in a table like this and put it in the demo folder:

| Screen | Online | Offline | Notes |
|---|---|---|---|
| Can I go out today? | | | |
| Where are the fish? | | | |
| Sea boundary warning | | | |
| Is a storm coming here? | | | |
| Engine dead — drift | | | |
| Emergency SMS | | | |
| Warn another boat | | | |
| Measure the sea | | | |
| Share with nearby boats | | | needs 2 phones — see TASK-2 §2C |
| Ask in Tamil | | | |

---

# PART 3D — Small, real bugs worth fixing

Ordered easiest first. Each is a genuine defect, not busywork.

### 1. Ranking questions return a meaningless action badge
*"Which is safer, Mandapam or Rameswaram?"* returns a `GO`/`NO-GO` badge,
but a ranking question has no subject zone, so the badge means nothing.
**Fix:** in `orca/agentic.py`, when `answer_kind == "ranking"`, suppress
the action badge. **Test:** a ranking answer carries no `action`.

### 2. Comparisons are not repeatable
The same comparison answered "Karaikal" once and correctly the next time.
Root cause is in `_rank_zones` in `orca/agentic.py`. **Fix:** make the
ordering fully deterministic — a stable tiebreak on zone name. **Test:**
call it 50 times, assert one distinct answer.

### 3. Misspellings only resolve when the LLM is up
`karaikkal` (two k's) falls back to nearest-by-GPS with no key.
**Fix:** add a small edit-distance pass to `_zone_by_substring`'s caller
in `orca/planner.py`. Keep it deterministic and boring — Levenshtein ≤ 2
against the ten zone names is plenty. **Test:** every zone name with one
letter doubled, one dropped, one swapped, resolves correctly with no
network.

> Heads up: if you are also doing **TASK-1 Part 1B**, that task rewrites
> `_zone_by_substring`. Coordinate, or do 1B first.

---

# Where everything is documented

| File | What it covers |
|---|---|
| `apkforteamates/README.md` | Installing and running the APK |
| `docs/HANDOFF.md` | Full state vs SIH26176, all known bugs |
| `docs/RESEARCH.md` §6 | Fact-check of the ChatGPT/Gemini/Grok answers — **read §6.1**, one of their top recommendations was impossible on real hardware |
| `docs/MOBILE_APP.md` | Mobile architecture, why the client owns no thresholds |
| `docs/CHATBOT.md` | The agentic layer and every failure mode |
| `docs/TAMIL_REVIEW.md` | The review sheet for 3A |
| `docs/TAMIL_AUDIO_SCRIPT.md` | The recording script for 3B |
