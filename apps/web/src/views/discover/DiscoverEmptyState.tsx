import { X, type LucideIcon } from 'lucide-react'
import { cn } from 'src/lib/utils'

export function DiscoverEmptyState({
  icon: Icon,
  message,
  onClearSearch,
  dim = false,
}: {
  icon: LucideIcon
  message: string
  onClearSearch?: () => void
  dim?: boolean
}) {
  return (
    <div className="py-16 text-center flex flex-col items-center">
      <Icon className={cn('w-10 h-10 mx-auto mb-3 text-paper-faint', dim ? 'opacity-20' : 'opacity-30')} />
      <p className={cn('font-mono text-sm text-paper-faint', onClearSearch && 'mb-4')}>{message}</p>
      {onClearSearch && (
        <button
          onClick={onClearSearch}
          className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber mx-auto rounded-sm focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
        >
          <X className="w-3.5 h-3.5" aria-hidden="true" />
          Clear search
        </button>
      )}
    </div>
  )
}
