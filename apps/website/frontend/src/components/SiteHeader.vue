<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import LogoMark from '@/components/LogoMark.vue'
import { statusTone, usePublicStatus } from '@/composables/usePublicStatus'

const route = useRoute()
const open = ref(false)
watch(
  () => route.fullPath,
  () => {
    open.value = false
  }
)

const { status, failed } = usePublicStatus()
const tone = computed(() => (failed.value ? 'unknown' : statusTone(status.value?.status)))
const pillText = computed(() => {
  if (tone.value === 'unknown') return 'status'
  return status.value?.status?.toLowerCase() ?? 'status'
})
const pillClass = computed(
  () =>
    ({
      ok: 'text-ok',
      warn: 'text-warn',
      down: 'text-down',
      unknown: 'text-slate-500'
    })[tone.value]
)
const dotClass = computed(
  () =>
    ({
      ok: 'bg-ok',
      warn: 'bg-warn',
      down: 'bg-down',
      unknown: 'bg-slate-400'
    })[tone.value]
)

const links = [
  { label: 'Platform', to: '/stack' },
  { label: 'Work', to: '/#work' },
  { label: 'For small companies', to: '/small-companies' },
  { label: 'About', to: '/#about' }
]
</script>

<template>
  <header class="sticky top-0 z-50 border-b border-slate-200 bg-white/90 backdrop-blur-sm">
    <nav
      class="mx-auto flex max-w-[1200px] items-center justify-between px-5 py-4 sm:px-8"
      aria-label="Main"
    >
      <RouterLink to="/" class="flex items-center gap-2.5 text-slate-900">
        <LogoMark :size="28" />
        <span class="text-[22px] font-semibold tracking-tight">manyfold</span>
      </RouterLink>

      <div class="hidden items-center gap-8 lg:flex">
        <RouterLink
          v-for="link in links"
          :key="link.to"
          :to="link.to"
          class="text-[15px] font-medium text-slate-700 transition-colors hover:text-brand"
          :class="{ 'text-brand': route.path === link.to }"
        >
          {{ link.label }}
        </RouterLink>
        <RouterLink
          to="/status"
          class="flex items-center gap-2 font-mono text-sm"
          :class="pillClass"
        >
          <span class="inline-block h-2 w-2 rounded-full" :class="dotClass" />{{ pillText }}
        </RouterLink>
        <!-- A plain link, not a RouterLink: /platform sits behind oauth2-proxy, so the browser
             has to make a real request for the proxy to send it to the sign-in page. -->
        <a
          href="/platform"
          class="text-[15px] font-medium text-slate-700 transition-colors hover:text-brand"
          >Sign in</a
        >
        <a
          href="mailto:thomas@manyfold.dk"
          class="rounded-lg bg-brand px-4 py-3 text-[15px] font-semibold text-white transition-colors hover:bg-brand-deep"
          >Get in touch</a
        >
      </div>

      <button
        type="button"
        class="flex h-11 w-11 items-center justify-center rounded-lg border border-slate-200 bg-white lg:hidden"
        :aria-expanded="open"
        aria-controls="site-menu"
        aria-label="Open menu"
        @click="open = !open"
      >
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          aria-hidden="true"
        >
          <path d="M4 7h16M4 12h16M4 17h16" />
        </svg>
      </button>
    </nav>

    <div v-if="open" id="site-menu" class="border-t border-slate-200 bg-white px-5 py-4 lg:hidden">
      <div class="flex flex-col gap-1">
        <RouterLink
          v-for="link in links"
          :key="link.to"
          :to="link.to"
          class="rounded-lg px-2 py-3 text-base font-medium text-slate-800"
        >
          {{ link.label }}
        </RouterLink>
        <RouterLink
          to="/status"
          class="flex items-center gap-2 rounded-lg px-2 py-3 font-mono text-sm"
          :class="pillClass"
        >
          <span class="inline-block h-2 w-2 rounded-full" :class="dotClass" />{{ pillText }}
        </RouterLink>
        <a href="/platform" class="rounded-lg px-2 py-3 text-base font-medium text-slate-800"
          >Sign in</a
        >
        <a
          href="mailto:thomas@manyfold.dk"
          class="mt-2 rounded-lg bg-brand px-4 py-3 text-center text-base font-semibold text-white"
          >Get in touch</a
        >
      </div>
    </div>
  </header>
</template>
