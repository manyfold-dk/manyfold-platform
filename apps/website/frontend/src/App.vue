<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import OperatorHeader from '@/components/OperatorHeader.vue'
import SiteFooter from '@/components/SiteFooter.vue'
import SiteHeader from '@/components/SiteHeader.vue'

const route = useRoute()
// Operator views sit behind oauth2-proxy and keep their own navigation;
// everything else is the public site.
const isOperatorView = computed(() => route.meta?.requiresServerAuth === true)
</script>

<template>
  <div class="flex min-h-screen flex-col bg-slate-50 font-sans text-slate-900">
    <OperatorHeader v-if="isOperatorView" />
    <SiteHeader v-else />
    <RouterView class="grow" />
    <SiteFooter v-if="!isOperatorView" />
  </div>
</template>
