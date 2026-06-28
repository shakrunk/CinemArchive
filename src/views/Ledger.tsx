import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'

// ─── Dashboard hero ───────────────────────────────────────────────────────────

function DashHero() {
  const stats = useAppStore((s) => s.stats)
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
        <span className="dot" /> now showing · {today}
      </p>
      <h1 className="display-title text-[clamp(40px,8vw,88px)] mt-3.5">
        An evening at <em>the&nbsp;pictures.</em>
      </h1>
      <p className="mt-4 max-w-[60ch] text-[clamp(15px,1.6vw,18px)] text-paper-dim">
        A private record of <strong className="text-paper font-bold">{total}</strong> titles,{' '}
        <strong className="text-paper font-bold">{stats.totalViewings}</strong> screenings, and roughly{' '}
        <strong className="text-paper font-bold">{hours}</strong> hours spent in the dark.
      </p>
    </div>
  )
}

// --- Stat ribbon ---

function StatRibbon() {
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

// ─── Panel shell ──────────────────────────────────────────────────────────────

function Panel({
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

// ─── Rating distribution histogram ────────────────────────────────────────────

function renderStarLabel(rating: number): string {
  const full = Math.floor(rating)
  const half = rating % 1 >= 0.5
  return '★'.repeat(full) + (half ? '½' : '')
}

function RatingDistribution({ className }: { className?: string }) {
  const dist = useAppStore((s) => s.stats.ratingDistribution)
  const avgRating = useAppStore((s) => s.stats.avgRating)
  const data = dist.filter((d) => d.count > 0).sort((a, b) => b.rating - a.rating)
  const maxCount = Math.max(...data.map((d) => d.count), 1)
  const roundedAvg = Math.round(avgRating * 2) / 2

  return (
    <Panel
      title="Critical record"
      hint={`rating distribution · ${avgRating.toFixed(1)} avg`}
      className={className}
    >
      <div className="flex flex-col gap-3">
        {data.map((d, i) => {
          const isAvg = d.rating === roundedAvg
          return (
            <div
              key={d.rating}
              className="grid items-center gap-3"
              style={{ gridTemplateColumns: '60px 1fr 28px' }}
            >
              <div
                className={cn(
                  'font-mono text-[13px] text-right leading-none select-none',
                  isAvg ? 'text-amber-bright' : 'text-amber',
                )}
              >
                {renderStarLabel(d.rating)}
              </div>
              <div className="h-3 rounded-md overflow-hidden bg-[var(--wash)]">
                <div
                  className="bar-fill h-full rounded-md"
                  style={{
                    width: `${(d.count / maxCount) * 100}%`,
                    background: 'linear-gradient(90deg, var(--amber-deep), var(--amber-bright))',
                    boxShadow: '0 0 16px -2px rgba(233,178,102,0.5)',
                    animationDelay: `${i * 60}ms`,
                  }}
                />
              </div>
              <span
                className={cn(
                  'font-mono text-[13px] text-right',
                  isAvg ? 'text-amber' : 'text-paper-dim',
                )}
              >
                {d.count}
              </span>
            </div>
          )
        })}
        {data.length === 0 && (
          <p className="text-center text-sm text-paper-faint py-8">No ratings yet</p>
        )}
      </div>
    </Panel>
  )
}

// ─── Genre bars ───────────────────────────────────────────────────────────────

function GenreBars({ className }: { className?: string }) {
  const genres = useAppStore((s) => s.stats.topGenres)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const maxCount = genres[0]?.count ?? 1

  return (
    <Panel title="By the genre" hint="top of the marquee" className={className}>
      <div className="flex flex-col gap-2">
        {genres.map((g, i) => (
          <button
            key={g.genre}
            onClick={() => {
              setFilter('genres', [g.genre])
              requestView('library')
            }}
            className="grid items-center gap-3 group w-full text-left rounded-md px-1 py-1 transition-colors hover:bg-[var(--wash)] cursor-pointer"
            style={{ gridTemplateColumns: '120px 1fr 36px' }}
          >
            <span className="text-[13px] text-paper-dim truncate transition-colors group-hover:text-amber-bright">
              {g.genre}
            </span>
            <div className="h-2.5 rounded-md overflow-hidden bg-[var(--wash)]">
              <div
                className="bar-fill h-full rounded-md"
                style={{
                  width: `${(g.count / maxCount) * 100}%`,
                  background:
                    i === 0
                      ? 'linear-gradient(90deg, var(--amber-deep), var(--amber-bright))'
                      : 'linear-gradient(90deg, rgba(128,115,95,0.4), rgba(128,115,95,0.7))',
                  animationDelay: `${i * 50}ms`,
                }}
              />
            </div>
            <span className="font-mono text-xs text-paper-faint text-right">{g.count}</span>
          </button>
        ))}
      </div>
    </Panel>
  )
}

// --- Decade filmstrip ---

function DecadeFilmstrip({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)

  const decades = useMemo(() => {
    const counts = new Map<number, number>()
    for (const t of titles) {
      const decade = Math.floor(t.year / 10) * 10
      counts.set(decade, (counts.get(decade) ?? 0) + 1)
    }
    return [...counts.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([decade, count]) => ({ label: `${decade}s`, count }))
  }, [titles])

  const maxCount = Math.max(...decades.map((d) => d.count), 1)

  return (
    <Panel title="By the era" hint="decade breakdown" className={className}>
      <div className="flex flex-col gap-2.5">
        {decades.map((d, i) => (
          <div
            key={d.label}
            className="grid items-center gap-3"
            style={{ gridTemplateColumns: '56px 1fr 28px' }}
          >
            <span className="font-mono text-[12px] text-paper-dim text-right">{d.label}</span>
            <div className="h-2.5 rounded-md overflow-hidden bg-[var(--wash)]">
              <div
                className="bar-fill h-full rounded-md"
                style={{
                  width: `${(d.count / maxCount) * 100}%`,
                  background:
                    d.count === maxCount
                      ? 'linear-gradient(90deg, var(--amber-deep), var(--amber-bright))'
                      : 'linear-gradient(90deg, rgba(128,115,95,0.4), rgba(128,115,95,0.7))',
                  animationDelay: `${i * 40}ms`,
                }}
              />
            </div>
            <span className="font-mono text-xs text-paper-faint text-right">{d.count}</span>
          </div>
        ))}
        {decades.length === 0 && (
          <p className="text-center text-sm text-paper-faint py-8">No titles yet</p>
        )}
      </div>
    </Panel>
  )
}

// --- Activity heatmap (52-week calendar) ---

function localDateStr(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function ActivityHeatmap({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)

  const viewingCounts = useMemo(() => {
    const map = new Map<string, number>()
    for (const t of titles) {
      for (const v of t.viewings) {
        if (v.date) map.set(v.date, (map.get(v.date) ?? 0) + 1)
      }
    }
    return map
  }, [titles])

  const todayStr = localDateStr(new Date())

  const { weeks, monthLabels, totalInYear } = useMemo(() => {
    const end = new Date()
    end.setDate(end.getDate() + (6 - end.getDay())) // advance to Saturday

    const start = new Date(end)
    start.setDate(end.getDate() - 52 * 7 + 1)

    const days: Array<{ date: string; count: number }> = []
    const cursor = new Date(start)
    while (cursor <= end) {
      const ds = localDateStr(cursor)
      days.push({ date: ds, count: viewingCounts.get(ds) ?? 0 })
      cursor.setDate(cursor.getDate() + 1)
    }

    const weeks: Array<Array<{ date: string; count: number }>> = []
    for (let i = 0; i < days.length; i += 7) weeks.push(days.slice(i, i + 7))

    const monthLabels: Array<{ weekIndex: number; label: string }> = []
    let lastMonth = -1
    weeks.forEach((week, wi) => {
      const month = Number(week[0].date.slice(5, 7))
      if (month !== lastMonth) {
        const d = new Date(Number(week[0].date.slice(0, 4)), month - 1, 1)
        monthLabels.push({ weekIndex: wi, label: d.toLocaleDateString('en-US', { month: 'short' }) })
        lastMonth = month
      }
    })

    const totalInYear = days.reduce((sum, d) => sum + d.count, 0)
    return { weeks, monthLabels, totalInYear }
  }, [viewingCounts])

  return (
    <Panel title="Time in the dark" hint="past 52 weeks" className={className}>
      <div className="overflow-x-auto -mx-1 px-1">
        {/* Month labels */}
        <div className="flex mb-1.5">
          {weeks.map((_, wi) => {
            const label = monthLabels.find((m) => m.weekIndex === wi)?.label
            return (
              <div key={wi} className="w-[13px] overflow-visible shrink-0 mr-[2px]">
                {label && (
                  <span className="font-mono text-[9px] text-paper-faint leading-none whitespace-nowrap">
                    {label}
                  </span>
                )}
              </div>
            )
          })}
        </div>
        {/* Heatmap grid */}
        <div className="flex gap-[2px]">
          {weeks.map((week, wi) => (
            <div key={wi} className="flex flex-col gap-[2px]">
              {week.map((cell) => (
                <div
                  key={cell.date}
                  className={cn(
                    'w-[13px] h-[13px] rounded-[2px] transition-opacity',
                    cell.count > 0 ? 'bg-amber' : 'bg-[var(--wash)]',
                    cell.date === todayStr && 'ring-1 ring-amber-bright/60',
                  )}
                  style={cell.count > 0 ? { boxShadow: '0 0 6px -1px rgba(233,178,102,0.4)' } : undefined}
                  title={
                    cell.count > 0
                      ? `${cell.date} - ${cell.count} viewing${cell.count !== 1 ? 's' : ''}`
                      : cell.date
                  }
                />
              ))}
            </div>
          ))}
        </div>
      </div>
      <p className="mt-4 font-mono text-[10px] tracking-[0.16em] uppercase text-paper-faint">
        {totalInYear} screening{totalInYear !== 1 ? 's' : ''} in the past year
      </p>
    </Panel>
  )
}

// ─── The auteurs (directors) ──────────────────────────────────────────────────

function TheAuteurs({ className }: { className?: string }) {
  const directors = useAppStore((s) => s.stats.topDirectors)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  if (directors.length === 0) return null

  return (
    <Panel title="The auteurs" hint="most-watched directors" className={className}>
      <ol className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-1">
        {directors.map((d, i) => (
          <li key={d.director}>
            <button
              onClick={() => {
                setFilter('search', d.director)
                requestView('library')
              }}
              className="w-full grid items-center gap-3 px-1.5 py-2.5 rounded-md transition-colors hover:bg-[var(--wash)] text-left cursor-pointer group"
              style={{ gridTemplateColumns: '26px 1fr auto' }}
            >
              <span className="font-mono text-xs text-amber-deep">{String(i + 1).padStart(2, '0')}</span>
              <span
                className="font-serif text-base font-medium text-paper truncate group-hover:underline decoration-amber/40"
                style={{ fontVariationSettings: '"opsz" 30' }}
              >
                {d.director}
              </span>
              <span className="flex items-center gap-2">
                <span className="font-mono text-xs text-paper-dim">{d.count}</span>
                <span className="flex gap-0.5">
                  {Array.from({ length: Math.min(d.count, 5) }, (_, k) => (
                    <i key={k} className="w-[5px] h-[5px] rounded-full bg-amber" />
                  ))}
                </span>
              </span>
            </button>
          </li>
        ))}
      </ol>
    </Panel>
  )
}

// --- Encore performances ---

function EncorePerformances({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)

  const encores = useMemo(() => {
    return titles
      .filter((t) => t.viewings.length >= 2)
      .sort((a, b) => b.viewings.length - a.viewings.length)
      .slice(0, 6)
  }, [titles])

  return (
    <Panel title="Encore performances" hint="most revisited" className={className}>
      {encores.length === 0 ? (
        <p className="text-center text-sm text-paper-faint py-8">No title has screened twice yet</p>
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

// ─── Ledger View ─────────────────────────────────────────────────────────────

export function Ledger() {
  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <DashHero />
      <StatRibbon />

      <div className="grid grid-cols-12 gap-4">
        <ActivityHeatmap className="col-span-12 lg:col-span-8" />
        <EncorePerformances className="col-span-12 lg:col-span-4" />
        <RatingDistribution className="col-span-12 lg:col-span-5" />
        <GenreBars className="col-span-12 lg:col-span-7" />
        <DecadeFilmstrip className="col-span-12" />
        <TheAuteurs className="col-span-12" />
      </div>
    </div>
  )
}
