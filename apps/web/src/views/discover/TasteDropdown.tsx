import { useState, useRef } from 'react'
import { Search, ChevronDown } from 'lucide-react'
import { cn } from 'src/lib/utils'
import { useClickOutside } from 'src/lib/useClickOutside'

interface TasteDropdownOption {
  id: string
  label: string
}

interface TasteDropdownProps {
  options: TasteDropdownOption[]
  value: string | null
  onChange: (id: string) => void
  ariaLabel: string
  placeholder?: string
}

// Options beyond this count get a filter input at the top of the menu — the cast
// list in particular grows with every library title and quickly outruns scrolling.
const TASTE_DROPDOWN_SEARCH_THRESHOLD = 8

export function TasteDropdown({ options, value, onChange, ariaLabel, placeholder = 'Select…' }: TasteDropdownProps) {
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState('')
  const ref = useRef<HTMLDivElement>(null)

  useClickOutside(ref, () => setOpen(false), open)

  const selected = options.find((o) => o.id === value)
  const searchable = options.length > TASTE_DROPDOWN_SEARCH_THRESHOLD
  const needle = filter.trim().toLowerCase()
  const visibleOptions = searchable && needle
    ? options.filter((o) => o.label.toLowerCase().includes(needle))
    : options

  function toggleOpen() {
    if (!open) setFilter('')
    setOpen(!open)
  }

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={toggleOpen}
        aria-label={ariaLabel}
        aria-expanded={open}
        disabled={options.length === 0}
        className="flex items-center gap-1.5 font-mono text-xs rounded-md border px-2.5 py-1.5 text-amber-bright hover:border-amber/40 transition-colors max-w-[220px] disabled:opacity-40 disabled:cursor-not-allowed"
        style={{ background: 'var(--inset)', borderColor: open ? 'rgb(var(--amber-rgb) / 0.4)' : 'var(--line)' }}
      >
        <span className="truncate">{selected?.label ?? placeholder}</span>
        <ChevronDown className={cn('w-3.5 h-3.5 shrink-0 transition-transform', open && 'rotate-180')} />
      </button>

      {open && (
        <div
          className="absolute left-0 top-[calc(100%+6px)] w-56 rounded-lg border shadow-2xl z-30 py-1 bg-card"
          style={{ borderColor: 'var(--line)' }}
        >
          {searchable && (
            <div className="px-1.5 pb-1">
              <div className="relative">
                <Search className="absolute left-2 top-1/2 -translate-y-1/2 w-3 h-3 text-paper-faint pointer-events-none" />
                <input
                  type="text"
                  autoFocus
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Escape') setOpen(false) }}
                  placeholder="Filter…"
                  aria-label={`Filter options for ${ariaLabel}`}
                  className="w-full h-7 pl-7 pr-2 rounded border text-[12px] font-sans text-paper placeholder:text-paper-faint focus:outline-hidden focus:ring-1 focus:ring-amber/30"
                  style={{ background: 'var(--inset)', borderColor: 'var(--line)' }}
                />
              </div>
            </div>
          )}
          <div
            role="listbox"
            aria-label={ariaLabel}
            className="max-h-56 overflow-y-auto scrollbar-thin"
          >
            {visibleOptions.length === 0 ? (
              <p className="px-3 py-2 text-[12px] font-mono text-paper-faint">No matches.</p>
            ) : (
              visibleOptions.map((o) => (
                <button
                  key={o.id}
                  type="button"
                  role="option"
                  aria-selected={o.id === value}
                  onClick={() => { onChange(o.id); setOpen(false) }}
                  className={cn(
                    'w-full text-left px-3 py-1.5 text-[13px] font-sans truncate transition-colors',
                    o.id === value
                      ? 'text-amber-bright bg-amber/15'
                      : 'text-paper-faint hover:text-paper hover:bg-(--inset-strong)'
                  )}
                >
                  {o.label}
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  )
}
