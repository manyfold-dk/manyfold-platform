// Published architecture decision records. The Markdown under
// src/content/decisions is the scrubbed, public version of each record; the
// private originals stay in docs/adr and are never imported here.

export interface Decision {
  slug: string
  number: string
  series: string
  title: string
  status: string
  date: string
  summary: string
  changes: string
  body: string
}

const REQUIRED = ['number', 'series', 'title', 'status', 'date', 'summary', 'changes'] as const

// Front matter here is flat `key: value` lines, so a YAML parser is not needed.
export function parseDecision(slug: string, raw: string): Decision {
  const match = /^---\n([\s\S]*?)\n---\n?([\s\S]*)$/.exec(raw)
  if (!match) throw new Error(`${slug}: missing front matter`)

  const fields: Record<string, string> = {}
  for (const line of match[1]!.split('\n')) {
    const separator = line.indexOf(':')
    if (separator === -1) continue
    const value = line.slice(separator + 1).trim()
    fields[line.slice(0, separator).trim()] = value.replace(/^"(.*)"$/, '$1')
  }

  for (const key of REQUIRED) {
    if (!fields[key]) throw new Error(`${slug}: front matter lacks "${key}"`)
  }

  return {
    slug,
    number: fields.number!,
    series: fields.series!,
    title: fields.title!,
    status: fields.status!,
    date: fields.date!,
    summary: fields.summary!,
    changes: fields.changes!,
    body: match[2]!.trim()
  }
}

const files = import.meta.glob<string>('../content/decisions/*.md', {
  query: '?raw',
  import: 'default',
  eager: true
})

// Newest first: the index reads as a log of recent thinking.
export const decisions: Decision[] = Object.entries(files)
  .map(([path, raw]) => parseDecision(path.split('/').pop()!.replace(/\.md$/, ''), raw))
  .sort((a, b) => b.date.localeCompare(a.date))

export function findDecision(slug: string): Decision | undefined {
  return decisions.find((decision) => decision.slug === slug)
}
