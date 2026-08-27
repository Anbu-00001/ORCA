// @ts-check
const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 0,
  reporter: [['list']],
  use: {
    headless: true,
    baseURL: 'http://127.0.0.1:8080',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: [
    {
      command: 'python3 -m http.server 8080 --directory web',
      url: 'http://127.0.0.1:8080/index.html',
      reuseExistingServer: true,
      timeout: 15000,
    },
    {
      // GROQ_API_KEY is deliberately unset: orca/api.py now loads .env, so
      // without this the whole e2e suite makes real, billed Groq calls and
      // 429s on the free tier mid-run. These specs assert the deterministic
      // path; e2e/agentic-exceptions.spec.js spawns its own backend when it
      // needs a key.
      command: 'bash -c "source .venv/bin/activate && GROQ_API_KEY= uvicorn orca.api:app --host 127.0.0.1 --port 8011"',
      url: 'http://127.0.0.1:8011/health',
      reuseExistingServer: true,
      timeout: 15000,
    },
  ],
});
