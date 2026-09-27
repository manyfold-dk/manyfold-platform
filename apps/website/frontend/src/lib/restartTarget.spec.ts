import { describe, expect, it } from 'vitest'
import type { OperatorAlert } from '@/types'
import { restartTargetFromAlert } from './restartTarget'

function alert(overrides: Partial<OperatorAlert>): OperatorAlert {
  return {
    fingerprint: 'f1',
    name: 'KubePodCrashLooping',
    severity: 'critical',
    namespace: 'shop',
    summary: 'Pod is crash looping',
    description: null,
    startsAt: '2026-09-23T10:00:00Z',
    labels: {},
    ...overrides
  }
}

describe('restartTargetFromAlert', () => {
  it('takes the pod and deployment from the labels', () => {
    const target = restartTargetFromAlert(
      alert({ labels: { pod: 'shop-api-7f8c-x2x9q', deployment: 'shop-api' } })
    )
    expect(target).toEqual({
      namespace: 'shop',
      podName: 'shop-api-7f8c-x2x9q',
      deploymentName: 'shop-api'
    })
  })

  it('leaves what the alert does not name empty instead of guessing from the alert name', () => {
    const target = restartTargetFromAlert(alert({ name: 'KubeJobFailed' }))
    expect(target.podName).toBe('')
    expect(target.deploymentName).toBe('')
  })

  it('falls back to the namespace label, and to empty', () => {
    expect(
      restartTargetFromAlert(alert({ namespace: null, labels: { namespace: 'ops' } })).namespace
    ).toBe('ops')
    expect(restartTargetFromAlert(alert({ namespace: null })).namespace).toBe('')
  })
})
