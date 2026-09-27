<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchDeepHealth } from '@/services/api'
import type { DeepHealthResponse } from '@/types'

const deepHealth = ref<DeepHealthResponse | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const autoRefreshMs = 20000
const autoRefreshEnabled = ref(true)
type TimerHandle = ReturnType<typeof globalThis.setInterval>

const refreshTimer = ref<TimerHandle | null>(null)
const selfResources = computed(() => deepHealth.value?.self.resources)

const isStale = computed(() => {
  if (!deepHealth.value) return false
  const ageMs = Date.now() - new Date(deepHealth.value.timestamp).getTime()
  return ageMs > 90000
})

const statusClass = (status: string) => {
  if (status === 'healthy') return 'bg-emerald-100 text-emerald-800'
  if (status === 'degraded') return 'bg-amber-100 text-amber-800'
  return 'bg-red-100 text-red-800'
}

const statusBadgeClass = (status: string) => {
  if (status === 'healthy') return 'bg-emerald-100 text-emerald-700'
  if (status === 'degraded') return 'bg-amber-100 text-amber-700'
  return 'bg-red-100 text-red-700'
}

function formatBytes(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  if (!bytes || bytes <= 0) return '0 B'
  const power = Math.floor(Math.log(bytes) / Math.log(1024))
  const value = bytes / Math.pow(1024, power)
  return `${value.toFixed(value > 10 ? 1 : 2)} ${units[power]}`
}

function formatBytesOrUnknown(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) {
    return 'N/A'
  }
  return formatBytes(bytes)
}

function formatDuration(seconds: number): string {
  if (!seconds) return '0s'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) return `${h}h ${m}m ${s}s`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

async function refreshDeepHealth() {
  if (loading.value) return
  loading.value = true
  error.value = null

  try {
    deepHealth.value = await fetchDeepHealth()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load deep health'
  } finally {
    loading.value = false
  }
}

function setupRefresh() {
  if (refreshTimer.value) return
  refreshTimer.value = globalThis.setInterval(() => {
    if (autoRefreshEnabled.value) {
      void refreshDeepHealth()
    }
  }, autoRefreshMs)
}

onMounted(() => {
  void refreshDeepHealth()
  setupRefresh()
})

onBeforeUnmount(() => {
  if (refreshTimer.value) {
    globalThis.clearInterval(refreshTimer.value)
    refreshTimer.value = null
  }
})
</script>

