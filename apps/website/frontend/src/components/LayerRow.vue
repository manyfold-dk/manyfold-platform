<script setup lang="ts">
// One row of the platform picture, shared by /stack and /status so the two pages
// cannot drift apart. A row is either a white service card (no `bar`) or one of
// the four coloured stack layers. The optional `status` slot carries a StatusChip.
withDefaults(
  defineProps<{
    name: string
    detail: string
    bar?: string
    text?: string
    sub?: string
  }>(),
  { bar: '', text: 'text-slate-900', sub: 'text-slate-700' }
)
</script>

<template>
  <div
    class="flex flex-col gap-2 rounded-lg px-6 py-4 sm:flex-row sm:items-center sm:justify-between sm:gap-6"
    :class="bar || 'border border-slate-200 bg-white'"
  >
    <span class="font-semibold" :class="text">{{ name }}</span>
    <span class="flex flex-wrap items-center gap-3 sm:justify-end">
      <span class="font-mono text-[13px] sm:text-right" :class="sub">{{ detail }}</span>
      <slot name="status" />
    </span>
  </div>
</template>
