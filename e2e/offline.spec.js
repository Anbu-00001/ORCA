// The boat test: does ORCA still answer when there is NO network at all?
//
// This file exists because of a specific, expensive lesson. The previous
// "wifi off" e2e test passed throughout the 2026-08-28 demo failure,
// because it asserted only that a QUESTION still got an ANSWER -- which
// was true, since /ask was served by a backend the test itself had
// started on localhost. Nothing asserted that the app survived losing
// the backend, which is precisely what happens a few km out of harbour.
//
// So the tests below cut the whole world, backend included, and then ask
// for a safety verdict. See docs/MOBILE_APP.md §4 and web/offline.js.
const { test, expect } = require('@playwright/test');

const API = process.env.ORCA_API || 'http://127.0.0.1:8011';
const PHONE = { width: 412, height: 915 }; // OPPO CPH2591, the device this was verified on

// Everything the shell needs must already be cached before the network
// goes. That IS the product: a crew connects in harbour, then sails.
async function primeInHarbour(page) {
  await page.goto(`/index.html?api=${encodeURIComponent(API)}`);
  await page.waitForFunction(
    () => window.ORCAOffline && window.ORCAOffline.bundleStatus().present,
    null, { timeout: 20000 }
  );
  // The service worker precaches 15 shell entries plus every font face.
  await page.waitForFunction(async () => {
    const names = await caches.keys();
    for (const n of names) {
      if ((await (await caches.open(n)).keys()).length >= 40) return true;
    }
    return false;
  }, null, { timeout: 30000 });

  // A CACHE IS NOT ENOUGH: the worker must also be CONTROLLING this page,
  // or the next navigation goes straight to the network and dies. On a
  // first visit the page that registers a worker is not controlled by it
  // until activate + clients.claim() have run, so waiting on
  // `caches` alone raced and every offline reload failed with
  // ERR_INTERNET_DISCONNECTED. This is the real precondition, and it is
  // also the honest one: a crew whose phone reaches this state in harbour
  // is a crew who can sail.
  await page.waitForFunction(
    () => !!navigator.serviceWorker.controller, null, { timeout: 20000 }
  );
}

