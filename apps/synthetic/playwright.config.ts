import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './src/runner/journeys',
  // A journey is selected via PW_JOURNEY (the CronJob sets it).
  testMatch: process.env.PW_JOURNEY ? `${process.env.PW_JOURNEY}.spec.ts` : '**/*.spec.ts',
  // The container runs as non-root pwuser with a root-owned /app, so write
  // failure artifacts (traces/screenshots) to a writable tmpdir instead.
  outputDir: process.env.PW_OUTPUT_DIR ?? '/tmp/pw-results',
  fullyParallel: false,
  retries: 1,
  timeout: 60_000,
  reporter: [['list']],
  use: {
    headless: true,
    ignoreHTTPSErrors: false,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    actionTimeout: 15_000,
  },
})
