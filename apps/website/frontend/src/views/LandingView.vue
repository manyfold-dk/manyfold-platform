<script setup lang="ts">
import { RouterLink } from 'vue-router'
import SectionEyebrow from '@/components/SectionEyebrow.vue'
import StatusCard from '@/components/StatusCard.vue'
import avatar from '@/assets/thomas-avatar.jpg'
import portrait from '@/assets/thomas.jpg'

const offers = [
  {
    title: 'Build',
    body: 'Senior hands-on platform and DevOps engineering inside your team: Kubernetes, GitOps, observability, infrastructure as code. On contract, usually six months or more.',
    link: { label: 'Selected work', to: '/#work' }
  },
  {
    title: 'Run',
    body: "A managed home for a small company's systems on my own platform. AI agents handle first-line operations around the clock, I handle what needs a person, and it costs less than a staffed team.",
    link: { label: 'For small companies', to: '/small-companies' }
  },
  {
    title: 'Advise',
    body: 'Architecture reviews and second opinions: EU-sovereign hosting options, platform strategy, and how to use GenAI on sensitive data without losing control of it.',
    link: { label: 'Write to me', href: 'mailto:thomas@manyfold.dk' }
  }
]

const cases = [
  {
    client: 'DSB · Danish State Railways',
    period: '2021–2026',
    title: 'Ticketing at more than 2 million transactions a day',
    body: 'Tech lead and architect on the Commercial Sales Flow: a rules engine for 100+ ticketing rules answering in under 150 ms. Built the Azure platform for the microservices as code, with Prometheus and Grafana observability.',
    tags: 'Quarkus · AKS · Cosmos DB · Bicep · GitHub Actions'
  },
  {
    client: 'ATP',
    period: '2026',
    title: 'Kubernetes platform for a pension fund',
    body: "Day-to-day operation and scaling of ATP's Kubernetes infrastructure and new platform features, inside the security and regulatory requirements of a national pension provider.",
    tags: 'Kubernetes · GitOps · compliance'
  },
  {
    client: 'UFST · Danish Tax Agency',
    period: '2026',
    title: 'Keeping citizen letters accurate and on time',
    body: 'Production operations for high-volume document generation on AWS: root-cause analysis across application and infrastructure, PostgreSQL forensics, monitoring, post-mortems.',
    tags: 'AWS · CloudWatch · PostgreSQL · Power BI'
  },
  {
    client: 'KMD',
    period: '2019–2020',
    title: 'A DevOps team from one to seven',
    body: "Built the DevOps team and the automated deployment process for KMD's life and pension solution: 6+ applications onboarded across 20+ environments.",
    tags: 'Azure DevOps · DSC · PowerShell'
  }
]

const principles = [
  {
    title: 'Boring is beautiful',
    body: 'Proven technology over new technology. Your systems need reliability, not my CV items.'
  },
  {
    title: 'Transparency by default',
    body: 'Everything is code in a repository you can read. An exit plan exists from day one.'
  },
  {
    title: 'Hosted in the EU',
    body: 'Applications and data hosted in the EU, on European infrastructure and under Danish ownership.'
  },
  {
    title: 'Automate the night shift',
    body: 'Agents handle the routine at 3 am. People handle what is new.'
  }
]
</script>

