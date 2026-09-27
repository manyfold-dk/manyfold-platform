import { config } from '../config.js';
import type {
  AlertSummary,
  HealthResponse,
  HealthSummary,
  RemediationSummary,
  StoredAlert,
} from '../types/index.js';

async function fetchJson<T>(path: string): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), config.backend.timeoutMs);
  try {
    const response = await fetch(`${config.backend.url}${path}`, { signal: controller.signal });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }
    return (await response.json()) as T;
  } finally {
    clearTimeout(timeout);
  }
}

interface BackendHealthResponse {
  overallStatus: string;
  infrastructure: {
    status: string;
    totalNodes: number;
    healthyNodes: number;
  };
  cluster: {
    status: string;
    controlPlaneHealthy: boolean;
    totalPods: number;
    runningPods: number;
    failedPods: number;
  };
  platform: {
    status: string;
    edge: { name: string; status: string; details: string };
    identity: { name: string; status: string; details: string };
    argocd: { name: string; status: string; details: string };
    registry: { name: string; status: string; details: string };
    observability: { name: string; status: string; details: string };
  };
  pipelines: {
    status: string;
    totalRuns24h: number;
    successfulRuns24h: number;
    failedRuns24h: number;
    successRate: number;
    lastRunStatus: string;
  };
  applications: {
    status: string;
    apps: Array<{ name: string; status: string; latencyMs: number }>;
  };
  activeAlerts: Array<{ name: string; severity: string; message: string }>;
}

function transformHealthResponse(raw: BackendHealthResponse): HealthResponse {
  const statusLabel = (s: string) => (s === 'healthy' ? 'OK' : s === 'degraded' ? '⚠️' : '❌');

  const layers: HealthResponse['layers'] = [
    {
      name: 'Infrastructure',
      status: raw.infrastructure.status as HealthResponse['overall'],
      details: `${raw.infrastructure.healthyNodes}/${raw.infrastructure.totalNodes} nodes ready`,
    },
    {
      name: 'Cluster',
      status: raw.cluster.status as HealthResponse['overall'],
      details: `Control plane ${raw.cluster.controlPlaneHealthy ? 'healthy' : 'unhealthy'}, ${raw.cluster.runningPods}/${raw.cluster.totalPods} pods running`,
    },
    {
      name: 'Platform',
      status: raw.platform.status as HealthResponse['overall'],
      details: [
        raw.platform.edge,
        raw.platform.identity,
        raw.platform.argocd,
        raw.platform.registry,
        raw.platform.observability,
      ]
        .filter((c) => c)
        .map((c) => `${c.name}: ${statusLabel(c.status)}`)
        .join(' | '),
    },
    {
      name: 'Pipelines',
      status: raw.pipelines.status as HealthResponse['overall'],
      // The block has no source since the Tekton controller was retired; its zero counts are
      // not measurements.
      details:
        raw.pipelines.status === 'unknown'
          ? 'no pipeline source'
          : `${raw.pipelines.successfulRuns24h} OK, ${raw.pipelines.failedRuns24h} failed / ${raw.pipelines.totalRuns24h} runs (24h) | ${Math.round(raw.pipelines.successRate)}% success rate`,
    },
    {
      name: 'Applications',
      status: raw.applications.status as HealthResponse['overall'],
      details: raw.applications.apps
        .map((a) => `${a.name}: ${statusLabel(a.status)} (${a.latencyMs}ms)`)
        .join(' | '),
    },
  ];

  return {
    overall: raw.overallStatus as HealthResponse['overall'],
    layers,
    activeAlerts: raw.activeAlerts.length,
  };
}

export async function getHealth(): Promise<HealthResponse> {
  const raw = await fetchJson<BackendHealthResponse>('/api/v1/health');
  return transformHealthResponse(raw);
}

export async function getHealthSummary(): Promise<HealthSummary> {
  return fetchJson<HealthSummary>('/api/v1/health/summary');
}

export async function getAlerts(): Promise<StoredAlert[]> {
  return fetchJson<StoredAlert[]>('/api/v1/alerts');
}

export async function getAlertSummary(): Promise<AlertSummary> {
  return fetchJson<AlertSummary>('/api/v1/alerts/summary');
}

export async function getRemediationSummary(): Promise<RemediationSummary> {
  return fetchJson<RemediationSummary>('/api/v1/remediation/summary');
}

/** The backend refused the call: the bot holds no identity it accepts for restarts. */
export class RemediationNotAuthorizedError extends Error {
  constructor(readonly status: number) {
    super(`backend refused the restart (HTTP ${status})`);
    this.name = 'RemediationNotAuthorizedError';
  }
}

/** The backend answered with an error status. A 4xx means the restart did not run; a 5xx proves nothing. */
export class RemediationHttpError extends Error {
  constructor(readonly status: number) {
    super(`backend answered HTTP ${status}`);
    this.name = 'RemediationHttpError';
  }
}

async function postRemediation(
  path: string,
  body: Record<string, string>,
): Promise<{ success: boolean; message: string }> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), config.backend.timeoutMs);
  try {
    const response = await fetch(`${config.backend.url}/api/v1/remediation/${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (response.status === 401 || response.status === 403) {
      throw new RemediationNotAuthorizedError(response.status);
    }
    if (!response.ok) {
      throw new RemediationHttpError(response.status);
    }
    return (await response.json()) as { success: boolean; message: string };
  } finally {
    clearTimeout(timeout);
  }
}

/** Restart a pod. The backend's request fields are `namespace` and `podName`. */
export function restartPod(
  namespace: string,
  podName: string,
): Promise<{ success: boolean; message: string }> {
  return postRemediation('pod/restart', { namespace, podName });
}

/** Restart a deployment. The backend's request fields are `namespace` and `deploymentName`. */
export function restartDeployment(
  namespace: string,
  deploymentName: string,
): Promise<{ success: boolean; message: string }> {
  return postRemediation('deployment/restart', { namespace, deploymentName });
}
