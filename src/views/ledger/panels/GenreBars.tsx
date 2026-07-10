// ─── Genre bars ───────────────────────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { rankBubbleAccent } from 'src/lib/utils'
import { deriveTopGenres } from 'src/store/ledgerDerive'
import { describeLedgerSettings, settingsDepKey, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty } from '../PanelShell'

export function GenreBars({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const settingsKey = settingsDepKey(settings)
  const genres = useMemo(
    () => deriveTopGenres(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey],
  )
  const maxCount = genres[0]?.count ?? 1

  return (
    <Panel
      title={settings?.title || 'By the genre'}
      hint={`top of the marquee${describeLedgerSettings(settings)}`}
      className={className}
    >
      {genres.length === 0 ? (
        <PanelEmpty message="No genres yet" />
      ) : (
        <div className="flex flex-wrap items-center justify-center gap-3 py-2">
          {genres.map((g, i) => {
            const t = Math.sqrt(g.count / maxCount)
            const size = 60 + t * 66
            return (
              <button
                key={g.genre}
                onClick={() => {
                  setFilter('genres', [g.genre])
                  requestView('library')
                }}
                className="rounded-full flex flex-col items-center justify-center text-center shrink-0 transition-transform hover:scale-105 cursor-pointer animate-[scaleIn_0.5s_ease-out_forwards]"
                style={{
                  width: size,
                  height: size,
                  background:
                    i === 0
                      ? 'radial-gradient(circle at 32% 28%, var(--amber-bright), var(--amber-deep))'
                      : 'radial-gradient(circle at 32% 28%, var(--ink-3), var(--ink-1))',
                  ...rankBubbleAccent(i === 0, 24, i * 55),
                }}
              >
                <span
                  className="font-serif font-medium leading-tight px-2"
                  style={{ fontSize: 11 + t * 6, color: i === 0 ? 'var(--on-amber)' : 'var(--paper)' }}
                >
                  {g.genre}
                </span>
                <span
                  className="font-mono text-[10px] mt-0.5"
                  style={{ color: i === 0 ? 'var(--on-amber)' : 'var(--paper-faint)', opacity: i === 0 ? 0.75 : 1 }}
                >
                  {g.count}
                </span>
              </button>
            )
          })}
        </div>
      )}
    </Panel>
  )
}
