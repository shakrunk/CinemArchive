// The Ledger view: stat hero + ribbon above a 12-column board of widget
// instances, with an edit mode (floating palette/details, drag reorder, edge
// resize). Panels live in ./panels, editor chrome in ./editor.

import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useShallow } from 'zustand/react/shallow'
import { Pencil, Check, ChevronUp, PanelLeftOpen, PanelRightOpen } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { LEDGER_PANEL_LABELS, LEDGER_PANEL_STANDARD_HEIGHT, defaultLedgerWidgets } from 'src/lib/ledgerPanels'
import { DashHero, StatRibbon } from './LedgerHero'
import { LedgerSkeleton } from './LedgerSkeleton'
import { PANEL_REGISTRY, WIDTH_GRID_CLASSES } from './panelRegistry'
import { WidgetPalette } from './editor/WidgetPalette'
import { WidgetDetails } from './editor/WidgetDetails'
import { useBoardDrag } from './editor/useBoardDrag'
import { floatingPanelStyle } from './editor/chrome'

export function Ledger() {
  // ⚡ Bolt: Batch Zustand selectors to reduce store subscriptions
  const {
    ownWidgets,
    viewedLedgerWidgets,
    addLedgerWidget,
    resetLedgerPrefs,
    friendView,
    isSharedView,
    loadingUser,
    libraryLoadError,
    titlesEmpty,
    loadUserLibrary
  } = useAppStore(
    useShallow((s) => ({
      ownWidgets: s.ledgerPrefs.widgets,
      viewedLedgerWidgets: s.viewedLedgerWidgets,
      addLedgerWidget: s.addLedgerWidget,
      resetLedgerPrefs: s.resetLedgerPrefs,
      friendView: s.viewerContext.kind === 'friend' ? s.viewerContext : null,
      isSharedView: s.isSharedView,
      loadingUser: s.loadingUser,
      libraryLoadError: s.libraryLoadError,
      titlesEmpty: s.titles.length === 0,
      loadUserLibrary: s.loadUserLibrary
    }))
  )
  const canEdit = !friendView && !isSharedView
  // Shared/friend views render the owner's synced board arrangement, falling
  // back to the default board when they never synced one. Editing is disabled
  // there, so the drag hook (which operates on the viewer's own prefs) never
  // touches these. Memoized: defaultLedgerWidgets() mints fresh ids per call.
  const defaultBoard = useMemo(() => defaultLedgerWidgets(), [])
  const widgets = canEdit ? ownWidgets : (viewedLedgerWidgets ?? defaultBoard)

  const [editing, setEditing] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [paletteHidden, setPaletteHidden] = useState(false)
  const [detailsHidden, setDetailsHidden] = useState(false)
  const editButtonRef = useRef<HTMLButtonElement>(null)
  const paletteContainerRef = useRef<HTMLDivElement>(null)

  function widgetById(id: string) {
    return widgets.find((w) => w.id === id)
  }

  /** Select a widget; a fresh selection also reveals the details panel if it
   *  was hidden — selecting is an explicit request to see its details. */
  function selectWidget(id: string | null) {
    setSelectedId(id)
    if (id) setDetailsHidden(false)
  }

  function stopEditing() {
    setEditing(false)
    setSelectedId(null)
  }

  // Esc: first press deselects the widget, second exits edit mode (focus
  // returns to the Edit button so keyboard users aren't stranded).
  useEffect(() => {
    if (!editing) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      if (selectedId) {
        setSelectedId(null)
      } else {
        setEditing(false)
        editButtonRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [editing, selectedId])

  // Move focus into the palette when edit mode opens (non-modal region).
  useEffect(() => {
    if (editing) paletteContainerRef.current?.focus()
  }, [editing])

  const {
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
  } = useBoardDrag({ selectWidget })

  // Initial fetch (or shared/friend load) with nothing to show yet — shimmer
  // instead of a flash of zeroed stats and empty-state panels.
  if (loadingUser && titlesEmpty) {
    return (
      <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
        <LedgerSkeleton />
      </div>
    )
  }

  // The load failed outright — surface it instead of a misleading empty board.
  if (libraryLoadError && titlesEmpty) {
    return (
      <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
        <DashHero />
        <div className="rounded-xl border border-dashed py-14 px-6 text-center" style={{ borderColor: 'rgba(200,90,60,0.4)' }}>
          <p className="font-serif text-lg text-paper">The projector jammed.</p>
          <p className="mt-2 text-sm text-paper-faint">{libraryLoadError}</p>
          {!isSharedView && (
            <button
              type="button"
              onClick={() => void loadUserLibrary()}
              className="mt-5 text-xs font-mono text-amber border border-amber/30 rounded-md px-4 py-2 hover:bg-amber/10 transition-colors"
            >
              Try again
            </button>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <div className="flex items-start justify-between gap-4">
        <DashHero />
        {canEdit && (
          <button
            type="button"
            ref={editButtonRef}
            onClick={() => (editing ? stopEditing() : setEditing(true))}
            className={cn(
              'shrink-0 inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 mt-1 text-xs font-sans border transition-colors',
              editing ? 'border-amber/40 bg-amber/10 text-amber' : 'border-[var(--line)] text-paper-faint hover:text-paper hover:border-[var(--line-2)]'
            )}
          >
            {editing ? <Check className="w-3.5 h-3.5" /> : <Pencil className="w-3.5 h-3.5" />}
            {editing ? 'Done' : 'Edit layout'}
          </button>
        )}
      </div>
      <StatRibbon />

      <div ref={boardRef}>
        {widgets.length === 0 && (
          <div
            className="rounded-xl border border-dashed py-16 px-6 text-center"
            style={{ borderColor: paletteOverId === 'end' ? 'var(--amber)' : 'var(--line-2)' }}
          >
            <p className="text-sm text-paper-faint">
              The board is empty.{' '}
              {editing ? 'Drag widgets here from the palette.' : canEdit ? 'Use “Edit layout” to add widgets.' : ''}
            </p>
          </div>
        )}
        <div
          ref={gridRef}
          className="grid grid-cols-12 gap-4"
          style={
            paletteOverId === 'end' && widgets.length > 0
              ? { outline: '2px dashed var(--amber)', outlineOffset: 8, borderRadius: 12 }
              : undefined
          }
        >
          {widgets.map((widget) => {
          const id = widget.id
          const { Component } = PANEL_REGISTRY[widget.panel]
          const isDragging = draggingId === id
          const isSettling = settlingId === id
          const isResizing = resizingId === id
          const isSelected = editing && selectedId === id
          // Reorder drags rearrange the board live, so only palette drags
          // still need a drop-target outline.
          const isOver = editing && !isDragging && paletteOverId === id

          return (
            <div
              key={id}
              ref={(el) => {
                if (el) itemRefs.current.set(id, el)
                else itemRefs.current.delete(id)
              }}
              onPointerDown={editing ? (e) => handlePanelPointerDown(e, id) : undefined}
              onPointerMove={editing ? handlePanelPointerMove : undefined}
              onPointerUp={editing ? (e) => handlePanelPointerEnd(e, id) : undefined}
              onPointerCancel={editing ? (e) => handlePanelPointerEnd(e, id) : undefined}
              className={cn(
                'relative flex flex-col',
                WIDTH_GRID_CLASSES[widget.width],
                editing && 'select-none',
                editing && !isDragging && 'cursor-grab',
                isDragging && 'cursor-grabbing',
              )}
              style={{
                height: LEDGER_PANEL_STANDARD_HEIGHT,
                // The drag translate is applied imperatively by useBoardDrag
                // (element.style.transform) to avoid per-pointermove renders.
                transition: isDragging || isResizing ? 'none' : 'transform 180ms ease',
                zIndex: isDragging || isSettling ? 20 : undefined,
                outline: isOver
                  ? '2px dashed var(--amber)'
                  : isResizing || isSelected
                    ? '2px solid var(--amber)'
                    : undefined,
                outlineOffset: '2px',
                borderRadius: '0.75rem',
                boxShadow: isDragging || isSettling ? '0 20px 50px -12px rgba(0,0,0,0.6)' : undefined,
              }}
            >
              <div
                className={cn(
                  'flex-1 min-h-0',
                  editing ? 'pointer-events-none overflow-hidden' : 'overflow-y-auto overflow-x-hidden scrollbar-thin',
                )}
              >
                <Component className="min-h-full" settings={widget.settings} width={widget.width} />
              </div>
              {editing && (
                <>
                  {/* Side edge handles — width only (desktop; panels are always
                      full-width below lg). Invisible until hovered so the edit
                      preview matches the live board. */}
                  <div
                    {...resizeHandleProps(id, 'e')}
                    title="Drag to resize width"
                    className="hidden lg:flex absolute inset-y-0 -right-2 w-4 z-10 cursor-ew-resize items-center justify-center group/re"
                  >
                    <span className="w-[3px] h-9 rounded-full bg-transparent group-hover/re:bg-amber transition-colors" />
                  </div>
                  <div
                    {...resizeHandleProps(id, 'w')}
                    title="Drag to resize width"
                    className="hidden lg:flex absolute inset-y-0 -left-2 w-4 z-10 cursor-ew-resize items-center justify-center group/rw"
                  >
                    <span className="w-[3px] h-9 rounded-full bg-transparent group-hover/rw:bg-amber transition-colors" />
                  </div>
                </>
              )}
            </div>
          )
        })}
        </div>
        {editing && widgets.length > 0 && (
          <p className="mt-4 font-mono text-[10px] tracking-[0.14em] uppercase text-paper-faint">
            click a widget to select · drag to reorder · drag side edges to resize · drag from the palette to add
          </p>
        )}
      </div>

      {/* ── Floating editor panels ── rendered in a portal: an ancestor
          (main.animate-view-in) keeps a transform, which would otherwise turn
          position:fixed into position:absolute-within-it. */}
      {editing && createPortal(
        <>
          {/* Palette — floats on the left (desktop), collapsible to an edge tab */}
          {paletteHidden ? (
            <button
              type="button"
              onClick={() => setPaletteHidden(false)}
              aria-label="Show widget palette"
              className="hidden lg:flex fixed left-0 top-28 z-40 items-center gap-1.5 rounded-r-md border border-l-0 border-[var(--line)] py-2.5 pl-1.5 pr-2 text-paper-faint hover:text-amber transition-colors"
              style={floatingPanelStyle}
            >
              <PanelLeftOpen className="w-4 h-4" />
              <span className="font-mono text-[9px] tracking-[0.16em] uppercase [writing-mode:vertical-rl]">
                Widgets
              </span>
            </button>
          ) : (
            <div
              ref={paletteContainerRef}
              tabIndex={-1}
              className="hidden lg:flex fixed left-4 xl:left-6 top-24 bottom-6 w-[280px] z-40 outline-none"
            >
              <WidgetPalette
                className="flex-1 min-h-0"
                onItemPointerDown={handlePaletteItemPointerDown}
                onItemPointerMove={handlePaletteItemPointerMove}
                onItemPointerEnd={handlePaletteItemPointerEnd}
                onItemActivate={(panel) => selectWidget(addLedgerWidget(panel))}
                onClose={stopEditing}
                onReset={() => {
                  resetLedgerPrefs()
                  setSelectedId(null)
                }}
                onHide={() => setPaletteHidden(true)}
              />
            </div>
          )}
          {/* Details — floats on the right when a widget is selected (desktop),
              collapsible to an edge tab without losing the selection */}
          {selectedId && widgetById(selectedId) && (
            detailsHidden ? (
              <button
                type="button"
                onClick={() => setDetailsHidden(false)}
                aria-label="Show widget details"
                className="hidden lg:flex fixed right-0 top-28 z-40 items-center gap-1.5 rounded-l-md border border-r-0 border-[var(--line)] py-2.5 pl-2 pr-1.5 text-paper-faint hover:text-amber transition-colors"
                style={floatingPanelStyle}
              >
                <span className="font-mono text-[9px] tracking-[0.16em] uppercase [writing-mode:vertical-rl]">
                  Details
                </span>
                <PanelRightOpen className="w-4 h-4" />
              </button>
            ) : (
              <div className="hidden lg:block fixed right-4 xl:right-6 top-24 w-[270px] z-40">
                <WidgetDetails
                  selectedId={selectedId}
                  onSelect={selectWidget}
                  onHide={() => setDetailsHidden(true)}
                />
              </div>
            )
          )}
          {/* Mobile — one bottom sheet: details when selected, palette otherwise;
              hideable to a reopen chip */}
          {(selectedId && widgetById(selectedId) ? detailsHidden : paletteHidden) ? (
            <button
              type="button"
              onClick={() =>
                selectedId && widgetById(selectedId) ? setDetailsHidden(false) : setPaletteHidden(false)
              }
              aria-label="Show layout editor panel"
              className="lg:hidden fixed right-3 bottom-20 z-[210] inline-flex items-center gap-1.5 rounded-md border border-[var(--line)] px-3 py-2 font-mono text-[10px] tracking-[0.14em] uppercase text-amber"
              style={floatingPanelStyle}
            >
              <ChevronUp className="w-3.5 h-3.5" />
              {selectedId && widgetById(selectedId) ? 'Details' : 'Widgets'}
            </button>
          ) : (
            <div className="lg:hidden fixed inset-x-3 bottom-20 z-[210] flex max-h-[min(62vh,520px)] pb-[env(safe-area-inset-bottom)]">
              {selectedId && widgetById(selectedId) ? (
                <WidgetDetails
                  className="flex-1"
                  selectedId={selectedId}
                  onSelect={selectWidget}
                  onHide={() => setDetailsHidden(true)}
                />
              ) : (
                <WidgetPalette
                  className="flex-1 min-h-0"
                  onItemPointerDown={handlePaletteItemPointerDown}
                  onItemPointerMove={handlePaletteItemPointerMove}
                  onItemPointerEnd={handlePaletteItemPointerEnd}
                  onItemActivate={(panel) => selectWidget(addLedgerWidget(panel))}
                  onClose={stopEditing}
                  onReset={() => {
                    resetLedgerPrefs()
                    setSelectedId(null)
                  }}
                  onHide={() => setPaletteHidden(true)}
                />
              )}
            </div>
          )}
          {/* Drag ghost following the pointer while adding from the palette */}
          {paletteGhost && (
            <div
              className="fixed z-[220] pointer-events-none -translate-x-1/2 -translate-y-1/2"
              style={{ left: paletteGhost.x, top: paletteGhost.y }}
            >
              <span
                className="rounded-md border border-amber/40 px-3 py-1.5 font-mono text-[10px] uppercase tracking-widest text-amber"
                style={{ background: 'var(--ink-1)', boxShadow: '0 12px 32px -8px rgba(0,0,0,0.7)' }}
              >
                {LEDGER_PANEL_LABELS[paletteGhost.panel]}
              </span>
            </div>
          )}
        </>,
        document.body,
      )}
    </div>
  )
}
