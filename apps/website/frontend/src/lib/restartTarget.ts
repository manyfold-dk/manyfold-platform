import type { OperatorAlert } from '@/types'

export interface RestartTarget {
  namespace: string
  podName: string
  deploymentName: string
}

/**
 * The restart form's starting values, taken from the alert's own labels. A field the alert does
 * not carry stays empty for the operator to fill in: a name guessed from the alert name (as this
 * page once did, turning `KubeJobFailed` into a pod called `kubejobfailed`) targets a pod that does
 * not exist.
 */
export function restartTargetFromAlert(alert: OperatorAlert): RestartTarget {
  const labels = alert.labels ?? {}
  return {
    namespace: alert.namespace || labels.namespace || '',
    podName: labels.pod || '',
    deploymentName: labels.deployment || ''
  }
}
