// @ts-check
const { test, expect } = require('@playwright/test');

// Isolated frontend tests: web/index.html?mock=1 loads web/mock_response.json
// instead of calling the network. This is the "never blocked on the backend"
// mode from the war plan (Prompt 4) -- it exercises rendering logic with a
// fixed, known payload, independent of live sea state or the API being up.

test.describe('frontend in mock mode', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?mock=1');
  });

  // R-55: ?mock=1 renders a fabricated advisory through the same
  // renderRecommendation() a live answer uses, so on its own it is
  // screenshot-identical to a real safety verdict. The banner is the only
  // thing that marks it -- assert it is really there and really readable,
  // not merely attached to the DOM.
  test('R-55: a mock render is visibly marked as mock and not a live advisory', async ({ page }) => {
    const banner = page.getByTestId('mock-mode-banner');
    await expect(banner).toBeVisible();
    await expect(banner).toContainText(/MOCK DATA/i);
    await expect(banner).toContainText(/NOT A LIVE ADVISORY/i);

    // It has to survive a screenshot, so it must occupy real space near the
    // top of the page -- a hairline border or an offscreen element passes
    // toBeVisible() but fails the thing R-55 actually asks for.
    const box = await banner.boundingBox();
    expect(box.height).toBeGreaterThan(20);
    expect(box.width).toBeGreaterThan(200);
    expect(box.y).toBeLessThan(100);
  });

  test('renders the answer card from the mock response', async ({ page }) => {
    await expect(page.getByTestId('answer-action')).toHaveText('SAFER ALTERNATIVE');
    await expect(page.getByTestId('answer-text')).toContainText('Karaikal');
  });

  // orca/agentic.py's chatbot layer: mock_response.json carries
  // agentic_used/detected_language exactly as the real /ask response
  // would when GROQ_API_KEY is set and the call succeeds. The badge is
  // honest, not decorative -- see web/index.html's #agentic-badge CSS
  // comment -- rendered directly from mock_response.json on load, same
  // as the answer card itself (mock mode renders once on init(), before
  // any submit -- see that function).
  test('agentic badge reflects agentic_used and answer-text carries the detected language', async ({ page }) => {
    await expect(page.getByTestId('agentic-badge')).not.toHaveClass(/hidden/);
    await expect(page.getByTestId('agentic-badge')).toHaveText('AI-enhanced');
    await expect(page.getByTestId('answer-text')).toHaveAttribute('lang', 'en');
  });

  test('Douglas ruler renders the real wave-height evidence with the correct band', async ({ page }) => {
    // mock_response.json's wave_height_m is 3.1 -- above the 2.5m hard-deny
    // line, so this must land in Douglas band 5 (Rough, per WMO's 2.50-4.00m)
    // and show the "ORCA stops here" note.
    const ruler = page.getByTestId('douglas-ruler');
    await expect(ruler).toContainText('3.1 m');
    await expect(ruler).toContainText('Douglas 5 Rough');
    await expect(ruler).toContainText('ORCA stops here');
    await expect(page.locator('.ruler-deny-line')).toBeAttached();
    await expect(page.locator('.ruler-marker')).toBeAttached();
  });

  test('shows the amber override banner because overridden is non-empty', async ({ page }) => {
    const banner = page.getByTestId('override-banner');
    await expect(banner).toBeVisible();
    await expect(banner).toContainText('ocean_state_agent');
  });

  test('evidence items are collapsed by default and expand on click', async ({ page }) => {
    const items = page.getByTestId('evidence-item');
    // mock_response.json carries 4 evidence items (wave, sst, chlorophyll,
    // wind) so all 5 agent_findings below can each point at a real one.
    await expect(items).toHaveCount(4);

    const first = items.first();
    // Source/confidence detail should not be visible before expanding.
    await expect(first.getByTestId('evidence-detail')).toBeHidden();

    await first.click();
    await expect(first.getByTestId('evidence-detail')).toBeVisible();
    await expect(first.getByTestId('evidence-detail')).toContainText('Open-Meteo Marine');
    await expect(first.getByTestId('evidence-detail')).toContainText('0.71');
  });

  test('map container is present', async ({ page }) => {
    await expect(page.getByTestId('map')).toBeAttached();
  });

  // The honesty strip: rendered straight from the backend's coverage_note,
  // never composed in the browser. mock_response.json has none (it's an
  // exact zone match), so it must stay hidden here -- the live suite
  // covers the case where one is present.
  test('coverage note stays hidden when the zone was an exact match', async ({ page }) => {
    await expect(page.getByTestId('coverage-note')).toHaveClass(/hidden/);
  });

  test('palette switch changes data-palette and persists across reload', async ({ page }) => {
    await expect(page.locator('html')).toHaveAttribute('data-palette', 'day');
    await expect(page.getByTestId('palette-day')).toHaveClass(/active/);

    await page.getByTestId('palette-night').click();
    await expect(page.locator('html')).toHaveAttribute('data-palette', 'night');
    await expect(page.getByTestId('palette-night')).toHaveClass(/active/);
    await expect(page.getByTestId('palette-day')).not.toHaveClass(/active/);

    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-palette', 'night');
  });

  test('Tamil sample button fills the query input with a known transcription', async ({ page }) => {
    const button = page.getByTestId('tamil-sample-button');
    await expect(button).toBeVisible();
    await button.click();
    const input = page.getByTestId('query-input');
    await expect(input).not.toHaveValue('');
  });
});

