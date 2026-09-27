<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchLayeredHealth } from '@/services/api'
import type { LayeredHealthResponse } from '@/types'

const health = ref<LayeredHealthResponse | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const expanded = ref(new Set<string>())
const autoRefreshMs = 30000

interface TopologyNode {
  id: string
  name: string
  status: string
  details: string
  x: number
  y: number
  size: number
}

interface TopologyEdge {
  from: string
  to: string
}

const applications = computed(() => health.value?.applications.apps ?? [])
const isLoading = computed(() => loading.value && !health.value)

const systemNodes = computed<TopologyNode[]>(() => {
  if (!health.value) return []
  return [
    {
      id: 'platform',
      name: 'Platform',
      status: health.value.platform.status,
      details: `${health.value.platform.edge.name} / ${health.value.platform.argocd.name}`,
      x: 430,
      y: 40,
      size: 110
    },
    {
      id: 'cluster',
      name: 'Cluster',
      status: health.value.cluster.status,
      details: `${health.value.cluster.runningPods} running / ${health.value.cluster.totalPods} pods`,
      x: 180,
      y: 145,
      size: 95
    },
    {
      id: 'infrastructure',
      name: 'Infrastructure',
      status: health.value.infrastructure.status,
      details: `${health.value.infrastructure.healthyNodes}/${health.value.infrastructure.totalNodes} nodes`,
      x: 305,
      y: 145,
      size: 95
    },
    {
      id: 'pipelines',
      name: 'Pipelines',
      status: health.value.pipelines.status,
      details: `${health.value.pipelines.lastRunStatus} · ${health.value.pipelines.totalRuns24h} runs`,
      x: 555,
      y: 145,
      size: 95
    },
    {
      id: 'observability',
      name: 'Registry',
      status: health.value.platform.registry.status,
      details: `${health.value.platform.registry.name} / ${health.value.platform.observability.name}`,
      x: 680,
      y: 145,
      size: 95
    }
  ]
})

const applicationNodes = computed<TopologyNode[]>(() => {
  if (!health.value || health.value.applications.apps.length === 0) {
    return []
  }

  const apps = health.value.applications.apps
  const max = Math.max(apps.length - 1, 1)
  const width = 520
  const startX = 170

  return apps.map((app, index) => {
    const x = startX + (max === 0 ? 0 : (width * index) / max)
    return {
      id: `app-${app.name}`,
      name: app.name,
      status: app.status,
      details: app.namespace || 'default',
      x,
      y: 250,
      size: 130
    }
  })
})

const topologyNodes = computed<TopologyNode[]>(() => [
  ...systemNodes.value,
  ...applicationNodes.value
])
const nodeById = computed(() => {
  const lookup = new Map<string, TopologyNode>()
  topologyNodes.value.forEach((node) => {
    lookup.set(node.id, node)
  })
  return lookup
})

const topologyEdges = computed<TopologyEdge[]>(() => {
  const links: TopologyEdge[] = []
  const source = 'platform'
  systemNodes.value.forEach((node) => {
    if (node.id !== source) {
      links.push({ from: source, to: node.id })
    }
  })
  applicationNodes.value.forEach((node) => {
    links.push({ from: source, to: node.id })
  })
  return links
})

const graphWidth = 860
const graphHeight = 320

function getNodeStatusClass(status: string) {
  if (status === 'healthy') return 'bg-emerald-100 text-emerald-800'
  if (status === 'degraded') return 'bg-amber-100 text-amber-800'
  return 'bg-red-100 text-red-800'
}

function getStatusBadgeClass(status: string) {
  if (status === 'healthy') return 'bg-emerald-100 text-emerald-700 border border-emerald-200'
  if (status === 'degraded') return 'bg-amber-100 text-amber-700 border border-amber-200'
  return 'bg-red-100 text-red-700 border border-red-200'
}

function statusClass(status: string) {
  if (status === 'healthy') return 'bg-emerald-100 text-emerald-800'
  if (status === 'degraded') return 'bg-amber-100 text-amber-800'
  return 'bg-red-100 text-red-800'
}

