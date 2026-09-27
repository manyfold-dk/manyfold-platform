<script setup lang="ts">
import { RouterLink } from 'vue-router'
import SectionEyebrow from '@/components/SectionEyebrow.vue'
import { decisions } from '@/lib/decisions'
</script>

<template>
  <main class="py-14 lg:py-20">
    <div class="mx-auto flex max-w-3xl flex-col gap-10 px-5 sm:px-8">
      <div class="flex flex-col gap-5">
        <SectionEyebrow tone="brand"> Decisions </SectionEyebrow>
        <h1 class="text-4xl leading-tight font-semibold tracking-[-0.03em] text-slate-900">
          Architecture decision records
        </h1>
        <p class="text-lg leading-relaxed text-slate-700">
          Every significant choice on the platform has a record: what was chosen, what was rejected,
          and why. These are a selection. They are the records as written, with names, sizes and
          endpoints removed, because the rest describes a running production platform.
        </p>
      </div>

      <ol class="flex flex-col gap-4">
        <li v-for="decision in decisions" :key="decision.slug">
          <RouterLink
            :to="`/decisions/${decision.slug}`"
            class="flex flex-col gap-2 rounded-lg border border-slate-200 bg-white p-6 transition-colors hover:border-brand"
          >
            <span class="font-mono text-[13px] text-slate-600">
              ADR {{ decision.number }} · {{ decision.series }} · {{ decision.status }} ·
              {{ decision.date }}
            </span>
            <span class="text-xl leading-snug font-semibold text-slate-900">{{
              decision.title
            }}</span>
            <span class="text-[15px] leading-relaxed text-slate-700">{{ decision.summary }}</span>
          </RouterLink>
        </li>
      </ol>
    </div>
  </main>
</template>
