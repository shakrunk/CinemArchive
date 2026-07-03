import { useMemo, useRef, useState } from 'react'
import { GripVertical, Eye, EyeOff, Pencil, Check } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { RadialRing, areaPath, getInitials, linePath, ratingColorVar } from 'src/components/LedgerCharts'
import type { LedgerPanelId, LedgerPanelWidth } from 'src/lib/ledgerPanels'
import { LEDGER_PANEL_LABELS, LEDGER_PANEL_WIDTH_ORDER, LEDGER_PANEL_WIDTH_LABELS } from 'src/lib/ledgerPanels'

// ─── Dashboard hero ───────────────────────────────────────────────────────────

function DashHero() {
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
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const data = dist.filter((d) => d.count > 0).sort((a, b) => b.rating - a.rating)
  const total = data.reduce((sum, d) => sum + d.count, 0)

  const gradient = useMemo(() => {
    if (total === 0) return 'var(--wash)'
    let cursor = 0
    const stops = data.map((d) => {
      const start = (cursor / total) * 100
      cursor += d.count
      const end = (cursor / total) * 100
      return `${ratingColorVar(d.rating)} ${start}% ${end}%`
    })
    return `conic-gradient(${stops.join(', ')})`
  }, [data, total])

  return (
    <Panel
      title="Critical record"
      hint={`rating distribution · ${avgRating.toFixed(1)} avg`}
      className={className}
    >
      {total === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 gap-3">
          <p className="text-center text-sm text-paper-faint">No ratings yet</p>
          <button
            onClick={() => requestView('library')}
            className="text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
          >
            Browse Library
          </button>
        </div>
      ) : (
        <div className="flex flex-col sm:flex-row items-center gap-8">
          <div className="relative w-[168px] h-[168px] shrink-0">
            <div className="donut-ring absolute inset-0 rounded-full" style={{ background: gradient }} />
            <div
              className="donut-hole absolute rounded-full flex flex-col items-center justify-center"
              style={{
                inset: '24px',
                background: 'linear-gradient(168deg, var(--ink-1), var(--ink-2))',
                border: '1px solid var(--line)',
              }}
            >
              <span className="stat-num text-[28px]">{avgRating.toFixed(1)}</span>
              <span className="font-mono text-[9px] tracking-[0.14em] uppercase text-paper-faint mt-1">
                avg · {total} rated
              </span>
            </div>
          </div>
          <div className="flex-1 w-full flex flex-col gap-0.5">
            {data.map((d) => (
              <button
                key={d.rating}
                onClick={() => {
                  setFilter('minRating', d.rating)
                  requestView('library')
                }}
                className="w-full flex items-center justify-between gap-3 px-2 py-1.5 rounded-md transition-colors hover:bg-[var(--wash)] cursor-pointer group"
              >
                <span className="flex items-center gap-2.5">
                  <i
                    className="w-2 h-2 rounded-full shrink-0"
                    style={{ background: ratingColorVar(d.rating), boxShadow: `0 0 8px -1px ${ratingColorVar(d.rating)}` }}
                  />
                  <span className="font-mono text-[12px] text-amber group-hover:text-amber-bright transition-colors">
                    {renderStarLabel(d.rating)}
                  </span>
                </span>
                <span className="font-mono text-[11px] text-paper-faint">
                  <span className="text-paper-dim">{d.count}</span> · {Math.round((d.count / total) * 100)}%
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
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

// --- Decade filmstrip ---

const FILMSTRIP_HOLES = Array.from({ length: 28 })

function DecadeFilmstrip({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)

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

  const points = useMemo(
    () =>
      decades.map((d, i) => ({
        x: decades.length === 1 ? 500 : (i / (decades.length - 1)) * 1000,
        y: 190 - (d.count / maxCount) * 164,
      })),
    [decades, maxCount],
  )

  return (
    <Panel title="By the era" hint="decade breakdown" className={className}>
      {decades.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 gap-3">
          <p className="text-center text-sm text-paper-faint">No titles yet</p>
          <button
            onClick={() => requestView('library')}
            className="text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
          >
            Browse Library
          </button>
        </div>
      ) : (
        <div
          className="rounded-xl overflow-x-auto overflow-y-hidden scrollbar-thin"
          style={{ background: 'linear-gradient(180deg, var(--ink-2), var(--ink-1))', border: '1px solid var(--line)' }}
        >
          <div style={{ minWidth: Math.max(decades.length * 64, 420) }}>
            <div className="filmstrip-holes pt-3">
              {FILMSTRIP_HOLES.map((_, i) => (
                <span key={i} />
              ))}
            </div>
            <div className="relative w-full h-[150px]">
              <svg viewBox="0 0 1000 200" preserveAspectRatio="none" className="absolute inset-0 w-full h-full block">
                <defs>
                  <linearGradient id="filmstrip-area" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="var(--amber)" stopOpacity="0.35" />
                    <stop offset="100%" stopColor="var(--amber)" stopOpacity="0" />
                  </linearGradient>
                </defs>
                <path d={areaPath(points, 190)} fill="url(#filmstrip-area)" />
                <path
                  d={linePath(points)}
                  fill="none"
                  stroke="var(--amber-bright)"
                  strokeWidth={3}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  pathLength={1}
                  className="chart-path-draw"
                  style={{ strokeDasharray: 1 }}
                  vectorEffect="non-scaling-stroke"
                />
              </svg>
              {/* Rendered as HTML dots, not SVG circles — preserveAspectRatio="none"
                  stretches x/y independently, which would turn <circle> into ellipses. */}
              {points.map((p, i) => (
                <span
                  key={i}
                  className="absolute rounded-full -translate-x-1/2 -translate-y-1/2"
                  style={{
                    left: `${(p.x / 1000) * 100}%`,
                    top: `${(p.y / 200) * 100}%`,
                    width: 14,
                    height: 14,
                    background: 'var(--ink-1)',
                    border: '2.5px solid var(--amber-bright)',
                  }}
                />
              ))}
            </div>
            <div className="filmstrip-holes pb-3">
              {FILMSTRIP_HOLES.map((_, i) => (
                <span key={i} />
              ))}
            </div>
            <div className="flex justify-between px-3.5 pb-3.5 pt-1.5">
              {decades.map((d) => (
                <button
                  key={d.label}
                  onClick={() => {
                    setFilter('decades', [d.label])
                    requestView('library')
                  }}
                  className="flex flex-col items-center gap-0.5 group cursor-pointer"
                >
                  <span className="font-mono text-[11px] text-paper-dim group-hover:text-amber-bright transition-colors">
                    {d.label}
                  </span>
                  <span className="font-mono text-[9px] text-paper-faint">{d.count}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
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

  const maxCellCount = useMemo(
    () => Math.max(...weeks.flat().map((c) => c.count), 1),
    [weeks],
  )

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
                  style={
                    cell.count > 0
                      ? {
                          opacity: 0.32 + 0.68 * Math.min(cell.count / maxCellCount, 1),
                          boxShadow: '0 0 6px -1px rgba(233,178,102,0.4)',
                        }
                      : undefined
                  }
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

// --- The run (monthly screening trend) ---

function monthLabel(month: string): string {
  const [y, m] = month.split('-').map(Number)
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short' })
}

function TheRun({ className }: { className?: string }) {
  const viewingsByMonth = useAppStore((s) => s.stats.viewingsByMonth)

  // viewingsByMonth only contains months with at least one viewing — fill the
  // gaps in between so the x-axis represents a true, evenly-spaced calendar
  // timeline rather than compressing silent months out of existence.
  const recent = useMemo(() => {
    const counts = new Map(viewingsByMonth.map((d) => [d.month, d.count]))
    const now = new Date()
    const months: { month: string; count: number }[] = []
    for (let i = 11; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
      months.push({ month: key, count: counts.get(key) ?? 0 })
    }
    return months
  }, [viewingsByMonth])

  const maxCount = Math.max(...recent.map((d) => d.count), 1)
  const total = recent.reduce((sum, d) => sum + d.count, 0)

  const points = useMemo(
    () =>
      recent.map((d, i) => ({
        x: recent.length === 1 ? 500 : (i / (recent.length - 1)) * 1000,
        y: 170 - (d.count / maxCount) * 140,
      })),
    [recent, maxCount],
  )

  const requestView = useAppStore((s) => s.requestView)

  return (
    <Panel title="The run" hint={`monthly screenings · last ${recent.length} mo`} className={className}>
      {total === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 gap-3">
          <p className="text-center text-sm text-paper-faint">No screenings in the past year</p>
          <button
            onClick={() => requestView('library')}
            className="text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
          >
            Browse Library
          </button>
        </div>
      ) : (
        <div>
          <div className="overflow-x-auto overflow-y-hidden scrollbar-thin">
            <div style={{ minWidth: Math.max(recent.length * 52, 420) }}>
              <div className="relative w-full h-[130px]">
                <svg viewBox="0 0 1000 190" preserveAspectRatio="none" className="absolute inset-0 w-full h-full block">
                  <defs>
                    <linearGradient id="run-area" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="var(--moon)" stopOpacity="0.4" />
                      <stop offset="100%" stopColor="var(--moon)" stopOpacity="0" />
                    </linearGradient>
                  </defs>
                  <path d={areaPath(points, 170)} fill="url(#run-area)" />
                  <path
                    d={linePath(points)}
                    fill="none"
                    stroke="var(--moon)"
                    strokeWidth={3}
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    pathLength={1}
                    className="chart-path-draw"
                    style={{ strokeDasharray: 1 }}
                    vectorEffect="non-scaling-stroke"
                  />
                </svg>
                {/* HTML dots, not SVG circles — preserveAspectRatio="none" stretches
                    x/y independently, which would turn <circle> into ellipses. */}
                {points.map(
                  (p, i) =>
                    recent[i].count > 0 && (
                      <span
                        key={i}
                        title={`${monthLabel(recent[i].month)} — ${recent[i].count} screening${recent[i].count !== 1 ? 's' : ''}`}
                        className="absolute rounded-full -translate-x-1/2 -translate-y-1/2"
                        style={{
                          left: `${(p.x / 1000) * 100}%`,
                          top: `${(p.y / 190) * 100}%`,
                          width: 10,
                          height: 10,
                          background: 'var(--moon)',
                        }}
                      />
                    ),
                )}
              </div>
              <div className="flex justify-between px-1">
                {recent.map((d) => (
                  <span key={d.month} className="font-mono text-[9px] text-paper-faint">
                    {monthLabel(d.month)}
                  </span>
                ))}
              </div>
            </div>
          </div>
          <p className="mt-4 font-mono text-[10px] tracking-[0.16em] uppercase text-paper-faint">
            {total} screening{total !== 1 ? 's' : ''} across the last {recent.length} months
          </p>
        </div>
      )}
    </Panel>
  )
}

// ─── The auteurs (directors) ──────────────────────────────────────────────────

function TheAuteurs({ className }: { className?: string }) {
  const directors = useAppStore((s) => s.stats.topDirectors)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  if (directors.length === 0) return null
  const maxCount = directors[0]?.count ?? 1

  return (
    <Panel title="The auteurs" hint="most-watched directors" className={className}>
      <ol className="flex flex-col gap-1">
        {directors.map((d, i) => {
          const pct = d.count / maxCount
          const color = i === 0 ? 'var(--amber-bright)' : 'rgba(128,115,95,0.6)'
          return (
            <li key={d.director}>
              <button
                onClick={() => {
                  setFilter('search', d.director)
                  requestView('library')
                }}
                className="w-full flex items-center gap-3 px-1.5 py-2.5 rounded-md transition-colors hover:bg-[var(--wash)] text-left cursor-pointer group"
              >
                <span className="font-mono text-xs text-amber-deep w-5 shrink-0">{String(i + 1).padStart(2, '0')}</span>
                <span
                  className="font-serif text-base font-medium text-paper truncate flex-1 min-w-0 group-hover:underline decoration-amber/40"
                  style={{ fontVariationSettings: '"opsz" 30' }}
                >
                  {d.director}
                </span>
                <span className="relative w-9 h-9 shrink-0">
                  <RadialRing pct={pct} size={36} stroke={4} color={color} delay={i * 60} />
                  <span className="absolute inset-0 flex items-center justify-center font-mono text-[10px] text-paper-dim">
                    {d.count}
                  </span>
                </span>
              </button>
            </li>
          )
        })}
      </ol>
    </Panel>
  )
}

// ─── The ensemble (leading cast) ───────────────────────────────────────────────

function TheEnsemble({ className }: { className?: string }) {
  const actors = useAppStore((s) => s.stats.topActors)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  if (actors.length === 0) return null
  const maxCount = actors[0]?.count ?? 1

  return (
    <Panel title="The ensemble" hint="most-billed leads" className={className}>
      <div className="flex flex-wrap items-start justify-center gap-x-5 gap-y-4 py-2">
        {actors.map((a, i) => {
          const t = a.count / maxCount
          const size = 56 + t * 40
          return (
            <button
              key={a.actor}
              onClick={() => {
                setFilter('search', a.actor)
                requestView('library')
              }}
              className="flex flex-col items-center gap-2 group cursor-pointer w-[104px] shrink-0"
            >
              <span
                className="rounded-full flex items-center justify-center font-serif font-medium transition-transform group-hover:scale-105 animate-[scaleIn_0.5s_ease-out_forwards]"
                style={{
                  width: size,
                  height: size,
                  fontSize: size * 0.32,
                  background:
                    i === 0
                      ? 'linear-gradient(155deg, var(--amber-bright), var(--amber-deep))'
                      : 'linear-gradient(155deg, var(--ink-3), var(--ink-1))',
                  color: i === 0 ? 'var(--on-amber)' : 'var(--paper-dim)',
                  border: i === 0 ? 'none' : '1px solid var(--line-2)',
                  boxShadow: i === 0 ? '0 8px 22px -8px rgba(233,178,102,0.55)' : 'var(--shadow)',
                  opacity: 0,
                  transform: 'scale(0)',
                  animationDelay: `${i * 70}ms`,
                }}
              >
                {getInitials(a.actor)}
              </span>
              <span className="text-[12.5px] text-center text-paper-dim group-hover:text-amber-bright transition-colors truncate max-w-full leading-tight">
                {a.actor}
              </span>
              <span className="font-mono text-[10px] text-paper-faint">{a.count}×</span>
            </button>
          )
        })}
      </div>
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

// ─── Ledger View ─────────────────────────────────────────────────────────────

const PANEL_REGISTRY: Record<LedgerPanelId, { Component: (props: { className?: string }) => React.ReactElement | null }> = {
  activity: { Component: ActivityHeatmap },
  encores: { Component: EncorePerformances },
  run: { Component: TheRun },
  ratings: { Component: RatingDistribution },
  genres: { Component: GenreBars },
  decades: { Component: DecadeFilmstrip },
  auteurs: { Component: TheAuteurs },
  ensemble: { Component: TheEnsemble },
}

// Grid column span per width preset — panels are always full-width below `lg`.
const WIDTH_GRID_CLASSES: Record<LedgerPanelWidth, string> = {
  sm: 'col-span-12 lg:col-span-4',
  md: 'col-span-12 lg:col-span-6',
  lg: 'col-span-12 lg:col-span-8',
  full: 'col-span-12',
}

interface LedgerDragMeta {
  id: LedgerPanelId
  startX: number
  startY: number
}

export function Ledger() {
  const ledgerPrefs = useAppStore((s) => s.ledgerPrefs)
  const moveLedgerPanel = useAppStore((s) => s.moveLedgerPanel)
  const reorderLedgerPanels = useAppStore((s) => s.reorderLedgerPanels)
  const toggleLedgerPanelHidden = useAppStore((s) => s.toggleLedgerPanelHidden)
  const setLedgerPanelWidth = useAppStore((s) => s.setLedgerPanelWidth)
  const friendView = useAppStore((s) => s.friendView)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const canEdit = !friendView && !isSharedView

  const [editing, setEditing] = useState(false)
  const itemRefs = useRef(new Map<LedgerPanelId, HTMLDivElement>())
  const dragStartRef = useRef({ x: 0, y: 0 })
  const [dragMeta, setDragMeta] = useState<LedgerDragMeta | null>(null)
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 })
  const [overId, setOverId] = useState<LedgerPanelId | null>(null)

  const visiblePanels = ledgerPrefs.order.filter((id) => !ledgerPrefs.hidden.includes(id))
  const panelsToRender = editing ? ledgerPrefs.order : visiblePanels

  function handleDragStart(e: React.PointerEvent<HTMLButtonElement>, id: LedgerPanelId) {
    dragStartRef.current = { x: e.clientX, y: e.clientY }
    setDragMeta({ id, startX: e.clientX, startY: e.clientY })
    setDragOffset({ x: 0, y: 0 })
    setOverId(null)
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  function handleDragMove(e: React.PointerEvent<HTMLButtonElement>) {
    if (!dragMeta) return
    setDragOffset({ x: e.clientX - dragStartRef.current.x, y: e.clientY - dragStartRef.current.y })
    let hit: LedgerPanelId | null = null
    for (const [id, el] of itemRefs.current) {
      if (id === dragMeta.id) continue
      const rect = el.getBoundingClientRect()
      if (e.clientX >= rect.left && e.clientX <= rect.right && e.clientY >= rect.top && e.clientY <= rect.bottom) {
        hit = id
        break
      }
    }
    setOverId(hit)
  }

  function endDrag(e: React.PointerEvent<HTMLButtonElement>) {
    if (!dragMeta) return
    e.currentTarget.releasePointerCapture(e.pointerId)
    if (overId && overId !== dragMeta.id) {
      const next = [...ledgerPrefs.order]
      const from = next.indexOf(dragMeta.id)
      const to = next.indexOf(overId)
      next.splice(from, 1)
      next.splice(to, 0, dragMeta.id)
      reorderLedgerPanels(next)
    }
    setDragMeta(null)
    setDragOffset({ x: 0, y: 0 })
    setOverId(null)
  }

  function handleGripKeyDown(e: React.KeyboardEvent, id: LedgerPanelId) {
    if (e.key === 'ArrowUp') { e.preventDefault(); moveLedgerPanel(id, 'up') }
    if (e.key === 'ArrowDown') { e.preventDefault(); moveLedgerPanel(id, 'down') }
  }

  function cycleWidth(id: LedgerPanelId) {
    const idx = LEDGER_PANEL_WIDTH_ORDER.indexOf(ledgerPrefs.widths[id])
    setLedgerPanelWidth(id, LEDGER_PANEL_WIDTH_ORDER[(idx + 1) % LEDGER_PANEL_WIDTH_ORDER.length])
  }

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <div className="flex items-start justify-between gap-4">
        <DashHero />
        {canEdit && (
          <button
            type="button"
            onClick={() => setEditing((e) => !e)}
            className={cn(
              'shrink-0 inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 mt-1 text-xs font-sans border transition-colors',
              editing ? 'border-amber/40 bg-amber/10 text-amber' : 'border-[var(--line)] text-paper-faint hover:text-paper hover:border-[var(--line-2)]'
            )}
          >
            {editing ? <Check className="w-3.5 h-3.5" /> : <Pencil className="w-3.5 h-3.5" />}
            {editing ? 'Done' : 'Edit layout'}
          </button>
        )}
      </div>
      <StatRibbon />

      <div className="grid grid-cols-12 gap-4">
        {panelsToRender.map((id) => {
          const { Component } = PANEL_REGISTRY[id]
          const hidden = ledgerPrefs.hidden.includes(id)
          const width = ledgerPrefs.widths[id]
          const isDragging = dragMeta?.id === id
          const isOver = editing && overId === id && !isDragging

          return (
            <div
              key={id}
              ref={(el) => {
                if (el) itemRefs.current.set(id, el)
                else itemRefs.current.delete(id)
              }}
              className={cn('relative flex flex-col', WIDTH_GRID_CLASSES[width])}
              style={{
                transform: isDragging ? `translate(${dragOffset.x}px, ${dragOffset.y}px)` : undefined,
                transition: isDragging ? 'none' : 'transform 180ms ease',
                zIndex: isDragging ? 20 : undefined,
                outline: isOver ? '2px dashed var(--amber)' : undefined,
                outlineOffset: isOver ? '2px' : undefined,
                borderRadius: isOver ? '0.75rem' : undefined,
                boxShadow: isDragging ? '0 20px 50px -12px rgba(0,0,0,0.6)' : undefined,
              }}
            >
              {editing && (
                <div className="flex items-center justify-between gap-2 mb-2 px-0.5">
                  <span className="font-mono text-[10px] uppercase tracking-widest text-paper-faint truncate">
                    {LEDGER_PANEL_LABELS[id]}
                  </span>
                  <div className="flex items-center gap-1 shrink-0">
                    <button
                      type="button"
                      onClick={() => cycleWidth(id)}
                      aria-label={`Resize ${LEDGER_PANEL_LABELS[id]} — currently ${LEDGER_PANEL_WIDTH_LABELS[width]}`}
                      className="font-mono text-[10px] w-7 h-7 rounded-md border border-[var(--line)] text-paper-faint hover:text-amber hover:border-amber/30 transition-colors"
                    >
                      {LEDGER_PANEL_WIDTH_LABELS[width]}
                    </button>
                    <button
                      type="button"
                      onClick={() => toggleLedgerPanelHidden(id)}
                      aria-pressed={!hidden}
                      aria-label={hidden ? `Show ${LEDGER_PANEL_LABELS[id]} on the Ledger` : `Hide ${LEDGER_PANEL_LABELS[id]} from the Ledger`}
                      className={cn(
                        'w-7 h-7 rounded-md border flex items-center justify-center',
                        hidden ? 'text-muted-foreground border-[var(--line)]' : 'text-amber border-amber/30 bg-amber/5'
                      )}
                    >
                      {hidden ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                    </button>
                    <button
                      type="button"
                      onPointerDown={(e) => handleDragStart(e, id)}
                      onPointerMove={handleDragMove}
                      onPointerUp={endDrag}
                      onPointerCancel={endDrag}
                      onKeyDown={(e) => handleGripKeyDown(e, id)}
                      aria-label={`Reorder ${LEDGER_PANEL_LABELS[id]} — drag, or use arrow keys`}
                      className="w-7 h-7 rounded-md border border-[var(--line)] flex items-center justify-center text-paper-faint hover:text-amber hover:border-amber/30 cursor-grab active:cursor-grabbing transition-colors"
                      style={{ touchAction: 'none' }}
                    >
                      <GripVertical className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              )}
              <div className={cn('flex-1', editing && 'pointer-events-none', editing && hidden && 'opacity-40')}>
                <Component className="h-full" />
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
