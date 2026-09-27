<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchRecentVitals } from '@/services/api'
import { clearLocalVitals, localVitalHistory } from '@/services/webVitals'
import type { WebVitalEntry } from '@/types'

const WEB_VITAL_LABELS: Record<string, string> = {
  CLS: 'Cumulative Layout Shift',
  FID: 'First Input Delay',
  INP: 'Interaction to Next Paint',
  LCP: 'Largest Contentful Paint',
  FCP: 'First Contentful Paint',
  TTFB: 'Time to First Byte'
}

const remoteSamples = ref<WebVitalEntry[] | null>(null)
const remoteError = ref<string | null>(null)
const remoteLoading = ref(false)
const useBackendVitals = ref(true)
const backendLimit = 100

const samples = computed(() => {
  if (useBackendVitals.value && remoteSamples.value && remoteSamples.value.length > 0) {
    return remoteSamples.value
  }
  return localVitalHistory.value
})

const dataSource = computed(() => {
  if (useBackendVitals.value && remoteSamples.value && remoteSamples.value.length > 0) {
    return 'backend'
  }
  return 'local'
})

const hasLocalFallback = computed(
  () => remoteSamples.value !== null && remoteSamples.value.length === 0
)

async function loadBackendVitals() {
  if (!useBackendVitals.value) {
    remoteSamples.value = null
    return
  }

  remoteLoading.value = true
  remoteError.value = null

  try {
    remoteSamples.value = await fetchRecentVitals(backendLimit)
  } catch (error) {
    remoteError.value = error instanceof Error ? error.message : 'Unable to load persisted vitals'
    remoteSamples.value = []
  } finally {
    remoteLoading.value = false
  }
}

function onBackendToggle(enabled: boolean) {
  useBackendVitals.value = enabled
  void loadBackendVitals()
}

const metricsByType = computed(() => {
  const buckets = new Map<string, WebVitalEntry[]>()
  samples.value.forEach((entry) => {
    const list = buckets.get(entry.name) ?? []
    list.push(entry)
    buckets.set(entry.name, list)
  })
  return Array.from(buckets.entries())
    .map(([name, entries]) => {
      const good = entries.filter((entry) => entry.rating === 'good').length
      const poor = entries.filter((entry) => entry.rating === 'poor').length
      const total = entries.length
      const latest = entries[0]
      const average = entries.reduce((sum, e) => sum + e.value, 0) / entries.length
      return {
        name,
        total,
        good,
        poor,
        average,
        latest,
        entries
      }
    })
    .sort((a, b) => a.name.localeCompare(b.name))
})

const routeDistribution = computed(() => {
  const routes = new Map<string, number>()
  samples.value.forEach((entry) => {
    const route = entry.route || 'unknown'
    routes.set(route, (routes.get(route) ?? 0) + 1)
  })
  return Array.from(routes.entries())
    .map(([route, count]) => ({ route, count }))
    .sort((a, b) => b.count - a.count)
})

const recentEntries = computed(() => samples.value.slice(0, 24))
const goodCount = computed(() => samples.value.filter((entry) => entry.rating === 'good').length)
const poorCount = computed(() => samples.value.filter((entry) => entry.rating === 'poor').length)
const needsImprovement = computed(
  () => samples.value.filter((entry) => entry.rating === 'needs-improvement').length
)
const hasSamples = computed(() => samples.value.length > 0)
const metricHint = computed(() =>
  Object.entries(WEB_VITAL_LABELS)
    .map(([abbr, full]) => `${abbr} — ${full}`)
    .join('  ')
)

function metricLabel(name: string): string {
  return WEB_VITAL_LABELS[name] ?? 'Web Vital metric'
}

onMounted(() => {
  void loadBackendVitals()
})
</script>

