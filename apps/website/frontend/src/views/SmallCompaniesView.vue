<script setup lang="ts">
import SectionEyebrow from '@/components/SectionEyebrow.vue'

const benefits = [
  {
    title: 'Hosting in Europe',
    body: 'Your applications and data hosted in the EU, on European infrastructure and under Danish ownership.'
  },
  {
    title: 'Updates without drama',
    body: 'Operating system, cluster and dependencies patched continuously, tested before they reach you.'
  },
  {
    title: 'Monitoring and backups',
    body: 'Problems are usually found and fixed before you notice. Backups are restored regularly to prove they work.'
  },
  {
    title: 'Agents on call, around the clock',
    body: 'AI agents with real operating rights are the first line. They act on known problems within seconds, day or night, inside limits I set. When something needs a person, I respond within 12 hours. It is also why this costs less than a staffed operations team.'
  },
  {
    title: 'A way out',
    body: 'Everything is code in a repository you own. If you leave, you take a working setup with you.'
  }
]

const steps = [
  {
    title: 'A conversation',
    body: 'What you run today, what worries you, and whether I am the right fit. No charge.'
  },
  {
    title: 'A look under the hood',
    body: 'I review your current setup and write down what I find, in plain language.'
  },
  {
    title: 'A fixed-scope move',
    body: 'A migration plan with a clear scope and price, carried out without taking you offline.'
  },
  {
    title: 'Running',
    body: 'A monthly agreement covering hosting and operations, with a regular report on what happened.'
  }
]
</script>

<template>
  <main>
    <section
      class="mx-auto flex max-w-[1200px] flex-col gap-12 px-5 py-14 sm:px-8 lg:flex-row lg:items-start lg:gap-20 lg:py-24"
    >
      <div class="flex shrink-0 flex-col gap-6 lg:w-[520px]">
        <SectionEyebrow tone="brand"> For small companies </SectionEyebrow>
        <h1
          class="text-4xl leading-tight font-semibold tracking-[-0.03em] text-slate-900 lg:text-5xl lg:leading-[1.2]"
        >
          Your systems, looked after.
        </h1>
        <p class="text-lg leading-relaxed text-slate-700">
          For companies with a web shop, a booking system or a handful of internal applications, and
          nobody whose job it is to keep them running.
        </p>
        <p class="text-lg leading-relaxed text-slate-700">
          I move them onto my platform and run them there: in the EU, under Danish ownership, with
          the kind of care a large company's platform team would give them.
        </p>
        <div class="rounded-lg bg-brand-tint px-6 py-5 text-[15px] leading-relaxed text-slate-900">
          I take on a small number of clients. Agents do the routine work, so my time goes to what
          needs judgement.
        </div>
      </div>

      <div class="flex grow flex-col">
        <SectionEyebrow class="pb-4"> What you get </SectionEyebrow>
        <div
          v-for="item in benefits"
          :key="item.title"
          class="flex items-start gap-4 border-t border-slate-200 py-5"
        >
          <span class="mt-2 h-2.5 w-2.5 shrink-0 rounded-sm bg-brand" />
          <div class="flex flex-col gap-1">
            <span class="text-[17px] font-semibold text-slate-900">{{ item.title }}</span>
            <span class="text-[15px] leading-relaxed text-slate-700">{{ item.body }}</span>
          </div>
        </div>
      </div>
    </section>

    <section class="border-y border-slate-200 bg-white">
      <div
        class="mx-auto flex max-w-[1200px] flex-col gap-10 px-5 py-14 sm:px-8 lg:gap-12 lg:py-24"
      >
        <div class="flex flex-col gap-3">
          <SectionEyebrow>How it starts</SectionEyebrow>
          <h2
            class="text-3xl leading-tight font-semibold tracking-[-0.02em] text-slate-900 lg:text-4xl"
          >
            From first conversation to running
          </h2>
        </div>
        <ol class="grid gap-8 sm:grid-cols-2 lg:grid-cols-4 lg:gap-10">
          <li v-for="(step, index) in steps" :key="step.title" class="flex flex-col gap-2.5">
            <span class="font-mono text-[13px] font-medium text-brand">Step {{ index + 1 }}</span>
            <h3 class="text-xl leading-snug font-semibold text-slate-900">
              {{ step.title }}
            </h3>
            <p class="text-[15px] leading-relaxed text-slate-700">
              {{ step.body }}
            </p>
          </li>
        </ol>
      </div>
    </section>

    <section
      class="mx-auto flex max-w-[1200px] flex-col gap-6 px-5 py-14 sm:px-8 md:flex-row md:items-center md:justify-between md:gap-12 lg:py-[72px]"
    >
      <div class="flex flex-col gap-2">
        <h2
          class="text-3xl leading-tight font-semibold tracking-[-0.02em] text-slate-900 lg:text-4xl"
        >
          Not sure if this fits?
        </h2>
        <p class="text-[17px] leading-relaxed text-slate-700">
          Write two lines about what you run. I will tell you honestly whether I can help.
        </p>
      </div>
      <a
        href="mailto:thomas@manyfold.dk"
        class="shrink-0 rounded-lg bg-brand px-5 py-3.5 text-center text-base font-semibold text-white transition-colors hover:bg-brand-deep"
        >thomas@manyfold.dk</a
      >
    </section>
  </main>
</template>
