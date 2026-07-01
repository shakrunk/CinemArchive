// Reusable chart primitives for the Ledger view. Pure SVG/CSS — no charting
// library is bundled (see CLAUDE.md), so these are hand-rolled and driven by
// the same CSS custom properties as the rest of the design system, which
// keeps them correct across the dark/light theme swap.

import type { CSSProperties } from 'react'

// ─── Rating → color (theme-safe, no hardcoded RGB) ─────────────────────────

// Note: --amber-muted/--amber-subtle exist only as *-rgb triples (consumed by
// Tailwind's rgb(var(...) / <alpha>) pattern) — there is no standalone
// var(--amber-muted) custom property, so it must be wrapped here.
const RATING_COLOR_VARS: Record<number, string> = {
  5: 'var(--amber-bright)',
  4.5: 'var(--amber)',
  4: 'var(--amber-deep)',
  3.5: 'rgb(var(--amber-muted-rgb))',
  3: 'rgb(var(--amber-subtle-rgb))',
  2.5: 'var(--ember-soft)',
  2: 'var(--ember-soft)',
  1.5: 'var(--ember)',
  1: 'var(--ember)',
}

export function ratingColorVar(rating: number): string {
  return RATING_COLOR_VARS[rating] ?? 'var(--paper-faint)'
}

// ─── Radial progress ring ───────────────────────────────────────────────────

export function RadialRing({
  pct,
  size = 36,
  stroke = 4,
  color,
  delay = 0,
}: {
  pct: number
  size?: number
  stroke?: number
  color: string
  delay?: number
}) {
  const r = (size - stroke) / 2
  const c = 2 * Math.PI * r
  const offset = c * (1 - Math.max(0, Math.min(1, pct)))

  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="-rotate-90 shrink-0">
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--wash)" strokeWidth={stroke} />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={r}
        fill="none"
        stroke={color}
        strokeWidth={stroke}
        strokeLinecap="round"
        strokeDasharray={c}
        style={
          {
            '--ring-circumference': c,
            '--ring-offset': offset,
            animation: `ring-draw 0.9s var(--ease) forwards`,
            animationDelay: `${delay}ms`,
          } as CSSProperties
        }
      />
    </svg>
  )
}

// ─── Line/area path builders (SVG, viewBox-unit coordinates) ───────────────

export interface ChartPoint {
  x: number
  y: number
}

export function linePath(points: ChartPoint[]): string {
  if (points.length === 0) return ''
  return points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x},${p.y}`).join(' ')
}

export function areaPath(points: ChartPoint[], baselineY: number): string {
  if (points.length === 0) return ''
  const line = linePath(points)
  const first = points[0]
  const last = points[points.length - 1]
  return `${line} L ${last.x},${baselineY} L ${first.x},${baselineY} Z`
}

// ─── Misc ───────────────────────────────────────────────────────────────────

export function getInitials(name: string): string {
  const parts = name.trim().split(/\s+/)
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}
