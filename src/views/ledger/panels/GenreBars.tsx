// ─── Genre bars ───────────────────────────────────────────────────────────────

import { useAppStore } from 'src/store/useAppStore'
import { Panel } from '../PanelShell'

export function GenreBars({ className }: { className?: string }) {
  const genres = useAppStore((s) => s.stats.topGenres)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const maxCount = genres[0]?.count ?? 1

  return (
    <Panel title="By the genre" hint="top of the marquee" className={className}>
      {genres.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 gap-3">
          <p className="text-center text-sm text-paper-faint">No genres yet</p>
          <button
            onClick={() => requestView('library')}
            className="text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
          >
            Browse Library
          </button>
        </div>
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
                  border: i === 0 ? 'none' : '1px solid var(--line-2)',
                  boxShadow: i === 0 ? '0 8px 24px -8px rgba(233,178,102,0.55)' : 'var(--shadow)',
                  opacity: 0,
                  transform: 'scale(0)',
                  animationDelay: `${i * 55}ms`,
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
