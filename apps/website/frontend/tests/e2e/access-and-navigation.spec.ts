import { expect, type Page, test } from '@playwright/test'

interface PodRestartRequest {
  namespace?: string
  podName?: string
}

interface DeploymentRestartRequest {
  namespace?: string
  deploymentName?: string
}

const healthSummaryResponse = {
  overall: 'healthy',
  infrastructure: 'healthy',
  cluster: 'healthy',
  platform: 'healthy',
  pipelines: 'healthy',
  applications: 'healthy',
  activeAlerts: 1,
  timestamp: '2026-02-27T15:00:00Z'
}

const statusResponse = {
  status: 'operational',
  services: [
    { name: 'website-backend', status: 'operational', latencyMs: 42 },
    { name: 'redis', status: 'degraded', latencyMs: 9 }
  ],
  timestamp: '2026-02-27T15:00:00Z'
}

const layeredHealth = {
  overallStatus: 'degraded',
  infrastructure: {
    status: 'healthy',
    totalNodes: 3,
    healthyNodes: 3,
    totalMemoryBytes: 64_000_000_000,
    usedMemoryBytes: 28_000_000_000,
    totalStorageBytes: 1_000_000_000_000,
    usedStorageBytes: 200_000_000_000
  },
  cluster: {
    status: 'degraded',
    controlPlaneHealthy: true,
    networkingHealthy: true,
    dnsHealthy: true,
    totalPods: 10,
    runningPods: 9,
    pendingPods: 1,
    failedPods: 0
  },
  network: {
    status: 'healthy',
    cilium: { name: 'Cilium', status: 'healthy', details: '6/6 pods running' },
    hubble: { name: 'Hubble', status: 'healthy', details: '1/1 pods running' }
  },
  // Mirrors the API exactly: there is no `tekton` component, and inventing one
  // here is what hid a crash in the summary panel against the real backend.
  platform: {
    status: 'healthy',
    edge: { name: 'Edge', status: 'healthy', details: 'answered in 613 ms from outside' },
    identity: { name: 'Identity and secrets', status: 'healthy', details: 'Keycloak healthy' },
    argocd: { name: 'ArgoCD', status: 'healthy', details: 'up' },
    registry: { name: 'Registry', status: 'healthy', details: 'up' },
    observability: { name: 'Observability', status: 'healthy', details: 'up' }
  },
  pipelines: {
    status: 'healthy',
    totalRuns24h: 120,
    successfulRuns24h: 110,
    failedRuns24h: 10,
    successRate: 91,
    lastRunStatus: 'success',
    lastRunTime: '2026-02-27T14:59:00Z'
  },
  applications: {
    status: 'healthy',
    apps: [
      {
        name: 'website',
        status: 'healthy',
        namespace: 'website',
        latencyMs: 12,
        message: 'Service check OK'
      },
      {
        name: 'shop',
        status: 'degraded',
        namespace: 'shop',
        latencyMs: 38,
        message: 'P95 latency increased'
      }
    ]
  },
  activeAlerts: [
    {
      name: 'Disk pressure',
      severity: 'warning',
      message: 'Filesystem utilization elevated on node-1',
      namespace: 'kube-system',
      since: '2026-02-27T14:55:00Z'
    }
  ],
  firingAlerts: 1
}

// /api/v1/alerts returns the backend's StoredAlert, not the health response's alert summary.
const operatorAlerts = [
  {
    fingerprint: 'a1',
    name: 'Disk pressure',
    severity: 'warning',
    namespace: 'kube-system',
    summary: 'Filesystem utilization elevated on node-1',
    description: null,
    startsAt: '2026-02-27T14:55:00Z',
    labels: {}
  },
  {
    fingerprint: 'a2',
    name: 'KubePodCrashLooping',
    severity: 'critical',
    namespace: 'shop',
    summary: 'Pod shop-api is crash looping',
    description: null,
    startsAt: '2026-02-27T15:05:00Z',
    labels: { pod: 'shop-api-7f8c-x2x9q', deployment: 'shop-api' }
  }
]

const deepHealth = {
  status: 'healthy',
  service: 'website-backend',
  version: 'test',
  timestamp: '2026-02-27T15:00:00Z',
  self: {
    status: 'healthy',
    uptimeSeconds: 180,
    resources: {
      cpuPercent: 12,
      memoryUsedBytes: 120_000_000,
      memoryMaxBytes: 500_000_000,
      activeThreads: 24
    },
    metrics: {
      jvmUptime: 1200,
      heapCommitted: 450_000_000,
      threadCount: 24
    }
  },
  dependencies: [
    {
      name: 'shop',
      type: 'http',
      status: 'healthy',
      latencyMs: 9,
      message: 'dependency healthy'
    }
  ],
  metadata: { region: 'local', environment: 'test' }
}

const alertSummary = {
  total: 1,
  critical: 0,
  warning: 1,
  info: 0
}

