import { describe, expect, it, vi } from 'vitest'
import { required, runJourney } from './journey.ts'
import type { JourneyResult } from '../shared/metrics.ts'

const meta = { front: 'shop', journey: 'smoke', tenant: 'platform', loginExpected: false }

describe('runJourney', () => {
  it('pushes up=1 when the journey passes', async () => {
    const push = vi.fn<(r: JourneyResult) => Promise<void>>(async () => {})
    await runJourney(meta, async () => {}, push)
    expect(push.mock.calls[0]![0].up).toBe(1)
  })

  it('pushes up=0 and reports the journey error, not the push error', async () => {
    const push = vi.fn(async () => {
      throw new Error('pushgateway down')
    })
    await expect(
      runJourney(
        meta,
        async () => {
          throw new Error('heading missing')
        },
        push,
      ),
    ).rejects.toThrow('heading missing')
    expect(push).toHaveBeenCalledOnce()
  })

  it('fails a passing journey whose result could not be pushed', async () => {
    await expect(
      runJourney(
        meta,
        async () => {},
        async () => {
          throw new Error('pushgateway down')
        },
      ),
    ).rejects.toThrow('pushgateway down')
  })

  it('pushes up=0 when a setting the journey reads is missing', async () => {
    const push = vi.fn<(r: JourneyResult) => Promise<void>>(async () => {})
    await expect(
      runJourney(
        meta,
        async () => {
          required('SYNTHETIC_TEST_UNSET')
        },
        push,
      ),
    ).rejects.toThrow('SYNTHETIC_TEST_UNSET is not set')
    expect(push.mock.calls[0]![0].up).toBe(0)
  })

  it('counts a login only when the journey expects one and passed', async () => {
    const push = vi.fn<(r: JourneyResult) => Promise<void>>(async () => {})
    await runJourney({ ...meta, loginExpected: true }, async () => {}, push)
    expect(push.mock.calls[0]![0].loginSuccess).toBe(1)
  })
})
