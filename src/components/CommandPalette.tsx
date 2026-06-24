import { useState, useEffect, useMemo, useRef } from 'react'
import * as DialogPrimitive from '@radix-ui/react-dialog'
import { Search, CornerDownLeft } from 'lucide-react'
import { rankCommands, type Command } from 'src/store/commands'
import { cn } from 'src/lib/utils'

interface CommandPaletteProps {
  open: boolean
  onClose: () => void
  commands: Command[]
  onRun: (cmd: Command) => void
}

export function CommandPalette({ open, onClose, commands, onRun }: CommandPaletteProps) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const listRef = useRef<HTMLDivElement>(null)

  const results = useMemo(() => rankCommands(commands, query, 8), [commands, query])

  // Keep the active row scrolled into view.
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
    <DialogPrimitive.Root
      open={open}
      onOpenChange={(o) => {
        if (!o) {
          // Reset here (not in an effect) so the next open starts fresh.
          setQuery('')
          setActive(0)
          onClose()
        }
      }}
    >
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="command-overlay" />
        <DialogPrimitive.Content
          className="command-content"
          onKeyDown={handleKeyDown}
          aria-label="Command palette"
        >
          <DialogPrimitive.Title className="sr-only">Command palette</DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            Search titles or run an action. Use arrow keys and Enter.
          </DialogPrimitive.Description>

          <div className="command-input-row">
            <Search className="w-[18px] h-[18px] text-paper-faint shrink-0" />
            <input
              autoFocus
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
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
