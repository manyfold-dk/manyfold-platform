<script setup lang="ts">
import { restartTargetFromAlert } from '@/lib/restartTarget'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  fetchActiveAlerts,
  fetchAlertSummary,
  fetchLayeredHealth,
  fetchRemediationSummary,
  restartDeployment,
  restartPod
} from '@/services/api'
import type { OperatorAlert, AlertSummary, PlatformHealth, RemediationSummary } from '@/types'
import type { LayeredHealthResponse } from '@/types'

interface Toast {
  id: number
  type: 'success' | 'error'
  text: string
}

interface HealthTrendPoint {
  timestamp: string
  overall: string
  infrastructure: string
  cluster: string
  platform: string
  pipelines: string
  applications: string
  activeAlerts: number
}

interface AlertActionInputs {
  namespace: string
  podName: string
  deploymentName: string
}

const health = ref<LayeredHealthResponse | null>(null)
const alertSummary = ref<AlertSummary | null>(null)
const remediation = ref<RemediationSummary | null>(null)
const activeAlerts = ref<OperatorAlert[]>([])

const isRefreshing = ref(false)
const error = ref<string | null>(null)
const lastUpdated = ref<string | null>(null)
const autoRefreshMs = 20000

const autoRefreshEnabled = ref(true)
type TimerHandle = ReturnType<typeof globalThis.setInterval>

const refreshTimer = ref<TimerHandle | null>(null)

const toasts = ref<Toast[]>([])
let toastSeq = 0
const trend = ref<HealthTrendPoint[]>([])

const actionInputs = ref<Record<number, AlertActionInputs>>({})
const actionBusy = ref<Record<string, boolean>>({})
const actionCooldownUntil = ref<Record<string, number>>({})

const healthSummary = computed(() => {
  if (!health.value) return null

  return [
    {
      label: 'Overall',
      status: health.value.overallStatus,
      detail: `${activeAlerts.value.length} active alert(s)`
    },
    {
      label: 'Infrastructure',
      status: health.value.infrastructure.status,
      detail: `${health.value.infrastructure.healthyNodes}/${health.value.infrastructure.totalNodes} nodes`
    },
    {
      label: 'Cluster',
      status: health.value.cluster.status,
      detail: `${health.value.cluster.runningPods} running / ${health.value.cluster.pendingPods} pending`
    },
    {
      label: 'Network',
      status: health.value.network.status,
      detail: `${health.value.network.cilium.details}, Hubble ${health.value.network.hubble.status}`
    },
    {
      label: 'Platform',
      status: health.value.platform.status,
      detail: platformDetail(health.value.platform)
    },
    {
      label: 'Pipelines',
      status: health.value.pipelines.status,
      detail:
        health.value.pipelines.status === 'unknown'
          ? 'no pipeline source'
          : `${health.value.pipelines.successfulRuns24h}/${health.value.pipelines.totalRuns24h} successful`
    },
    {
      label: 'Applications',
      status: health.value.applications.status,
      detail: `${health.value.applications.apps.length} services`
    }
  ]
})

// Name the components that are not healthy, or say how many are. This used to
// read a named component the API has never returned: against the real backend
// it threw and took the whole summary panel with it, and only the e2e mock,
// which invented that component, kept the tests green.
function platformDetail(platform: PlatformHealth) {
  const components = [
    platform.edge,
    platform.identity,
    platform.argocd,
    platform.registry,
    platform.observability
  ].filter(Boolean)
  const unwell = components.filter((c) => c.status !== 'healthy')
  if (!unwell.length) return `${components.length} components healthy`
  return unwell.map((c) => `${c.name}: ${c.status}`).join(', ')
}

// Unknown means nothing measured the signal this pass (Prometheus did not
// answer, say): a gap in the monitoring, not a fault, so it is not drawn red.
const statusColor = (status: string) => {
  if (status === 'healthy') return 'bg-emerald-100 text-emerald-800'
  if (status === 'degraded') return 'bg-amber-100 text-amber-800'
  if (status === 'unknown') return 'bg-slate-100 text-slate-600'
  return 'bg-red-100 text-red-800'
}

