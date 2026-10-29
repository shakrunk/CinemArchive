import { SubsectionLabel } from 'src/components/ui/typography'
import { cn } from 'src/lib/utils'

// Every drawer section sits in one of these bordered cards so related content
// reads as a common region (gestalt) instead of floating in open space.

export function SectionCard({
  title,
  action,
  className,
  children,
}: {
  title?: React.ReactNode
  /** Rendered on the right of the heading row (e.g. a "Log a viewing" button). */
  action?: React.ReactNode
  className?: string
  children: React.ReactNode
}) {
  return (
    <section
      className={cn('rounded-xl border p-4 sm:p-5 min-w-0', className)}
      style={{ borderColor: 'var(--line)', background: 'var(--wash)' }}
    >
      {(title || action) && (
        <div className="flex items-center justify-between gap-3 mb-4">
          <SubsectionLabel className="mb-0">{title}</SubsectionLabel>
          {action}
        </div>
      )}
      {children}
    </section>
  )
}
