import { devices, defineConfig } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT_DIR = path.dirname(fileURLToPath(import.meta.url))
const FRONTEND_DIR = path.resolve(ROOT_DIR)
const BACKEND_DIR = path.resolve(ROOT_DIR, '../backend')

const BACKEND_PORT = process.env.PW_BACKEND_PORT ?? '18080'
const FRONTEND_PORT = process.env.PW_FRONTEND_PORT ?? '4173'
const SKIP_BACKEND = process.env.PW_SKIP_BACKEND?.toLowerCase() === 'true'

const webServers = [
  {
    command: `VITE_FRONTEND_ACCESS_PASSWORD=changeme pnpm exec vite --host 0.0.0.0 --port ${FRONTEND_PORT}`,
    url: `http://localhost:${FRONTEND_PORT}`,
    cwd: FRONTEND_DIR,
    reuseExistingServer: !process.env.CI,
    timeout: 300000,
    stdout: 'pipe'
  } as const
]

if (!SKIP_BACKEND) {
  webServers.unshift({
    command: `mvn quarkus:dev -Dquarkus.http.port=${BACKEND_PORT} -Dquarkus.http.host=0.0.0.0 -Dquarkus.kubernetes-client.devservices.enabled=false -Dquarkus.redis.devservices.enabled=false`,
    // Not /q/health/ready: application.properties sets
    // quarkus.smallrye-health.root-path=/health, so the /q/ path 404s and
    // Playwright never treats the backend as up. It only ever passed by reusing
    // a dev server that an earlier run had left behind.
    url: `http://localhost:${BACKEND_PORT}/health/ready`,
    cwd: BACKEND_DIR,
    reuseExistingServer: !process.env.CI,
    timeout: 300000,
    stdout: 'pipe'
  })
}

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 1,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? 'json' : 'html',
  timeout: 30000,

  use: {
    baseURL: `http://localhost:${FRONTEND_PORT}`,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure'
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ],

  webServer: webServers
})
