import { defineConfig } from '@playwright/test';

// The smoke test starts the real forge server itself (see e2e/smoke.spec.ts) and drives it in headless Chromium.
export default defineConfig({
  testDir: 'e2e',
  timeout: 180_000,
  workers: 1,
  reporter: 'list',
  // FORGE_E2E_BROWSER: optional path to a Chromium or headless-shell binary (when the one Playwright expects is not installed).
  use: { headless: true, screenshot: 'only-on-failure', launchOptions: { executablePath: process.env['FORGE_E2E_BROWSER'] || undefined } },
});
