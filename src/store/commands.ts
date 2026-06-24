// Pure command model + ranking for the command palette. Verified by
// scripts/verify-command-logic.mjs. No React / store imports here.

export interface Command {
  id: string
  kind: 'title' | 'action'
  label: string
  hint?: string        // shown muted on the right (e.g. "dir. Villeneuve · 2017")
  keywords?: string    // extra match text (genres, synonyms); ranks below label
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

// Tiered match score; -1 means "no match". Shorter labels get a tiny boost so
// ties resolve to the more specific item. Empty query → 0 (keep input order).
export function scoreCommand(cmd: Command, query: string): number {
  const q = query.trim().toLowerCase()
  if (!q) return 0
  const label = cmd.label.toLowerCase()
  const kw = (cmd.keywords ?? '').toLowerCase()
  const lenAdj = Math.min(label.length, 40) * 0.1
  const boundary = new RegExp(`\\b${escapeRegex(q)}`)

  if (label === q) return 100 - lenAdj
  if (label.startsWith(q)) return 80 - lenAdj
  if (boundary.test(label)) return 60 - lenAdj
  if (label.includes(q)) return 40 - lenAdj
  if (kw) {
    if (boundary.test(kw)) return 20
    if (kw.includes(q)) return 10
  }
  return -1
}

export function rankCommands(commands: Command[], query: string, limit = 8): Command[] {
  if (!query.trim()) return commands.slice(0, limit)
  const scored = commands
    .map((cmd) => ({ cmd, score: scoreCommand(cmd, query) }))
    .filter((x) => x.score >= 0)
  scored.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score
    if (a.cmd.label.length !== b.cmd.label.length) return a.cmd.label.length - b.cmd.label.length
    return a.cmd.id < b.cmd.id ? -1 : a.cmd.id > b.cmd.id ? 1 : 0
  })
  return scored.slice(0, limit).map((x) => x.cmd)
}