function nodeStyle(node: TopologyNode): Record<string, string> {
  return {
    left: `${(node.x / graphWidth) * 100}%`,
    top: `${(node.y / graphHeight) * 100}%`,
    width: `${Math.min(node.size, 130)}px`
  }
}

function edgeCoordinates(edge: TopologyEdge) {
  const fromNode = nodeById.value.get(edge.from)
  const toNode = nodeById.value.get(edge.to)
  if (!fromNode || !toNode) {
    return null
  }
  return {
    x1: `${((fromNode.x + fromNode.size / 2) / graphWidth) * 100}%`,
    y1: `${((fromNode.y + 28) / graphHeight) * 100}%`,
    x2: `${((toNode.x + toNode.size / 2) / graphWidth) * 100}%`,
    y2: `${(toNode.y / graphHeight) * 100}%`
  }
}

function toHumanMs(ms: number): string {
  return `${ms}ms`
}

function toggleExpanded(name: string) {
  const next = new Set(expanded.value)
  if (next.has(name)) {
    next.delete(name)
  } else {
    next.add(name)
  }
  expanded.value = next
}

function isExpanded(name: string): boolean {
  return expanded.value.has(name)
}

async function refresh() {
  if (loading.value) return
  loading.value = true
  error.value = null
  try {
    health.value = await fetchLayeredHealth()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load topology'
  } finally {
    loading.value = false
  }
}

type TimerHandle = ReturnType<typeof globalThis.setInterval>

let timer: TimerHandle | null = null

onMounted(() => {
  void refresh()
  timer = globalThis.setInterval(() => {
    void refresh()
  }, autoRefreshMs)
})

onBeforeUnmount(() => {
  if (timer) {
    globalThis.clearInterval(timer)
    timer = null
  }
})
</script>

