import type { LucideIcon } from 'lucide-react'
import { cn, SECONDARY_AMBER_BUTTON_LG } from 'src/lib/utils'

/** Page-level "nothing here yet" state — icon, heading, subtext, and a CTA. */
export function EmptyState({
  Icon,
  title,
  subtext,
  subtextClassName,
  ctaLabel,
  onCta,
  ctaClassName,
}: {
  Icon: LucideIcon
  title: string
  subtext: React.ReactNode
  subtextClassName?: string
  ctaLabel: string
  onCta: () => void
  ctaClassName?: string
}) {
  return (
    <div className="text-center py-24 px-5 text-paper-faint">
      <Icon className="w-14 h-14 mx-auto mb-5 text-amber-deep opacity-50" />
      <p className="font-serif text-2xl text-paper-dim font-light">{title}</p>
      <p className={cn('font-sans text-sm mt-2 opacity-70', subtextClassName)}>{subtext}</p>
      <button onClick={onCta} className={cn(SECONDARY_AMBER_BUTTON_LG, ctaClassName)}>
        {ctaLabel}
      </button>
    </div>
  )
}
