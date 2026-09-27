<script setup lang="ts">
import { RouterLink } from 'vue-router'
import LogoMark from '@/components/LogoMark.vue'

// Navigation for the signed-in operator views (everything under oauth2-proxy).
const links = [
  { label: 'Status', to: '/status' },
  { label: 'Playground', to: '/demo' },
  { label: 'Platform', to: '/platform' },
  { label: 'Deep Health', to: '/platform/deep' },
  { label: 'Fleet', to: '/platform/fleet' },
  { label: 'Quest', to: '/platform/quest' }
]
</script>

<template>
  <header class="sticky top-0 z-50 border-b border-slate-200 bg-white/90 backdrop-blur-sm">
    <nav
      class="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-6 py-4"
      aria-label="Operator"
    >
      <RouterLink to="/" class="flex items-center gap-2.5 text-slate-900">
        <LogoMark :size="24" />
        <span class="text-lg font-semibold tracking-tight">manyfold</span>
      </RouterLink>
      <div class="flex flex-wrap gap-6">
        <RouterLink
          v-for="link in links"
          :key="link.to"
          :to="link.to"
          class="text-sm font-medium text-slate-600 transition-colors hover:text-slate-900"
          exact-active-class="text-brand"
        >
          {{ link.label }}
        </RouterLink>
        <!-- A form POST, not a link: signing out changes state, so it must not be triggerable
             by a cross-site GET. The backend answers 303 to the proxy's logout. -->
        <form method="post" action="/api/v1/auth/logout">
          <button
            type="submit"
            class="text-sm font-medium text-slate-600 transition-colors hover:text-slate-900"
          >
            Sign out
          </button>
        </form>
      </div>
    </nav>
  </header>
</template>
