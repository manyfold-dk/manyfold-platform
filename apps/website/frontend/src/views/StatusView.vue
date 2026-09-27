<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import LayerRow from '@/components/LayerRow.vue'
import SectionEyebrow from '@/components/SectionEyebrow.vue'
import Sparkline from '@/components/Sparkline.vue'
import StatusChip from '@/components/StatusChip.vue'
import UptimeStrip from '@/components/UptimeStrip.vue'
import { statusTone } from '@/composables/usePublicStatus'
import { fetchHealthSummary, fetchStatus, fetchStatusHistory } from '@/services/api'
import type { HealthSummary, StatusHistory, StatusResponse } from '@/types'

const healthSummary = ref<HealthSummary | null>(null)
const statusResponse = ref<StatusResponse | null>(null)
const history = ref<StatusHistory | null>(null)
// The series behind the charts move every two to five minutes and the backend
// caches them for two, so re-fetching them on every 30 second poll would be
// bytes spent on a picture that cannot have changed.
const historyIntervalMs = 120000
const historyFetchedAt = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
const lastUpdated = ref<string | null>(null)
const autoRefreshMs = 30000
const autoRefreshEnabled = ref(true)
type TimerHandle = ReturnType<typeof globalThis.setInterval>

const refreshTimer = ref<TimerHandle | null>(null)

const serviceChecks = computed(() => statusResponse.value?.services ?? [])
const statusTimestamp = computed(() => {
  const values = [statusResponse.value?.timestamp, healthSummary.value?.timestamp]
  const sorted = values
    .filter(Boolean)
    .map((v) => new Date(v as string).getTime())
    .filter(Number.isFinite)
    .sort((a, b) => b - a)
  if (!sorted.length) return null
  return new Date(sorted[0])
})

const stale = computed(() => {
  if (!statusTimestamp.value) return false
  return Date.now() - statusTimestamp.value.getTime() > 60000
})

// The picture is the one on /stack, and every row now carries its own signal:
// /api/v1/health/summary reports one status per stack layer and one per platform
// service. Edge is the odd one out and the most valuable -- it is a probe run
// from Cloudflare's network against the public site, so it is the only row here
// measured from outside the platform looking in.
const services = computed(() => [
  {
    name: 'Edge',
    detail: 'Cloudflare (EU) DNS with a failover zone',
    value: healthSummary.value?.edge ?? null
  },
  {
    name: 'Identity and secrets',
    detail: 'Keycloak · OpenBao',
    value: healthSummary.value?.identity ?? null
  },
  {
    name: 'Delivery',
    detail: 'Argo CD GitOps · Tekton · GitHub Actions',
    value: healthSummary.value?.delivery ?? null
  },
  {
    name: 'Observability',
    detail: 'Prometheus · Loki · Tempo · Grafana',
    value: healthSummary.value?.observability ?? null
  }
])

const stack = computed(() => [
  {
    name: 'Tenants',
    detail: 'Workloads running on the platform',
    value: healthSummary.value?.applications ?? null,
    bar: 'bg-brand-pale',
    text: 'text-slate-900',
    sub: 'text-slate-900'
  },
  {
    name: 'Network',
    detail: 'Cilium · Hubble',
    value: healthSummary.value?.network ?? null,
    bar: 'bg-brand-sky',
    text: 'text-slate-900',
    sub: 'text-slate-900'
  },
  {
    name: 'Cluster',
    detail: 'Kubernetes on Talos Linux',
    value: healthSummary.value?.cluster ?? null,
    bar: 'bg-brand',
    text: 'text-white',
    sub: 'text-blue-100'
  },
  {
    name: 'Infrastructure',
    detail: 'Hetzner Cloud, Helsinki',
    value: healthSummary.value?.infrastructure ?? null,
    bar: 'bg-brand-deep',
    text: 'text-white',
    sub: 'text-blue-100'
  }
])

const overall = computed(() => healthSummary.value?.overall ?? statusResponse.value?.status ?? null)
const headline = computed(() => {
  if (error.value) return 'Status unavailable right now'
  if (!overall.value) return 'Checking…'
  const tone = statusTone(overall.value)
  if (tone === 'ok') return 'All systems operational'
  if (tone === 'warn') return 'Degraded performance'
  return 'Service disruption'
})
const headlineClass = computed(() => {
  if (error.value || !overall.value) return 'text-slate-500'
  return { ok: 'text-ok', warn: 'text-warn', down: 'text-down', unknown: 'text-slate-500' }[
    statusTone(overall.value)
  ]
})

