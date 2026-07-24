// Reusable chart primitives for the Ledger view. Pure SVG/CSS — no charting
// library is bundled (see CLAUDE.md), so these are hand-rolled and driven by
// the same CSS custom properties as the rest of the design system, which
// keeps them correct across the dark/light theme swap.

import { useId, type CSSProperties, type ReactNode } from 'react'
import { areaPath, linePath, type ChartPoint } from 'src/lib/utils'
import type { ChartTipContent, useChartTip } from './ChartTip'

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

// ─── Mini line chart ("sparkline") ──────────────────────────────────────────

export interface SparklinePoint extends ChartPoint {
  /** Tooltip content for this point's dot; omit to render no dot at this
   *  point (e.g. a zero-count month in a gap-filled series). */
  tooltip?: ChartTipContent
}

/** Gradient-filled, animated-draw line chart with HTML dot markers, shared by
 *  every ledger panel that plots a value over an ordered x-axis. Dots are
 *  HTML `<span>`s rather than SVG `<circle>`s because the chart's
 *  `preserveAspectRatio="none"` stretches x/y independently, which would
 *  otherwise turn circles into ellipses. */
export function MiniLineChart({
  points,
  viewBoxHeight,
  areaBaseline,
  heightPx,
  lineColor,
  areaColor,
  areaOpacity = 0.35,
  dotSize = 10,
  dotStyle = 'ring',
  tipBind,
  beforePaths,
}: {
  points: SparklinePoint[]
  /** Total SVG viewBox height (x always spans 0–1000) — also the denominator for dot `top%` placement. */
  viewBoxHeight: number
  /** Y at which the area fill polygon closes. */
  areaBaseline: number
  /** Rendered container height in pixels. */
  heightPx: number
  lineColor: string
  /** Gradient fill color; defaults to `lineColor`. */
  areaColor?: string
  areaOpacity?: number
  dotSize?: number
  /** 'ring': ink-filled dot with a colored border (default). 'filled': a solid `lineColor` dot. */
  dotStyle?: 'ring' | 'filled'
  tipBind: ReturnType<typeof useChartTip>['bind']
  /** Extra SVG content (e.g. a reference line) rendered before the area/line paths. */
  beforePaths?: ReactNode
}) {
  const gradientId = useId()

  return (
    <div className="relative w-full" style={{ height: heightPx }}>
      <svg
        viewBox={`0 0 1000 ${viewBoxHeight}`}
        preserveAspectRatio="none"
        className="absolute inset-0 w-full h-full block"
      >
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={areaColor ?? lineColor} stopOpacity={areaOpacity} />
            <stop offset="100%" stopColor={areaColor ?? lineColor} stopOpacity="0" />
          </linearGradient>
        </defs>
        {beforePaths}
        <path d={areaPath(points, areaBaseline)} fill={`url(#${gradientId})`} />
        <path
          d={linePath(points)}
          fill="none"
          stroke={lineColor}
          strokeWidth={3}
          strokeLinecap="round"
          strokeLinejoin="round"
          pathLength={1}
          className="chart-path-draw"
          style={{ strokeDasharray: 1 }}
          vectorEffect="non-scaling-stroke"
        />
      </svg>
      {points.map(
        (p, i) =>
          p.tooltip && (
            <span
              key={i}
              {...tipBind(p.tooltip)}
              className="absolute rounded-full -translate-x-1/2 -translate-y-1/2"
              style={{
                left: `${(p.x / 1000) * 100}%`,
                top: `${(p.y / viewBoxHeight) * 100}%`,
                width: dotSize,
                height: dotSize,
                background: dotStyle === 'ring' ? 'var(--ink-1)' : lineColor,
                border: dotStyle === 'ring' ? `2.5px solid ${lineColor}` : undefined,
              }}
            />
          ),
      )}
    </div>
  )
}
