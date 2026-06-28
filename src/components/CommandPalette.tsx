import { useState, useEffect, useMemo, useRef } from 'react'
import * as DialogPrimitive from '@radix-ui/react-dialog'
import { Search, CornerDownLeft } from 'lucide-react'
import { rankCommands, type Command } from 'src/store/commands'
import { useAppStore } from 'src/store/useAppStore'
import type { AppView } from 'src/lib/navigation'
import { cn } from 'src/lib/utils'

interface CommandPaletteProps {
  open: boolean
  onClose: () => void
  onNavigate: (view: AppView) => void
}

export function CommandPalette({ open, onClose, onNavigate }: CommandPaletteProps) {
  const titles = useAppStore((s) => s.titles)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const openAddTitle = useAppStore((s) => s.openAddTitle)
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const setViewMode = useAppStore((s) => s.setViewMode)

  const { commands, runMap } = useMemo(() => {
    const list: Command[] = []
    const map: Record<string, () => void> = {}

    if (!isSharedView) {
      list.push({ id: 'action:add', kind: 'action', label: 'Add a title', hint: 'new', keywords: 'create new movie series' })
      map['action:add'] = () => openAddTitle()
    }
    list.push({ id: 'action:view-upnext', kind: 'action', label: 'Go to Up Next', hint: 'view', keywords: 'continue watching' })
    map['action:view-upnext'] = () => onNavigate('upnext')
    list.push({ id: 'action:view-library', kind: 'action', label: 'Go to the Library', hint: 'view', keywords: 'collection posters' })
    map['action:view-library'] = () => onNavigate('library')
    list.push({ id: 'action:view-ledger', kind: 'action', label: 'Go to the Ledger', hint: 'view', keywords: 'stats dashboard' })
    map['action:view-ledger'] = () => onNavigate('ledger')
    list.push({ id: 'action:layout-grid', kind: 'action', label: 'Library: poster wall', hint: 'layout', keywords: 'grid posters' })
    map['action:layout-grid'] = () => { onNavigate('library'); setViewMode('grid') }
    list.push({ id: 'action:layout-list', kind: 'action', label: 'Library: ledger list', hint: 'layout', keywords: 'list table' })
    map['action:layout-list'] = () => { onNavigate('library'); setViewMode('list') }

    for (const t of titles) {
      const id = `title:${t.id}`
      const hint = [t.director ? `dir. ${t.director}` : t.type === 'tv' ? 'series' : 'film', t.year]
        .filter(Boolean)
        .join(' · ')
      list.push({ id, kind: 'title', label: t.title, hint, keywords: t.genres.join(' ') })
      map[id] = () => openDetailDrawer(t.id)
    }
    return { commands: list, runMap: map }
  }, [titles, isSharedView, openAddTitle, openDetailDrawer, setViewMode, onNavigate])

  function handleRun(cmd: Command) {
    onClose()
    runMap[cmd.id]?.()
  }
  return (
    <DialogPrimitive.Root open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="command-overlay" />
        <DialogPrimitive.Content className="command-content" aria-label="Command palette">
          <DialogPrimitive.Title className="sr-only">Command palette</DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            Search titles or run an action. Use arrow keys and Enter.
          </DialogPrimitive.Description>
          {/* Body holds the query state. Radix unmounts Content when closed, so the
              body remounts fresh on each open — no manual reset needed. */}
          <CommandPaletteBody commands={commands} onRun={handleRun} />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

function CommandPaletteBody({
  commands,
  onRun,
}: {
  commands: Command[]
  onRun: (cmd: Command) => void
}) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const listRef = useRef<HTMLDivElement>(null)

  const results = useMemo(() => rankCommands(commands, query, 8), [commands, query])

  // Keep the active row scrolled into view (no setState — safe in an effect).
  useEffect(() => {
    const el = listRef.current?.querySelector<HTMLElement>(`[data-idx="${active}"]`)
    el?.scrollIntoView({ block: 'nearest' })
  }, [active])

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((i) => (results.length ? (i + 1) % results.length : 0))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((i) => (results.length ? (i - 1 + results.length) % results.length : 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const cmd = results[active]
      if (cmd) onRun(cmd)
    }
  }

  return (
    <div onKeyDown={handleKeyDown}>
      <div className="command-input-row">
        <Search className="w-[18px] h-[18px] text-paper-faint shrink-0" />
        <input
          autoFocus
          aria-label="Search command palette"
          value={query}
          onChange={(e) => { setQuery(e.target.value); setActive(0) }}
          placeholder="Search titles, jump to a view, add a title…"
          className="command-input"
          autoComplete="off"
          spellCheck={false}
          role="combobox"
          aria-expanded
          aria-controls="command-results"
          aria-activedescendant={results[active] ? `cmd-${results[active].id}` : undefined}
        />
        <kbd className="command-kbd">ESC</kbd>
      </div>

      <div id="command-results" ref={listRef} role="listbox" className="command-list">
        {results.length === 0 ? (
          <div className="command-empty">No matches. Try a different search.</div>
        ) : (
          results.map((cmd, idx) => (
            <button
              key={cmd.id}
              id={`cmd-${cmd.id}`}
              data-idx={idx}
              role="option"
              aria-selected={idx === active}
              onMouseEnter={() => setActive(idx)}
              onClick={() => onRun(cmd)}
              className={cn('command-item', idx === active && 'is-active')}
            >
              <span
                className={cn(
                  'command-item__kind',
                  cmd.kind === 'action' ? 'text-amber' : 'text-paper-faint'
                )}
              >
                {cmd.kind === 'action' ? '›' : '▸'}
              </span>
              <span className="command-item__label">{cmd.label}</span>
              {cmd.hint && <span className="command-item__hint">{cmd.hint}</span>}
              {idx === active && (
                <CornerDownLeft className="w-3.5 h-3.5 text-amber shrink-0 ml-1" />
              )}
            </button>
          ))
        )}
      </div>
    </div>
  )
}
