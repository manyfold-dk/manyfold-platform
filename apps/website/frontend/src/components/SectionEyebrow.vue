<script setup lang="ts">
withDefaults(defineProps<{ tone?: 'muted' | 'brand' | 'onDeep' }>(), { tone: 'muted' })
</script>

<template>
  <div
    class="font-mono text-xs font-medium tracking-[0.08em] uppercase"
    :class="{
      'text-slate-500': tone === 'muted',
      'text-brand': tone === 'brand',
      'text-blue-200': tone === 'onDeep'
    }"
  >
    <slot />
  </div>
</template>
