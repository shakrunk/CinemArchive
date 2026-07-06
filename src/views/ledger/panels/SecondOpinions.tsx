// ─── Second opinions (your rating vs the critics) ─────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { scopedTitles } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty } from '../PanelShell'

export function SecondOpinions({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const settingsKey = JSON.stringify(settings ?? {})

  const rows = useMemo(() => {
    const { titles: scoped, topN } = scopedTitles('verdicts', titles, settings)
    return scoped
      .filter((t) => typeof t.rating === 'number' && typeof t.imdbRating === 'number')
      .map((t) => {
        const mine = (t.rating as number) * 2 // 0–5 stars → 0–10 scale
        const critics = t.imdbRating as number
        return { title: t, mine, critics, delta: mine - critics }
      })
      .sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta))
      .slice(0, topN)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [titles, settingsKey])

  return (
    <Panel
      title={settings?.title || 'Second opinions'}
      hint={`your call vs the critics${describeLedgerSettings(settings)}`}
      className={className}
    >
      {rows.length === 0 ? (
        <PanelEmpty message="Rate a few titles to compare against IMDb" />
      ) : (
        <ol className="flex flex-col gap-1">
          {rows.map((r, i) => {
            const up = r.delta >= 0
            return (
              <li key={r.title.id}>
                <button
                  onClick={() => {
                    setFilter('search', r.title.title)
                    requestView('library')
                  }}
                  className="w-full grid items-center gap-3 px-1.5 py-2 rounded-md transition-colors hover:bg-[var(--wash)] text-left cursor-pointer group"
                  style={{ gridTemplateColumns: '1fr minmax(90px, 130px) auto' }}
                >
                  <div className="min-w-0">
                    <span
                      className="font-serif text-sm font-medium text-paper truncate block group-hover:underline decoration-amber/40"
                      style={{ fontVariationSettings: '"opsz" 30' }}
                    >
                      {r.title.title}
                    </span>
                    <span className="font-mono text-[10px] text-paper-faint">{r.title.year}</span>
                  </div>
                  <div className="flex flex-col gap-1">
                    <span className="flex items-center gap-1.5">
                      <span className="font-mono text-[8px] uppercase tracking-widest text-paper-faint w-7 shrink-0">you</span>
                      <span className="flex-1 h-[6px] rounded-full bg-[var(--wash)] overflow-hidden">
                        <span
                          className="block h-full rounded-full bar-fill"
                          style={{ width: `${r.mine * 10}%`, background: 'var(--amber-bright)', animationDelay: `${i * 60}ms` }}
                        />
                      </span>
                    </span>
                    <span className="flex items-center gap-1.5">
                      <span className="font-mono text-[8px] uppercase tracking-widest text-paper-faint w-7 shrink-0">imdb</span>
                      <span className="flex-1 h-[6px] rounded-full bg-[var(--wash)] overflow-hidden">
                        <span
                          className="block h-full rounded-full bar-fill"
                          style={{ width: `${r.critics * 10}%`, background: 'var(--moon)', animationDelay: `${i * 60 + 40}ms` }}
                        />
                      </span>
                    </span>
                  </div>
                  <span
                    className="font-mono text-[11px] px-1.5 py-0.5 rounded-md border shrink-0"
                    style={{
                      color: up ? 'var(--amber-bright)' : 'var(--ember)',
                      borderColor: up ? 'rgba(233,178,102,0.3)' : 'rgba(200,90,60,0.35)',
                    }}
                    title={`You: ${r.mine.toFixed(1)} · IMDb: ${r.critics.toFixed(1)}`}
                  >
                    {up ? '+' : '−'}{Math.abs(r.delta).toFixed(1)}
                  </span>
                </button>
              </li>
            )
          })}
        </ol>
      )}
    </Panel>
  )
}
