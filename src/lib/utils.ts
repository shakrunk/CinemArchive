import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function fmtDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

/** Format a YYYY-MM-DD date as e.g. "Jul 16, 2010", parsing as local-naive to avoid TZ drift. */
export function fmtReleaseDate(ymd: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(ymd)
  if (!m) return ymd
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

/** Map an ISO 639-1 language code to its English display name, falling back to the upper-cased code. */
export function languageName(code: string): string {
  try {
    const name = new Intl.DisplayNames(['en'], { type: 'language' }).of(code)
    if (name && name.toLowerCase() !== code.toLowerCase()) return name
  } catch {
    // Intl.DisplayNames unsupported — fall through to the raw code.
  }
  return code.toUpperCase()
}

/** '⌘' on macOS/iOS, 'Ctrl' everywhere else. */
export const modKey: '⌘' | 'Ctrl' =
  typeof navigator !== 'undefined' && /Mac|iPhone|iPad|iPod/.test(navigator.platform)
    ? '⌘'
    : 'Ctrl'

export function fmtDateTime(iso: string): { date: string; time: string } {
  const d = new Date(iso)
  return {
    date: d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }),
    time: d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true }),
  }
}

// ─── Chart Helpers ──────────────────────────────────────────────────────────

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

export function getInitials(name: string): string {
  const parts = name.trim().split(/\s+/)
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

/**
 * Per-card entrance delays (ms) for a staggered fade/slide-in grid, capped at `max`
 * concurrently-animated cards so a long list doesn't queue up a slow cascade — cards
 * past the cap render with no delay. The (i * 7) % n shuffle spreads the stagger order
 * across the grid instead of animating strictly left-to-right/row-by-row.
 */
export function staggerDelays(count: number, max = 24): number[] {
  const n = Math.min(count, max)
  return Array.from({ length: count }, (_, i) => {
    if (i >= max) return 0
    const slot = n > 0 ? (i * 7) % n : 0
    return slot * 15
  })
}