const statusDot = (status: string) => {
  if (status === 'healthy') return 'bg-emerald-500'
  if (status === 'degraded') return 'bg-amber-500'
  if (status === 'unknown') return 'bg-slate-400'
  return 'bg-red-500'
}

const severityColor = (severity: string) => {
  if (severity === 'critical') return 'text-red-700 bg-red-50 border-red-100'
  if (severity === 'warning') return 'text-amber-700 bg-amber-50 border-amber-100'
  return 'text-slate-700 bg-slate-50 border-slate-100'
}

const statusTrend = computed(() => {
  return trend.value.slice(0, 8).map((point) => ({
    ...point,
    secondsAgo: Math.floor((Date.now() - new Date(point.timestamp).getTime()) / 1000)
  }))
})

const alertTickerText = computed(() => {
  if (activeAlerts.value.length === 0) {
    return 'No active alert conditions'
  }
  return activeAlerts.value
    .map((alert) => `${alert.name}: ${alert.summary || alert.namespace || 'unresolved'}`)
    .join('   ✶   ')
})

function toHumanBytes(bytes: number): string {
  if (bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const idx = Math.floor(Math.log(bytes) / Math.log(1024))
  const value = bytes / Math.pow(1024, idx)
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[idx]}`
}

function scoreStatus(status: string): number {
  if (status === 'healthy') return 3
  if (status === 'degraded') return 2
  return 1
}

// Score the measured layers only: an unknown layer (today the pipelines block, which has no
// source since the Tekton controller was retired) leaves both the score and its ceiling.
function summarizeHealthTrend(): string {
  if (!health.value) return ''
  const measured = [
    health.value.infrastructure.status,
    health.value.cluster.status,
    health.value.platform.status,
    health.value.pipelines.status,
    health.value.applications.status
  ].filter((status) => status !== 'unknown')
  if (measured.length === 0) return 'unknown'
  const score = measured.map(scoreStatus).reduce((total, value) => total + value, 0)
  return `${score}/${measured.length * 3}`
}

function addToast(type: 'success' | 'error', text: string) {
  const id = toastSeq++
  toasts.value.push({ id, type, text })

  globalThis.setTimeout(() => {
    toasts.value = toasts.value.filter((toast) => toast.id !== id)
  }, 4500)
}

function normalizeActionInputs(alert: OperatorAlert, index: number) {
  actionInputs.value[index] = restartTargetFromAlert(alert)
}

function alertActionPrefix(alert: OperatorAlert, index: number): string {
  return `${index}-${alert.name}-${alert.namespace || 'default'}`
}

function getActionInputs(index: number): AlertActionInputs {
  return (
    actionInputs.value[index] ??
    (actionInputs.value[index] = {
      namespace: '',
      podName: '',
      deploymentName: ''
    })
  )
}

function updateActionInput(index: number, key: keyof AlertActionInputs, value: string) {
  getActionInputs(index)[key] = value
}

function isActionInCooldown(actionKey: string): boolean {
  const until = actionCooldownUntil.value[actionKey]
  return !!until && until > Date.now()
}

function remainingCooldown(actionKey: string): number {
  const until = actionCooldownUntil.value[actionKey]
  if (!until) return 0
  return Math.max(0, Math.ceil((until - Date.now()) / 1000))
}

function setActionBusy(actionKey: string, busy: boolean) {
  actionBusy.value[actionKey] = busy
}

function isActionBusy(actionKey: string): boolean {
  return !!actionBusy.value[actionKey]
}

function setCooldown(actionKey: string, ms: number = 60000) {
  actionCooldownUntil.value[actionKey] = Date.now() + ms
}

async function runPodRemediation(alert: OperatorAlert, index: number) {
  const key = alertActionPrefix(alert, index) + '-pod'
  if (isActionInCooldown(key) || isActionBusy(key)) {
    return
  }

  const inputs = getActionInputs(index)
  if (!inputs) {
    addToast('error', `No action details for ${alert.name}`)
    return
  }
  if (!inputs.namespace || !inputs.podName) {
    addToast('error', 'Namespace and pod name are required')
    return
  }

  setActionBusy(key, true)
  try {
    const result = await restartPod(inputs.namespace.trim(), inputs.podName.trim())
    if (result.success) {
      addToast('success', result.message || `Pod ${inputs.podName} restart initiated`)
      setCooldown(key)
      await refreshDashboard()
    } else {
      addToast('error', result.message || 'Pod restart was blocked')
    }
  } catch (e) {
    addToast('error', e instanceof Error ? e.message : 'Failed to restart pod')
  } finally {
    setActionBusy(key, false)
  }
}

async function runDeploymentRemediation(alert: OperatorAlert, index: number) {
  const key = alertActionPrefix(alert, index) + '-deployment'
  if (isActionInCooldown(key) || isActionBusy(key)) {
    return
  }

  const inputs = getActionInputs(index)
  if (!inputs) {
    addToast('error', `No action details for ${alert.name}`)
    return
  }
  if (!inputs.namespace || !inputs.deploymentName) {
    addToast('error', 'Namespace and deployment name are required')
    return
  }

  setActionBusy(key, true)
  try {
    const result = await restartDeployment(inputs.namespace.trim(), inputs.deploymentName.trim())
    if (result.success) {
      addToast('success', result.message || `Deployment ${inputs.deploymentName} restart initiated`)
      setCooldown(key)
      await refreshDashboard()
    } else {
      addToast('error', result.message || 'Deployment restart was blocked')
    }
  } catch (e) {
    addToast('error', e instanceof Error ? e.message : 'Failed to restart deployment')
  } finally {
    setActionBusy(key, false)
  }
}

function refreshHealthSnapshot(healthSummary?: LayeredHealthResponse) {
  if (healthSummary) {
    const point: HealthTrendPoint = {
      timestamp: new Date().toISOString(),
      overall: healthSummary.overallStatus,
      infrastructure: healthSummary.infrastructure.status,
      cluster: healthSummary.cluster.status,
      platform: healthSummary.platform.status,
      pipelines: healthSummary.pipelines.status,
      applications: healthSummary.applications.status,
      activeAlerts: healthSummary.firingAlerts
    }
    trend.value = [point, ...trend.value].slice(0, 20)
  }
}

async function refreshDashboard() {
  if (isRefreshing.value) return
  isRefreshing.value = true
  error.value = null

  try {
    const [healthResponse, remediationResponse, alertSummaryResponse, alertsResponse] =
      await Promise.all([
        fetchLayeredHealth(),
        fetchRemediationSummary(),
        fetchAlertSummary(),
        fetchActiveAlerts()
      ])

    health.value = healthResponse
    remediation.value = remediationResponse
    alertSummary.value = alertSummaryResponse
    activeAlerts.value = alertsResponse
    lastUpdated.value = new Date().toLocaleString()

    alertsResponse.forEach((alert, index) => {
      normalizeActionInputs(alert, index)
    })

    refreshHealthSnapshot(healthResponse)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load dashboard'
  } finally {
    isRefreshing.value = false
  }
}

function setupAutoRefresh() {
  if (refreshTimer.value) return
  refreshTimer.value = globalThis.setInterval(() => {
    if (autoRefreshEnabled.value) {
      void refreshDashboard()
    }
  }, autoRefreshMs)
}

function setAutoRefreshState(enabled: boolean) {
  if (enabled === autoRefreshEnabled.value) return

  autoRefreshEnabled.value = enabled
  if (enabled) {
    setupAutoRefresh()
  } else if (refreshTimer.value) {
    globalThis.clearInterval(refreshTimer.value)
    refreshTimer.value = null
  }
}

onMounted(() => {
  void refreshDashboard().finally(() => {
    setupAutoRefresh()
  })
})

onBeforeUnmount(() => {
  if (refreshTimer.value) {
    globalThis.clearInterval(refreshTimer.value)
    refreshTimer.value = null
  }
})

const memoryUtilization = computed(() => {
  if (!health.value) return 0
  const { totalMemoryBytes, usedMemoryBytes } = health.value.infrastructure
  if (!totalMemoryBytes) return 0
  return Math.round((usedMemoryBytes / totalMemoryBytes) * 100)
})

const storageUtilization = computed(() => {
  if (!health.value) return 0
  const { totalStorageBytes, usedStorageBytes } = health.value.infrastructure
  if (!totalStorageBytes) return 0
  return Math.round((usedStorageBytes / totalStorageBytes) * 100)
})
</script>

<template>
  <main class="min-h-screen bg-slate-50 py-12">
    <div class="mx-auto max-w-7xl px-6">
      <div class="mb-8 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 class="text-3xl font-bold tracking-tight text-slate-900">Platform Control Center</h1>
          <p class="mt-1 text-sm text-slate-500">
            Auto-refresh every {{ autoRefreshMs / 1000 }} seconds
          </p>
          <p v-if="lastUpdated" class="mt-1 text-xs text-slate-400">
            Last updated: {{ lastUpdated }}
          </p>
        </div>
        <div class="flex flex-wrap items-center gap-3">
          <label class="flex items-center gap-2 text-sm text-slate-600">
            <input
              v-model="autoRefreshEnabled"
              type="checkbox"
              @change="setAutoRefreshState(($event.target as HTMLInputElement).checked)"
            />
            Auto-refresh
          </label>
          <button
            :disabled="isRefreshing"
            class="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 disabled:opacity-50"
            @click="refreshDashboard"
          >
            {{ isRefreshing ? 'Refreshing...' : 'Refresh now' }}
          </button>
        </div>
      </div>

      <div class="fixed right-4 top-4 z-50 w-full max-w-md space-y-2">
        <div
          v-for="toast in toasts"
          :key="toast.id"
          class="rounded-lg border border-slate-200 px-4 py-3 shadow-sm"
          :class="
            toast.type === 'success' ? 'bg-emerald-50 text-emerald-900' : 'bg-red-50 text-red-900'
          "
        >
          {{ toast.text }}
        </div>
      </div>

      <div v-if="error" class="mb-6 rounded-xl border border-red-200 bg-red-50 p-4 text-red-700">
        {{ error }}
      </div>

      <div v-if="health" class="mb-6 rounded-xl border border-slate-200 bg-white p-6">
        <div class="mb-5 flex items-center gap-3">
          <span class="h-3 w-3 rounded-full" :class="statusDot(health.overallStatus)" />
          <h2 class="text-xl font-semibold text-slate-900">
            Overall status: {{ health.overallStatus }}
          </h2>
          <span class="ml-auto text-sm font-semibold text-slate-600">
            Health score {{ summarizeHealthTrend() }}
          </span>
        </div>

        <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-6">
          <div
            v-for="item in healthSummary"
            :key="item.label"
            class="rounded-lg border border-slate-200 bg-slate-50 p-4"
          >
            <p class="text-xs text-slate-500">
              {{ item.label }}
            </p>
            <p
              class="mt-1 inline-flex rounded-full px-2.5 py-1 text-xs font-medium"
              :class="statusColor(item.status)"
            >
              {{ item.status }}
            </p>
            <p class="mt-2 text-sm text-slate-600">
              {{ item.detail }}
            </p>
          </div>
        </div>

        <div class="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <div class="rounded-lg border border-slate-200 bg-slate-50 p-4">
            <p class="text-xs text-slate-500">Memory</p>
            <p class="mt-1 text-lg font-semibold text-slate-900">
              {{ toHumanBytes(health.infrastructure.usedMemoryBytes) }} /
              {{ toHumanBytes(health.infrastructure.totalMemoryBytes) }}
            </p>
            <p class="mt-1 text-xs text-slate-500">{{ memoryUtilization }}% utilized</p>
          </div>
          <div class="rounded-lg border border-slate-200 bg-slate-50 p-4">
            <p class="text-xs text-slate-500">Storage</p>
            <p class="mt-1 text-lg font-semibold text-slate-900">
              {{ toHumanBytes(health.infrastructure.usedStorageBytes) }} /
              {{ toHumanBytes(health.infrastructure.totalStorageBytes) }}
            </p>
            <p class="mt-1 text-xs text-slate-500">{{ storageUtilization }}% utilized</p>
          </div>
          <div class="rounded-lg border border-slate-200 bg-slate-50 p-4">
            <p class="text-xs text-slate-500">Remediation actions</p>
            <p class="mt-1 text-lg font-semibold text-slate-900">
              {{ remediation?.recentActions ?? 0 }} this hour
            </p>
            <p class="mt-1 text-xs text-slate-500">
              {{ remediation?.status ?? 'normal' }}
            </p>
          </div>
          <div class="rounded-lg border border-slate-200 bg-slate-50 p-4">
            <p class="text-xs text-slate-500">Alerts summary</p>
            <p class="mt-1 text-lg font-semibold text-slate-900">
              {{ alertSummary?.total ?? 0 }} active
            </p>
            <p class="mt-1 text-xs text-slate-500">
              {{ alertSummary?.critical ?? 0 }} critical / {{ alertSummary?.warning ?? 0 }} warning
              / {{ alertSummary?.info ?? 0 }} info
            </p>
          </div>
        </div>
      </div>

      <div
        class="mb-8 overflow-hidden rounded-xl border border-slate-200 bg-slate-900 px-4 py-2 text-emerald-200"
      >
        <div class="animate-[marquee_18s_linear_infinite] whitespace-nowrap">
          <span class="inline-block">{{ alertTickerText }}</span>
        </div>
      </div>

      <div class="mb-6">
        <h2 class="mb-3 text-lg font-semibold text-slate-900">Health trend (latest to oldest)</h2>
        <div class="overflow-hidden rounded-xl border border-slate-200 bg-white">
          <table class="w-full text-left text-sm">
            <thead class="bg-slate-50">
              <tr>
                <th class="px-4 py-3 font-medium text-slate-600">When</th>
                <th class="px-4 py-3 font-medium text-slate-600">Overall</th>
                <th class="px-4 py-3 font-medium text-slate-600">Infra / Cluster</th>
                <th class="px-4 py-3 font-medium text-slate-600">Platform</th>
                <th class="px-4 py-3 font-medium text-slate-600">Pipelines / Apps</th>
                <th class="px-4 py-3 font-medium text-slate-600">Active alerts</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              <tr v-for="point in statusTrend" :key="point.timestamp">
                <td class="px-4 py-3 text-slate-600">{{ point.secondsAgo }}s ago</td>
                <td class="px-4 py-3">
                  <span
                    class="rounded-full px-2 py-1 text-xs font-medium"
                    :class="statusColor(point.overall)"
                  >
                    {{ point.overall }}
                  </span>
                </td>
                <td class="px-4 py-3 text-slate-600">
                  {{ point.infrastructure }} / {{ point.cluster }}
                </td>
                <td class="px-4 py-3 text-slate-600">
                  {{ point.platform }}
                </td>
                <td class="px-4 py-3 text-slate-600">
                  {{ point.pipelines }} / {{ point.applications }}
                </td>
                <td class="px-4 py-3 text-slate-600">
                  {{ point.activeAlerts }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div>
        <h2 class="mb-4 text-lg font-semibold text-slate-900">
          Active alerts + remediation cockpit
        </h2>
        <div class="overflow-hidden rounded-xl border border-slate-200 bg-white">
          <table class="w-full text-left text-sm">
            <thead class="border-b border-slate-200 bg-slate-50">
              <tr>
                <th class="px-4 py-3 font-medium text-slate-600">Alert</th>
                <th class="px-4 py-3 font-medium text-slate-600">Severity</th>
                <th class="px-4 py-3 font-medium text-slate-600">Since</th>
                <th class="px-4 py-3 font-medium text-slate-600">Message</th>
                <th class="px-4 py-3 font-medium text-slate-600">Remediation</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              <tr
                v-for="(alert, index) in activeAlerts"
                :key="`${alert.name}-${alert.namespace}-${index}`"
              >
                <td class="px-4 py-4">
                  <p class="font-medium text-slate-900">
                    {{ alert.name }}
                  </p>
                  <p class="text-xs text-slate-500">
                    {{ alert.namespace || 'default' }}
                  </p>
                </td>
                <td class="px-4 py-4">
                  <span
                    class="rounded-full border px-2 py-1 text-xs font-medium capitalize"
                    :class="severityColor(alert.severity)"
                  >
                    {{ alert.severity }}
                  </span>
                </td>
                <td class="px-4 py-4 text-slate-600">
                  {{ alert.startsAt ? new Date(alert.startsAt).toLocaleTimeString() : '' }}
                </td>
                <td class="max-w-[260px] px-4 py-4 text-slate-600">
                  {{ alert.summary || alert.description }}
                </td>
                <td class="px-4 py-4">
                  <div class="space-y-2">
                    <div class="grid gap-2 lg:grid-cols-12">
                      <input
                        :value="getActionInputs(index).namespace"
                        class="col-span-4 rounded-md border border-slate-200 px-2 py-1 text-xs"
                        placeholder="namespace"
                        @input="
                          updateActionInput(
                            index,
                            'namespace',
                            ($event.target as HTMLInputElement | null)?.value || ''
                          )
                        "
                      />
                      <input
                        :value="getActionInputs(index).podName"
                        class="col-span-6 rounded-md border border-slate-200 px-2 py-1 text-xs"
                        placeholder="pod"
                        @input="
                          updateActionInput(
                            index,
                            'podName',
                            ($event.target as HTMLInputElement | null)?.value || ''
                          )
                        "
                      />
                      <button
                        :disabled="
                          isActionInCooldown(alertActionPrefix(alert, index) + '-pod') ||
                          isActionBusy(alertActionPrefix(alert, index) + '-pod')
                        "
                        class="col-span-2 rounded-md bg-emerald-500 px-2 py-1 text-xs font-medium text-white hover:bg-emerald-600 disabled:cursor-not-allowed disabled:opacity-50"
                        @click="runPodRemediation(alert, index)"
                      >
                        {{
                          remainingCooldown(alertActionPrefix(alert, index) + '-pod') > 0
                            ? `Pod ${remainingCooldown(alertActionPrefix(alert, index) + '-pod')}s`
                            : 'Restart pod'
                        }}
                      </button>
                    </div>
                    <div class="grid gap-2 lg:grid-cols-12">
                      <input
                        :value="getActionInputs(index).namespace"
                        class="col-span-4 rounded-md border border-slate-200 px-2 py-1 text-xs"
                        placeholder="namespace"
                        @input="
                          updateActionInput(
                            index,
                            'namespace',
                            ($event.target as HTMLInputElement | null)?.value || ''
                          )
                        "
                      />
                      <input
                        :value="getActionInputs(index).deploymentName"
                        class="col-span-6 rounded-md border border-slate-200 px-2 py-1 text-xs"
                        placeholder="deployment"
                        @input="
                          updateActionInput(
                            index,
                            'deploymentName',
                            ($event.target as HTMLInputElement | null)?.value || ''
                          )
                        "
                      />
                      <button
                        :disabled="
                          isActionInCooldown(alertActionPrefix(alert, index) + '-deployment') ||
                          isActionBusy(alertActionPrefix(alert, index) + '-deployment')
                        "
                        class="col-span-2 rounded-md bg-blue-500 px-2 py-1 text-xs font-medium text-white hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-50"
                        @click="runDeploymentRemediation(alert, index)"
                      >
                        {{
                          remainingCooldown(alertActionPrefix(alert, index) + '-deployment') > 0
                            ? `Deploy ${remainingCooldown(alertActionPrefix(alert, index) + '-deployment')}s`
                            : 'Restart deploy'
                        }}
                      </button>
                    </div>
                  </div>
                </td>
              </tr>
              <tr v-if="activeAlerts.length === 0">
                <td class="px-4 py-8 text-center text-sm text-slate-500" colspan="5">
                  No active alerts right now.
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
@keyframes marquee {
  0% {
    transform: translateX(100%);
  }
  100% {
    transform: translateX(-100%);
  }
}
</style>
