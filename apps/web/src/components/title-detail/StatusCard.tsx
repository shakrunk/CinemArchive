import { ChevronDown } from 'lucide-react'
import { cn } from 'src/lib/utils'
import type { WatchStatus } from 'src/store/mockData'
import { SectionCard } from './SectionCard'

const STATUS_OPTIONS: { value: WatchStatus; label: string }[] = [
  { value: 'watched', label: 'Watched' },
  { value: 'watchlist', label: 'Watchlist' },
  { value: 'watching', label: 'Watching' },
  { value: 'dropped', label: 'Dropped' },
]

export function StatusCard({
  status,
  isSharedView,
  onChange,
}: {
  status: WatchStatus
  isSharedView: boolean
  onChange: (status: WatchStatus) => void
}) {
  return (
    <SectionCard title="Status">
      <div className="relative inline-block">
        <select
          value={status}
          onChange={isSharedView ? undefined : (e) => onChange(e.target.value as WatchStatus)}
          disabled={isSharedView}
          aria-label="Title status"
          className={cn(
            'appearance-none font-sans text-sm rounded-lg pl-3 pr-9 py-2 bg-secondary border border-amber/30 focus:outline-hidden focus:border-amber/60',
            isSharedView && 'opacity-60 cursor-default'
          )}
          style={{ color: 'var(--amber)' }}
        >
          {STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
        <ChevronDown
          className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4"
          style={{ color: 'var(--amber)' }}
        />
      </div>
    </SectionCard>
  )
}
