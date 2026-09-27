import { afterEach, describe, expect, it, vi } from 'vitest'
import worker, { parseTargets, probe, push } from './index.ts'

describe('parseTargets', () => {
  it('keeps well-formed targets and skips the rest', () => {
    const targets = parseTargets(
      JSON.stringify([
        { front: 'www', url: 'https://www.example.com/', expect: 200 },
        { front: 'app', url: 'http://plain.example.com/', expect: 302 },
        { front: 'x' },
      ]),
    )
    expect(targets).toEqual([{ front: 'www', url: 'https://www.example.com/', expect: 200 }])
  })

  it('treats a missing or broken variable as no targets', () => {
    expect(parseTargets(undefined)).toEqual([])
    expect(parseTargets('{not json')).toEqual([])
  })
})

describe('probe', () => {
  const target = { front: 'www', url: 'https://www.example.com/', expect: 200 }

  it('reports up and HSTS when the front answers as expected', async () => {
    const out = await probe(
      target,
      async () =>
        new Response(null, { status: 200, headers: { 'strict-transport-security': 'max-age=1' } }),
    )
    expect(out).toContain(
      'synthetic_check_up{front="www",journey="edge",vantage="external",tenant="platform"} 1',
    )
    expect(out).toContain(
      'synthetic_assertion_ok{front="www",journey="edge",vantage="external",tenant="platform",assertion="hsts"} 1',
    )
  })

  it('reports down when the status differs or the fetch fails', async () => {
    expect(await probe(target, async () => new Response(null, { status: 502 }))).toContain(
      'synthetic_check_up{front="www",journey="edge",vantage="external",tenant="platform"} 0',
    )
    expect(
      await probe(target, async () => {
        throw new Error('dns')
      }),
    ).toContain(
      'synthetic_check_up{front="www",journey="edge",vantage="external",tenant="platform"} 0',
    )
  })
})

describe('push', () => {
  const env = { INGEST_URL: 'https://ingest.example.com', INGEST_TOKEN: 't' }

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('succeeds only on a 2xx answer', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(await push('www', 'x 1\n', env, async () => new Response(null, { status: 200 }))).toBe(
      true,
    )
    expect(await push('www', 'x 1\n', env, async () => new Response(null, { status: 401 }))).toBe(
      false,
    )
    expect(
      await push('www', 'x 1\n', env, async () => {
        throw new Error('reset')
      }),
    ).toBe(false)
  })

  it('pushes every front and then fails the run naming the refused ones', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const pushed: string[] = []
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      if (init?.method !== 'PUT') return new Response(null, { status: 200 })
      pushed.push(url)
      return new Response(null, { status: url.endsWith('/front/app') ? 503 : 200 })
    })
    const targets = JSON.stringify([
      { front: 'app', url: 'https://app.example.com/', expect: 200 },
      { front: 'www', url: 'https://www.example.com/', expect: 200 },
    ])
    await expect(
      worker.scheduled({} as ScheduledEvent, { ...env, TARGETS: targets }),
    ).rejects.toThrow('push failed for app')
    expect(pushed).toHaveLength(2)
  })
})
