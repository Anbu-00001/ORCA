# ORCA — project rules for Claude Code

## Context
Marine advisory system for Indian fishermen. SIH 2026 prototype.
Demoed live on 28th. Reliability beats features. Boring beats clever.

## HARD RULES — never violate

1. NO SYNTHETIC DATA. Never generate, mock, simulate or fall back to
   placeholder marine data. If a source fails, raise loudly. An absent
   reading is correct; a fabricated one destroys the project's claim.
2. NO `except: pass`. No swallowed exceptions anywhere.
3. Every number shown to a user MUST be a MarineObservation carrying
   source, valid_time, confidence and provenance. Bare floats are dropped.
4. orca/policy.py contains NO LLM calls. Deterministic Python only.
   It is the project's safety guarantee and must be unit-testable.
5. Do not modify orca/schema.py or orca/policy.py once tests pass,
   unless explicitly asked.
6. No new dependencies without asking. No frameworks for five functions.
7. Prefer boring, readable code. No factories, no plugin registries,
   no premature abstraction.
8. The demo must run with NO network access. Everything reads from
   data/cache/. Any network call outside data/fetch.py is a bug.

## Stack
Python 3.11, FastAPI, plain HTML + MapLibre from CDN.
No build step. No database. JSON files on disk.

## Definition of done
A task is done when I have RUN it and seen correct output —
not when you report it as complete. Always show me the command to run
and the output you got.
