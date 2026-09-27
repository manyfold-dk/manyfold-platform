<script setup lang="ts">
import { RouterLink } from 'vue-router'
import LayerRow from '@/components/LayerRow.vue'
import SectionEyebrow from '@/components/SectionEyebrow.vue'
import deliveryDiagram from '@/assets/diagrams/delivery-light.svg'
import operationsDiagram from '@/assets/diagrams/operations-light.svg'
import tenancyDiagram from '@/assets/diagrams/tenancy-light.svg'

const services = [
  { name: 'Edge', detail: 'Cloudflare (EU) DNS with a failover zone' },
  { name: 'Identity and secrets', detail: 'Keycloak · OpenBao' },
  { name: 'Delivery', detail: 'Argo CD GitOps · Tekton · GitHub Actions' },
  { name: 'Observability', detail: 'Prometheus · Loki · Tempo · Grafana' }
]

// The four layers the logo is drawn from. Colours match LogoMark.vue.
const stack = [
  {
    name: 'Tenants',
    detail:
      'Isolated per company: a landing zone Argo CD onboards, self-service storage and egress through Crossplane',
    bar: 'bg-brand-pale',
    text: 'text-slate-900',
    sub: 'text-slate-900'
  },
  {
    name: 'Network',
    detail: 'Cilium · Hubble',
    bar: 'bg-brand-sky',
    text: 'text-slate-900',
    sub: 'text-slate-900'
  },
  {
    name: 'Cluster',
    detail: 'Kubernetes on Talos Linux · Velero backups',
    bar: 'bg-brand',
    text: 'text-white',
    sub: 'text-blue-100'
  },
  {
    name: 'Infrastructure',
    detail: 'Hetzner Cloud, Helsinki',
    bar: 'bg-brand-deep',
    text: 'text-white',
    sub: 'text-blue-100'
  }
]

const practices: { title: string; body: string; link?: { label: string; to: string } }[] = [
  {
    title: 'Decisions are written down',
    body: 'Every significant choice has an architecture decision record: what was chosen, what was rejected, and why.',
    link: { label: 'Read a selection', to: '/decisions' }
  },
  {
    title: 'Git is the only way in',
    body: 'Argo CD applies what is in the repository. No manual changes on the cluster, so the repository is always the truth.'
  },
  {
    title: 'Restores are rehearsed',
    body: 'Backups are only worth what the last restore drill proved. Drills and break-glass recovery are documented runbooks.'
  },
  {
    title: 'Agents take the routine',
    body: 'AI agents watch the platform around the clock and handle known problems from runbooks. Anything new comes to me.'
  }
]

// Generated in the Manyfold design system with the fonts embedded; the source and the
// script live with the brand assets. Each alt text carries the diagram's whole claim.
const diagrams = [
  {
    src: deliveryDiagram,
    alt: 'Delivery: a change on main runs the checks, CI builds and pushes a versioned image and commits its tag to Git, Argo CD applies Git to the cluster and reverts drift, and the cluster pulls the image from the registry.',
    caption:
      'Delivery. CI builds the image and writes its tag to Git; Argo CD applies what Git says and undoes anything that drifts from it. Every deploy takes this path; the only other hand on the cluster is the executor below, within its policy.'
  },
  {
    src: operationsDiagram,
    alt: 'Operations: an alert goes to triage, then to a policy gate in the executor. Low-severity, low-risk actions run within their class; anything else becomes an approval card for the operator. Every invocation, approval and outcome is appended to an audit log.',
    caption:
      'Operations. The agent may only act through executor scripts that enforce a severity × risk matrix, so a prompt cannot widen what it is allowed to do.',
    link: {
      label: 'Read the decision',
      to: '/decisions/0004-single-ops-agent-with-constrained-execution'
    }
  },
  {
    src: tenancyDiagram,
    alt: "Tenancy: two tenants share the platform services; each has an operator-owned landing zone of delivery scope, namespaces and quota, default-deny network, own identity realm, admission policies and scoped secrets, around workloads shipped from the tenant's own repository.",
    caption:
      "Tenancy. Guardrails live in my repository and workloads in the tenant's, so a tenant can ship what it likes and still cannot widen its own quota, access or network policy."
  }
]

const views = [
  {
    title: 'Status',
    body: 'Current state of every public service, refreshed every 30 seconds.',
    to: '/status',
    operator: false
  },
  {
    title: 'Fleet map',
    body: 'The topology from platform to cluster to applications, with health per node.',
    to: '/platform/fleet',
    operator: true
  },
  {
    title: 'Deep health',
    body: 'Layer-by-layer checks: cluster, GitOps, pipelines, applications.',
    to: '/platform/deep',
    operator: true
  }
]
</script>

