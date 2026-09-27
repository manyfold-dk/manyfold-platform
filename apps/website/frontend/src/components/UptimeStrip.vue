<script setup lang="ts">
import { computed } from 'vue'

// One segment per bucket, drawn with the page's own status tokens. Not an SVG:
// a row of flex children is simpler, stretches to any width, and needs no
// viewBox arithmetic to stay crisp.
//
// A bucket with no reading is grey, never red. Nothing was measured then, which
// is a gap in the monitoring rather than an outage, and colouring it as downtime
// would be a lie told in a very believable way.
const props = defineProps<{
  buckets: (number | null)[]
  ratio: number | null
  days: number
}>()

const FULL = 0.999
const MOSTLY = 0.95

function toneOf(value: number | null) {
  if (value === null) return 'bg-slate-200'
  if (value >= FULL) return 'bg-ok'
  if (value >= MOSTLY) return 'bg-warn'
  return 'bg-down'
}

const segments = computed(() =>
  props.buckets.map((value, index) => ({ key: index, tone: toneOf(value) }))
)

const measured = computed(() => props.buckets.filter((b) => b !== null).length)

const summary = computed(() => {
  if (props.ratio === null) return 'no readings yet'
  // One decimal, and never rounded up to a clean 100% from something less.
  const percent = Math.floor(props.ratio * 1000) / 10
  return `${percent}% up`
})

const label = computed(
  () =>
    `Availability over the last ${props.days} days: ${summary.value}, from ${measured.value} of ${props.buckets.length} periods measured`
)
</script>

<template>
  <div class="flex flex-col gap-2">
    <div class="flex items-end gap-[2px]" role="img" :aria-label="label">
      <span
        v-for="segment in segments"
        :key="segment.key"
        class="h-7 flex-1 rounded-[2px]"
        :class="segment.tone"
      />
    </div>
    <div class="flex justify-between font-mono text-[11px] text-slate-500">
      <span>{{ days }} days ago</span>
      <span>{{ summary }}</span>
      <span>now</span>
    </div>
  </div>
</template>
