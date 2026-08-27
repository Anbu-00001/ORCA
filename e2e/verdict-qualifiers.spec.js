// @ts-check
const { test, expect } = require('@playwright/test');

// R-38 (`severity`) and R-40 (`blind_agents`) -- the two fields that
// qualify a verdict rather than being one. Both are strictly additive per
// API_CONTRACT.md, which is the property most of this file is about: a
// response that does not carry them must render exactly like it did before
// they existed, and a response that does must never leak into the next one.
//
// Positive severity coverage runs against the REAL backend on :8011 (it
// supplies the field on every answer). `blind_agents` is `[]` on the real
// cache -- every agent has something to look at -- so the non-empty case is
// a real response with only that one field overridden, rather than a
// hand-written payload of invented marine numbers.

const API = 'http://127.0.0.1:8011';
const QUERY = { query: 'Should I go fishing near Nagapattinam?', lat: 10.7672, lon: 79.8449 };

/** A genuine /ask response, with only the fields under test overridden. */
async function realAnswer(request, overrides) {
  const resp = await request.post(`${API}/ask`, { data: QUERY });
  expect(resp.ok()).toBeTruthy();
  return Object.assign(await resp.json(), overrides);
}

/** Serve `payloads[n]` to the nth /ask the page makes. */
async function serveAnswers(page, payloads) {
  let n = 0;
  await page.route(`${API}/ask`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(payloads[Math.min(n++, payloads.length - 1)]),
    })
  );
  await page.goto(`/index.html?api=${encodeURIComponent(API)}`);
}

async function ask(page) {
  await page.getByTestId('query-input').fill(QUERY.query);
  await page.getByTestId('lat-input').fill(String(QUERY.lat));
  await page.getByTestId('lon-input').fill(String(QUERY.lon));
  await page.getByTestId('ask-button').click();
}

test.describe('R-38 severity', () => {
  test('a real answer renders the severity the backend actually returned', async ({ page, request }) => {
    const resp = await request.post(`${API}/ask`, { data: QUERY });
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    // Whatever today's cache produces, it is one of the four real values.
    expect(['none', 'advisory', 'hard_deny', 'unknown']).toContain(data.severity);

    await page.goto(`/index.html?api=${encodeURIComponent(API)}`);
    await ask(page);
    await expect(page.getByTestId('answer-action')).toHaveText(
      /^(GO|DO NOT GO|SAFER ALTERNATIVE|CANNOT ASSESS)$/,
      { timeout: 15000 }
    );

    const indicator = page.getByTestId('severity-indicator');
    await expect(indicator).toBeVisible();
    // Shown verbatim -- not relabelled or recomputed in the browser.
    await expect(indicator).toContainText(data.severity);
  });

  // The whole reason R-38 exists: a hard deny that got rerouted still
  // reads as action SAFER ALTERNATIVE. The badge must keep saying that,
  // and the severity must be readable beside it without becoming a second
  // verdict of its own.
  test('a rerouted hard deny is distinguishable from a mild advisory override', async ({ page, request }) => {
    await serveAnswers(page, [await realAnswer(request, { action: 'SAFER ALTERNATIVE', severity: 'hard_deny' })]);
    await ask(page);

    await expect(page.getByTestId('answer-action')).toHaveText('SAFER ALTERNATIVE', { timeout: 15000 });
    const indicator = page.getByTestId('severity-indicator');
    await expect(indicator).toBeVisible();
    await expect(indicator).toContainText('hard_deny');
    // A qualifier, not a verdict: it never carries a GO/DO NOT GO string.
    await expect(indicator).not.toContainText(/GO/);
  });

  test('a missing severity renders no severity UI at all', async ({ page, request }) => {
    const payload = await realAnswer(request, {});
    delete payload.severity;
    await serveAnswers(page, [payload]);
    await ask(page);

    await expect(page.getByTestId('answer-action')).not.toBeEmpty({ timeout: 15000 });
    await expect(page.getByTestId('severity-indicator')).toBeHidden();
    // Hidden, and empty -- no placeholder dash, no "unknown" stand-in.
    await expect(page.getByTestId('severity-indicator')).toHaveText('');
  });

  test('a null severity renders no severity UI at all', async ({ page, request }) => {
    await serveAnswers(page, [await realAnswer(request, { severity: null })]);
    await ask(page);

    await expect(page.getByTestId('answer-action')).not.toBeEmpty({ timeout: 15000 });
    await expect(page.getByTestId('severity-indicator')).toBeHidden();
  });
});

