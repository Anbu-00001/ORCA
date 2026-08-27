# ORCA — suggested commands

```bash
source .venv/bin/activate                 # always activate first
pip install -r requirements.txt

pytest -q                                 # backend tests, ~103, <10s
npx playwright test                       # e2e, ~12, boots its own servers
                                           # (playwright.config.js webServer[]
                                           # on 127.0.0.1:8080 + :8011) --
                                           # do NOT hand-start servers first

python data/fetch.py                      # refresh data/cache/ with REAL
                                           # network calls -- the only script
                                           # that should ever do this

python scripts/generate_demo_scenarios.py --base-url <running-api-url>
                                           # regenerate demo/scenarios.json
                                           # from a live /ask; run this close
                                           # to any actual demo, conditions
                                           # (real sea state) drift daily

uvicorn orca.api:app --host 127.0.0.1 --port <port>   # run API standalone
python -m http.server 8080 --directory web            # serve frontend
# open web/index.html?api=http://127.0.0.1:<port>
# NOTE: port 8000 (the documented default) was occupied by an unrelated
# process on the dev machine at least once -- don't assume it's free.

python -m orca.mcp_server                 # run the MCP server (stdio)
```

## Compliance checks — run before calling anything "done"
```bash
grep -rn "except:" orca/ data/                              # must be empty
grep -rni "mock\|sample\|synthetic\|dummy" data/cache/*.json # must be empty
```
