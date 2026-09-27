<script setup lang="ts">
import { computed } from 'vue'

// A response-time line in the page's brand colour. The viewBox is a fixed grid
// that the SVG stretches to whatever width it is given, so the chart is
// responsive without measuring anything in JavaScript; non-scaling-stroke keeps
// the line an even weight despite that stretch.
//
// Gaps break the line rather than being interpolated across: a straight segment
// over missing data would invent readings that were never taken.
const props = defineProps<{
  points: (number | null)[]
  latestMs: number | null
  hours: number
}>()

const WIDTH = 100
const HEIGHT = 32
const PAD = 2

const bounds = computed(() => {
  const values = props.points.filter((p): p is number => p !== null)
  if (!values.length) return null
  const min = Math.min(...values)
  const max = Math.max(...values)
  // A flat line should sit in the middle rather than collapse onto an edge.
  return max === min ? { min: min - 1, max: max + 1 } : { min, max }
})

function x(index: number) {
  return (index / Math.max(props.points.length - 1, 1)) * WIDTH
}

function y(value: number) {
  const b = bounds.value
  if (!b) return HEIGHT / 2
  const ratio = (value - b.min) / (b.max - b.min)
  return HEIGHT - PAD - ratio * (HEIGHT - PAD * 2)
}

// Each run of consecutive readings becomes its own polyline.
const runs = computed(() => {
  const out: string[][] = []
  let run: string[] = []
  props.points.forEach((value, index) => {
    if (value === null) {
      if (run.length > 1) out.push(run)
      run = []
      return
    }
    run.push(`${x(index).toFixed(2)},${y(value).toFixed(2)}`)
  })
  if (run.length > 1) out.push(run)
  return out.map((r) => r.join(' '))
})

// One closed shape per run, so the fill breaks where the line breaks. A single
// path across every run would quietly bridge the gaps it is meant to show.
const areas = computed(() =>
  runs.value.map((run) => {
    const coords = run.split(' ')
    const startX = coords[0].split(',')[0]
    const endX = coords[coords.length - 1].split(',')[0]
    return `M ${startX},${HEIGHT} L ${coords.join(' L ')} L ${endX},${HEIGHT} Z`
  })
)

const peak = computed(() => (bounds.value ? Math.round(bounds.value.max) : null))
const trough = computed(() => (bounds.value ? Math.round(bounds.value.min) : null))

const label = computed(() => {
  if (!bounds.value) return `No response times recorded in the last ${props.hours} hours`
  return `Response time over the last ${props.hours} hours, between ${trough.value} and ${peak.value} milliseconds`
})
</script>

<template>
  <div class="flex flex-col gap-2">
    <svg
      :viewBox="`0 0 ${WIDTH} ${HEIGHT}`"
      preserveAspectRatio="none"
      class="h-10 w-full text-brand"
      role="img"
      :aria-label="label"
    >
      <path
        v-for="(shape, index) in areas"
        :key="`area-${index}`"
        :d="shape"
        class="fill-brand opacity-10"
      />
      <polyline
        v-for="(run, index) in runs"
        :key="index"
        :points="run"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linejoin="round"
        stroke-linecap="round"
        vector-effect="non-scaling-stroke"
      />
    </svg>
    <div class="flex justify-between font-mono text-[11px] text-slate-500">
      <span>{{ hours }}h ago</span>
      <span v-if="peak !== null">peak {{ peak }} ms</span>
      <span v-else>no readings</span>
      <span>now</span>
    </div>
  </div>
</template>
