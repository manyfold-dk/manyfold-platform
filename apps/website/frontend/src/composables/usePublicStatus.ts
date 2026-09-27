import { readonly, ref } from 'vue'
import { fetchHealthSummary, fetchStatus } from '@/services/api'
import type { HealthSummary, StatusResponse } from '@/types'

// One shared, lazily started poll of the two public status endpoints, used by the
// site header pill and the landing page status card. Failure is a normal state
// (local dev without a backend, or an outage) and renders as "unavailable".
const status = ref<StatusResponse | null>(null)
const summary = ref<HealthSummary | null>(null)
const failed = ref(false)
let started = false

async function load() {
  try {
    const [s, h] = await Promise.all([fetchStatus(), fetchHealthSummary()])
    status.value = s
    summary.value = h
    failed.value = false
  } catch {
    failed.value = true
  }
}

export function usePublicStatus() {
  if (!started && typeof window !== 'undefined') {
    started = true
    void load()
    window.setInterval(() => void load(), 60000)
  }
  return { status: readonly(status), summary: readonly(summary), failed: readonly(failed) }
}

export function statusTone(value: string | undefined | null): 'ok' | 'warn' | 'down' | 'unknown' {
  if (!value) return 'unknown'
  const v = value.toLowerCase()
  if (v === 'operational' || v === 'healthy') return 'ok'
  if (v === 'degraded') return 'warn'
  // The API says "unknown" when a check produced no usable reading -- a probe
  // that stopped running, say. That is not a fault, and must not render as one.
  if (v === 'unknown') return 'unknown'
  return 'down'
}
