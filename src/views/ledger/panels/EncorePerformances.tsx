// ─── Encore performances ──────────────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { Panel } from '../PanelShell'

export function EncorePerformances({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)

  const encores = useMemo(() => {
    return titles
      .filter((t) => t.viewings.length >= 2)
      .sort((a, b) => b.viewings.length - a.viewings.length)
      .slice(0, 6)
  }, [titles])

  const requestView = useAppStore((s) => s.requestView)

  return (
    <Panel title="Encore performances" hint="most revisited" className={className}>
      {encores.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 gap-3">
          <p className="text-center text-sm text-paper-faint">No title has screened twice yet</p>
          <button
            onClick={() => requestView('library')}
            className="text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
          >
            Browse Library
          </button>
        </div>
      ) : (
        <ol className="flex flex-col gap-1">
          {encores.map((t, i) => (
            <li
              key={t.id}
              className="grid items-center gap-3 px-1.5 py-2.5 rounded-md transition-colors hover:bg-[var(--wash)]"
              style={{ gridTemplateColumns: '26px 1fr auto' }}
            >
              <span className="font-mono text-xs text-amber-deep">{String(i + 1).padStart(2, '0')}</span>
              <div className="min-w-0">
                <span
                  className="font-serif text-sm font-medium text-paper truncate block"
                  style={{ fontVariationSettings: '"opsz" 30' }}
                >
                  {t.title}
                </span>
                <span className="font-mono text-[10px] text-paper-faint">{t.year}</span>
              </div>
              <span className="flex gap-0.5 shrink-0">
                {Array.from({ length: Math.min(t.viewings.length, 6) }, (_, k) => (
                  <i key={k} className="w-[5px] h-[5px] rounded-full bg-amber" />
                ))}
              </span>
            </li>
          ))}
        </ol>
      )}
    </Panel>
  )
}