<template>
  <main class="min-h-screen bg-slate-50 py-12">
    <div class="mx-auto max-w-6xl px-6">
      <div class="mb-8 flex items-center justify-between gap-4">
        <div>
          <h1 class="text-3xl font-bold tracking-tight text-slate-900">
            Frontend Performance Playground
          </h1>
          <p class="mt-2 text-sm text-slate-500">
            Hybrid explorer for client-side and server-persisted Web Vitals.
          </p>
        </div>
        <label class="flex items-center gap-2 text-sm text-slate-600">
          <input
            :checked="useBackendVitals"
            type="checkbox"
            @change="(event: Event) => onBackendToggle((event.target as HTMLInputElement).checked)"
          />
          Prefer backend samples
        </label>
      </div>

      <section class="mb-4 rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-600">
        <p>
          Data source:
          <span class="font-medium text-slate-900"
            >{{ dataSource }} ({{ samples.length }} samples)</span
          >
        </p>
        <p v-if="remoteLoading" class="mt-1 text-amber-600">Loading backend samples...</p>
        <p v-if="remoteError" class="mt-1 text-red-600">
          {{ remoteError }}.
          {{ hasLocalFallback ? 'Using local session samples.' : 'Showing local cache only.' }}
        </p>
        <p
          v-if="!remoteError && useBackendVitals && remoteSamples && remoteSamples.length === 0"
          class="mt-1 text-slate-500"
        >
          Backend returned no samples yet. Using local session samples.
        </p>
      </section>

      <div class="flex gap-3">
        <button
          class="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700"
          @click="clearLocalVitals"
        >
          Clear samples
        </button>
        <button
          v-if="useBackendVitals"
          :disabled="remoteLoading"
          class="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-900 transition-colors hover:bg-slate-100 disabled:opacity-50"
          @click="loadBackendVitals"
        >
          {{ remoteLoading ? 'Refreshing backend samples' : 'Refresh backend samples' }}
        </button>
      </div>

      <section class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <article class="rounded-xl border border-slate-200 bg-white p-4">
          <p class="text-xs text-slate-500">Total samples</p>
          <p class="mt-2 text-3xl font-semibold text-slate-900">
            {{ samples.length }}
          </p>
        </article>
        <article class="rounded-xl border border-slate-200 bg-white p-4">
          <p class="text-xs text-slate-500">Good rating</p>
          <p class="mt-2 text-3xl font-semibold text-emerald-600">
            {{ goodCount }}
          </p>
        </article>
        <article class="rounded-xl border border-slate-200 bg-white p-4">
          <p class="text-xs text-slate-500">Needs improvement</p>
          <p class="mt-2 text-3xl font-semibold text-amber-600">
            {{ needsImprovement }}
          </p>
        </article>
        <article class="rounded-xl border border-slate-200 bg-white p-4">
          <p class="text-xs text-slate-500">Poor rating</p>
          <p class="mt-2 text-3xl font-semibold text-red-600">
            {{ poorCount }}
          </p>
        </article>
      </section>

      <section class="mt-8 rounded-xl border border-slate-200 bg-white p-6">
        <h2 class="text-lg font-semibold text-slate-900">Metrics by name</h2>
        <p
          v-if="hasSamples"
          class="mt-2 text-xs text-slate-500"
          title="Web Vital acronyms explained"
        >
          {{ metricHint }}
        </p>
        <p v-else class="mt-2 text-xs text-slate-500">Waiting for metrics to explain acronyms.</p>
        <div class="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <article
            v-for="metric in metricsByType"
            :key="metric.name"
            class="rounded-lg border border-slate-100 p-4"
          >
            <p class="text-sm font-semibold text-slate-900">
              {{ metric.name }} — {{ metricLabel(metric.name) }}
            </p>
            <p class="mt-2 text-xs text-slate-500">
              Last {{ metric.latest.name }}: {{ metric.latest.value.toFixed(2) }} ({{
                metric.latest.rating
              }})
            </p>
            <p class="mt-1 text-xs text-slate-500">Avg: {{ metric.average.toFixed(2) }}</p>
            <p class="mt-2 text-xs text-slate-500">
              good {{ metric.good }} / poor {{ metric.poor }} / total {{ metric.total }}
            </p>
          </article>
        </div>
      </section>

      <section class="mt-8 rounded-xl border border-slate-200 bg-white p-6">
        <h2 class="text-lg font-semibold text-slate-900">Route distribution</h2>
        <div class="mt-4">
          <p
            v-for="entry in routeDistribution"
            :key="entry.route"
            class="mb-2 flex justify-between text-sm text-slate-600"
          >
            <span>{{ entry.route }}</span>
            <span>{{ entry.count }}</span>
          </p>
          <p v-if="routeDistribution.length === 0" class="text-sm text-slate-500">
            No route data yet.
          </p>
        </div>
      </section>

      <section class="mt-8 rounded-xl border border-slate-200 bg-white p-6">
        <h2 class="text-lg font-semibold text-slate-900">Recent samples</h2>
        <div class="mt-4 overflow-x-auto">
          <table class="w-full text-left text-sm">
            <thead class="text-slate-600">
              <tr>
                <th class="py-2 pr-4">Time</th>
                <th class="py-2 pr-4">Metric</th>
                <th class="py-2 pr-4">Value</th>
                <th class="py-2 pr-4">Rating</th>
                <th class="py-2 pr-4">Route</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100 text-slate-700">
              <tr v-for="entry in recentEntries" :key="entry.id">
                <td class="py-2 pr-4 text-slate-500">
                  {{ new Date(entry.timestamp).toLocaleTimeString() }}
                </td>
                <td class="py-2 pr-4">
                  {{ entry.name }}
                </td>
                <td class="py-2 pr-4">
                  {{ entry.value.toFixed(2) }}
                </td>
                <td class="py-2 pr-4">
                  {{ entry.rating }}
                </td>
                <td class="py-2 pr-4">
                  {{ entry.route || '/' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </main>
</template>