test.describe('offline at sea', () => {
  test.use({ viewport: PHONE });

  test('the stored bundle covers every zone and is small enough for a harbour link', async ({ page }) => {
    await primeInHarbour(page);
    const stored = await page.evaluate(() => ({
      zones: window.ORCAOffline.bundleStatus().zoneCount,
      bytes: (localStorage.getItem(window.ORCAOffline.BUNDLE_KEY) || '').length,
    }));
    expect(stored.zones).toBe(10);
    // Measured 52 KB. The ceiling is generous on purpose -- this guards
    // against the bundle silently growing into something a 2G link in a
    // harbour cannot pull, not against a few KB of drift.
    expect(stored.bytes).toBeLessThan(400 * 1024);
  });

  test('the app shell loads with the backend AND the network gone', async ({ page, context }) => {
    await primeInHarbour(page);
    await context.setOffline(true);
    await page.reload({ waitUntil: 'load' });
    // If the service worker is not serving the shell, this reload is a
    // browser error page and every assertion below is unreachable.
    await expect(page.getByTestId('cache-status')).toContainText('zones stored');
    await expect(page.locator('#answer-card')).toBeVisible();
  });

  test('a safety question is answered from cache, and says that it was', async ({ page, context }) => {
    await primeInHarbour(page);
    await context.setOffline(true);
    await page.reload({ waitUntil: 'load' });

    await page.locator('#query-input, textarea, input[type=text]').first()
      .fill('Is it safe at Mandapam?');
    await page.getByRole('button', { name: /ask orca/i }).click();

    const action = page.locator('#answer-action');
    await expect(action).not.toHaveText('ERROR', { timeout: 15000 });
    await expect(action).not.toHaveText('NO DATA');
    // The verdict is policy.py's, carried in the bundle -- not recomputed.
    await expect(action).toHaveText(/GO|DO NOT GO|SAFER ALTERNATIVE|CANNOT ASSESS/);
    // Rule 1's shape: a cached answer must never present as a live one.
    await expect(page.getByTestId('coverage-note')).toContainText('Offline');
  });

  test('a cached answer still carries its evidence and provenance', async ({ page, context }) => {
    await primeInHarbour(page);
    await context.setOffline(true);
    await page.reload({ waitUntil: 'load' });
    await page.locator('#query-input, textarea, input[type=text]').first()
      .fill('Is it safe at Mandapam?');
    await page.getByRole('button', { name: /ask orca/i }).click();
    await expect(page.getByTestId('evidence-item').first()).toBeVisible({ timeout: 15000 });
    // CLAUDE.md rule 3 does not weaken because the reading came off disk.
    const detail = await page.getByTestId('evidence-detail').first().textContent();
    expect(detail).toContain('source');
    expect(detail).toContain('provenance');
  });

  test('a multi-zone question names every zone it matched instead of silently picking one', async ({ page, context }) => {
    // Online, orca/planner.py's _zone_by_substring() returns the FIRST
    // zone in declaration order, so this question answers for Chennai and
    // never mentions Thoothukudi -- a GO badge for a voyage ending at the
    // roughest zone in the fleet (docs/CHATBOT.md §8.1). Offline there is
    // no model to disambiguate, so the client refuses to guess quietly.
    await primeInHarbour(page);
    await context.setOffline(true);
    await page.reload({ waitUntil: 'load' });
    await page.locator('#query-input, textarea, input[type=text]').first()
      .fill("I'm sailing from Chennai down to Thoothukudi, is it safe?");
    await page.getByRole('button', { name: /ask orca/i }).click();
    const note = page.getByTestId('coverage-note');
    await expect(note).toContainText('more than one place', { timeout: 15000 });
    await expect(note).toContainText('Chennai');
    await expect(note).toContainText('Thoothukudi');
  });

  test('with nothing cached, it says so rather than improvising', async ({ page, context }) => {
    // The one case where there is genuinely no answer. An absent answer
    // is correct; an invented one is not, and a GO-coloured badge here
    // would be the worst possible failure.
    await page.goto(`/index.html?api=${encodeURIComponent(API)}`);
    await page.evaluate(() => localStorage.clear());
    await context.setOffline(true);
    await page.locator('#query-input, textarea, input[type=text]').first()
      .fill('Is it safe at Mandapam?');
    await page.getByRole('button', { name: /ask orca/i }).click();
    const action = page.locator('#answer-action');
    await expect(action).toHaveText('NO DATA', { timeout: 15000 });
    // Non-permissive: the absence of a judgement must never look like GO.
    await expect(action).toHaveClass(/action-unknown/);
  });
});

test.describe('the PWA is actually installable', () => {
  test('the manifest and both icon sizes are served', async ({ request }) => {
    const manifest = await request.get('/manifest.json');
    expect(manifest.ok()).toBeTruthy();
    const body = await manifest.json();
    expect(body.start_url).toBeTruthy();
    expect(body.display).toBe('standalone');
    // A maskable icon is what stops Android drawing the mark inside a
    // white rounded square on the launcher.
    const purposes = body.icons.map((i) => i.purpose);
    expect(purposes).toContain('maskable');
    for (const icon of body.icons) {
      expect((await request.get(icon.src.replace('./', '/'))).ok()).toBeTruthy();
    }
  });

  test('the Tamil font is vendored, not fetched from Google', async ({ page }) => {
    // docs/MOBILE_APP.md: device Tamil fonts vary badly across Android
    // OEMs, and ORCA's users read Tamil on mid-range Android phones. A
    // Tamil face fetched from fonts.gstatic.com is a face a boat does
    // not have.
    const remote = [];
    page.on('request', (r) => {
      const h = new URL(r.url()).hostname;
      if (h.includes('googleapis') || h.includes('gstatic')) remote.push(r.url());
    });
    await page.goto(`/index.html?api=${encodeURIComponent(API)}`);
    await page.waitForTimeout(2000);
    expect(remote).toEqual([]);
    const css = await page.request.get('/vendor/fonts.css');
    expect(css.ok()).toBeTruthy();
    expect(await css.text()).toContain('Noto Sans Tamil UI');
  });
});
