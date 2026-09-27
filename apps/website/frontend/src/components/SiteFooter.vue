<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <footer class="mt-auto border-t border-slate-200 bg-white">
    <div
      class="mx-auto flex max-w-[1200px] flex-wrap items-center justify-between gap-4 px-5 py-8 text-sm text-slate-500 sm:px-8"
    >
      <span>Manyfold · Copenhagen · CVR DK34287961</span>
      <div class="flex flex-wrap gap-6">
        <a href="mailto:thomas@manyfold.dk" class="text-brand hover:text-brand-deep"
          >thomas@manyfold.dk</a
        >
        <a href="https://www.linkedin.com/in/tbvl/" class="text-brand hover:text-brand-deep"
          >LinkedIn</a
        >
        <a href="https://github.com/tbvl" class="text-brand hover:text-brand-deep">GitHub</a>
        <RouterLink to="/decisions" class="text-brand hover:text-brand-deep">
          Decisions
        </RouterLink>
        <RouterLink to="/privacy" class="text-brand hover:text-brand-deep"> Privacy </RouterLink>
      </div>
    </div>
  </footer>
</template>
