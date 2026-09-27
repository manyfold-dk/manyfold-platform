<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import MarkdownIt from 'markdown-it'
import { useDocumentTitle } from '@/composables/useDocumentTitle'
import { useInternalLinks } from '@/composables/useInternalLinks'
import { findDecision } from '@/lib/decisions'

// html stays off: the records are our own, but nothing in them needs raw HTML.
const markdown = new MarkdownIt({ html: false, typographer: true })

const route = useRoute()

const decision = computed(() => findDecision(String(route.params.slug)))
const html = computed(() => (decision.value ? markdown.render(decision.value.body) : ''))

useDocumentTitle(() =>
  decision.value ? `ADR ${decision.value.number}: ${decision.value.title}` : 'Decision not found'
)

const followInternalLink = useInternalLinks('/decisions/')
</script>

<template>
  <main class="py-14 lg:py-20">
    <div class="mx-auto max-w-3xl px-5 sm:px-8">
      <RouterLink to="/decisions" class="text-sm font-semibold text-brand hover:underline">
        All decisions
      </RouterLink>

      <template v-if="decision">
        <p class="mt-8 font-mono text-[13px] text-slate-600">
          ADR {{ decision.number }} · {{ decision.series }} · {{ decision.status }} ·
          {{ decision.date }}
        </p>
        <h1 class="mt-3 text-4xl leading-tight font-semibold tracking-[-0.03em] text-slate-900">
          {{ decision.title }}
        </h1>

        <!-- eslint-disable vue/no-v-html -- rendered from our own Markdown with html disabled -->
        <article class="decision-body mt-10" @click="followInternalLink" v-html="html" />
        <!-- eslint-enable vue/no-v-html -->

        <p class="mt-12 border-t border-slate-200 pt-6 text-sm leading-relaxed text-slate-600">
          Published version. {{ decision.changes }} The reasoning is unchanged.
        </p>
      </template>

      <template v-else>
        <h1 class="mt-8 text-4xl font-semibold tracking-[-0.03em] text-slate-900">
          No such decision record
        </h1>
        <p class="mt-4 text-lg text-slate-700">
          It may not be published. The list has the ones that are.
        </p>
      </template>
    </div>
  </main>
</template>

<style scoped>
.decision-body {
  color: var(--color-slate-700);
  line-height: 1.7;
}

.decision-body :deep(h2) {
  margin-top: 2.5rem;
  font-size: 1.5rem;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--color-slate-900);
}

.decision-body :deep(h3) {
  margin-top: 2rem;
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--color-slate-900);
}

.decision-body :deep(p),
.decision-body :deep(ul),
.decision-body :deep(ol),
.decision-body :deep(blockquote),
.decision-body :deep(table) {
  margin-top: 1rem;
}

.decision-body :deep(ul) {
  list-style: disc;
  padding-left: 1.25rem;
}

.decision-body :deep(ol) {
  list-style: decimal;
  padding-left: 1.25rem;
}

.decision-body :deep(li) {
  margin-top: 0.375rem;
}

.decision-body :deep(li > ul),
.decision-body :deep(li > p) {
  margin-top: 0.375rem;
}

.decision-body :deep(strong) {
  font-weight: 600;
  color: var(--color-slate-900);
}

.decision-body :deep(a) {
  color: var(--color-brand);
}

.decision-body :deep(a:hover) {
  text-decoration: underline;
}

.decision-body :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.875em;
  overflow-wrap: anywhere;
}

.decision-body :deep(blockquote) {
  border-left: 2px solid var(--color-slate-900);
  padding: 0.25rem 0 0.25rem 1.25rem;
}

.decision-body :deep(blockquote > p:first-child) {
  margin-top: 0;
}

/* Wide tables scroll inside their own box so the page never does. */
.decision-body :deep(table) {
  display: block;
  overflow-x: auto;
  border-collapse: collapse;
  font-size: 0.9375rem;
}

.decision-body :deep(th),
.decision-body :deep(td) {
  border-bottom: 1px solid var(--color-slate-200);
  padding: 0.625rem 1rem 0.625rem 0;
  text-align: left;
  vertical-align: top;
}

.decision-body :deep(th) {
  font-weight: 600;
  color: var(--color-slate-900);
}
</style>
