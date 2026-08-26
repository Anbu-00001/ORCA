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

  test('renders the answer card from the mock response', async ({ page }) => {
    await expect(page.getByTestId('answer-action')).toHaveText('SAFER ALTERNATIVE');
    await expect(page.getByTestId('answer-text')).toContainText('Zone B');
  });

  test('shows the amber override banner because overridden is non-empty', async ({ page }) => {
    const banner = page.getByTestId('override-banner');
    await expect(banner).toBeVisible();
    await expect(banner).toContainText('ocean_state_agent');
  });

  test('evidence items are collapsed by default and expand on click', async ({ page }) => {
    const items = page.getByTestId('evidence-item');
    await expect(items).toHaveCount(2);

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

  test('Tamil sample button fills the query input with a known transcription', async ({ page }) => {
    const button = page.getByTestId('tamil-sample-button');
    await expect(button).toBeVisible();
    await button.click();
    const input = page.getByTestId('query-input');
    await expect(input).not.toHaveValue('');
  });
});
