// External probe: a Cloudflare Worker that checks each public front from outside the cluster
// every two minutes and pushes the result to the token-gated Pushgateway ingest. Credential-free:
// it asserts the status code and HSTS, nothing behind a login.

import { gauge } from '../shared/metrics.ts'

export interface Env {
  INGEST_URL: string
  INGEST_TOKEN: string
  /** JSON list of {front, url, expect}: the fronts to probe and the status each must answer. */
  TARGETS: string
}

export interface Target {
  front: string
  url: string
  expect: number
}

/** The targets from the TARGETS variable; a malformed entry is skipped and logged. */
export function parseTargets(raw: string | undefined): Target[] {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw ?? '[]')
  } catch {
    console.error('synthetic-probe: TARGETS is not JSON')
    return []
  }
  if (!Array.isArray(parsed)) return []
  return parsed.filter((t): t is Target => {
    const ok =
      typeof t?.front === 'string' &&
      typeof t?.url === 'string' &&
      /^https:\/\//.test(t.url) &&
      Number.isInteger(t?.expect)
    if (!ok) console.error('synthetic-probe: skipping malformed target', JSON.stringify(t))
    return ok
  })
}

/** One probe's samples. `fetcher` is injectable for tests. */
export async function probe(
  target: Target,
  fetcher: (url: string) => Promise<Response> = (url) =>
    fetch(url, { redirect: 'manual', cf: { cacheTtl: 0 } } as RequestInit),
): Promise<string> {
  const labels = { front: target.front, journey: 'edge', vantage: 'external', tenant: 'platform' }
  const start = Date.now()
  let up = 0
  let hsts = 0
  try {
    const res = await fetcher(target.url)
    up = res.status === target.expect ? 1 : 0
    hsts = res.headers.get('strict-transport-security') ? 1 : 0
  } catch {
    // unreachable: up stays 0
  }
  const duration = (Date.now() - start) / 1000
  return (
    [
      gauge('synthetic_check_up', labels, up),
      gauge('synthetic_check_duration_seconds', labels, duration),
      gauge('synthetic_assertion_ok', { ...labels, assertion: 'hsts' }, hsts),
      gauge('synthetic_run_timestamp_seconds', labels, Math.floor(Date.now() / 1000)),
    ].join('\n') + '\n'
  )
}

/**
 * Pushes one front's samples. A refused push (a rotated token, an ingest outage) counts as a
 * failure like a network error: the Pushgateway would otherwise keep serving the last result.
 */
export async function push(
  front: string,
  body: string,
  env: Pick<Env, 'INGEST_URL' | 'INGEST_TOKEN'>,
  fetcher: (url: string, init: RequestInit) => Promise<Response> = fetch,
): Promise<boolean> {
  try {
    const res = await fetcher(
      `${env.INGEST_URL}/metrics/job/synthetic-external/front/${encodeURIComponent(front)}`,
      {
        method: 'PUT',
        headers: { Authorization: `Bearer ${env.INGEST_TOKEN}`, 'Content-Type': 'text/plain' },
        body,
      },
    )
    if (res.ok) return true
    console.error(`synthetic-probe: push refused for ${front}: HTTP ${res.status}`)
  } catch (e) {
    console.error(`synthetic-probe: push failed for ${front}:`, e)
  }
  return false
}

export default {
  async scheduled(_event: ScheduledEvent, env: Env): Promise<void> {
    const failed: string[] = []
    for (const target of parseTargets(env.TARGETS)) {
      // One front's failed push must not stop the others.
      if (!(await push(target.front, await probe(target), env))) failed.push(target.front)
    }
    // Fail the invocation, so the cron trigger's own history shows the run as failed.
    if (failed.length > 0) throw new Error(`synthetic-probe: push failed for ${failed.join(', ')}`)
  },
}
