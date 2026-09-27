<script setup lang="ts">
// The Manyfold mark: the four bottom layers of the platform stack
// (tenants, network, cluster, infrastructure), light to deep.
withDefaults(defineProps<{ size?: number }>(), { size: 28 })
</script>

<template>
  <svg viewBox="0 0 40 40" :width="size" :height="size" aria-hidden="true">
    <rect x="0" y="0" width="40" height="7" rx="2" fill="#93C5FD" />
    <rect x="0" y="11" width="40" height="7" rx="2" fill="#60A5FA" />
    <rect x="0" y="22" width="40" height="7" rx="2" fill="#2563EB" />
    <rect x="0" y="33" width="40" height="7" rx="2" fill="#1E40AF" />
  </svg>
</template>
