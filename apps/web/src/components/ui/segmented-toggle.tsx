import { cn } from 'src/lib/utils'

export function SegmentedToggle<T extends string>({
  ariaLabel,
  options,
  value,
  onChange,
}: {
  ariaLabel: string
  options: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  return (
    <div className="grid grid-cols-2 gap-2" role="tablist" aria-label={ariaLabel}>
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          role="tab"
          aria-selected={value === opt.value}
          onClick={() => onChange(opt.value)}
          className={cn(
            'rounded-lg border py-2.5 font-sans text-sm font-medium transition-colors',
            value === opt.value
              ? 'border-amber/50 bg-amber/10 text-amber'
              : 'border-border bg-secondary/20 text-muted-foreground hover:border-amber/25'
          )}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}
