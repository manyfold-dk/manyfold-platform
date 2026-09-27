import { expect, test } from '@playwright/test'

// The public site: landing, the platform (stack) page, the small-companies page,
// privacy, and the navigation between them. Status endpoints are mocked so the
// tests do not depend on a running backend.

const summary = {
  overall: 'healthy',
  infrastructure: 'healthy',
  network: 'healthy',
  cluster: 'healthy',
  applications: 'healthy',
  platform: 'healthy',
  edge: 'healthy',
  identity: 'healthy',
  delivery: 'healthy',
  observability: 'healthy',
  pipelines: 'healthy',
  activeAlerts: 0,
  timestamp: '2026-09-20T12:00:00Z'
}

// 28 buckets over a fortnight: mostly up, one degraded period, one outage, and
// one period with nothing recorded.
const buckets = Array.from({ length: 28 }, (_, i) => {
  if (i === 3) return null
  if (i === 9) return 0.97
  if (i === 10) return 0.62
  return 1
})

const latencyPoints = Array.from({ length: 48 }, (_, i) => (i === 20 ? null : 200 + (i % 7) * 12))

const historyPayload = {
  checks: [
    {
      name: 'From the internet',
      uptime: { days: 14, bucketHours: 12, buckets, ratio: 0.9848 },
      latency: { hours: 24, stepMinutes: 30, points: latencyPoints, latestMs: 220 }
    },
    {
      name: 'From inside the cluster',
      uptime: { days: 14, bucketHours: 12, buckets: buckets.map(() => 1), ratio: 1 },
      latency: { hours: 24, stepMinutes: 30, points: latencyPoints, latestMs: 695 }
    }
  ],
  timestamp: '2026-09-21T12:00:00Z'
}

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/status/history', (route) => route.fulfill({ json: historyPayload }))
  await page.route('**/api/v1/status', (route) =>
    route.fulfill({
      json: { status: 'operational', services: [], timestamp: '2026-09-20T12:00:00Z' }
    })
  )
  await page.route('**/api/v1/health/summary', (route) => route.fulfill({ json: summary }))
})

test('landing page presents the practice, the person and live status', async ({ page }) => {
  await page.goto('/')
  await expect(
    page.getByRole('heading', { level: 1, name: /Kubernetes platforms, built and run in Europe/ })
  ).toBeVisible()
  await expect(page.getByText('Platform engineer, Copenhagen')).toBeVisible()
  await expect(page.getByRole('status')).toHaveText('All systems operational')
  await expect(page.getByRole('heading', { name: 'Three ways to work with me' })).toBeVisible()
  await expect(page.getByText('CVR DK34287961')).toBeVisible()
  await expect(page).toHaveTitle(/Manyfold/)
})

test('status card degrades honestly when the API is down', async ({ page }) => {
  await page.unroute('**/api/v1/status')
  await page.route('**/api/v1/status', (route) => route.fulfill({ status: 503 }))
  await page.goto('/')
  await expect(page.getByRole('status')).toHaveText('Status unavailable right now')
})

test('main navigation reaches the public pages', async ({ page }) => {
  await page.goto('/')
  const nav = page.getByRole('navigation', { name: 'Main' })

  await nav.getByRole('link', { name: 'Platform', exact: true }).click()
  await expect(page).toHaveURL(/\/stack$/)
  await expect(
    page.getByRole('heading', { level: 1, name: /production platform on European infrastructure/ })
  ).toBeVisible()
  await expect(page.getByText('The stack · the four layers in the logo')).toBeVisible()

  await nav.getByRole('link', { name: 'For small companies' }).click()
  await expect(page).toHaveURL(/\/small-companies$/)
  await expect(
    page.getByRole('heading', { level: 1, name: 'Your systems, looked after.' })
  ).toBeVisible()
  await expect(page.getByText('Agents on call, around the clock')).toBeVisible()

  await nav.getByRole('link', { name: 'About' }).click()
  await expect(page).toHaveURL(/\/#about$/)
  await expect(page.getByRole('heading', { name: 'Thomas Berg von Linde' })).toBeVisible()
})

test('the header offers a sign-in that leaves the SPA for the protected operator area', async ({
  page
}) => {
  await page.goto('/')
  const signIn = page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Sign in' })
  await expect(signIn).toBeVisible()
  await expect(signIn).toHaveAttribute('href', '/platform')
})

test('/about redirects to the about section and privacy is public', async ({ page }) => {
  await page.goto('/about')
  await expect(page).toHaveURL(/\/#about$/)

  await page.goto('/privacy')
  await expect(page.getByRole('heading', { level: 1, name: 'Privacy' })).toBeVisible()
  await expect(page.getByText(/no analytics trackers/i)).toBeVisible()
})

test('phone layout has a working menu and no sideways scroll', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await page.getByRole('button', { name: 'Open menu' }).click()
  await expect(page.locator('#site-menu').getByRole('link', { name: 'Sign in' })).toHaveAttribute(
    'href',
    '/platform'
  )
  await page.locator('#site-menu').getByRole('link', { name: 'For small companies' }).click()
  await expect(page).toHaveURL(/\/small-companies$/)
  const overflows = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth
  )
  expect(overflows).toBe(false)
})

