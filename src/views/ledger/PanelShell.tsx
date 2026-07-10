// Shared card shell + empty state + small label helpers used across the
// Ledger's panels. Split out of Ledger.tsx so each panel lives in its own file.

import { useAppStore } from 'src/store/useAppStore'
import { cn, SECONDARY_AMBER_BUTTON } from 'src/lib/utils'

export function Panel({
  title,
  hint,
  className,
  children,
}: {
  title: string
  hint: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <article className={cn('panel p-6', className)}>
      <header className="panel__head mb-5">
        <h2 className="panel__title text-[21px]">{title}</h2>
        <span className="panel__hint">{hint}</span>
      </header>
      {children}
    </article>
  )
}

// Shared empty-state body for panels with no data yet.
export function PanelEmpty({ message }: { message: string }) {
  const requestView = useAppStore((s) => s.requestView)
  return (
    <div className="flex flex-col items-center justify-center py-8 gap-3">
      <p className="text-center text-sm text-paper-faint">{message}</p>
      <button onClick={() => requestView('library')} className={SECONDARY_AMBER_BUTTON}>
        Browse Library
      </button>
    </div>
  )
}

