// @ts-check
const { test, expect } = require('@playwright/test');

// Full end-to-end tests against the REAL running FastAPI backend (started
// by playwright.config.js's webServer on :8011) and the REAL data/cache/.
// No mocked network here -- this is the actual proof the wired-together
// system works, not just that the HTML renders a fixture.

const API = 'http://127.0.0.1:8011';

test.describe('frontend against the real live API', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`/index.html?api=${encodeURIComponent(API)}`);
  });

  test('offline badge reflects the real /health endpoint', async ({ page }) => {
    const badge = page.getByTestId('offline-badge');
    await expect(badge).toBeVisible();
    // Whatever it says, it must be one of the two real states, not stuck loading.
    await expect(badge).toHaveText(/ONLINE|OFFLINE/);
  });

  test('submitting a real query returns a real, evidence-backed answer', async ({ page }) => {
    await page.getByTestId('query-input').fill('Should I go fishing near Nagapattinam?');
    await page.getByTestId('lat-input').fill('10.7672');
    await page.getByTestId('lon-input').fill('79.8449');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toHaveText(/GO|DO NOT GO|SAFER ALTERNATIVE/);

    const items = page.getByTestId('evidence-item');
    await expect(items.first()).toBeVisible({ timeout: 10000 });
    const count = await items.count();
    expect(count).toBeGreaterThan(0);
  });

  test('every rendered evidence number shows a real source and timestamp when expanded', async ({ page }) => {
    await page.getByTestId('query-input').fill('Nagapattinam');
    await page.getByTestId('lat-input').fill('10.7672');
    await page.getByTestId('lon-input').fill('79.8449');
    await page.getByTestId('ask-button').click();

    const first = page.getByTestId('evidence-item').first();
    await expect(first).toBeVisible({ timeout: 10000 });
    await first.click();
    const detail = first.getByTestId('evidence-detail');
    await expect(detail).toBeVisible();
    await expect(detail).toContainText('confidence', { ignoreCase: true });
    // The provenance URL for every real source we fetch from.
    await expect(detail).toContainText(/open-meteo|erddap|coastwatch/i);
  });

  // orca/agentic.py's chatbot layer, through the real HTTP + browser path
  // (not just calling the Python function directly, as tests/test_agentic.py
  // does) -- proves the wiring in orca/api.py's /ask handler, not just the
  // module in isolation. Skips itself (this test only, not the rest of the
  // suite) if the webServer wasn't started with a real key, same shape as
  // the pytest "agentic" marker.
  test('a free-text query with no zone name literally in it resolves via the real agentic layer', async ({ page }) => {
    test.skip(!process.env.GROQ_API_KEY, 'requires the webServer to be started with a real GROQ_API_KEY');
    await page.getByTestId('query-input').fill('Is it safe to fish near the southernmost tip of India today?');
    await page.getByTestId('lat-input').fill('8.0883');
    await page.getByTestId('lon-input').fill('77.5385');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toHaveText(/GO|DO NOT GO|SAFER ALTERNATIVE/, { timeout: 15000 });
    await expect(page.getByTestId('agentic-badge')).not.toHaveClass(/hidden/);
    // Real backend, real Groq call: proves the LLM actually resolved
    // "southernmost tip of India" to the real Kanyakumari zone, something
    // plain substring matching (the pre-existing behaviour) could never do.
    await expect(page.getByTestId('answer-text')).toContainText(/Kanyakumari/i);
  });

  test('a zone with a real live conflict shows the amber override banner', async ({ page }) => {
    // As of the last demo/scenarios.json capture, Karaikal has a genuine
    // wind-risk-vs-opportunity conflict live. If sea state has changed by
    // the time this runs, re-run scripts/generate_demo_scenarios.py to
    // find which zone currently shows it -- this test asserts the
    // *mechanism* (banner appears exactly when overridden is non-empty),
    // not a specific frozen outcome.
    await page.getByTestId('query-input').fill('Karaikal');
    await page.getByTestId('lat-input').fill('10.9327');
    await page.getByTestId('lon-input').fill('79.8319');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toBeVisible({ timeout: 10000 });
    const action = await page.getByTestId('answer-action').textContent();
    const banner = page.getByTestId('override-banner');

    if (action && action.trim() === 'SAFER ALTERNATIVE') {
      await expect(banner).toBeVisible();
    } else {
      await expect(banner).toBeHidden();
    }
  });

  test('offline badge flips when connectivity is lost, but /ask still works from cache', async ({ page }) => {
    // Simulates "physically switch off the wifi" (S8.6): intercept only
    // /health's connectivity probe. /ask is left completely untouched,
    // hitting the real backend/cache -- proving the demo doesn't depend
    // on the badge's state to keep functioning.
    await page.route(`${API}/health`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'ok', offline_mode: true, cache_age_min: 5, cache_observation_count: 42 }),
      })
    );
    await page.reload();

    const badge = page.getByTestId('offline-badge');
    await expect(badge).toHaveText('OFFLINE');

    await page.getByTestId('query-input').fill('Nagapattinam');
    await page.getByTestId('lat-input').fill('10.7672');
    await page.getByTestId('lon-input').fill('79.8449');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toHaveText(/GO|DO NOT GO|SAFER ALTERNATIVE/, { timeout: 10000 });
    await expect(page.getByTestId('evidence-item').first()).toBeVisible();
  });

  test('a clean GO zone does NOT show the override banner', async ({ page }) => {
    await page.getByTestId('query-input').fill('Nagapattinam');
    await page.getByTestId('lat-input').fill('10.7672');
    await page.getByTestId('lon-input').fill('79.8449');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toBeVisible({ timeout: 10000 });
    const action = (await page.getByTestId('answer-action').textContent() || '').trim();
    if (action === 'GO') {
      await expect(page.getByTestId('override-banner')).toBeHidden();
    }
  });
});

