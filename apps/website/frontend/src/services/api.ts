import type {
  HealthSummary,
  LayeredHealthResponse,
  AlertSummary,
  OperatorAlert,
  RemediationResult,
  RemediationSummary,
  StatusResponse,
  StatusHistory,
  DeepHealthResponse,
  WebVitalEntry
} from '@/types'

const RAW_API_BASE = (import.meta.env.VITE_API_BASE || '/api/v1').trim().replace(/\/$/, '')
const API_BASE =
  import.meta.env.DEV && (/^https?:\/\//.test(RAW_API_BASE) || /^\/\//.test(RAW_API_BASE))
    ? '/api/v1'
    : RAW_API_BASE

export async function fetchHealthSummary(): Promise<HealthSummary> {
  const response = await fetch(`${API_BASE}/health/summary`)
  if (!response.ok) throw new Error(`API error: ${response.status}`)
  return response.json()
}

export async function fetchLayeredHealth(): Promise<LayeredHealthResponse> {
  const response = await fetch(`${API_BASE}/health`)
  if (!response.ok) throw new Error(`API error: ${response.status}`)
  return response.json()
}

export async function fetchAlertSummary(): Promise<AlertSummary> {
  const response = await fetch(`${API_BASE}/alerts/summary`)
  if (!response.ok) throw new Error(`API error: ${response.status}`)
  return response.json()
}

export async function fetchActiveAlerts(): Promise<OperatorAlert[]> {
  const response = await fetch(`${API_BASE}/alerts`)
  if (!response.ok) throw new Error(`API error: ${response.status}`)
  return response.json()
}

export async function fetchStatus(): Promise<StatusResponse> {
  const response = await fetch(`${API_BASE}/status`)
  if (!response.ok) throw new Error(`API error: ${response.status}`)
  return response.json()
}

export async function fetchStatusHistory(): Promise<StatusHistory> {
  const response = await fetch(`${API_BASE}/status/history`)
  if (!response.ok) throw new Error(`API error: ${response.status}`)
  return response.json()
}

export async function fetchDeepHealth(): Promise<DeepHealthResponse> {
  const response = await fetch(`${API_BASE}/health/deep`)
  if (!response.ok) throw new Error(`API error: ${response.status}`)
  return response.json()
}

export async function fetchRecentVitals(limit = 50): Promise<WebVitalEntry[]> {
  const response = await fetch(
    `${API_BASE}/metrics/vitals/recent?limit=${encodeURIComponent(String(limit))}`
  )
  if (!response.ok) {
    throw new Error(`API error: ${response.status}`)
  }
  return response.json()
}

export async function restartPod(namespace: string, podName: string): Promise<RemediationResult> {
  const response = await fetch(`${API_BASE}/remediation/pod/restart`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ namespace, podName })
  })
  if (!response.ok) throw new Error(`API error: ${response.status}`)
  return response.json()
}

export async function restartDeployment(
  namespace: string,
  deploymentName: string
): Promise<RemediationResult> {
  const response = await fetch(`${API_BASE}/remediation/deployment/restart`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ namespace, deploymentName })
  })
  if (!response.ok) throw new Error(`API error: ${response.status}`)
  return response.json()
}

export async function fetchRemediationSummary(): Promise<RemediationSummary> {
  const response = await fetch(`${API_BASE}/remediation/summary`)
  if (!response.ok) throw new Error(`API error: ${response.status}`)
  return response.json()
}