// Only alerts at warning or critical reach this count; the permanently firing
// heartbeat and the inhibited info alerts are not news and are not counted.
const alertLabel = computed(() => {
  const count = healthSummary.value?.activeAlerts ?? 0
  return count === 1 ? '1 alert needs attention' : `${count} alerts need attention`
})

// Some checks have no duration to report -- a certificate expiry is a date, not
// a round trip -- so the line is left out rather than filled with a placeholder.
function formatLatency(ms: number | null | undefined) {
  if (ms === null || ms === undefined || ms < 0) return null
  return `${ms} ms`
}

// The charts are a nice-to-have: a failure here leaves the rest of the page
// working and simply draws nothing, rather than reporting the platform as down.
async function refreshHistory(force = false) {
  if (!force && Date.now() - historyFetchedAt.value < historyIntervalMs) return
  try {
    history.value = await fetchStatusHistory()
    historyFetchedAt.value = Date.now()
  } catch {
    history.value = null
  }
}

async function refreshStatus() {
  if (loading.value) return
  loading.value = true
  error.value = null

  try {
    const [health, status] = await Promise.all([fetchHealthSummary(), fetchStatus()])
    healthSummary.value = health
    statusResponse.value = status
    lastUpdated.value = new Date().toLocaleString()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load status'
  } finally {
    loading.value = false
  }

  void refreshHistory()
}

function setupRefresh() {
  if (refreshTimer.value) return
  refreshTimer.value = globalThis.setInterval(() => {
    if (autoRefreshEnabled.value) {
      void refreshStatus()
    }
  }, autoRefreshMs)
}

function toggleAutoRefresh(enabled: boolean) {
  autoRefreshEnabled.value = enabled
  if (!enabled && refreshTimer.value) {
    globalThis.clearInterval(refreshTimer.value)
    refreshTimer.value = null
    return
  }

  if (enabled) {
    setupRefresh()
  }
}

