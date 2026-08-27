# ORCA — task completion checklist

A task is not done on "the code looks right" — CLAUDE.md's own definition of
done is: it was RUN, and real output was seen.

1. Backend touched -> `pytest -q` passes (baseline 103 — count should only
   grow, never shrink unless tests were deliberately removed for a reason
   worth stating).
2. `orca/policy.py` touched -> additionally do the manual mutation check
   (temporarily neuter the changed rule, confirm the specific tests that
   should fail do fail, revert, confirm full green again) before committing
   — see the docstrings in `tests/test_policy.py` for the pattern.
3. Frontend touched -> `npx playwright test` passes (baseline 12). It starts
   its own servers; don't hand-start anything first.
4. `data/fetch.py` or anything cache-shaped touched -> actually run
   `python data/fetch.py` and look at real stdout, then run both compliance
   greps from `mem:suggested_commands`.
5. Anything demo-facing touched -> re-run
   `scripts/generate_demo_scenarios.py` against a live server and eyeball the
   output; don't assume yesterday's `demo/scenarios.json` still reflects
   current code/data.
6. `git status` clean, changes committed in small units per `mem:conventions`.