// The other half of R-55: the marking must be specific to mock mode. A
// banner that leaked onto the live path would be worse than none at all --
// it would teach the reader to ignore it. Separate describe because the
// suite above forces ?mock=1 in its beforeEach.
test.describe('frontend on the live path', () => {
  test('R-55: no mock banner without ?mock=1', async ({ page }) => {
    await page.goto('/index.html');
    await expect(page.getByTestId('mock-mode-banner')).toBeHidden();
  });
});

// web/three-viz.js -- a per-query 3D reasoning graph and a geospatial
// ocean diorama (real ETOPO 2022 bathymetry + risk columns), both driven
// by the same mock_response.json / mock_bathymetry.json fixtures as the
// rest of this file. See TEAM_STATUS.md for what each draws from.
test.describe('3D visualizations (mock mode)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?mock=1');
  });

  test('3D Ocean toggle swaps views and renders a correctly-sized canvas', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (err) => errors.push(err.message));

    await expect(page.getByTestId('view-toggle-2d')).toHaveClass(/active/);
    await page.getByTestId('view-toggle-3d').click();
    await expect(page.getByTestId('view-toggle-3d')).toHaveClass(/active/);
    await expect(page.getByTestId('ocean3d-container')).toHaveClass(/visible/);
    await expect(page.locator('#map-2d-layer')).toHaveClass(/hidden-view/);

    // mock_bathymetry.json is a local static file -- this should resolve
    // quickly and without the "awaiting" placeholder staying stuck.
    await expect(page.getByTestId('ocean3d-container')).not.toHaveClass(/awaiting/, { timeout: 5000 });

    const canvas = page.locator('#ocean3d-container canvas');
    await expect(canvas).toBeAttached();
    // Regression guard for a real bug caught during development: a stray
    // inline `position: relative` on #ocean3d-container (written by a
    // naive "ensure positioned" helper) silently overrides its CSS
    // `position: absolute; inset: 0`, collapsing the canvas to ~0x0.
    const box = await canvas.boundingBox();
    expect(box.width).toBeGreaterThan(100);
    expect(box.height).toBeGreaterThan(100);

    expect(errors).toEqual([]);
  });

  test('switching back to 2D map restores it and pauses the 3D view', async ({ page }) => {
    await page.getByTestId('view-toggle-3d').click();
    await expect(page.getByTestId('ocean3d-container')).toHaveClass(/visible/);

    await page.getByTestId('view-toggle-2d').click();
    await expect(page.getByTestId('ocean3d-container')).not.toHaveClass(/visible/);
    await expect(page.getByTestId('view-toggle-2d')).toHaveClass(/active/);
    await expect(page.locator('#map-2d-layer')).not.toHaveClass(/hidden-view/);
  });

  test('reasoning graph toggle reveals a canvas built from agent_findings', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (err) => errors.push(err.message));

    await expect(page.getByTestId('reasoning3d-container')).toHaveClass(/collapsed/);
    await page.getByTestId('reasoning3d-toggle').click();
    await expect(page.getByTestId('reasoning3d-container')).not.toHaveClass(/collapsed/);

    const canvas = page.locator('#reasoning3d-container canvas');
    await expect(canvas).toBeAttached();
    const box = await canvas.boundingBox();
    expect(box.width).toBeGreaterThan(50);
    expect(box.height).toBeGreaterThan(50);

    // Toggling it shut again should stop the render loop, not error out.
    await page.getByTestId('reasoning3d-toggle').click();
    await expect(page.getByTestId('reasoning3d-container')).toHaveClass(/collapsed/);

    expect(errors).toEqual([]);
  });

  test('the 3D-to-query bridge (window.__ORCA_SELECT_ZONE__) drives the same inputs the 2D map markers do', async ({ page }) => {
    // Exercises the actual integration boundary the ocean diorama's
    // raycaster click handler calls, without depending on the exact
    // projected screen position of a rotating 3D object (that coupling
    // would make this test flaky for no real gain -- the thing worth
    // proving is that the bridge function does the right thing).
    await page.evaluate(() => window.__ORCA_SELECT_ZONE__('Rameswaram', 9.2811, 79.3151));
    await expect(page.getByTestId('query-input')).toHaveValue('Rameswaram');
    await expect(page.getByTestId('lat-input')).toHaveValue('9.2811');
    await expect(page.getByTestId('lon-input')).toHaveValue('79.3151');
  });
});
