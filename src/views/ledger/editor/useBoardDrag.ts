// Pointer-driven board interactions for the Ledger's edit mode: reorder drag,
// edge resize (width presets only — heights are standardized), and dragging a
// panel type from the palette onto the board.

import { useRef, useState } from 'react'
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
  const dragRef = useRef<DragMeta | null>(null)
  const overRef = useRef<string | null>(null)
  const [draggingId, setDraggingId] = useState<string | null>(null)
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 })
  const [overId, setOverId] = useState<string | null>(null)

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

  // ── Reorder handlers ──────────────────────────────────────────────────────

  function handlePanelPointerDown(e: React.PointerEvent<HTMLDivElement>, id: string) {
    if (e.button !== 0 || resizeRef.current) return
    // Toolbar buttons and resize handles opt out of starting a reorder drag.
    if ((e.target as HTMLElement).closest('[data-ledger-control]')) return
    dragRef.current = { id, startX: e.clientX, startY: e.clientY, active: false }
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
      setDraggingId(drag.id)
    }
    setDragOffset({ x: dx, y: dy })
    let hit: string | null = null
    for (const [id, el] of itemRefs.current) {
      if (id === drag.id) continue
      const rect = el.getBoundingClientRect()
      if (e.clientX >= rect.left && e.clientX <= rect.right && e.clientY >= rect.top && e.clientY <= rect.bottom) {
        hit = id
        break
      }
    }
    overRef.current = hit
    setOverId(hit)
  }

  function handlePanelPointerEnd(e: React.PointerEvent<HTMLDivElement>, id: string) {
    const drag = dragRef.current
    dragRef.current = null
    if (!drag) return
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
    const over = overRef.current
    if (!drag.active) {
      // A press without movement is a selection, not a drag.
      selectWidget(id)
    } else if (over && over !== drag.id) {
      const ids = widgets.map((w) => w.id)
      const from = ids.indexOf(drag.id)
      const to = ids.indexOf(over)
      ids.splice(from, 1)
      ids.splice(to, 0, drag.id)
      reorderLedgerWidgets(ids)
    }
    overRef.current = null
    setDraggingId(null)
    setDragOffset({ x: 0, y: 0 })
    setOverId(null)
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
    if (width !== widgetById(r.id)?.width) setLedgerWidgetWidth(r.id, width)
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
    dragOffset,
    overId,
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
