import { describe, expect, it } from 'vitest'
import { escapeLabelValue, formatJourney, gauge } from './metrics.ts'

describe('gauge', () => {
  it('writes the TYPE header with every sample', () => {
    expect(gauge('synthetic_check_up', { front: 'www' }, 1)).toBe(
      '# TYPE synthetic_check_up gauge\nsynthetic_check_up{front="www"} 1',
    )
  })

  it('escapes label values: a quote cannot end the label early', () => {
    expect(escapeLabelValue('a"b\\c\nd')).toBe('a\\"b\\\\c\\nd')
    expect(gauge('m', { front: 'x"}' }, 0)).toContain('m{front="x\\"}"} 0')
  })
})

describe('formatJourney', () => {
  it('renders the runner metrics with internal vantage', () => {
    const out = formatJourney({
      front: 'shop',
      journey: 'login',
      tenant: 'platform',
      up: 1,
      durationSeconds: 2.5,
      loginSuccess: 1,
      runTimestamp: 1000,
    })
    const labels = '{front="shop",journey="login",vantage="internal",tenant="platform"}'
    expect(out).toContain(`synthetic_check_up${labels} 1`)
    expect(out).toContain(`synthetic_login_success${labels} 1`)
    expect(out).toContain(`synthetic_check_duration_seconds${labels} 2.5`)
    expect(out).toContain(`synthetic_run_timestamp_seconds${labels} 1000`)
    expect(out.endsWith('\n')).toBe(true)
  })
})