test('decisions are listed, open as full records, and link to each other', async ({ page }) => {
  await page.goto('/decisions')
  await expect(
    page.getByRole('heading', { level: 1, name: 'Architecture decision records' })
  ).toBeVisible()
  await expect(page).toHaveTitle('Decisions · Manyfold')

  await page.getByRole('link', { name: /Pod rebalancing strategy/ }).click()
  await expect(page).toHaveURL(/\/decisions\/0031-pod-rebalancing-strategy$/)
  await expect(
    page.getByRole('heading', { level: 1, name: 'Pod rebalancing strategy' })
  ).toBeVisible()
  await expect(page.getByRole('heading', { level: 2, name: 'Decision' })).toBeVisible()
  await expect(page.getByText('Published version.')).toBeVisible()
  await expect(page).toHaveTitle('ADR 0031: Pod rebalancing strategy · Manyfold')

  await page.getByRole('link', { name: 'ADR 0014: Cloud provider selection' }).click()
  await expect(page).toHaveURL(/\/decisions\/0014-cloud-provider-selection$/)
  await expect(page.getByText('Note, September 2026.')).toBeVisible()
})

test('an unpublished decision says so instead of rendering a blank page', async ({ page }) => {
  await page.goto('/decisions/0099-not-a-record')
  await expect(
    page.getByRole('heading', { level: 1, name: 'No such decision record' })
  ).toBeVisible()
})

test('the stack page links to the decisions', async ({ page }) => {
  await page.goto('/stack')
  await page.getByRole('link', { name: 'Read a selection' }).click()
  await expect(page).toHaveURL(/\/decisions$/)
})

test('the stack page draws delivery, operations and tenancy', async ({ page }) => {
  await page.goto('/stack')
  await expect(
    page.getByRole('heading', { level: 2, name: 'Three mechanisms, drawn out' })
  ).toBeVisible()
  for (const name of [/^Delivery:/, /^Operations:/, /^Tenancy:/]) {
    const img = page.getByRole('img', { name })
    await img.scrollIntoViewIfNeeded()
    await expect(img).toBeVisible()
    // A broken asset path renders an empty box that is still "visible"; the width says it loaded.
    // The images are lazy, so poll until the response has arrived instead of reading it once.
    await expect
      .poll(() => img.evaluate((el: HTMLImageElement) => (el.complete ? el.naturalWidth : 0)))
      .toBeGreaterThan(0)
  }
  await page.getByRole('link', { name: 'Read the decision' }).click()
  await expect(page).toHaveURL(/\/decisions\/0004-/)
})

// The shared mock returns no services, which correctly hides the checks section.
// These are the four checks the backend actually returns.
const checks = {
  status: 'operational',
  services: [
    { name: 'Backend API', status: 'operational', latencyMs: 4, detail: 'live check, just now' },
    {
      name: 'From the internet',
      status: 'operational',
      latencyMs: 613,
      detail: 'Cloudflare network, 1 minute ago'
    },
    {
      name: 'From inside the cluster',
      status: 'operational',
      latencyMs: 935,
      detail: 'browser journey, 3 minutes ago'
    },
    {
      name: 'TLS certificate',
      status: 'operational',
      latencyMs: null,
      detail: 'valid for another 58 days'
    }
  ],
  timestamp: '2026-09-20T12:00:00Z'
}

test('/status shows the stack picture with a signal on every row', async ({ page }) => {
  await page.unroute('**/api/v1/status')
  await page.route('**/api/v1/status', (route) => route.fulfill({ json: checks }))
  await page.goto('/status')

  // Same layering and labels as /stack.
  await expect(
    page.getByRole('heading', { level: 1, name: /platform, layer by layer/i })
  ).toBeVisible()
  await expect(page.getByText('The stack · the four layers in the logo')).toBeVisible()
  for (const layer of ['Tenants', 'Network', 'Cluster', 'Infrastructure']) {
    await expect(page.getByText(layer, { exact: true })).toBeVisible()
  }

  // Every row of the picture carries its own chip: four services, four layers.
  // Scoped to the picture, because the footnote below it and the header pill
  // spell out the same words.
  const picture = page.getByRole('region', { name: 'Live status by layer' })
  await expect(page.getByRole('status')).toHaveText('All systems operational')
  await expect(picture.getByText('healthy', { exact: true })).toHaveCount(8)
  await expect(picture.getByText('not measured', { exact: true })).toHaveCount(0)
})