// web/three-viz.js against the real backend: real GET /bathymetry (NOAA
// ETOPO 2022, cached by data/fetch.py) and a real /ask response's
// agent_findings/zone_summaries -- not fixtures this time.
test.describe('3D visualizations against the real live API', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`/index.html?api=${encodeURIComponent(API)}`);
  });

  test('GET /bathymetry itself returns a real, non-empty grid', async ({ request }) => {
    const resp = await request.get(`${API}/bathymetry`);
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    expect(data.points.length).toBeGreaterThan(0);
    expect(data.points[0]).toHaveProperty('elevation_m');
  });

  test('3D Ocean view loads real relief from the running backend', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (err) => errors.push(err.message));

    await page.getByTestId('view-toggle-3d').click();
    await expect(page.getByTestId('ocean3d-container')).not.toHaveClass(/awaiting/, { timeout: 10000 });

    const canvas = page.locator('#ocean3d-container canvas');
    await expect(canvas).toBeAttached();
    const box = await canvas.boundingBox();
    expect(box.width).toBeGreaterThan(100);
    expect(box.height).toBeGreaterThan(100);
    expect(errors).toEqual([]);
  });

  test('reasoning graph reflects a real submitted query, not a stale/mock one', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (err) => errors.push(err.message));

    await page.getByTestId('query-input').fill('Nagapattinam');
    await page.getByTestId('lat-input').fill('10.7672');
    await page.getByTestId('lon-input').fill('79.8449');
    await page.getByTestId('ask-button').click();
    await expect(page.getByTestId('answer-action')).toHaveText(/GO|DO NOT GO|SAFER ALTERNATIVE/, { timeout: 10000 });

    await page.getByTestId('reasoning3d-toggle').click();
    const canvas = page.locator('#reasoning3d-container canvas');
    await expect(canvas).toBeAttached();
    const box = await canvas.boundingBox();
    expect(box.width).toBeGreaterThan(50);
    expect(errors).toEqual([]);
  });

  test('the 3D-to-query bridge round-trips through the real backend', async ({ page }) => {
    await page.evaluate(() => window.__ORCA_SELECT_ZONE__('Thoothukudi', 8.4730, 78.1215));
    await expect(page.getByTestId('query-input')).toHaveValue('Thoothukudi');
    await expect(page.getByTestId('answer-action')).toHaveText(/GO|DO NOT GO|SAFER ALTERNATIVE/, { timeout: 10000 });
    await expect(page.getByTestId('evidence-item').first()).toBeVisible();
  });
});

// "Physically turn off the wifi. Don't simulate it." (S8.6) is a human
// instruction for the real demo -- what we CAN verify from here is the
// part that's actually risky: on a real laptop with wifi off, localhost
// traffic (the static file server, the FastAPI backend) keeps working
// fine, but any CDN request (MapLibre's JS from unpkg, its tiles from
// demotiles.maplibre.org) will hang or fail. If that takes the whole
// page down instead of degrading to the fallback, that's a real bug.
test.describe('page behaviour with all external network blocked (wifi-off simulation)', () => {
  test('page still loads and answers queries with only localhost reachable', async ({ page }) => {
    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      const isLocal = url.hostname === '127.0.0.1' || url.hostname === 'localhost';
      if (isLocal) return route.continue();
      return route.abort('internetdisconnected');
    });

    await page.goto(`/index.html?api=${encodeURIComponent(API)}`);

    // The page itself (served from localhost) must still render and be usable.
    await expect(page.getByTestId('query-input')).toBeVisible();
    await expect(page.getByTestId('offline-badge')).toBeVisible();

    // MapLibre's CDN script was blocked -- the SVG fallback must show
    // instead of a blank/broken map area.
    await expect(page.locator('#map-fallback')).toHaveClass(/visible/, { timeout: 5000 });

    // The real backend is on localhost, so it's still reachable and the
    // core "ask a question, get an evidenced answer" flow keeps working.
    await page.getByTestId('query-input').fill('Nagapattinam');
    await page.getByTestId('lat-input').fill('10.7672');
    await page.getByTestId('lon-input').fill('79.8449');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toHaveText(/GO|DO NOT GO|SAFER ALTERNATIVE/, { timeout: 10000 });
    await expect(page.getByTestId('evidence-item').first()).toBeVisible();
  });
});
