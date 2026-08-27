// @ts-check
const { test, expect } = require('@playwright/test');
const { spawn } = require('child_process');

// orca/agentic.py's most safety-critical real-world exceptional case:
// what happens on stage if the Groq key is wrong, revoked, rate-limited,
// or just mistyped? tests/test_agentic.py already covers every MOCKED
// failure mode (ConnectionError, Timeout, malformed JSON, non-200
// status); this is the one live gap -- a genuine, real 401 from the real
// Groq API, hit through the actual running backend + browser, not
// simulated. Spins up a second, disposable backend instance on its own
// port with a syntactically-plausible but deliberately invalid key --
// the shared webServer on :8011 (playwright.config.js) either has the
// real key or none, so this can't reuse it.

const PORT = 8012;
const API = `http://127.0.0.1:${PORT}`;
let backend;

test.beforeAll(async () => {
  backend = spawn(
    'bash',
    ['-c', `source .venv/bin/activate && uvicorn orca.api:app --host 127.0.0.1 --port ${PORT}`],
    {
      cwd: __dirname + '/..',
      env: { ...process.env, GROQ_API_KEY: 'gsk_deliberately_invalid_for_headless_exception_testing' },
    }
  );
  const deadline = Date.now() + 15000;
  // eslint-disable-next-line no-constant-condition
  while (true) {
    try {
      const r = await fetch(`${API}/health`);
      if (r.ok) break;
    } catch (e) {
      // not up yet
    }
    if (Date.now() > deadline) throw new Error('disposable backend did not start in time');
    await new Promise((r) => setTimeout(r, 300));
  }
});

test.afterAll(() => {
  backend.kill();
});

test.describe('agentic layer against a real, live, invalid GROQ_API_KEY', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`/index.html?api=${encodeURIComponent(API)}`);
  });

  test('a query needing the LLM for zone resolution still answers correctly via the deterministic fallback', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (err) => errors.push(err.message));

    // Plain substring matching (the zero-risk path) could never resolve
    // this -- it genuinely needs the LLM step, which will genuinely fail
    // with a real 401 against this instance's bad key.
    await page.getByTestId('query-input').fill('Is it safe to fish near the southernmost tip of India today?');
    await page.getByTestId('lat-input').fill('8.0883');
    await page.getByTestId('lon-input').fill('77.5385');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toHaveText(/^(GO|DO NOT GO|SAFER ALTERNATIVE|CANNOT ASSESS)$/, { timeout: 15000 });
    await expect(page.getByTestId('evidence-item').first()).toBeVisible();
    // Honest badge: it did NOT get agentic help this time -- a real 401
    // must never be silently presented as if the enhancement worked.
    await expect(page.getByTestId('agentic-badge')).toHaveClass(/hidden/);
    expect(errors).toEqual([]);
  });

  test('an exact-name query is completely unaffected by the broken key (zero-risk substring path never touches the network)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (err) => errors.push(err.message));

    await page.getByTestId('query-input').fill('Nagapattinam');
    await page.getByTestId('lat-input').fill('10.7672');
    await page.getByTestId('lon-input').fill('79.8449');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toHaveText(/^(GO|DO NOT GO|SAFER ALTERNATIVE|CANNOT ASSESS)$/, { timeout: 15000 });
    await expect(page.getByTestId('evidence-item').first()).toBeVisible();
    expect(errors).toEqual([]);
  });

  test('GET /health on the same broken-key instance is unaffected (key only matters to /ask)', async ({ request }) => {
    const resp = await request.get(`${API}/health`);
    expect(resp.ok()).toBeTruthy();
  });
});
