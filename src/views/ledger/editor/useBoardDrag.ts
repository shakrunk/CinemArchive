// Pointer-driven board interactions for the Ledger's edit mode: reorder drag,
// edge resize (width presets only — heights are standardized), and dragging a
// panel type from the palette onto the board.

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import type { LedgerPanelId } from 'src/lib/ledgerPanels'
import { LEDGER_PANEL_WIDTH_SPANS, nearestPanelWidth } from 'src/lib/ledgerPanels'

/** Which side edge a resize drag started from. Heights are standardized, so
 *  edges only adjust the grid-column width (snapped to the S/M/L/Full presets). */
type ResizeEdge = 'e' | 'w'

interface ResizeMeta {
  id: string
  edge: ResizeEdge
  startX: number
  startSpan: number
  colWidth: number
}

interface DragMeta {
  id: string
  startX: number
  startY: number
  active: boolean
  // Live-reorder bookkeeping: the panel's untransformed layout position at
  // activation, the cumulative layout shift from reorders committed mid-drag,
  // and the last pointer delta — together they keep the held panel pinned
  // under the cursor while its grid slot moves out from beneath it.
  layoutLeft: number
  layoutTop: number
  shiftX: number
  shiftY: number
  lastDX: number
  lastDY: number
}

interface PaletteDragMeta {
  panel: LedgerPanelId
  startX: number
  startY: number
  active: boolean
}

// Pixels of pointer travel before a press on a panel becomes a reorder drag.
const DRAG_THRESHOLD = 6

// setPointerCapture can throw (NotFoundError) if the pointer is already gone
// by the time the handler runs — treat capture as best-effort.
function capturePointer(el: Element, pointerId: number) {
  try {
    el.setPointerCapture(pointerId)
  } catch {
    /* ignore */
  }
}

