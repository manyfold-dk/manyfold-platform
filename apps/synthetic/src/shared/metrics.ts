// Prometheus text exposition shared by the in-cluster runner and the edge Worker. Both push the
// same metric names to one Pushgateway, and a metric registered without a `# TYPE` line becomes
// UNTYPED: the Pushgateway then refuses the other side's typed push (HTTP 400). So every sample
// goes out with its TYPE header, from this one module.

export type Labels = Record<string, string>

/** Escapes a label value as the exposition format requires: backslash, quote, newline. */
export function escapeLabelValue(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n')
}

/** One gauge sample with its TYPE header. */
export function gauge(name: string, labels: Labels, value: number): string {
  const rendered = Object.entries(labels)
    .map(([key, v]) => `${key}="${escapeLabelValue(v)}"`)
    .join(',')
  return `# TYPE ${name} gauge\n${name}{${rendered}} ${value}`
}

/** A journey run by the in-cluster browser runner. */
export interface JourneyResult {
  front: string
  journey: string
  tenant: string
  up: 0 | 1
  durationSeconds: number
  loginSuccess: 0 | 1
  runTimestamp: number
}

export function formatJourney(r: JourneyResult): string {
  const labels = { front: r.front, journey: r.journey, vantage: 'internal', tenant: r.tenant }
  return (
    [
      gauge('synthetic_check_up', labels, r.up),
      gauge('synthetic_check_duration_seconds', labels, r.durationSeconds),
      gauge('synthetic_login_success', labels, r.loginSuccess),
      gauge('synthetic_run_timestamp_seconds', labels, r.runTimestamp),
    ].join('\n') + '\n'
  )
}

/** Pushes one journey result, grouped by front and journey so a re-run replaces the last. */
export async function pushJourney(r: JourneyResult, pushgatewayUrl: string): Promise<void> {
  const url = `${pushgatewayUrl}/metrics/job/synthetic-${encodeURIComponent(r.front)}/journey/${encodeURIComponent(r.journey)}`
  const res = await fetch(url, {
    method: 'PUT',
    body: formatJourney(r),
    headers: { 'Content-Type': 'text/plain' },
  })
  if (!res.ok) throw new Error(`pushgateway ${res.status}: ${await res.text()}`)
}