<template>
  <main class="min-h-screen bg-slate-50 py-12">
    <div class="mx-auto max-w-6xl px-6">
      <div class="mb-8 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 class="text-3xl font-bold tracking-tight text-slate-900">Deep Health Explorer</h1>
          <p class="mt-1 text-sm text-slate-500">
            Nested service health including JVM + dependencies.
          </p>
        </div>
        <div class="flex flex-wrap items-center gap-3">
          <label class="flex items-center gap-2 text-sm text-slate-600">
            <input v-model="autoRefreshEnabled" type="checkbox" />
            Auto-refresh
          </label>
          <button
            :disabled="loading"
            class="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 disabled:opacity-50"
            @click="refreshDeepHealth"
          >
            {{ loading ? 'Loading...' : 'Refresh' }}
          </button>
        </div>
      </div>

      <div v-if="error" class="mb-6 rounded-xl border border-red-200 bg-red-50 p-4 text-red-700">
        {{ error }}
      </div>

      <section v-if="deepHealth" class="grid gap-6">
        <div class="rounded-xl border border-slate-200 bg-white p-6">
          <div class="flex items-center gap-3">
            <span
              class="inline-flex rounded-full px-3 py-1 text-sm font-semibold"
              :class="statusClass(deepHealth.status)"
            >
              {{ deepHealth.status }}
            </span>
            <h2 class="text-xl font-semibold text-slate-900">
              {{ deepHealth.service }} ({{ deepHealth.version }})
            </h2>
            <span
              class="ml-auto rounded-full px-3 py-1 text-xs font-semibold"
              :class="isStale ? 'bg-red-100 text-red-700' : 'bg-slate-100 text-slate-700'"
            >
              {{ isStale ? 'STALE' : 'LIVE' }}
            </span>
          </div>
          <p class="mt-3 text-sm text-slate-500">
            Last checked {{ new Date(deepHealth.timestamp).toLocaleString() }}
          </p>

          <div class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <article class="rounded-lg border border-slate-100 p-4">
              <p class="text-xs text-slate-500">JVM Status</p>
              <p
                class="mt-2 inline-flex rounded-full px-2 py-1 text-sm font-medium"
                :class="statusBadgeClass(deepHealth.self.status)"
              >
                {{ deepHealth.self.status }}
              </p>
              <p class="mt-3 text-sm text-slate-700">
                Uptime: {{ formatDuration(deepHealth.self.uptimeSeconds) }}
              </p>
            </article>
            <article class="rounded-lg border border-slate-100 p-4">
              <p class="text-xs text-slate-500">CPU</p>
              <p class="mt-2 text-lg font-semibold text-slate-900">
                {{ selfResources?.cpuPercent?.toFixed(1) ?? 'N/A' }}%
              </p>
            </article>
            <article class="rounded-lg border border-slate-100 p-4">
              <p class="text-xs text-slate-500">Threads</p>
              <p class="mt-2 text-lg font-semibold text-slate-900">
                {{ selfResources?.activeThreads ?? 'N/A' }}
              </p>
            </article>
            <article class="rounded-lg border border-slate-100 p-4">
              <p class="text-xs text-slate-500">Heap Used</p>
              <p class="mt-2 text-lg font-semibold text-slate-900">
                {{ formatBytesOrUnknown(selfResources?.memoryUsedBytes) }}
              </p>
            </article>
            <article class="rounded-lg border border-slate-100 p-4">
              <p class="text-xs text-slate-500">Heap Max</p>
              <p class="mt-2 text-lg font-semibold text-slate-900">
                {{ formatBytesOrUnknown(selfResources?.memoryMaxBytes) }}
              </p>
            </article>
          </div>
        </div>

        <div class="rounded-xl border border-slate-200 bg-white p-6">
          <h3 class="text-lg font-semibold text-slate-900">Dependencies</h3>
          <div class="mt-4 grid gap-3">
            <article
              v-for="dependency in deepHealth.dependencies"
              :key="dependency.name"
              class="rounded-lg border border-slate-100 p-4"
            >
              <div class="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <p class="text-sm font-semibold text-slate-900">
                    {{ dependency.name }}
                  </p>
                  <p class="mt-1 text-xs text-slate-500">
                    {{ dependency.type }} · {{ dependency.latencyMs }}ms
                  </p>
                </div>
                <span
                  class="rounded-full px-2 py-1 text-xs font-medium"
                  :class="statusBadgeClass(dependency.status)"
                >
                  {{ dependency.status }}
                </span>
              </div>
              <p class="mt-2 text-sm text-slate-600">
                {{ dependency.message || 'No dependency notes' }}
              </p>
            </article>
            <p
              v-if="deepHealth.dependencies.length === 0"
              class="rounded-lg border border-slate-100 bg-slate-50 p-4 text-sm text-slate-500"
            >
              No dependencies currently defined.
            </p>
          </div>
        </div>

        <div class="rounded-xl border border-slate-200 bg-white p-6">
          <h3 class="text-lg font-semibold text-slate-900">Runtime metadata</h3>
          <div class="mt-4 grid gap-2">
            <p
              v-for="[key, value] in Object.entries(deepHealth.metadata)"
              :key="key"
              class="text-sm text-slate-600"
            >
              <span class="font-semibold text-slate-900">{{ key }}</span
              >:
              {{ value }}
            </p>
          </div>
        </div>

        <div class="rounded-xl border border-slate-200 bg-white p-6">
          <h3 class="text-lg font-semibold text-slate-900">Self metrics</h3>
          <div class="mt-4 grid gap-2">
            <p
              v-for="[key, value] in Object.entries(deepHealth.self.metrics)"
              :key="key"
              class="text-sm text-slate-600"
            >
              <span class="font-semibold text-slate-900">{{ key }}</span
              >:
              {{ value }}
            </p>
            <p
              v-if="Object.keys(deepHealth.self.metrics).length === 0"
              class="text-sm text-slate-500"
            >
              No custom self metrics exposed.
            </p>
          </div>
        </div>
      </section>
    </div>
  </main>
</template>