export function useBoardDrag({ selectWidget }: { selectWidget: (id: string | null) => void }) {
  const widgets = useAppStore((s) => s.ledgerPrefs.widgets)
  const reorderLedgerWidgets = useAppStore((s) => s.reorderLedgerWidgets)
  const addLedgerWidget = useAppStore((s) => s.addLedgerWidget)
  const setLedgerWidgetWidth = useAppStore((s) => s.setLedgerWidgetWidth)

  const itemRefs = useRef(new Map<string, HTMLDivElement>())
  const gridRef = useRef<HTMLDivElement>(null)
  const boardRef = useRef<HTMLDivElement>(null)

  // Reorder drag — the whole panel is the drag surface in edit mode.
  // Reorders commit live while the pointer is still down; `overRef` only
  // gates re-triggering while the pointer stays over the same panel.
  const dragRef = useRef<DragMeta | null>(null)
  const overRef = useRef<string | null>(null)
  const [draggingId, setDraggingId] = useState<string | null>(null)
  // Keeps the released panel elevated while it settles into its slot.
  const [settlingId, setSettlingId] = useState<string | null>(null)
  const settleTimerRef = useRef<number | undefined>(undefined)
  useEffect(() => () => window.clearTimeout(settleTimerRef.current), [])

  // Resize drag — handles live on the panel's side edges.
  const resizeRef = useRef<ResizeMeta | null>(null)
  const [resizingId, setResizingId] = useState<string | null>(null)

  // Palette drag — dropping a panel type from the palette onto the board.
  const paletteDragRef = useRef<PaletteDragMeta | null>(null)
  const paletteOverRef = useRef<string | null>(null)
  const [paletteGhost, setPaletteGhost] = useState<{ panel: LedgerPanelId; x: number; y: number } | null>(null)
  const [paletteOverId, setPaletteOverId] = useState<string | null>(null)

  function widgetById(id: string) {
    return widgets.find((w) => w.id === id)
  }

  // ── FLIP animation ────────────────────────────────────────────────────────
  // When a reorder, insert, or resize changes the board arrangement, the other
  // panels should slide into their new spot instead of snapping (CSS grid has
  // no native transition for implicit position changes). Call captureFlipRects
  // right before triggering the store update that will move things; the
  // layout effect below then measures the after-state and plays the delta.
  const flipRectsRef = useRef<Map<string, DOMRect> | null>(null)

  function captureFlipRects() {
    const rects = new Map<string, DOMRect>()
    for (const [id, el] of itemRefs.current) rects.set(id, el.getBoundingClientRect())
    flipRectsRef.current = rects
  }

  const widgetArrangementKey = widgets.map((w) => `${w.id}:${w.width}`).join('|')

  useLayoutEffect(() => {
    const before = flipRectsRef.current
    flipRectsRef.current = null
    if (!before) return
    const drag = dragRef.current
    for (const [id, el] of itemRefs.current) {
      if (drag?.active && id === drag.id) {
        // Don't FLIP the held panel — its pointer-follow transform must stay
        // put. Re-anchor it to the new grid slot so the panel doesn't jump
        // when its layout position changes under it.
        const rect = el.getBoundingClientRect()
        const currentX = drag.lastDX - drag.shiftX
        const currentY = drag.lastDY - drag.shiftY
        drag.shiftX = rect.left - currentX - drag.layoutLeft
        drag.shiftY = rect.top - currentY - drag.layoutTop
        el.style.transition = 'none'
        el.style.transform = `translate(${drag.lastDX - drag.shiftX}px, ${drag.lastDY - drag.shiftY}px)`
        continue
      }
      const from = before.get(id)
      if (!from) continue
      const to = el.getBoundingClientRect()
      const dx = from.left - to.left
      const dy = from.top - to.top
      if (!dx && !dy) continue
      el.style.transition = 'none'
      el.style.transform = `translate(${dx}px, ${dy}px)`
      // Force layout so the browser commits the inverted start position
      // before we transition it back to identity.
      el.getBoundingClientRect()
      el.style.transition = 'transform 220ms ease'
      el.style.transform = ''
    }
  }, [widgetArrangementKey])

  // ── Reorder handlers ──────────────────────────────────────────────────────

  function handlePanelPointerDown(e: React.PointerEvent<HTMLDivElement>, id: string) {
    if (e.button !== 0 || resizeRef.current) return
    // Toolbar buttons and resize handles opt out of starting a reorder drag.
    if ((e.target as HTMLElement).closest('[data-ledger-control]')) return
    dragRef.current = {
      id,
      startX: e.clientX,
      startY: e.clientY,
      active: false,
      layoutLeft: 0,
      layoutTop: 0,
      shiftX: 0,
      shiftY: 0,
      lastDX: 0,
      lastDY: 0,
    }
    overRef.current = null
    capturePointer(e.currentTarget, e.pointerId)
  }

  function handlePanelPointerMove(e: React.PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current
    if (!drag) return
    const dx = e.clientX - drag.startX
    const dy = e.clientY - drag.startY
    if (!drag.active) {
      if (Math.hypot(dx, dy) < DRAG_THRESHOLD) return
      drag.active = true
      const rect = itemRefs.current.get(drag.id)?.getBoundingClientRect()
      if (rect) {
        drag.layoutLeft = rect.left
        drag.layoutTop = rect.top
      }
      setDraggingId(drag.id)
    }
    drag.lastDX = dx
    drag.lastDY = dy
    // Apply the drag transform imperatively — routing it through React state
    // re-rendered the entire board on every pointermove.
    const el = itemRefs.current.get(drag.id)
    if (el) el.style.transform = `translate(${dx - drag.shiftX}px, ${dy - drag.shiftY}px)`
    let hit: string | null = null
    for (const [id, item] of itemRefs.current) {
      if (id === drag.id) continue
      const rect = item.getBoundingClientRect()
      if (e.clientX >= rect.left && e.clientX <= rect.right && e.clientY >= rect.top && e.clientY <= rect.bottom) {
        hit = id
        break
      }
    }
    if (hit !== overRef.current) {
      overRef.current = hit
      if (hit) {
        // Commit the reorder while the panel is still held — the FLIP effect
        // slides the other panels around it, and re-anchors the held panel's
        // transform to its new slot. Read the order from the store: with
        // rapid pointermoves the `widgets` closure can be a render behind.
        captureFlipRects()
        const ids = useAppStore.getState().ledgerPrefs.widgets.map((w) => w.id)
        const from = ids.indexOf(drag.id)
        const to = ids.indexOf(hit)
        if (from !== -1 && to !== -1 && from !== to) {
          ids.splice(from, 1)
          ids.splice(to, 0, drag.id)
          reorderLedgerWidgets(ids)
        } else {
          flipRectsRef.current = null
        }
      }
    }
  }

  function handlePanelPointerEnd(e: React.PointerEvent<HTMLDivElement>, id: string) {
    const drag = dragRef.current
    dragRef.current = null
    if (!drag) return
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
    const dragged = itemRefs.current.get(drag.id)
    if (!drag.active) {
      // A press without movement is a selection, not a drag.
      selectWidget(id)
    } else if (dragged) {
      // The order is already committed; ease the panel into its slot instead
      // of snapping, keeping it elevated until the settle finishes.
      dragged.style.transition = 'transform 180ms ease'
      dragged.style.transform = ''
      setSettlingId(drag.id)
      window.clearTimeout(settleTimerRef.current)
      settleTimerRef.current = window.setTimeout(() => setSettlingId(null), 200)
    }
    overRef.current = null
    setDraggingId(null)
  }

  // ── Resize handlers (width only — heights are standardized) ──────────────

  function startResize(e: React.PointerEvent<HTMLDivElement>, id: string, edge: ResizeEdge) {
    if (e.button !== 0) return
    e.preventDefault()
    e.stopPropagation()
    const widget = widgetById(id)
    if (!widget) return
    const gridWidth = gridRef.current?.getBoundingClientRect().width ?? 0
    resizeRef.current = {
      id,
      edge,
      startX: e.clientX,
      startSpan: LEDGER_PANEL_WIDTH_SPANS[widget.width],
      colWidth: gridWidth > 0 ? gridWidth / 12 : 100,
    }
    selectWidget(id)
    setResizingId(id)
    capturePointer(e.currentTarget, e.pointerId)
  }

  function moveResize(e: React.PointerEvent<HTMLDivElement>) {
    const r = resizeRef.current
    if (!r) return
    // Snap the dragged column span to the nearest preset.
    const dir = r.edge === 'w' ? -1 : 1
    const span = r.startSpan + (dir * (e.clientX - r.startX)) / r.colWidth
    const width = nearestPanelWidth(span)
    if (width !== widgetById(r.id)?.width) {
      captureFlipRects()
      setLedgerWidgetWidth(r.id, width)
    }
  }

  function endResize(e: React.PointerEvent<HTMLDivElement>) {
    const r = resizeRef.current
    resizeRef.current = null
    if (!r) return
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
    setResizingId(null)
  }

  function resizeHandleProps(id: string, edge: ResizeEdge) {
    return {
      'data-ledger-control': true,
      onPointerDown: (e: React.PointerEvent<HTMLDivElement>) => startResize(e, id, edge),
      onPointerMove: moveResize,
      onPointerUp: endResize,
      onPointerCancel: endResize,
      style: { touchAction: 'none' } as React.CSSProperties,
    }
  }

  // ── Palette drag — add a widget by dragging it onto the board ────────────

  function handlePaletteItemPointerDown(e: React.PointerEvent<HTMLDivElement>, panel: LedgerPanelId) {
    if (e.button !== 0) return
    paletteDragRef.current = { panel, startX: e.clientX, startY: e.clientY, active: false }
    paletteOverRef.current = null
    capturePointer(e.currentTarget, e.pointerId)
  }

  function handlePaletteItemPointerMove(e: React.PointerEvent<HTMLDivElement>) {
    const drag = paletteDragRef.current
    if (!drag) return
    const dx = e.clientX - drag.startX
    const dy = e.clientY - drag.startY
    if (!drag.active) {
      if (Math.hypot(dx, dy) < DRAG_THRESHOLD) return
      drag.active = true
    }
    setPaletteGhost({ panel: drag.panel, x: e.clientX, y: e.clientY })
    // Hit-test board cards first; anywhere else over the board appends.
    let hit: string | null = null
    for (const [id, el] of itemRefs.current) {
      const rect = el.getBoundingClientRect()
      if (e.clientX >= rect.left && e.clientX <= rect.right && e.clientY >= rect.top && e.clientY <= rect.bottom) {
        hit = id
        break
      }
    }
    if (!hit) {
      const board = boardRef.current?.getBoundingClientRect()
      if (board && e.clientX >= board.left && e.clientX <= board.right && e.clientY >= board.top) {
        hit = 'end'
      }
    }
    paletteOverRef.current = hit
    setPaletteOverId(hit)
  }

  function handlePaletteItemPointerEnd(e: React.PointerEvent<HTMLDivElement>) {
    const drag = paletteDragRef.current
    paletteDragRef.current = null
    if (!drag) return
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
    const over = paletteOverRef.current
    paletteOverRef.current = null
    setPaletteGhost(null)
    setPaletteOverId(null)
    if (!drag.active) {
      // A plain tap/click adds to the end of the board.
      selectWidget(addLedgerWidget(drag.panel))
      return
    }
    if (!over) return // dropped outside the board — cancel
    captureFlipRects()
    const newId = addLedgerWidget(drag.panel)
    if (over !== 'end') {
      const ids = widgets.map((w) => w.id)
      ids.splice(ids.indexOf(over), 0, newId)
      reorderLedgerWidgets(ids)
    }
    selectWidget(newId)
  }

  return {
    itemRefs,
    gridRef,
    boardRef,
    draggingId,
    settlingId,
    resizingId,
    paletteGhost,
    paletteOverId,
    handlePanelPointerDown,
    handlePanelPointerMove,
    handlePanelPointerEnd,
    resizeHandleProps,
    handlePaletteItemPointerDown,
    handlePaletteItemPointerMove,
    handlePaletteItemPointerEnd,
  }
}
