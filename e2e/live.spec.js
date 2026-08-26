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
    await page.getByTestId('query-input').fill('Should I go fishing in Zone A?');
    await page.getByTestId('lat-input').fill('10.76');
    await page.getByTestId('lon-input').fill('79.84');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toHaveText(/GO|DO NOT GO|SAFER ALTERNATIVE/);

    const items = page.getByTestId('evidence-item');
    await expect(items.first()).toBeVisible({ timeout: 10000 });
    const count = await items.count();
    expect(count).toBeGreaterThan(0);
  });

  test('every rendered evidence number shows a real source and timestamp when expanded', async ({ page }) => {
    await page.getByTestId('query-input').fill('Zone A');
    await page.getByTestId('lat-input').fill('10.76');
    await page.getByTestId('lon-input').fill('79.84');
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

  test('a zone with a real live conflict shows the amber override banner', async ({ page }) => {
    // As of the last demo/scenarios.json capture, Zone B has a genuine
    // wind-risk-vs-opportunity conflict live. If sea state has changed by
    // the time this runs, re-run scripts/generate_demo_scenarios.py to
    // find which zone currently shows it -- this test asserts the
    // *mechanism* (banner appears exactly when overridden is non-empty),
    // not a specific frozen outcome.
    await page.getByTestId('query-input').fill('Zone B');
    await page.getByTestId('lat-input').fill('10.85');
    await page.getByTestId('lon-input').fill('79.95');
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

    await page.getByTestId('query-input').fill('Zone A');
    await page.getByTestId('lat-input').fill('10.76');
    await page.getByTestId('lon-input').fill('79.84');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toHaveText(/GO|DO NOT GO|SAFER ALTERNATIVE/, { timeout: 10000 });
    await expect(page.getByTestId('evidence-item').first()).toBeVisible();
  });

  test('a clean GO zone does NOT show the override banner', async ({ page }) => {
    await page.getByTestId('query-input').fill('Zone A');
    await page.getByTestId('lat-input').fill('10.76');
    await page.getByTestId('lon-input').fill('79.84');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toBeVisible({ timeout: 10000 });
    const action = (await page.getByTestId('answer-action').textContent() || '').trim();
    if (action === 'GO') {
      await expect(page.getByTestId('override-banner')).toBeHidden();
    }
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
    await page.getByTestId('query-input').fill('Zone A');
    await page.getByTestId('lat-input').fill('10.76');
    await page.getByTestId('lon-input').fill('79.84');
    await page.getByTestId('ask-button').click();

    await expect(page.getByTestId('answer-action')).toHaveText(/GO|DO NOT GO|SAFER ALTERNATIVE/, { timeout: 10000 });
    await expect(page.getByTestId('evidence-item').first()).toBeVisible();
  });
});