<template>
  <main>
    <section
      class="mx-auto flex max-w-[1200px] flex-col gap-12 px-5 py-14 sm:px-8 lg:flex-row lg:items-start lg:gap-20 lg:py-24"
    >
      <div class="flex shrink-0 flex-col gap-6 lg:w-[520px]">
        <SectionEyebrow tone="brand"> The platform </SectionEyebrow>
        <h1
          class="text-4xl leading-tight font-semibold tracking-[-0.03em] text-balance text-slate-900 lg:text-5xl lg:leading-[1.2]"
        >
          A production platform on European infrastructure
        </h1>
        <p class="text-lg leading-relaxed text-slate-700">
          It hosts this site, the first tenant's systems and my own workloads. Everything on it is
          defined in code, built from open-source components, and portable to any other Kubernetes
          cluster.
        </p>
        <p class="text-lg leading-relaxed text-slate-700">
          It is also how I stay sharp: the same stack and the same habits I bring to client
          platforms, run for real.
        </p>
      </div>

      <div class="flex grow flex-col gap-2">
        <SectionEyebrow class="pt-2 pb-1"> Platform services · run on the stack </SectionEyebrow>
        <LayerRow v-for="row in services" :key="row.name" :name="row.name" :detail="row.detail" />
        <SectionEyebrow class="pt-5 pb-1"> The stack · the four layers in the logo </SectionEyebrow>
        <LayerRow
          v-for="row in stack"
          :key="row.name"
          :name="row.name"
          :detail="row.detail"
          :bar="row.bar"
          :text="row.text"
          :sub="row.sub"
        />
      </div>
    </section>

    <section class="border-y border-slate-200 bg-white">
      <div
        class="mx-auto flex max-w-[1200px] flex-col gap-10 px-5 py-14 sm:px-8 lg:gap-12 lg:py-24"
      >
        <div class="flex flex-col gap-3">
          <SectionEyebrow>How it is run</SectionEyebrow>
          <h2
            class="text-3xl leading-tight font-semibold tracking-[-0.02em] text-balance text-slate-900 lg:text-4xl"
          >
            Operated like a platform team would, by one person and a few agents
          </h2>
        </div>
        <div class="grid gap-8 sm:grid-cols-2 lg:grid-cols-4 lg:gap-10">
          <div
            v-for="item in practices"
            :key="item.title"
            class="flex flex-col gap-2.5 border-t-2 border-slate-900 pt-5"
          >
            <h3 class="text-xl leading-snug font-semibold text-slate-900">
              {{ item.title }}
            </h3>
            <p class="text-[15px] leading-relaxed text-slate-700">
              {{ item.body }}
            </p>
            <RouterLink
              v-if="item.link"
              :to="item.link.to"
              class="text-sm font-semibold text-brand hover:underline"
            >
              {{ item.link.label }}
            </RouterLink>
          </div>
        </div>
      </div>
    </section>

    <section class="mx-auto flex max-w-[1200px] flex-col gap-10 px-5 py-14 sm:px-8 lg:py-24">
      <div class="flex max-w-[65ch] flex-col gap-3">
        <SectionEyebrow>How it fits together</SectionEyebrow>
        <h2
          class="text-3xl leading-tight font-semibold tracking-[-0.02em] text-balance text-slate-900 lg:text-4xl"
        >
          Three mechanisms, drawn out
        </h2>
        <p class="text-lg leading-relaxed text-slate-700">
          How a change reaches the cluster, what the agents are allowed to do, and how tenants are
          kept apart.
        </p>
      </div>
      <figure v-for="diagram in diagrams" :key="diagram.src" class="flex flex-col gap-3">
        <img
          :src="diagram.src"
          :alt="diagram.alt"
          width="960"
          loading="lazy"
          class="h-auto w-full max-w-[960px] rounded-lg"
        />
        <figcaption class="max-w-[65ch] text-[15px] leading-relaxed text-slate-700">
          {{ diagram.caption }}
          <RouterLink
            v-if="diagram.link"
            :to="diagram.link.to"
            class="font-semibold text-brand hover:underline"
          >
            {{ diagram.link.label }}
          </RouterLink>
        </figcaption>
      </figure>
    </section>

    <section class="mx-auto flex max-w-[1200px] flex-col gap-8 px-5 pb-14 sm:px-8 lg:pb-24">
      <div class="flex flex-col gap-3">
        <SectionEyebrow>See it running</SectionEyebrow>
        <h2
          class="text-3xl leading-tight font-semibold tracking-[-0.02em] text-slate-900 lg:text-4xl"
        >
          Live views, straight from the cluster
        </h2>
      </div>
      <div class="grid gap-6 md:grid-cols-3">
        <component
          :is="view.operator ? 'a' : RouterLink"
          v-for="view in views"
          :key="view.title"
          v-bind="view.operator ? { href: view.to } : { to: view.to }"
          class="flex flex-col gap-2 rounded-lg border border-slate-200 bg-white p-6 transition-colors hover:border-brand"
        >
          <span class="flex items-center justify-between gap-3">
            <span class="text-lg font-semibold text-slate-900">{{ view.title }}</span>
            <span
              v-if="view.operator"
              class="rounded bg-slate-100 px-2 py-0.5 font-mono text-[11px] text-slate-600"
              >sign-in</span
            >
          </span>
          <span class="text-[15px] leading-relaxed text-slate-700">{{ view.body }}</span>
          <span class="text-sm font-semibold text-brand">Open</span>
        </component>
      </div>
    </section>
  </main>
</template>
