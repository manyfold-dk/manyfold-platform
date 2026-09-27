import { describe, expect, it } from 'vitest'
import { decisions, findDecision, parseDecision } from './decisions'

describe('parseDecision', () => {
  const raw = [
    '---',
    'number: "0001"',
    'series: Platform',
    'title: A title: with a colon',
    'status: Accepted',
    'date: 2026-01-01',
    'summary: Short.',
    'changes: Nothing.',
    '---',
    '',
    '## Status',
    ''
  ].join('\n')

  it('reads front matter and body', () => {
    const decision = parseDecision('0001-a-title', raw)
    expect(decision.number).toBe('0001')
    expect(decision.title).toBe('A title: with a colon')
    expect(decision.body).toBe('## Status')
  })

  it('rejects a record without front matter', () => {
    expect(() => parseDecision('x', '## Status')).toThrow(/missing front matter/)
  })

  it('rejects a record that lacks a required field', () => {
    expect(() => parseDecision('x', raw.replace('changes: Nothing.\n', ''))).toThrow(/"changes"/)
  })
})

describe('published decisions', () => {
  it('lists records newest first and finds them by slug', () => {
    expect(decisions.length).toBeGreaterThan(0)
    const dates = decisions.map((decision) => decision.date)
    expect(dates).toEqual([...dates].sort().reverse())
    expect(findDecision(decisions[0]!.slug)).toBe(decisions[0])
    expect(findDecision('no-such-record')).toBeUndefined()
  })

  it('names each file after its number', () => {
    for (const decision of decisions) {
      expect(decision.slug.startsWith(`${decision.number}-`)).toBe(true)
    }
  })

  // The published records describe a running platform. These patterns are the
  // generic half of the publication check: anything shaped like an address, an
  // internal host, an exact version or a server type fails the build. Names are
  // checked outside the repository, because a list of names would itself leak.
  const forbidden: [string, RegExp][] = [
    ['IPv4 address', /\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/],
    ['exact version', /\bv?\d+\.\d+\.\d+\b/],
    ['manyfold.dk subdomain', /\b[a-z0-9-]+\.manyfold\.dk\b/i],
    ['tailnet host', /\bts\.net\b/i],
    ['cluster-internal host', /\.svc\b|\.cluster\.local\b/i],
    ['encrypted secret file', /\.enc\.ya?ml\b/i],
    ['Hetzner server type', /\bc[acpx]x?\d{2}\b/i],
    // The lookbehind skips the same words inside an external URL.
    ['repository path', /(?<![\w/.-])(platform|infrastructure|docs|apps)\/[a-z0-9-]+\//i]
  ]

  it.each(forbidden)('contains no %s', (_label, pattern) => {
    for (const decision of decisions) {
      const text = `${decision.summary}\n${decision.changes}\n${decision.body}`
      const hit = pattern.exec(text)
      expect(hit?.[0], `${decision.slug} contains "${hit?.[0]}"`).toBeUndefined()
    }
  })
})