<template>
  <main>
    <!-- Hero -->
    <section
      class="mx-auto flex max-w-[1200px] flex-col gap-12 px-5 py-14 sm:px-8 lg:flex-row lg:items-center lg:gap-20 lg:py-24"
    >
      <div class="flex grow flex-col gap-6">
        <SectionEyebrow tone="brand"> Platform engineering · Copenhagen </SectionEyebrow>
        <h1
          class="text-4xl leading-[1.12] font-semibold tracking-[-0.03em] text-balance text-slate-900 sm:text-5xl lg:text-[56px] lg:leading-[1.1]"
        >
          Kubernetes platforms, built and run in Europe.
        </h1>
        <p class="max-w-[600px] text-[17px] leading-relaxed text-slate-700 sm:text-[19px]">
          25 years in systems where downtime is expensive: rail ticketing, pensions, tax. I build
          Kubernetes platforms for teams, and I run one of my own on European infrastructure.
        </p>
        <div class="flex flex-col gap-3 pt-2 sm:flex-row sm:items-center sm:gap-5">
          <a
            href="mailto:thomas@manyfold.dk"
            class="rounded-lg bg-brand px-5 py-3.5 text-center text-base font-semibold text-white transition-colors hover:bg-brand-deep"
            >Get in touch</a
          >
          <RouterLink
            to="/stack"
            class="px-1 py-3.5 text-center text-base font-semibold text-brand hover:text-brand-deep"
          >
            See the platform
          </RouterLink>
        </div>
        <div class="flex items-center gap-3 pt-2">
          <img
            :src="avatar"
            alt=""
            width="44"
            height="44"
            class="h-11 w-11 shrink-0 rounded-full object-cover"
          />
          <div class="flex flex-col gap-0.5">
            <span class="text-[15px] font-semibold text-slate-900">Thomas Berg von Linde</span>
            <span class="text-sm text-slate-500">Platform engineer, Copenhagen</span>
          </div>
        </div>
      </div>
      <div class="w-full shrink-0 lg:w-[440px]">
        <StatusCard />
      </div>
    </section>

    <!-- What I do -->
    <section class="border-y border-slate-200 bg-white">
      <div
        class="mx-auto flex max-w-[1200px] flex-col gap-10 px-5 py-14 sm:px-8 lg:gap-12 lg:py-24"
      >
        <div class="flex flex-col gap-3">
          <SectionEyebrow>What I do</SectionEyebrow>
          <h2
            class="text-3xl leading-tight font-semibold tracking-[-0.02em] text-slate-900 lg:text-4xl"
          >
            Three ways to work with me
          </h2>
        </div>
        <div class="grid gap-10 md:grid-cols-3 lg:gap-12">
          <div
            v-for="offer in offers"
            :key="offer.title"
            class="flex flex-col gap-3 border-t-2 border-slate-900 pt-5"
          >
            <h3 class="text-2xl font-semibold text-slate-900">
              {{ offer.title }}
            </h3>
            <p class="leading-relaxed text-slate-700">
              {{ offer.body }}
            </p>
            <RouterLink
              v-if="offer.link.to"
              :to="offer.link.to"
              class="text-[15px] font-semibold text-brand hover:text-brand-deep"
            >
              {{ offer.link.label }}
            </RouterLink>
            <a
              v-else
              :href="offer.link.href"
              class="text-[15px] font-semibold text-brand hover:text-brand-deep"
              >{{ offer.link.label }}</a
            >
          </div>
        </div>
      </div>
    </section>

    <!-- Selected work -->
    <section
      id="work"
      class="mx-auto flex max-w-[1200px] scroll-mt-20 flex-col gap-10 px-5 py-14 sm:px-8 lg:gap-12 lg:py-24"
    >
      <div class="flex flex-col gap-3">
        <SectionEyebrow>Selected work</SectionEyebrow>
        <h2
          class="text-3xl leading-tight font-semibold tracking-[-0.02em] text-balance text-slate-900 lg:text-4xl"
        >
          25 years in software, mostly where downtime is expensive
        </h2>
      </div>
      <div class="grid gap-6 md:grid-cols-2">
        <article
          v-for="item in cases"
          :key="item.client"
          class="flex flex-col gap-2.5 rounded-lg border border-slate-200 bg-white p-6"
        >
          <div class="flex items-baseline justify-between gap-3">
            <span class="text-[15px] font-semibold text-brand">{{ item.client }}</span>
            <span class="shrink-0 font-mono text-xs text-slate-500">{{ item.period }}</span>
          </div>
          <h3 class="text-xl leading-snug font-semibold text-slate-900">
            {{ item.title }}
          </h3>
          <p class="text-[15px] leading-relaxed text-slate-700">
            {{ item.body }}
          </p>
          <div class="font-mono text-xs text-slate-500">
            {{ item.tags }}
          </div>
        </article>
      </div>
    </section>

    <!-- Principles -->
    <section class="bg-brand-deep">
      <div
        class="mx-auto flex max-w-[1200px] flex-col gap-10 px-5 py-14 sm:px-8 lg:gap-12 lg:py-24"
      >
        <div class="flex flex-col gap-3">
          <SectionEyebrow tone="onDeep"> How I work </SectionEyebrow>
          <h2
            class="text-3xl leading-tight font-semibold tracking-[-0.02em] text-white lg:text-4xl"
          >
            Four rules the platform is built on
          </h2>
        </div>
        <div class="grid gap-8 sm:grid-cols-2 lg:grid-cols-4 lg:gap-10">
          <div v-for="rule in principles" :key="rule.title" class="flex flex-col gap-2">
            <div class="text-xl font-semibold text-white">
              {{ rule.title }}
            </div>
            <p class="text-[15px] leading-relaxed text-blue-100">
              {{ rule.body }}
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- About -->
    <section
      id="about"
      class="mx-auto flex max-w-[1200px] scroll-mt-20 flex-col gap-10 px-5 py-14 sm:px-8 md:flex-row md:items-center lg:gap-20 lg:py-24"
    >
      <img
        :src="portrait"
        alt="Thomas Berg von Linde"
        width="320"
        height="400"
        class="h-[250px] w-[200px] shrink-0 rounded-xl object-cover md:h-[400px] md:w-[320px]"
      />
      <div class="flex flex-col gap-5">
        <SectionEyebrow>About</SectionEyebrow>
        <h2
          class="text-3xl leading-tight font-semibold tracking-[-0.02em] text-slate-900 lg:text-4xl"
        >
          Thomas Berg von Linde
        </h2>
        <p class="max-w-[640px] text-[17px] leading-relaxed text-slate-700">
          Platform engineer, architect and developer. Independent through Manyfold since 2012,
          before that four years at Accenture as a technology architect. MSc in IT from the IT
          University of Copenhagen, Google Certified Professional Cloud Architect. Most of my work
          has been in finance, insurance, public transport and the public sector.
        </p>
        <div class="flex items-center gap-2.5 self-start rounded-lg bg-brand-tint px-4 py-3">
          <span class="h-2 w-2 rounded-full bg-brand" />
          <span class="text-[15px] font-medium text-slate-900"
            >Available for new engagements from January 2027</span
          >
        </div>
        <div class="flex flex-wrap gap-6 text-[15px] font-semibold">
          <a href="https://www.linkedin.com/in/tbvl/" class="text-brand hover:text-brand-deep"
            >LinkedIn</a
          >
          <a href="https://github.com/tbvl" class="text-brand hover:text-brand-deep">GitHub</a>
          <a href="mailto:thomas@manyfold.dk" class="text-brand hover:text-brand-deep"
            >thomas@manyfold.dk</a
          >
        </div>
      </div>
    </section>
  </main>
</template>
