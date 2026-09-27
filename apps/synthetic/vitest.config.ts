import { defineConfig } from 'vitest/config'

// Unit tests are *.test.ts. Playwright journeys (*.spec.ts under src/runner/journeys) run in the
// browser runner, not here.
export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
  },
})
