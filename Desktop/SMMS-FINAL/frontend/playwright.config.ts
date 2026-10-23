import { defineConfig, devices } from '@playwright/test';

// Browsers come from the machine image; never run `playwright install` here.
process.env.PLAYWRIGHT_BROWSERS_PATH = process.env.PLAYWRIGHT_BROWSERS_PATH ?? '/opt/pw-browsers';

// Free local port in 55610-55619 (override with E2E_PORT).
const port = Number(process.env.E2E_PORT ?? 55610);

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npx next dev -H 127.0.0.1 -p ${port}`,
    url: `http://127.0.0.1:${port}/login`,
    reuseExistingServer: false,
    timeout: 180_000,
    env: { NEXT_TELEMETRY_DISABLED: '1' },
  },
});