const recentVitalsResponse = [
  {
    name: 'LCP',
    value: 1120,
    rating: 'good',
    delta: 20,
    id: 'entry-3',
    navigationType: 'navigate',
    route: '/demo',
    timestamp: Date.now() - 1000
  },
  {
    name: 'FCP',
    value: 980,
    rating: 'good',
    delta: 15,
    id: 'entry-4',
    navigationType: 'navigate',
    route: '/demo',
    timestamp: Date.now() - 3000
  }
]

const remediationSummary = {
  recentActions: 3,
  status: 'normal'
}

async function stubApiRoutes(page: Page) {
  await page.route('**/api/v1/health/summary', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(healthSummaryResponse)
    })
  })
  await page.route('**/api/v1/status', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(statusResponse)
    })
  })
  await page.route('**/api/v1/health', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(layeredHealth)
    })
  })
  await page.route('**/api/v1/health/deep', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(deepHealth)
    })
  })
  await page.route('**/api/v1/alerts/summary', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(alertSummary)
    })
  })
  await page.route('**/api/v1/alerts', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(operatorAlerts)
    })
  })
  await page.route('**/api/v1/remediation/summary', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(remediationSummary)
    })
  })
  await page.route('**/api/v1/metrics/vitals/recent', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([])
    })
  })
}

async function stubRemediationEndpoints(page: Page) {
  let podCalls = 0
  let deploymentCalls = 0

  await page.route('**/api/v1/remediation/pod/restart', async (route) => {
    const request = route.request()
    const payload = request.postDataJSON?.() as PodRestartRequest | null
    podCalls += 1
    if (!payload || !payload.namespace || !payload.podName) {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ success: false, action: 'pod', message: 'Missing parameters' })
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        action: 'pod',
        resource: payload.podName,
        message: `Pod ${payload.podName} restart initiated`
      })
    })
  })

  await page.route('**/api/v1/remediation/deployment/restart', async (route) => {
    const request = route.request()
    const payload = request.postDataJSON?.() as DeploymentRestartRequest | null
    deploymentCalls += 1
    if (!payload || !payload.namespace || !payload.deploymentName) {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          action: 'deployment',
          message: 'Missing parameters'
        })
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        action: 'deployment',
        resource: payload.deploymentName,
        message: `Deployment ${payload.deploymentName} restart initiated`
      })
    })
  })

  return {
    getPodCalls: () => podCalls,
    getDeploymentCalls: () => deploymentCalls
  }
}

