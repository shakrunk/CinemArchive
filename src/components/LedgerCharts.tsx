// Reusable chart primitives for the Ledger view. Pure SVG/CSS — no charting
// library is bundled (see CLAUDE.md), so these are hand-rolled and driven by
// the same CSS custom properties as the rest of the design system, which
// keeps them correct across the dark/light theme swap.

import type { CSSProperties } from 'react'

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