test('/status reports what a check could not measure instead of guessing', async ({ page }) => {
  // A paused probe reports unknown. It must read as a gap in the monitoring, not
  // as an outage -- the Pushgateway would otherwise serve its last value forever.
  await page.unroute('**/api/v1/health/summary')
  await page.route('**/api/v1/health/summary', (route) =>
    route.fulfill({ json: { ...summary, edge: 'unknown' } })
  )
  await page.goto('/status')

  const picture = page.getByRole('region', { name: 'Live status by layer' })
  await expect(page.getByRole('status')).toHaveText('All systems operational')
  await expect(picture.getByText('not measured', { exact: true })).toHaveCount(1)
  await expect(picture.getByText('healthy', { exact: true })).toHaveCount(7)
})

test('/status reports both vantages and the certificate', async ({ page }) => {
  await page.unroute('**/api/v1/status')
  await page.route('**/api/v1/status', (route) => route.fulfill({ json: checks }))
  await page.goto('/status')

  await expect(page.getByRole('heading', { name: 'What answered, and how fast' })).toBeVisible()
  // The name also titles the chart card below, so scope to the check card.
  const card = page.locator('article', { hasText: 'Cloudflare network, 1 minute ago' })
  await expect(card.getByText('From the internet', { exact: true })).toBeVisible()
  await expect(page.locator('article', { hasText: 'browser journey' })).toBeVisible()
  await expect(page.getByText('613 ms', { exact: true })).toBeVisible()
  // Each reading says where it came from and how old it is.
  await expect(page.getByText('Cloudflare network, 1 minute ago')).toBeVisible()
  await expect(page.getByText('valid for another 58 days')).toBeVisible()
})

test('/status counts only the alerts a human would act on', async ({ page }) => {
  // Zero is not news, so the line is absent; the heartbeat and the inhibited
  // info alerts never reach this count.
  await page.goto('/status')
  await expect(page.getByText(/needs? attention/)).toHaveCount(0)

  await page.unroute('**/api/v1/health/summary')
  await page.route('**/api/v1/health/summary', (route) =>
    route.fulfill({ json: { ...summary, activeAlerts: 2 } })
  )
  await page.reload()
  await expect(page.getByText('2 alerts need attention')).toBeVisible()
})

test('/status degrades honestly when the health API is down', async ({ page }) => {
  await page.unroute('**/api/v1/health/summary')
  await page.route('**/api/v1/health/summary', (route) => route.fulfill({ status: 503 }))
  await page.goto('/status')

  await expect(page.getByRole('status')).toHaveText('Status unavailable right now')
  await expect(page.getByText(/health API did not answer/i)).toBeVisible()
  // No row may claim a status once the signal is gone.
  const picture = page.getByRole('region', { name: 'Live status by layer' })
  await expect(picture.getByText('healthy')).toHaveCount(0)
  await expect(picture.getByText('not measured', { exact: true })).toHaveCount(8)
})

test('/status reports a degraded layer without relying on colour alone', async ({ page }) => {
  await page.unroute('**/api/v1/health/summary')
  await page.route('**/api/v1/health/summary', (route) =>
    route.fulfill({ json: { ...summary, overall: 'degraded', cluster: 'degraded' } })
  )
  await page.goto('/status')

  await expect(page.getByRole('status')).toHaveText('Degraded performance')
  await expect(page.getByText('degraded').first()).toBeVisible()
})

test('phone layout of /status has no sideways scroll', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 844 })
  await page.goto('/status')
  await expect(
    page.getByRole('heading', { level: 1, name: /platform, layer by layer/i })
  ).toBeVisible()
  const overflows = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth
  )
  expect(overflows).toBe(false)
})

// /status.html is the standalone phone widget. It reads the full
// /api/v1/health response and renders its own layer list, so it can drift
// away from /stack and /status without anything noticing.
const layeredHealth = {
  overallStatus: 'healthy',
  infrastructure: { status: 'healthy', totalNodes: 6, healthyNodes: 6 },
  network: {
    status: 'healthy',
    cilium: { name: 'Cilium', status: 'healthy', details: '6/6 pods running' },
    hubble: { name: 'Hubble', status: 'healthy', details: '1/1 pods running' }
  },
  cluster: {
    status: 'healthy',
    controlPlaneHealthy: true,
    totalPods: 174,
    runningPods: 138,
    pendingPods: 0,
    failedPods: 0
  },
  platform: {
    status: 'healthy',
    edge: { name: 'Edge', status: 'healthy', details: 'answered in 613 ms from outside' },
    identity: { name: 'Identity and secrets', status: 'healthy', details: 'Keycloak healthy' },
    argocd: { name: 'ArgoCD', status: 'healthy', details: '66 apps synced' },
    registry: { name: 'Registry', status: 'healthy', details: '5/5 pods running' },
    observability: { name: 'Observability', status: 'healthy', details: '22/22 pods running' }
  },
  pipelines: {
    status: 'healthy',
    totalRuns24h: 0,
    successfulRuns24h: 0,
    failedRuns24h: 0,
    successRate: 0,
    lastRunStatus: 'none',
    lastRunTime: ''
  },
  applications: {
    status: 'healthy',
    apps: [
      {
        name: 'Website Backend',
        status: 'healthy',
        namespace: 'website',
        latencyMs: 35,
        message: ''
      }
    ]
  },
  activeAlerts: [],
  firingAlerts: 0,
  timestamp: '2026-09-21T15:30:00Z'
}

