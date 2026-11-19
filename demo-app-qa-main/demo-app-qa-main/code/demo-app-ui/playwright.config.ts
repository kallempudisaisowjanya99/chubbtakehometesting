import { defineConfig, devices } from '@playwright/test';

// Locally, use the installed Google Chrome so no browser download is needed.
// CI uses Playwright's bundled Chromium. Override with PW_CHANNEL (e.g. "msedge"; empty = bundled).
const chromeChannel =
  process.env.PW_CHANNEL !== undefined
    ? process.env.PW_CHANNEL || undefined
    : process.env.CI
      ? undefined
      : 'chrome';

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  timeout: 30_000,
  // The Docker UI runs `next dev`, which compiles each page on first visit; allow for that.
  expect: { timeout: 10_000 },
  reporter: [
    ['html', { outputFolder: 'playwright-report' }],
    ['junit', { outputFile: 'playwright-report/results.xml' }],
  ],
  use: {
    baseURL: 'http://localhost:3001',
    trace: 'on-first-retry',
  },
  globalSetup: './tests/e2e/global-setup.ts',
  projects: [
    // Logs in once per role and saves the sessions the other projects reuse.
    { name: 'setup', testMatch: /auth\.setup\.ts/, use: { ...devices['Desktop Chrome'], channel: chromeChannel } },
    {
      name: 'chromium',
      dependencies: ['setup'],
      use: { ...devices['Desktop Chrome'], channel: chromeChannel },
    },
    ...(process.env.CI
      ? [
          {
            name: 'firefox',
            dependencies: ['setup'],
            use: { ...devices['Desktop Firefox'] },
          },
        ]
      : []),
  ],
  webServer: {
    command: process.env.CI
      ? 'npm run build && npm run start -- --port 3001'
      : 'npm run dev -- --port 3001',
    url: 'http://localhost:3001',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
