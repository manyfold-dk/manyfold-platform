<script setup lang="ts">
import { computed } from 'vue'
import { statusTone } from '@/composables/usePublicStatus'

// A status is never carried by colour alone: the chip always spells the state out.
// White pill with a ring so it reads on both the white service rows and the
// coloured stack bars.
const props = withDefaults(defineProps<{ value?: string | null; unmeasured?: string }>(), {
  value: null,
  unmeasured: 'not measured'
})

const tone = computed(() => statusTone(props.value))
const label = computed(() => {
  const value = props.value?.toLowerCase()
  // The API's "unknown" means what no value at all means: nothing measured this.
  // Say that in words rather than passing the enum through to a visitor.
  return !value || value === 'unknown' ? props.unmeasured : value
})
const dotClass = computed(
  () => ({ ok: 'bg-ok', warn: 'bg-warn', down: 'bg-down', unknown: 'bg-slate-400' })[tone.value]
)
const textClass = computed(
  () =>
    ({ ok: 'text-ok', warn: 'text-warn', down: 'text-down', unknown: 'text-slate-500' })[tone.value]
)
</script>

<template>
  <span
    class="inline-flex shrink-0 items-center gap-2 rounded-full bg-white px-3 py-1 font-mono text-xs font-medium ring-1 ring-slate-200"
  >
    <span class="h-2 w-2 shrink-0 rounded-full" :class="dotClass" />
    <span :class="textClass">{{ label }}</span>
  </span>
</template>