test.describe('R-40 blind_agents', () => {
  test('names the agents that had no evidence', async ({ page, request }) => {
    await serveAnswers(page, [
      await realAnswer(request, { blind_agents: ['eo_satellite_agent', 'wind_agent'] }),
    ]);
    await ask(page);

    const blind = page.getByTestId('blind-agents');
    await expect(blind).toBeVisible({ timeout: 15000 });
    await expect(blind).toContainText('eo_satellite_agent');
    await expect(blind).toContainText('wind_agent');
  });

  test('an empty blind_agents array renders no blind-agent UI', async ({ page, request }) => {
    // This is the real backend's own value today, so it is also the case
    // that must stay silent on the demo path.
    await serveAnswers(page, [await realAnswer(request, { blind_agents: [] })]);
    await ask(page);

    await expect(page.getByTestId('answer-action')).not.toBeEmpty({ timeout: 15000 });
    await expect(page.getByTestId('blind-agents')).toBeHidden();
    await expect(page.getByTestId('blind-agents')).toHaveText('');
  });

  test('a missing blind_agents field renders no blind-agent UI', async ({ page, request }) => {
    const payload = await realAnswer(request, {});
    delete payload.blind_agents;
    await serveAnswers(page, [payload]);
    await ask(page);

    await expect(page.getByTestId('answer-action')).not.toBeEmpty({ timeout: 15000 });
    await expect(page.getByTestId('blind-agents')).toBeHidden();
  });
});

test.describe('neither field leaks between answers', () => {
  test('a second answer without severity/blind_agents drops the first answer\'s', async ({ page, request }) => {
    const withFields = await realAnswer(request, {
      severity: 'hard_deny',
      blind_agents: ['eo_satellite_agent'],
    });
    const withoutFields = await realAnswer(request, {});
    delete withoutFields.severity;
    delete withoutFields.blind_agents;
    await serveAnswers(page, [withFields, withoutFields]);

    await ask(page);
    await expect(page.getByTestId('severity-indicator')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('blind-agents')).toBeVisible();

    await ask(page);
    // Same answer text either side, so wait on the qualifiers themselves.
    await expect(page.getByTestId('severity-indicator')).toBeHidden({ timeout: 15000 });
    await expect(page.getByTestId('blind-agents')).toBeHidden();
    await expect(page.getByTestId('severity-indicator')).toHaveText('');
    await expect(page.getByTestId('blind-agents')).toHaveText('');
  });

  test('an off_topic answer shows neither, even after a verdict that had both', async ({ page, request }) => {
    const verdict = await realAnswer(request, {
      severity: 'advisory',
      blind_agents: ['wind_agent'],
    });
    const offTopic = await realAnswer(request, { answer_kind: 'off_topic' });
    delete offTopic.severity;
    delete offTopic.blind_agents;
    await serveAnswers(page, [verdict, offTopic]);

    await ask(page);
    await expect(page.getByTestId('severity-indicator')).toBeVisible({ timeout: 15000 });

    await ask(page);
    // The off-topic path blanks the verdict badge; the qualifiers of that
    // verdict must go with it.
    await expect(page.getByTestId('answer-action')).toBeEmpty({ timeout: 15000 });
    await expect(page.getByTestId('severity-indicator')).toBeHidden();
    await expect(page.getByTestId('blind-agents')).toBeHidden();
  });

  test('a failed request clears the previous answer\'s severity and blind agents', async ({ page, request }) => {
    let n = 0;
    const first = await realAnswer(request, {
      severity: 'hard_deny',
      blind_agents: ['eo_satellite_agent'],
    });
    await page.route(`${API}/ask`, (route) =>
      n++ === 0
        ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(first) })
        : route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ detail: 'no observations' }) })
    );
    await page.goto(`/index.html?api=${encodeURIComponent(API)}`);

    await ask(page);
    await expect(page.getByTestId('severity-indicator')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('blind-agents')).toBeVisible();

    await ask(page);
    await expect(page.getByTestId('answer-action')).toHaveText('ERROR', { timeout: 15000 });
    await expect(page.getByTestId('severity-indicator')).toBeHidden();
    await expect(page.getByTestId('blind-agents')).toBeHidden();
  });
});

// The canonical fixture predates both fields and deliberately still lacks
// them, so ?mock=1 is a free check that the absent case renders nothing on
// a page that renders everything else.
test.describe('the mock fixture, which carries neither field', () => {
  test('renders no severity and no blind-agent UI', async ({ page }) => {
    await page.goto('/index.html?mock=1');
    await expect(page.getByTestId('answer-action')).toHaveText('SAFER ALTERNATIVE');
    await expect(page.getByTestId('severity-indicator')).toBeHidden();
    await expect(page.getByTestId('blind-agents')).toBeHidden();
  });
});
