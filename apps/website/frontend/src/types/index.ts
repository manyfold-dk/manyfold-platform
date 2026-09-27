// --- Health API types (ADR-0023) ---

// One signal per row of the picture on /stack and /status: the four stack
// layers, then the four services that run on them. `activeAlerts` counts only
// alerts at warning or critical -- the ones a human would act on.
export interface HealthSummary {
  overall: string
  infrastructure: string
  network: string
  cluster: string
  applications: string
  platform: string
  edge: string
  identity: string
  delivery: string
  observability: string
  pipelines: string
  activeAlerts: number
  timestamp: string
}

export interface LayeredHealthResponse {
  overallStatus: string
  infrastructure: InfrastructureHealth
  network: NetworkHealth
  cluster: ClusterHealth
  platform: PlatformHealth
  pipelines: PipelinesHealth
  applications: ApplicationsHealth
  activeAlerts: ActiveAlert[]
  firingAlerts: number
  timestamp: string
}

export interface InfrastructureHealth {
  status: string
  totalNodes: number
  healthyNodes: number
  totalMemoryBytes: number
  usedMemoryBytes: number
  totalStorageBytes: number
  usedStorageBytes: number
}

export interface NetworkHealth {
  status: string
  cilium: ComponentHealth
  hubble: ComponentHealth
}

export interface ClusterHealth {
  status: string
  controlPlaneHealthy: boolean
  networkingHealthy: boolean
  dnsHealthy: boolean
  totalPods: number
  runningPods: number
  pendingPods: number
  failedPods: number
}

export interface PlatformHealth {
  status: string
  edge: ComponentHealth
  identity: ComponentHealth
  argocd: ComponentHealth
  registry: ComponentHealth
  observability: ComponentHealth
}

export interface ComponentHealth {
  name: string
  status: string
  details: string
}

export interface PipelinesHealth {
  status: string
  totalRuns24h: number
  successfulRuns24h: number
  failedRuns24h: number
  successRate: number
  lastRunStatus: string
  lastRunTime: string
}

export interface ApplicationsHealth {
  status: string
  apps: AppHealth[]
}

export interface AppHealth {
  name: string
  status: string
  namespace: string
  latencyMs: number
  message: string
}

/** An alert in the layered health response (/api/v1/health). */
export interface ActiveAlert {
  name: string
  severity: string
  message: string
  namespace: string
  since: string
}

/** A firing alert as /api/v1/alerts returns it (the backend's StoredAlert). */
export interface OperatorAlert {
  fingerprint: string
  name: string
  severity: string
  namespace: string | null
  summary: string | null
  description: string | null
  startsAt: string | null
  labels: Record<string, string>
}

export interface RemediationResult {
  success: boolean
  action: string
  resource: string
  message: string
}

export interface RemediationSummary {
  recentActions: number
  status: string
}

export interface AlertSummary {
  total: number
  critical: number
  warning: number
  info: number
}

export interface StatusService {
  name: string
  status: string
  latencyMs: number | null
  // Where the reading came from and how old it is. Null for older responses.
  detail?: string | null
}

// Recent history for the public checks, drawn as inline SVG by the page. The
// server sends numbers rather than images so the charts use the page's own
// colour tokens, stay crisp at any density and carry a text alternative.
export interface StatusHistory {
  checks: CheckHistory[]
  timestamp: string
}

export interface CheckHistory {
  name: string
  uptime: UptimeHistory
  latency: LatencyHistory
}

export interface UptimeHistory {
  days: number
  bucketHours: number
  // null where nothing was recorded: a gap in the monitoring, not an outage.
  buckets: (number | null)[]
  ratio: number | null
}

export interface LatencyHistory {
  hours: number
  stepMinutes: number
  points: (number | null)[]
  latestMs: number | null
}

export interface StatusResponse {
  status: string
  services: StatusService[]
  timestamp: string
}

export interface DependencyHealth {
  name: string
  type: string
  status: string
  latencyMs: number
  message: string
}

export interface SelfHealth {
  status: string
  uptimeSeconds: number
  resources: ResourceUsage | null
  metrics: Record<string, number | string | boolean | null>
}

export interface ResourceUsage {
  cpuPercent: number
  memoryUsedBytes: number
  memoryMaxBytes: number
  activeThreads: number
}

export interface DeepHealthResponse {
  status: string
  service: string
  version: string
  timestamp: string
  self: SelfHealth
  dependencies: DependencyHealth[]
  metadata: Record<string, string | number | boolean | null | object>
}

export interface WebVitalEntry {
  name: string
  value: number
  rating: 'good' | 'needs-improvement' | 'poor'
  delta: number
  id: string
  navigationType: string
  route: string
  timestamp: number
}