<template>
  <main class="min-h-screen bg-slate-50 py-12">
    <div class="mx-auto max-w-7xl px-6">
      <div class="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 class="text-3xl font-bold text-slate-900">Fleet Map & App Directory</h1>
          <p class="mt-2 text-sm text-slate-500">
            Topology style view with health state, app latency, namespace, and expandable
            drill-down.
          </p>
        </div>
        <button
          :disabled="loading"
          class="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 disabled:opacity-50"
          @click="refresh"
        >
          {{ loading ? 'Loading...' : 'Refresh' }}
        </button>
      </div>

      <div v-if="error" class="mb-6 rounded-xl border border-red-200 bg-red-50 p-4 text-red-700">
        {{ error }}
      </div>

      <div v-if="isLoading" class="rounded-xl border border-slate-200 bg-white p-6 text-slate-600">
        Loading topology...
      </div>

      <section v-if="health" class="rounded-xl border border-slate-200 bg-white p-6">
        <h2 class="text-lg font-semibold text-slate-900">Topology (health graph)</h2>
        <div class="relative mt-4 overflow-x-auto">
          <div
            class="relative h-[320px] min-h-[320px] w-full border border-slate-100 bg-slate-50"
            role="img"
            aria-label="Platform topology graph"
          >
            <svg
              :viewBox="`0 0 ${graphWidth} ${graphHeight}`"
              class="absolute inset-0 h-full w-full"
              preserveAspectRatio="xMidYMid meet"
            >
              <line
                v-for="edge in topologyEdges"
                :key="`${edge.from}-${edge.to}`"
                :x1="edgeCoordinates(edge)?.x1"
                :y1="edgeCoordinates(edge)?.y1"
                :x2="edgeCoordinates(edge)?.x2"
                :y2="edgeCoordinates(edge)?.y2"
                class="stroke-slate-300"
                stroke-width="2"
              />
            </svg>
            <div
              v-for="node in topologyNodes"
              :key="node.id"
              class="pointer-events-none absolute rounded-lg border border-slate-200 bg-white p-3 text-center shadow-sm"
              :style="nodeStyle(node)"
              :aria-label="`${node.name} ${node.status}`"
            >
              <p class="text-[11px] uppercase tracking-wide text-slate-500">
                {{ node.name }}
              </p>
              <p class="mt-2">
                <span
                  class="inline-flex rounded-full px-2 py-1 text-[11px] font-semibold"
                  :class="getNodeStatusClass(node.status)"
                >
                  {{ node.status }}
                </span>
              </p>
              <p class="mt-2 truncate px-1 text-[11px] text-slate-600">
                {{ node.details }}
              </p>
            </div>
          </div>
        </div>

        <h3 class="mt-6 mb-3 text-sm font-semibold text-slate-900">Applications</h3>
        <div class="overflow-hidden rounded-xl border border-slate-200">
          <table class="w-full text-left text-sm">
            <thead class="bg-slate-50">
              <tr>
                <th class="px-4 py-3 font-medium text-slate-600">Service</th>
                <th class="px-4 py-3 font-medium text-slate-500">Namespace</th>
                <th class="px-4 py-3 font-medium text-slate-500">Status</th>
                <th class="px-4 py-3 font-medium text-slate-500">Latency</th>
                <th class="px-4 py-3 font-medium text-slate-500">Details</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              <template v-for="app in applications" :key="app.name">
                <tr>
                  <td class="px-4 py-3 font-medium text-slate-900">
                    {{ app.name }}
                  </td>
                  <td class="px-4 py-3 text-slate-600">
                    {{ app.namespace || 'default' }}
                  </td>
                  <td class="px-4 py-3">
                    <span
                      class="rounded-full px-2 py-1 text-xs font-medium"
                      :class="statusClass(app.status)"
                    >
                      {{ app.status }}
                    </span>
                  </td>
                  <td class="px-4 py-3 text-slate-600">
                    {{ toHumanMs(app.latencyMs) }}
                  </td>
                  <td class="px-4 py-3">
                    <button
                      class="text-sm text-emerald-600 hover:underline"
                      type="button"
                      @click="toggleExpanded(app.name)"
                    >
                      {{ isExpanded(app.name) ? 'Hide details' : 'Show details' }}
                    </button>
                  </td>
                </tr>
                <tr v-if="isExpanded(app.name)">
                  <td class="bg-slate-50 px-4 py-3 text-sm text-slate-600" colspan="5">
                    <p>
                      <span class="font-semibold text-slate-900">Namespace:</span>
                      {{ app.namespace || 'default' }}
                    </p>
                    <p class="mt-1">
                      <span class="font-semibold text-slate-900">Latency:</span>
                      {{ toHumanMs(app.latencyMs) }}
                    </p>
                    <p class="mt-1">
                      <span class="font-semibold text-slate-900">Message:</span>
                      {{ app.message || 'No message available' }}
                    </p>
                  </td>
                </tr>
              </template>
              <tr v-if="applications.length === 0">
                <td class="px-4 py-6 text-center text-slate-500" colspan="5">
                  No applications discovered yet.
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <article class="rounded-lg border border-slate-100 bg-slate-50 p-3">
            <p class="text-xs text-slate-500">Application count</p>
            <p class="mt-1 text-lg font-semibold text-slate-900">
              {{ applications.length }}
            </p>
          </article>
          <article class="rounded-lg border border-slate-100 bg-slate-50 p-3">
            <p class="text-xs text-slate-500">Topology edges</p>
            <p class="mt-1 text-lg font-semibold text-slate-900">
              {{ topologyEdges.length }}
            </p>
          </article>
          <article class="rounded-lg border border-slate-100 bg-slate-50 p-3">
            <p class="text-xs text-slate-500">Live node statuses</p>
            <div class="mt-1 flex flex-wrap gap-2">
              <span
                class="inline-flex rounded-full border px-2 py-1 text-xs"
                :class="getStatusBadgeClass(health.platform.status)"
              >
                Platform
              </span>
              <span
                class="inline-flex rounded-full border px-2 py-1 text-xs"
                :class="getStatusBadgeClass(health.cluster.status)"
              >
                Cluster
              </span>
              <span
                class="inline-flex rounded-full border px-2 py-1 text-xs"
                :class="getStatusBadgeClass(health.infrastructure.status)"
              >
                Infra
              </span>
            </div>
          </article>
        </div>
      </section>
    </div>
  </main>
</template>