onMounted(() => {
  void refreshStatus()
  void refreshHistory(true)
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
  <main>
    <section
      class="mx-auto flex max-w-[1200px] flex-col gap-12 px-5 py-14 sm:px-8 lg:flex-row lg:items-start lg:gap-20 lg:py-24"
    >
      <div class="flex shrink-0 flex-col gap-6 lg:w-[480px]">
        <SectionEyebrow tone="brand"> Status </SectionEyebrow>
        <h1
          class="text-4xl leading-tight font-semibold tracking-[-0.03em] text-balance text-slate-900 lg:text-5xl lg:leading-[1.2]"
        >
          The platform, layer by layer
        </h1>
        <p class="text-2xl font-semibold" :class="headlineClass" role="status">
          {{ headline }}
        </p>
        <p class="text-lg leading-relaxed text-slate-700">
          The same picture the
          <RouterLink to="/stack" class="text-brand hover:text-brand-deep">
            platform page
          </RouterLink>
          shows, with a live signal on every row. Checked every 30 seconds.
        </p>

        <div
          v-if="error"
          class="rounded-lg border border-slate-200 bg-white px-5 py-4 text-[15px] leading-relaxed text-slate-700"
        >
          The health API did not answer, so nothing below is current. That is a fault in the status
          reporting, which may or may not mean the platform itself is unwell.
        </div>

        <div class="flex flex-wrap items-center gap-4 text-sm text-slate-500">
          <span
            v-if="stale"
            class="inline-flex items-center gap-2 rounded-full bg-white px-3 py-1 font-mono text-xs font-medium text-warn ring-1 ring-slate-200"
          >
            <span class="h-2 w-2 rounded-full bg-warn" />Stale data
          </span>
          <span v-if="lastUpdated">Updated {{ lastUpdated }}</span>
          <span v-if="healthSummary?.activeAlerts" class="font-mono text-xs">{{ alertLabel }}</span>
        </div>

        <div class="flex flex-wrap items-center gap-5">
          <button
            type="button"
            :disabled="loading"
            class="rounded-lg bg-brand px-5 py-3 text-[15px] font-semibold text-white transition-colors hover:bg-brand-deep disabled:opacity-50"
            @click="refreshStatus"
          >
            {{ loading ? 'Checking…' : 'Refresh' }}
          </button>
          <label class="flex items-center gap-2 text-[15px] text-slate-700">
            <input
              v-model="autoRefreshEnabled"
              type="checkbox"
              class="h-4 w-4 rounded border-slate-300"
              @change="
                (event: Event) => {
                  toggleAutoRefresh((event.target as HTMLInputElement).checked)
                }
              "
            />
            Auto-refresh
          </label>
        </div>
      </div>

      <div class="flex grow flex-col gap-2">
        <!-- A labelled region so the picture is one thing to a screen reader, and
             so the footnote below it is not part of what the rows report. -->
        <section class="flex flex-col gap-2" aria-label="Live status by layer">
          <SectionEyebrow class="pt-2 pb-1"> Platform services · run on the stack </SectionEyebrow>
          <LayerRow v-for="row in services" :key="row.name" :name="row.name" :detail="row.detail">
            <template #status>
              <StatusChip :value="error ? null : row.value" />
            </template>
          </LayerRow>

          <SectionEyebrow class="pt-5 pb-1">
            The stack · the four layers in the logo
          </SectionEyebrow>
          <LayerRow
            v-for="row in stack"
            :key="row.name"
            :name="row.name"
            :detail="row.detail"
            :bar="row.bar"
            :text="row.text"
            :sub="row.sub"
          >
            <template #status>
              <StatusChip :value="error ? null : row.value" />
            </template>
          </LayerRow>
        </section>

        <p class="pt-4 text-[13px] leading-relaxed text-slate-500">
          Every row is measured on its own. Edge is a probe run from Cloudflare's network against
          this site; the rest are checked from inside the cluster. A row reads
          <span class="font-mono">not measured</span> when its check produced no reading, which is a
          gap in the monitoring rather than a fault on the platform.
        </p>
      </div>
    </section>

    <!-- Two independent sources: the live checks and the recorded series. Either
         one is worth showing on its own, so neither hides the other. -->
    <section
      v-if="serviceChecks.length || history?.checks?.length"
      class="border-y border-slate-200 bg-white"
    >
      <div class="mx-auto flex max-w-[1200px] flex-col gap-8 px-5 py-14 sm:px-8 lg:py-20">
        <div class="flex flex-col gap-3">
          <SectionEyebrow>Checks</SectionEyebrow>
          <h2
            class="text-3xl leading-tight font-semibold tracking-[-0.02em] text-slate-900 lg:text-4xl"
          >
            What answered, and how fast
          </h2>
        </div>
        <div v-if="serviceChecks.length" class="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          <article
            v-for="service in serviceChecks"
            :key="service.name"
            class="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-6"
          >
            <span class="text-lg font-semibold text-slate-900">{{ service.name }}</span>
            <StatusChip :value="service.status" class="self-start" />
            <span
              v-if="formatLatency(service.latencyMs)"
              class="font-mono text-[13px] text-slate-500"
              >{{ formatLatency(service.latencyMs) }}</span
            >
            <span v-if="service.detail" class="text-[13px] leading-relaxed text-slate-500">{{
              service.detail
            }}</span>
          </article>
        </div>

        <div v-if="history?.checks?.length" class="grid gap-6 md:grid-cols-2">
          <article
            v-for="check in history.checks"
            :key="check.name"
            class="flex flex-col gap-6 rounded-lg border border-slate-200 bg-white p-6"
          >
            <span class="text-lg font-semibold text-slate-900">{{ check.name }}</span>

            <div class="flex flex-col gap-2">
              <SectionEyebrow>Answered · {{ check.uptime.days }} days</SectionEyebrow>
              <UptimeStrip
                :buckets="check.uptime.buckets"
                :ratio="check.uptime.ratio"
                :days="check.uptime.days"
              />
            </div>

            <div class="flex flex-col gap-2">
              <SectionEyebrow>How fast · {{ check.latency.hours }} hours</SectionEyebrow>
              <Sparkline
                :points="check.latency.points"
                :latest-ms="check.latency.latestMs"
                :hours="check.latency.hours"
              />
            </div>
          </article>
        </div>

        <p v-if="history?.checks?.length" class="text-[13px] leading-relaxed text-slate-500">
          Drawn from the synthetic probes, not from this page: the bars are one per
          {{ history.checks[0].uptime.bucketHours }} hours and a grey one means nothing was recorded
          in that period rather than that the site was down. The series are read from Prometheus on
          a timer and served from a cache, so looking at this page does not put load on the
          monitoring.
        </p>
      </div>
    </section>
  </main>
</template>
