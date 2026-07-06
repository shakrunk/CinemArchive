// Dashboard hero + stat ribbon shown above the widget board.

import { useAppStore } from 'src/store/useAppStore'

export function DashHero() {
  const stats = useAppStore((s) => s.stats)
  const friendView = useAppStore((s) => s.friendView)
  const today = new Date().toLocaleDateString('en-US', {
    weekday: 'long',
    month: 'long',
    day: 'numeric',
  })
  const total = stats.totalMovies + stats.totalSeries
  const hours = Math.round(stats.totalMinutes / 60)

  return (
    <div className="mb-[clamp(28px,4vw,44px)]">
      <p className="kicker">
        <span className="dot" /> {friendView ? `${friendView.displayName}'s ledger` : `now showing · ${today}`}
      </p>
      <h1 className="display-title text-[clamp(40px,8vw,88px)] mt-3.5">
        {friendView ? (
          <>An evening with <em>{friendView.displayName}.</em></>
        ) : (
          <>An evening at <em>the&nbsp;pictures.</em></>
        )}
      </h1>
      <p className="mt-4 max-w-[60ch] text-[clamp(15px,1.6vw,18px)] text-paper-dim">
        {friendView ? 'Their' : 'A private'} record of <strong className="text-paper font-bold">{total}</strong> titles,{' '}
        <strong className="text-paper font-bold">{stats.totalViewings}</strong> screenings, and roughly{' '}
        <strong className="text-paper font-bold">{hours}</strong> hours spent in the dark.
      </p>
    </div>
  )
}

export function StatRibbon() {
  const stats = useAppStore((s) => s.stats)
  const hours = Math.round(stats.totalMinutes / 60)
  const days = (stats.totalMinutes / 60 / 24).toFixed(1)
  const total = stats.totalMovies + stats.totalSeries

  const items: Array<{ value: string; unit?: string; sub: string }> = [
    { value: String(total), sub: `${stats.totalMovies} films · ${stats.totalSeries} series` },
    { value: String(stats.totalViewings), sub: 'screenings' },
    { value: stats.avgRating.toFixed(1), unit: 'star', sub: 'avg rating' },
    { value: String(hours), unit: 'h', sub: 'screen time' },
    { value: days, unit: 'd', sub: 'in the dark' },
  ]

  return (
    <div className="flex items-start overflow-x-auto pb-3 mb-[clamp(24px,4vw,40px)] border-b border-[var(--line)]">
      {items.map((item, i) => (
        <div key={item.sub} className="flex items-stretch shrink-0">
          {i > 0 && (
            <div className="w-px bg-[var(--line-2)] mx-6 sm:mx-8 self-stretch" />
          )}
          <div className="flex flex-col">
            <div className="stat-num text-[clamp(26px,3vw,40px)]">
              {item.value}
              {item.unit && <span className="unit">{item.unit === 'star' ? '★' : item.unit}</span>}
            </div>
            <div className="font-mono text-[10px] tracking-[0.18em] uppercase text-paper-faint mt-1.5 whitespace-nowrap">
              {item.sub}
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}