// These specs exercise what the operator views render, not who may reach them.
// Access control is not in the SPA: the passphrase screen this suite used to
// unlock was replaced by oauth2-proxy in front of the cluster, so a browser
// pointed at the dev server reaches the views directly and there is nothing to
// log in to. The routes' requiresServerAuth guard only forces a full-page
// navigation, which page.goto() already is.
//
// The auth itself is verified against the deployed site, not here: every
// /platform* and /demo path must answer 302 to the identity provider.
test.describe('Platform features from top to bottom', () => {
  test.beforeEach(async ({ page }) => {
    await stubApiRoutes(page)
  })

  test('feature 4: /status is public and shows stale data indicator', async ({ page }) => {
    await page.route('**/api/v1/status', async (route) => {
      const staleStatus = {
        ...statusResponse,
        timestamp: '2000-01-01T00:00:00Z'
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(staleStatus)
      })
    })
    await page.route('**/api/v1/health/summary', async (route) => {
      const staleSummary = {
        ...healthSummaryResponse,
        timestamp: '2000-01-01T00:00:00Z'
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(staleSummary)
      })
    })

    await page.goto('/status')
    await expect(
      page.getByRole('heading', { level: 1, name: /platform, layer by layer/i })
    ).toBeVisible()
    await expect(page.getByText('Stale data')).toBeVisible()
    await expect(page.getByText('website-backend')).toBeVisible()
  })

  test('feature 1: platform control center supports remediation actions', async ({ page }) => {
    const { getPodCalls, getDeploymentCalls } = await stubRemediationEndpoints(page)

    await page.goto('/platform')
    await expect(page.getByRole('heading', { name: /Platform Control Center/i })).toBeVisible()
    const rowPod = page.getByRole('row').filter({ hasText: 'Disk pressure' })
    await rowPod.getByPlaceholder('namespace').first().fill('website')
    await rowPod.getByPlaceholder('pod').first().fill('website-backend-abc123')
    const podButton = rowPod.getByRole('button', { name: 'Restart pod' }).first()
    await podButton.click()
    // No assertion on the button being briefly disabled: the same button's label
    // switches to the cooldown ("Pod 30s") on click, so the 'Restart pod' locator
    // stops matching and the check is a race it only wins on an idle machine. The
    // outcomes below prove the action fired.
    await expect(page.getByText(/Pod website-backend-abc123 restart initiated/i)).toBeVisible()
    await expect(getPodCalls()).toBe(1)
    await expect(getDeploymentCalls()).toBe(0)
    await expect(rowPod.getByRole('button', { name: /Pod \d+s/ })).toBeVisible()
  })

  test('platform alerts pre-fill the restart form from labels, never from the alert name', async ({
    page
  }) => {
    await page.goto('/platform')
    const labelled = page.getByRole('row').filter({ hasText: 'KubePodCrashLooping' })
    await expect(labelled.getByText('Pod shop-api is crash looping')).toBeVisible()
    await expect(labelled.getByPlaceholder('namespace').first()).toHaveValue('shop')
    await expect(labelled.getByPlaceholder('pod').first()).toHaveValue('shop-api-7f8c-x2x9q')

    const unlabelled = page.getByRole('row').filter({ hasText: 'Disk pressure' })
    await expect(unlabelled.getByText('Filesystem utilization elevated on node-1')).toBeVisible()
    await expect(unlabelled.getByPlaceholder('pod').first()).toHaveValue('')
  })

  test('feature 3: deep health explorer renders JVM + dependency cards', async ({ page }) => {
    await page.goto('/platform/deep')
    await expect(page.getByRole('heading', { name: /Deep Health Explorer/i })).toBeVisible()
    await expect(page.getByText('website-backend (test)')).toBeVisible()
    await expect(page.getByText(/JVM Status/i)).toBeVisible()
    await expect(page.getByRole('heading', { name: /Dependencies/i })).toBeVisible()
    await expect(page.getByText('shop')).toBeVisible()
    await expect(page.getByText('dependency healthy')).toBeVisible()
  })

  test('feature 5: performance playground displays sampled web vitals', async ({ page }) => {
    const samples = [
      {
        name: 'LCP',
        value: 1120,
        rating: 'good',
        delta: 20,
        id: 'entry-1',
        navigationType: 'navigate',
        route: '/demo',
        timestamp: Date.now()
      },
      {
        name: 'FCP',
        value: 980,
        rating: 'good',
        delta: 15,
        id: 'entry-2',
        navigationType: 'navigate',
        route: '/demo',
        timestamp: Date.now() - 5000
      }
    ]

    await page.addInitScript((payload) => {
      localStorage.setItem('manyfold-web-vitals', payload)
    }, JSON.stringify(samples))

    await page.goto('/demo')
    await expect(
      page.getByRole('heading', { name: /Frontend Performance Playground/i })
    ).toBeVisible()
    await expect(page.getByText('Total samples')).toBeVisible()
    // Scope to the samples table: each value also appears in the "Last ..." and
    // "Avg: ..." summaries above it, which makes a bare getByText ambiguous.
    const samplesTable = page.getByRole('table')
    await expect(samplesTable.getByRole('cell', { name: '1120.00', exact: true })).toBeVisible()
    await expect(samplesTable.getByRole('cell', { name: '980.00', exact: true })).toBeVisible()
  })

  test('feature 5: performance playground prefers persisted backend samples', async ({ page }) => {
    await page.unroute('**/api/v1/metrics/vitals/recent')
    await page.route('**/api/v1/metrics/vitals/recent', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(recentVitalsResponse)
      })
    })

    await page.goto('/demo')
    await expect(
      page.getByRole('heading', { name: /Frontend Performance Playground/i })
    ).toBeVisible()
    await expect(page.getByText(/Data source: .*backend/)).toBeVisible()
    await expect(page.getByText('Backend returned no samples yet').first()).toBeHidden()
  })

  test('feature 6: fleet map shows visual topology and application details', async ({ page }) => {
    await page.goto('/platform/fleet')
    await expect(page.getByRole('heading', { name: /Fleet Map & App Directory/i })).toBeVisible()
    await expect(page.getByText('Topology (health graph)')).toBeVisible()
    const firstRow = page.getByRole('button', { name: 'Show details' }).first()
    await firstRow.click()
    await expect(page.getByText('Message: Service check OK')).toBeVisible()
    await expect(page.getByText('Topology edges')).toBeVisible()
  })

  test('feature 7: stability quest flow can be completed', async ({ page }) => {
    await page.goto('/platform/quest')
    await expect(page.getByRole('button', { name: 'Start' })).toBeVisible()
    await page.getByRole('button', { name: 'Start' }).click()

    await expect(page.getByText('Pod Crash Drill')).toBeVisible()
    await page.getByRole('button', { name: 'Restart Pod' }).click()
    await expect(page.getByText('Scenario pod-crash: correct action (pod)')).toBeVisible()
    await page.getByRole('button', { name: 'Restart Deployment' }).click()
    await expect(page.getByText('Scenario stale-config: correct action (deployment)')).toBeVisible()
    await page.getByRole('button', { name: 'Restart Deployment' }).click()
    await expect(page.getByText('Scenario rolling-fix: correct action (deployment)')).toBeVisible()
    await expect(page.getByText('Quest complete')).toBeVisible()
    await expect(page.getByText(/Final score/)).toBeVisible()
  })
})
