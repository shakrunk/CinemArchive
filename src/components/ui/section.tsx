import type { LucideIcon } from 'lucide-react'
import { cn } from 'src/lib/utils'

/**
 * Section shell: uniform heading/description/card framing. Pass `id` when the
 * section is a scroll-to anchor target (e.g. Profile's settings list); omit
 * it for standalone pages that don't need one (e.g. Friends).
 */
export function Section({
  id,
  title,
  Icon,
  description,
  children,
}: {
  id?: string
  title: string
  Icon: LucideIcon
  description?: string
  children: React.ReactNode
}) {
  return (
    <section id={id ? `settings-${id}` : undefined} className={cn(id && 'scroll-mt-24')}>
      <h2 className="font-sans text-xs uppercase tracking-widest text-muted-foreground flex items-center gap-1.5 mb-2">
        <Icon className="w-3.5 h-3.5 text-amber" />
        {title}
      </h2>
      {description && (
        <p className="font-sans text-xs text-muted-foreground leading-relaxed mb-3 max-w-[60ch]">
          {description}
        </p>
      )}
      <div
        className="rounded-xl border p-4 sm:p-5 space-y-4"
        style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
      >
        {children}
      </div>
    </section>
  )
}
