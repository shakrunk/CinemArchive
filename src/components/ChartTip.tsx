// Reusable chart tooltip for the Ledger's hand-rolled visualizations,
// replacing native `title=` attributes (which never fire on touch and can't
// be styled). One hook per panel:
//
//   const tip = useChartTip()
//   <div {...tip.bind({ label: '12 Mar 2026', value: '2 screenings' })} />
//   {tip.node}
//
// Mouse: follows the cursor, clamped to the viewport. Touch/pen: shows
// anchored above the tapped element for a moment. bind() also composes an
// aria-label from the label/value pair, so the exact figure is exposed to
// screen readers for free.

import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

export interface ChartTipContent {
  label: string
  value?: string
}

interface TipState extends ChartTipContent {
  x: number
  y: number
}

const TOUCH_DISMISS_MS = 1800
const OFFSET = 14

function clampToViewport(x: number, y: number): { x: number; y: number } {
  const pad = 8
  return {
    x: Math.min(Math.max(x, pad), window.innerWidth - pad),
    y: Math.min(Math.max(y, pad), window.innerHeight - pad),
  }
}

export function useChartTip() {
  const [tip, setTip] = useState<TipState | null>(null)
  const touchTimer = useRef<number | undefined>(undefined)

  useEffect(() => () => window.clearTimeout(touchTimer.current), [])

  const bind = useCallback(
    (content: ChartTipContent) => ({
      'aria-label': content.value ? `${content.label}: ${content.value}` : content.label,
      onPointerEnter: (e: React.PointerEvent) => {
        if (e.pointerType !== 'mouse') return
        setTip({ ...content, ...clampToViewport(e.clientX + OFFSET, e.clientY + OFFSET) })
      },
      onPointerMove: (e: React.PointerEvent) => {
        if (e.pointerType !== 'mouse') return
        setTip({ ...content, ...clampToViewport(e.clientX + OFFSET, e.clientY + OFFSET) })
      },
      onPointerLeave: (e: React.PointerEvent) => {
        if (e.pointerType !== 'mouse') return
        setTip(null)
      },
      onPointerDown: (e: React.PointerEvent) => {
        if (e.pointerType === 'mouse') return
        // Anchor above the tapped element rather than under the finger.
        const rect = e.currentTarget.getBoundingClientRect()
        setTip({ ...content, ...clampToViewport(rect.left + rect.width / 2, rect.top - OFFSET) })
        window.clearTimeout(touchTimer.current)
        touchTimer.current = window.setTimeout(() => setTip(null), TOUCH_DISMISS_MS)
      },
    }),
    [],
  )

  const node = tip
    ? createPortal(
        <div
          role="tooltip"
          className="fixed z-[300] pointer-events-none -translate-x-1/2 -translate-y-full rounded-md border border-[var(--line)] px-2.5 py-1.5 animate-[chart-tip-in_80ms_ease-out]"
          style={{
            left: tip.x,
            top: tip.y,
            background: 'linear-gradient(168deg, var(--ink-1), var(--ink-2))',
            boxShadow: '0 12px 32px -10px rgba(0,0,0,0.7)',
            borderLeft: '2px solid var(--amber)',
            maxWidth: 260,
          }}
        >
          <span className="block font-mono text-[9px] tracking-[0.14em] uppercase text-paper-faint whitespace-nowrap">
            {tip.label}
          </span>
          {tip.value && (
            <span className="block text-[12px] text-paper mt-0.5 whitespace-nowrap">{tip.value}</span>
          )}
        </div>,
        document.body,
      )
    : null

  return { bind, node }
}
