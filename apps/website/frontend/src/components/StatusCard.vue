<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { statusTone, usePublicStatus } from '@/composables/usePublicStatus'

// Live data only. The detail text per layer is a fixed description of what runs
// there; the dot and the headline come from the public health endpoints.
const { status, summary, failed } = usePublicStatus()

const layers = computed(() => [
  { name: 'Infrastructure', detail: 'Hetzner, Helsinki', value: summary.value?.infrastructure },
  { name: 'Cluster', detail: 'Kubernetes · Talos Linux', value: summary.value?.cluster },
  { name: 'Platform', detail: 'Argo CD · observability', value: summary.value?.platform },
  { name: 'Pipelines', detail: 'build and deploy', value: summary.value?.pipelines },
  { name: 'Applications', detail: 'tenant workloads', value: summary.value?.applications }
])

const headline = computed(() => {
  if (failed.value) return 'Status unavailable right now'
  if (!status.value) return 'Checking…'
  const tone = statusTone(status.value.status)
  if (tone === 'ok') return 'All systems operational'
  if (tone === 'warn') return 'Degraded performance'
  return 'Service disruption'
})
const headlineClass = computed(() => {
  if (failed.value || !status.value) return 'text-slate-500'
  return { ok: 'text-ok', warn: 'text-warn', down: 'text-down', unknown: 'text-slate-500' }[
    statusTone(status.value.status)
  ]
})
function dot(value: string | undefined) {
  return { ok: 'bg-ok', warn: 'bg-warn', down: 'bg-down', unknown: 'bg-slate-300' }[
    statusTone(value)
  ]
}
</script>

<template>
  <div class="flex w-full flex-col rounded-xl border border-slate-200 bg-white px-6 pt-6 pb-2.5">
    <div class="flex items-center justify-between pb-4">
      <div class="font-mono text-xs font-medium tracking-[0.08em] text-slate-500 uppercase">
        Live from the platform
      </div>
      <RouterLink to="/status" class="text-[13px] font-medium text-brand hover:text-brand-deep">
        Status page
      </RouterLink>
    </div>
    <div class="pb-4 text-xl font-semibold" :class="headlineClass" role="status">
      {{ headline }}
    </div>
    <div
      v-for="layer in layers"
      :key="layer.name"
      class="flex items-center justify-between gap-4 border-t border-slate-200 py-3.5"
    >
      <span class="text-[15px] font-medium text-slate-900">{{ layer.name }}</span>
      <span class="ml-auto text-right font-mono text-[13px] text-slate-500">{{
        layer.detail
      }}</span>
      <span
        class="h-2 w-2 shrink-0 rounded-full"
        :class="dot(layer.value)"
        :title="layer.value ?? 'unknown'"
      />
    </div>
  </div>
</template>
