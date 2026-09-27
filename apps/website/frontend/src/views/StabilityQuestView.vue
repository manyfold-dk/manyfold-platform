<script setup lang="ts">
import { computed, ref } from 'vue'

interface Scenario {
  id: string
  title: string
  alertMessage: string
  namespace: string
  podName: string
  deploymentName: string
  idealAction: 'pod' | 'deployment'
}

const scenarios: Scenario[] = [
  {
    id: 'pod-crash',
    title: 'Pod Crash Drill',
    alertMessage: 'API pod is crash-looping and not restarting.',
    namespace: 'website',
    podName: 'website-backend-7f8c8d9c',
    deploymentName: 'website-backend',
    idealAction: 'pod'
  },
  {
    id: 'stale-config',
    title: 'Config Drift Drill',
    alertMessage: 'Application config appears stale after rollout.',
    namespace: 'shop',
    podName: 'shop-worker-54ff6',
    deploymentName: 'shop-api',
    idealAction: 'deployment'
  },
  {
    id: 'rolling-fix',
    title: 'Rolling Update Drill',
    alertMessage: 'Frontend requests spike with partial failures.',
    namespace: 'website',
    podName: 'website-backend-5f6d',
    deploymentName: 'website-backend',
    idealAction: 'deployment'
  }
]

const questState = ref('ready')
const currentScenarioIndex = ref(0)
const score = ref(0)
const eventLog = ref<string[]>([])
const isProcessing = ref(false)
const showSummary = ref(false)

const currentScenario = computed(() => scenarios[currentScenarioIndex.value] || null)
const completedCount = computed(() => currentScenarioIndex.value + (showSummary.value ? 1 : 0))
const maxScore = scenarios.length * 100

function resetQuest() {
  questState.value = 'running'
  currentScenarioIndex.value = 0
  score.value = 0
  eventLog.value = []
  showSummary.value = false
}

function addLog(message: string) {
  const time = new Date().toLocaleTimeString()
  eventLog.value = [`[${time}] ${message}`, ...eventLog.value].slice(0, 8)
}

// A drill only: the choice is scored, and nothing is restarted. Real restarts belong on the
// platform page, where the operator names the target.
function executeAction(action: 'pod' | 'deployment') {
  if (!currentScenario.value || isProcessing.value) return
  isProcessing.value = true

  const scenario = currentScenario.value
  const isCorrect = scenario.idealAction === action
  if (isCorrect) {
    score.value += 100
    addLog(`Scenario ${scenario.id}: correct action (${action})`)
  } else {
    score.value = Math.max(0, score.value - 20)
    addLog(`Scenario ${scenario.id}: wrong action (${action})`)
  }

  const remaining = scenarios.length - currentScenarioIndex.value - 1
  if (remaining > 0) {
    currentScenarioIndex.value += 1
  } else {
    showSummary.value = true
    questState.value = 'done'
  }
  isProcessing.value = false
}

function statusMessage() {
  if (questState.value === 'ready') return 'Press Start to begin'
  if (questState.value === 'running')
    return `Scenario ${completedCount.value} of ${scenarios.length}`
  return 'Mission complete'
}
</script>

<template>
  <main class="min-h-screen bg-slate-50 py-12">
    <div class="mx-auto max-w-4xl px-6">
      <div class="rounded-xl border border-slate-200 bg-white p-6">
        <div class="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h1 class="text-3xl font-bold text-slate-900">Stability Quest</h1>
            <p class="mt-1 text-sm text-slate-500">
              Scripted incident drill with scoring. Nothing is restarted.
            </p>
          </div>
          <div class="flex items-center gap-3">
            <button
              :disabled="isProcessing || questState === 'running'"
              class="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 disabled:opacity-50"
              @click="resetQuest"
            >
              {{ questState === 'ready' ? 'Start' : 'Restart' }}
            </button>
          </div>
        </div>

        <div class="mt-6 rounded-lg border border-slate-100 bg-slate-50 p-4">
          <p class="text-sm font-semibold text-slate-900">Score: {{ score }} / {{ maxScore }}</p>
          <p class="mt-1 text-sm text-slate-600">
            {{ statusMessage() }}
          </p>
        </div>

        <div
          v-if="currentScenario && questState === 'running'"
          class="mt-6 rounded-lg border border-slate-200 bg-white p-4"
        >
          <p class="text-sm font-semibold text-slate-900">
            {{ currentScenario.title }}
          </p>
          <p class="mt-2 text-slate-700">
            {{ currentScenario.alertMessage }}
          </p>
          <p class="mt-3 text-sm text-slate-500">
            Namespace: {{ currentScenario.namespace }}<br />
            Pod: {{ currentScenario.podName }} · Deployment: {{ currentScenario.deploymentName }}
          </p>

          <div class="mt-4 flex gap-3">
            <button
              class="rounded-lg bg-emerald-500 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-emerald-600"
              :disabled="isProcessing"
              @click="executeAction('pod')"
            >
              Restart Pod
            </button>
            <button
              class="rounded-lg bg-blue-500 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-600"
              :disabled="isProcessing"
              @click="executeAction('deployment')"
            >
              Restart Deployment
            </button>
          </div>
        </div>

        <p
          v-if="questState === 'ready'"
          class="mt-6 rounded-lg border border-slate-100 bg-slate-50 p-4 text-sm text-slate-700"
        >
          Welcome, operator. Pick the right remediation path for each simulated incident. Wrong
          choices reduce score.
        </p>

        <div v-if="showSummary" class="mt-6 rounded-lg border border-slate-100 bg-slate-50 p-4">
          <p class="text-lg font-semibold text-slate-900">Quest complete 🎯</p>
          <p class="mt-2 text-sm text-slate-600">Final score: {{ score }} out of {{ maxScore }}</p>
        </div>

        <div class="mt-8">
          <h2 class="text-sm font-semibold text-slate-900">Action log</h2>
          <ul class="mt-3 space-y-2">
            <li v-for="entry in eventLog" :key="entry" class="text-sm text-slate-600">
              {{ entry }}
            </li>
            <li v-if="eventLog.length === 0" class="text-sm text-slate-500">No actions yet.</li>
          </ul>
        </div>
      </div>
    </div>
  </main>
</template>