test('the phone widget lists the layers in the same order as /stack', async ({ page }) => {
  await page.route('**/api/v1/health', (route) => route.fulfill({ json: layeredHealth }))
  await page.goto('/status.html')

  const names = page.locator('#servicesGrid .service-name')
  await expect(names.first()).toBeVisible()
  // The info tag lives inside .service-name, so take the leading text node.
  const ordered = await names.evaluateAll((els) =>
    els.map((el) => el.childNodes[0]?.textContent?.trim() ?? '')
  )
  // Services that run on the stack first, then the four stack layers top down,
  // exactly as /stack and /status show them.
  expect(ordered).toEqual([
    'Platform',
    'Pipelines',
    'Tenants',
    'Network',
    'Cluster',
    'Infrastructure'
  ])
})

test('the phone widget reads an unmeasured layer as not measured, not an outage', async ({
  page
}) => {
  // Prometheus did not answer: the backend reports the pipelines as unknown.
  // That is a gap in the monitoring and must not read as an outage.
  await page.route('**/api/v1/health', (route) =>
    route.fulfill({
      json: {
        ...layeredHealth,
        pipelines: { ...layeredHealth.pipelines, status: 'unknown', lastRunStatus: 'unknown' }
      }
    })
  )
  await page.goto('/status.html')

  const pipelines = page.locator('#servicesGrid .service-row', { hasText: 'Pipelines' })
  await expect(pipelines.locator('.service-status')).toHaveText('not measured')
  await expect(pipelines.locator('.service-status')).not.toHaveClass(/outage/)
  await expect(pipelines.getByText('Pipeline metrics unavailable')).toBeVisible()
  await expect(page.locator('#servicesGrid .service-status.outage')).toHaveCount(0)
})

test('/status charts the history of both probes', async ({ page }) => {
  await page.goto('/status')

  const internet = page.locator('article', { hasText: 'Answered · 14 days' }).first()
  await expect(internet.getByText('98.4% up')).toBeVisible()
  await expect(internet.getByText('14 days ago')).toBeVisible()
  await expect(page.getByText('100% up')).toBeVisible()

  // One bar per bucket, and the strip carries a text alternative so the shape
  // is not the only way to read it.
  const strip = page.getByRole('img', { name: /Availability over the last 14 days/ }).first()
  await expect(strip.locator('span')).toHaveCount(28)

  await expect(
    page.getByRole('img', { name: /Response time over the last 24 hours/ }).first()
  ).toBeVisible()
})

test('/status draws a period with no readings as a gap, not an outage', async ({ page }) => {
  await page.goto('/status')

  const strip = page.getByRole('img', { name: /Availability over the last 14 days/ }).first()
  // evaluateAll does not retry, so wait for the fetched series to be drawn first.
  await expect(strip.locator('span')).toHaveCount(28)
  const tones = await strip.locator('span').evaluateAll((els) => els.map((el) => el.className))

  // Bucket 3 was never measured: grey. Bucket 10 was a real outage: red. A gap
  // in the monitoring must never be painted as downtime.
  expect(tones[3]).toContain('bg-slate-200')
  expect(tones[10]).toContain('bg-down')
  expect(tones[9]).toContain('bg-warn')
  expect(tones[0]).toContain('bg-ok')

  // And the summary counts only the periods that were measured.
  await expect(strip).toHaveAttribute('aria-label', /from 27 of 28 periods measured/)
})

test('/status still works when the history API is unavailable', async ({ page }) => {
  // The charts are an extra. Losing them must not take the page with them or
  // suggest the platform is unwell.
  await page.unroute('**/api/v1/status/history')
  await page.route('**/api/v1/status/history', (route) => route.fulfill({ status: 503 }))
  await page.unroute('**/api/v1/status')
  await page.route('**/api/v1/status', (route) => route.fulfill({ json: checks }))
  await page.goto('/status')

  await expect(page.getByRole('status')).toHaveText('All systems operational')
  await expect(page.getByRole('heading', { name: 'What answered, and how fast' })).toBeVisible()
  await expect(page.getByRole('img', { name: /Availability over the last/ })).toHaveCount(0)
})
