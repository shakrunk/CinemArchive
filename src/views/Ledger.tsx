import { useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  Pencil,
  Check,
  X,
  Plus,
  Copy,
  ChevronUp,
  ChevronDown,
  RotateCcw,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
} from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { RadialRing, areaPath, getInitials, linePath, ratingColorVar } from 'src/components/LedgerCharts'
import type { LedgerPanelId, LedgerPanelWidth } from 'src/lib/ledgerPanels'
import {
  DEFAULT_LEDGER_PANEL_ORDER,
  LEDGER_PANEL_LABELS,
  LEDGER_PANEL_DESCRIPTIONS,
  LEDGER_PANEL_WIDTH_ORDER,
  LEDGER_PANEL_WIDTH_LABELS,
  LEDGER_PANEL_WIDTH_SPANS,
  LEDGER_PANEL_STANDARD_HEIGHT,
  nearestPanelWidth,
} from 'src/lib/ledgerPanels'

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

// Shared empty-state body for panels with no data yet.
function PanelEmpty({ message }: { message: string }) {
  const requestView = useAppStore((s) => s.requestView)
  return (
    <div className="flex flex-col items-center justify-center py-8 gap-3">
      <p className="text-center text-sm text-paper-faint">{message}</p>
      <button
        onClick={() => requestView('library')}
        className="text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
      >
        Browse Library
      </button>
    </div>
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
  const maxCount = directors[0]?.count ?? 1
  if (directors.length === 0) {
    return (
      <Panel title="The auteurs" hint="most-watched directors" className={className}>
        <PanelEmpty message="No directors yet" />
      </Panel>
    )
  }

  return (
    <Panel title="The auteurs" hint="most-watched directors" className={className}>
      {directors.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 gap-3">
          <p className="text-center text-sm text-paper-faint">No directors yet</p>
          <button
            onClick={() => requestView('library')}
            className="text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
          >
            Browse Library
          </button>
        </div>
      ) : (
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
      )}
    </Panel>
  )
}

// ─── The ensemble (leading cast) ───────────────────────────────────────────────

function TheEnsemble({ className }: { className?: string }) {
  const actors = useAppStore((s) => s.stats.topActors)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const maxCount = actors[0]?.count ?? 1
  if (actors.length === 0) {
    return (
      <Panel title="The ensemble" hint="most-billed leads" className={className}>
        <PanelEmpty message="No cast data yet" />
      </Panel>
    )
  }

  return (
    <Panel title="The ensemble" hint="most-billed leads" className={className}>
      {actors.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 gap-3">
          <p className="text-center text-sm text-paper-faint">No actors yet</p>
          <button
            onClick={() => requestView('library')}
            className="text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
          >
            Browse Library
          </button>
        </div>
      ) : (
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
      )}
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

// ─── Feature lengths (movie runtime buckets) ─────────────────────────────────

const RUNTIME_BUCKETS = [
  { label: 'Short & sweet', range: 'under 90m', min: 0, max: 90 },
  { label: 'Standard feature', range: '90–120m', min: 90, max: 120 },
  { label: 'The long haul', range: '120–150m', min: 120, max: 150 },
  { label: 'An epic', range: '150m and up', min: 150, max: Infinity },
]

function RuntimeSpectrum({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)

  const { rows, total, avg } = useMemo(() => {
    const runtimes = titles
      .filter((t) => t.type === 'movie' && (t.runtime ?? 0) > 0)
      .map((t) => t.runtime as number)
    const rows = RUNTIME_BUCKETS.map((b) => ({
      ...b,
      count: runtimes.filter((r) => r >= b.min && r < b.max).length,
    }))
    const avg = runtimes.length
      ? Math.round(runtimes.reduce((sum, r) => sum + r, 0) / runtimes.length)
      : 0
    return { rows, total: runtimes.length, avg }
  }, [titles])

  const maxCount = Math.max(...rows.map((r) => r.count), 1)

  return (
    <Panel
      title="Feature lengths"
      hint={total ? `movie runtimes · ${avg}m avg` : 'movie runtimes'}
      className={className}
    >
      {total === 0 ? (
        <PanelEmpty message="No movie runtimes yet" />
      ) : (
        <div className="flex flex-col gap-4 py-1">
          {rows.map((b, i) => (
            <div key={b.label} className="flex items-center gap-3">
              <div className="w-[128px] shrink-0">
                <span
                  className="font-serif text-sm font-medium text-paper block leading-tight"
                  style={{ fontVariationSettings: '"opsz" 30' }}
                >
                  {b.label}
                </span>
                <span className="font-mono text-[9px] text-paper-faint">{b.range}</span>
              </div>
              <div className="flex-1 h-[16px] rounded-sm bg-[var(--wash)] overflow-hidden">
                <div
                  className="h-full rounded-sm bar-fill"
                  style={{
                    width: `${(b.count / maxCount) * 100}%`,
                    background: 'linear-gradient(90deg, var(--amber-deep), var(--amber-bright))',
                    animationDelay: `${i * 80}ms`,
                  }}
                />
              </div>
              <span className="font-mono text-[11px] text-paper-dim w-8 text-right shrink-0">{b.count}</span>
            </div>
          ))}
        </div>
      )}
    </Panel>
  )
}

// ─── On the air (TV networks) ─────────────────────────────────────────────────

function OnTheAir({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)

  const networks = useMemo(() => {
    const counts = new Map<string, number>()
    for (const t of titles) {
      if (t.type === 'tv' && t.network) counts.set(t.network, (counts.get(t.network) ?? 0) + 1)
    }
    return [...counts.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 6)
      .map(([network, count]) => ({ network, count }))
  }, [titles])

  const maxCount = networks[0]?.count ?? 1

  return (
    <Panel title="On the air" hint="where the series live" className={className}>
      {networks.length === 0 ? (
        <PanelEmpty message="No series with a network yet" />
      ) : (
        <div className="flex flex-col gap-1">
          {networks.map((n, i) => (
            <button
              key={n.network}
              onClick={() => {
                setFilter('networks', [n.network])
                requestView('library')
              }}
              className="w-full flex items-center gap-3 px-1.5 py-2 rounded-md transition-colors hover:bg-[var(--wash)] text-left cursor-pointer group"
            >
              <span
                className="font-serif text-sm font-medium text-paper truncate w-[38%] shrink-0 group-hover:underline decoration-amber/40"
                style={{ fontVariationSettings: '"opsz" 30' }}
              >
                {n.network}
              </span>
              <span className="flex-1 h-[12px] rounded-sm bg-[var(--wash)] overflow-hidden">
                <span
                  className="block h-full rounded-sm bar-fill"
                  style={{
                    width: `${(n.count / maxCount) * 100}%`,
                    background: i === 0
                      ? 'linear-gradient(90deg, var(--amber-deep), var(--amber-bright))'
                      : 'rgba(128,115,95,0.55)',
                    animationDelay: `${i * 70}ms`,
                  }}
                />
              </span>
              <span className="font-mono text-[11px] text-paper-dim w-6 text-right shrink-0">{n.count}</span>
            </button>
          ))}
        </div>
      )}
    </Panel>
  )
}

// ─── Second opinions (your rating vs the critics) ─────────────────────────────

function SecondOpinions({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)

  const rows = useMemo(() => {
    return titles
      .filter((t) => typeof t.rating === 'number' && typeof t.imdbRating === 'number')
      .map((t) => {
        const mine = (t.rating as number) * 2 // 0–5 stars → 0–10 scale
        const critics = t.imdbRating as number
        return { title: t, mine, critics, delta: mine - critics }
      })
      .sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta))
      .slice(0, 6)
  }, [titles])

  return (
    <Panel title="Second opinions" hint="your call vs the critics" className={className}>
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

// ─── In translation (original languages) ──────────────────────────────────────

function languageName(code: string): string {
  try {
    return new Intl.DisplayNames(['en'], { type: 'language' }).of(code) ?? code.toUpperCase()
  } catch {
    return code.toUpperCase()
  }
}

function InTranslation({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)

  const langs = useMemo(() => {
    const counts = new Map<string, number>()
    for (const t of titles) {
      if (t.originalLanguage) counts.set(t.originalLanguage, (counts.get(t.originalLanguage) ?? 0) + 1)
    }
    return [...counts.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 6)
      .map(([code, count]) => ({ code, name: languageName(code), count }))
  }, [titles])

  const total = langs.reduce((sum, l) => sum + l.count, 0)
  const maxCount = langs[0]?.count ?? 1

  return (
    <Panel title="In translation" hint="original languages" className={className}>
      {langs.length === 0 ? (
        <PanelEmpty message="No language data yet" />
      ) : (
        <div className="flex flex-col gap-1">
          {langs.map((l, i) => (
            <div key={l.code} className="flex items-center gap-3 px-1.5 py-2 rounded-md transition-colors hover:bg-[var(--wash)]">
              <span className="font-mono text-[10px] uppercase text-amber-deep w-7 shrink-0">{l.code}</span>
              <span
                className="font-serif text-sm font-medium text-paper truncate w-[34%] shrink-0"
                style={{ fontVariationSettings: '"opsz" 30' }}
              >
                {l.name}
              </span>
              <span className="flex-1 h-[10px] rounded-sm bg-[var(--wash)] overflow-hidden">
                <span
                  className="block h-full rounded-sm bar-fill"
                  style={{
                    width: `${(l.count / maxCount) * 100}%`,
                    background: i === 0
                      ? 'linear-gradient(90deg, var(--amber-deep), var(--amber-bright))'
                      : 'rgba(128,115,95,0.55)',
                    animationDelay: `${i * 70}ms`,
                  }}
                />
              </span>
              <span className="font-mono text-[10px] text-paper-faint w-12 text-right shrink-0">
                {Math.round((l.count / total) * 100)}%
              </span>
            </div>
          ))}
        </div>
      )}
    </Panel>
  )
}

// ─── Screening nights (viewings by day of week) ───────────────────────────────

const DAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

function ScreeningNights({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)

  const { counts, total, peak } = useMemo(() => {
    const counts = Array.from({ length: 7 }, () => 0)
    for (const t of titles) {
      for (const v of t.viewings) {
        if (!v.date) continue
        const [y, m, d] = v.date.split('-').map(Number)
        counts[new Date(y, m - 1, d).getDay()]++
      }
    }
    const total = counts.reduce((sum, c) => sum + c, 0)
    const peak = counts.indexOf(Math.max(...counts))
    return { counts, total, peak }
  }, [titles])

  const maxCount = Math.max(...counts, 1)

  return (
    <Panel
      title="Screening nights"
      hint={total ? `by day of week · ${DAY_LABELS[peak]} is busiest` : 'by day of week'}
      className={className}
    >
      {total === 0 ? (
        <PanelEmpty message="No dated screenings yet" />
      ) : (
        <div className="flex items-end justify-between gap-2 sm:gap-4 h-[150px] px-1 pt-2">
          {counts.map((c, day) => (
            <div key={day} className="flex-1 flex flex-col items-center justify-end gap-1.5 h-full min-w-0">
              <span className="font-mono text-[10px] text-paper-dim">{c}</span>
              <div
                className="w-full max-w-[46px] rounded-t-md"
                style={{
                  height: `${Math.max((c / maxCount) * 100, 2)}%`,
                  background: day === peak
                    ? 'linear-gradient(180deg, var(--amber-bright), var(--amber-deep))'
                    : 'linear-gradient(180deg, rgba(128,115,95,0.55), rgba(128,115,95,0.25))',
                  boxShadow: day === peak ? '0 6px 18px -6px rgba(233,178,102,0.5)' : undefined,
                  transformOrigin: 'bottom',
                  transform: 'scaleY(0)',
                  animation: 'col-grow 0.7s var(--ease) forwards',
                  animationDelay: `${day * 60}ms`,
                }}
              />
              <span
                className={cn(
                  'font-mono text-[10px]',
                  day === peak ? 'text-amber-bright' : 'text-paper-faint',
                )}
              >
                {DAY_LABELS[day]}
              </span>
            </div>
          ))}
        </div>
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
  runtimes: { Component: RuntimeSpectrum },
  networks: { Component: OnTheAir },
  verdicts: { Component: SecondOpinions },
  languages: { Component: InTranslation },
  weekdays: { Component: ScreeningNights },
}

// Grid column span per width preset — panels are always full-width below `lg`.
const WIDTH_GRID_CLASSES: Record<LedgerPanelWidth, string> = {
  sm: 'col-span-12 lg:col-span-4',
  md: 'col-span-12 lg:col-span-6',
  lg: 'col-span-12 lg:col-span-8',
  full: 'col-span-12',
}

/** Which side edge a resize drag started from. Heights are standardized, so
 *  edges only adjust the grid-column width (snapped to the S/M/L/Full presets). */
type ResizeEdge = 'e' | 'w'

interface ResizeMeta {
  id: string
  edge: ResizeEdge
  startX: number
  startSpan: number
  colWidth: number
}

interface DragMeta {
  id: string
  startX: number
  startY: number
  active: boolean
}

interface PaletteDragMeta {
  panel: LedgerPanelId
  startX: number
  startY: number
  active: boolean
}

// Pixels of pointer travel before a press on a panel becomes a reorder drag.
const DRAG_THRESHOLD = 6

// ─── Layout editor: floating palette + details panels ───────────────────────

const editorBtnClass =
  'inline-flex items-center justify-center gap-1.5 rounded-md border border-[var(--line)] px-2.5 py-1.5 text-xs font-sans text-paper-dim hover:text-paper hover:border-[var(--line-2)] transition-colors disabled:opacity-35 disabled:pointer-events-none'

const floatingPanelStyle: React.CSSProperties = {
  background: 'linear-gradient(168deg, var(--ink-1), var(--ink-2))',
  boxShadow: '0 24px 60px -18px rgba(0,0,0,0.75)',
}

/** Live, scaled-down render of a panel type for the palette. */
function WidgetPreview({ panel }: { panel: LedgerPanelId }) {
  const { Component } = PANEL_REGISTRY[panel]
  return (
    <div
      aria-hidden
      className="relative h-[96px] overflow-hidden rounded-md border border-[var(--line)] pointer-events-none select-none bg-[var(--ink-2)]"
    >
      <div className="absolute top-0 left-0 w-[600px] origin-top-left" style={{ transform: 'scale(0.4)' }}>
        <Component />
      </div>
      <div
        className="absolute inset-0"
        style={{ background: 'linear-gradient(180deg, transparent 55%, var(--ink-1) 96%)' }}
      />
    </div>
  )
}

function WidgetPalette({
  onItemPointerDown,
  onItemPointerMove,
  onItemPointerEnd,
  onItemActivate,
  onClose,
  onReset,
  onHide,
  className,
}: {
  onItemPointerDown: (e: React.PointerEvent<HTMLDivElement>, panel: LedgerPanelId) => void
  onItemPointerMove: (e: React.PointerEvent<HTMLDivElement>) => void
  onItemPointerEnd: (e: React.PointerEvent<HTMLDivElement>) => void
  onItemActivate: (panel: LedgerPanelId) => void
  onClose: () => void
  onReset: () => void
  onHide: () => void
  className?: string
}) {
  const widgets = useAppStore((s) => s.ledgerPrefs.widgets)
  const counts = useMemo(() => {
    const map = new Map<LedgerPanelId, number>()
    for (const w of widgets) map.set(w.panel, (map.get(w.panel) ?? 0) + 1)
    return map
  }, [widgets])

  return (
    <aside
      aria-label="Widget palette"
      className={cn('rounded-xl border border-[var(--line)] p-3.5 flex flex-col gap-3 min-h-0', className)}
      style={floatingPanelStyle}
    >
      <header className="flex items-center justify-between gap-2 shrink-0">
        <h2 className="font-mono text-[10px] tracking-[0.18em] uppercase text-paper-dim">Widgets</h2>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={onHide}
            aria-label="Hide widget palette"
            className="w-6 h-6 rounded-md text-paper-faint hover:text-paper flex items-center justify-center transition-colors"
          >
            <PanelLeftClose className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-sans border border-amber/40 bg-amber/10 text-amber"
          >
            <Check className="w-3.5 h-3.5" /> Done
          </button>
        </div>
      </header>
      <p className="font-mono text-[9px] tracking-[0.12em] uppercase text-paper-faint shrink-0">
        drag onto the board · or tap to add
      </p>
      <ul className="flex flex-col gap-2.5 overflow-y-auto scrollbar-thin -mx-1 px-1 min-h-0">
        {DEFAULT_LEDGER_PANEL_ORDER.map((panel) => {
          const count = counts.get(panel) ?? 0
          return (
            <li key={panel}>
              {/* Not a <button>: the live preview inside renders panels that
                  contain their own buttons, and buttons cannot nest. */}
              <div
                role="button"
                tabIndex={0}
                onPointerDown={(e) => onItemPointerDown(e, panel)}
                onPointerMove={onItemPointerMove}
                onPointerUp={onItemPointerEnd}
                onPointerCancel={onItemPointerEnd}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onItemActivate(panel)
                  }
                }}
                style={{ touchAction: 'pan-y' }}
                aria-label={`Add ${LEDGER_PANEL_LABELS[panel]} to the board`}
                className="w-full rounded-lg border border-transparent hover:border-amber/30 p-1.5 text-left transition-colors cursor-grab active:cursor-grabbing group"
              >
                <WidgetPreview panel={panel} />
                <span className="flex items-center gap-2 mt-1.5 px-0.5">
                  <span className="flex-1 min-w-0">
                    <span className="block text-[12px] text-paper truncate">{LEDGER_PANEL_LABELS[panel]}</span>
                    <span className="block font-mono text-[9px] text-paper-faint truncate mt-0.5">
                      {LEDGER_PANEL_DESCRIPTIONS[panel]}
                    </span>
                  </span>
                  {count > 0 && <span className="font-mono text-[9px] text-amber-deep shrink-0">×{count}</span>}
                  <span className="w-5 h-5 shrink-0 rounded-md border border-[var(--line)] flex items-center justify-center text-paper-faint group-hover:text-amber group-hover:border-amber/30 transition-colors">
                    <Plus className="w-3 h-3" />
                  </span>
                </span>
              </div>
            </li>
          )
        })}
      </ul>
      <button
        type="button"
        onClick={onReset}
        className="shrink-0 inline-flex items-center justify-center gap-1.5 rounded-md px-2.5 py-1 text-[11px] font-sans text-paper-faint hover:text-paper transition-colors"
      >
        <RotateCcw className="w-3 h-3" /> Reset to default layout
      </button>
    </aside>
  )
}

function WidgetDetails({
  selectedId,
  onSelect,
  onHide,
  className,
}: {
  selectedId: string
  onSelect: (id: string | null) => void
  onHide: () => void
  className?: string
}) {
  const widgets = useAppStore((s) => s.ledgerPrefs.widgets)
  const duplicateLedgerWidget = useAppStore((s) => s.duplicateLedgerWidget)
  const removeLedgerWidget = useAppStore((s) => s.removeLedgerWidget)
  const moveLedgerWidget = useAppStore((s) => s.moveLedgerWidget)
  const setLedgerWidgetWidth = useAppStore((s) => s.setLedgerWidgetWidth)

  const selected = widgets.find((w) => w.id === selectedId)
  const selectedIndex = selected ? widgets.findIndex((w) => w.id === selected.id) : -1
  if (!selected) return null

  return (
    <aside
      aria-label="Widget details"
      className={cn('rounded-xl border border-[var(--line)] p-4 flex flex-col gap-3.5', className)}
      style={floatingPanelStyle}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="font-serif text-[15px] font-medium text-paper leading-tight">
            {LEDGER_PANEL_LABELS[selected.panel]}
          </h3>
          <p className="font-mono text-[9px] tracking-[0.14em] uppercase text-paper-faint mt-1">
            widget {selectedIndex + 1} of {widgets.length}
          </p>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button
            type="button"
            onClick={onHide}
            aria-label="Hide widget details"
            className="w-6 h-6 rounded-md text-paper-faint hover:text-paper flex items-center justify-center transition-colors"
          >
            <PanelRightClose className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={() => onSelect(null)}
            aria-label="Deselect widget"
            className="w-6 h-6 rounded-md text-paper-faint hover:text-paper flex items-center justify-center"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      <div>
        <p className="font-mono text-[9px] tracking-[0.16em] uppercase text-paper-faint mb-1.5">Size</p>
        <div className="grid grid-cols-4 gap-1">
          {LEDGER_PANEL_WIDTH_ORDER.map((w) => (
            <button
              key={w}
              type="button"
              onClick={() => setLedgerWidgetWidth(selected.id, w)}
              aria-pressed={selected.width === w}
              className={cn(
                'rounded-md border py-1.5 font-mono text-[10px] transition-colors',
                selected.width === w
                  ? 'border-amber/40 bg-amber/10 text-amber'
                  : 'border-[var(--line)] text-paper-faint hover:text-paper hover:border-[var(--line-2)]',
              )}
            >
              {LEDGER_PANEL_WIDTH_LABELS[w]}
            </button>
          ))}
        </div>
      </div>

      <div>
        <p className="font-mono text-[9px] tracking-[0.16em] uppercase text-paper-faint mb-1.5">Position</p>
        <div className="flex items-center gap-1.5">
          <button
            type="button"
            onClick={() => moveLedgerWidget(selected.id, 'up')}
            disabled={selectedIndex <= 0}
            aria-label="Move widget earlier"
            className={editorBtnClass}
          >
            <ChevronUp className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={() => moveLedgerWidget(selected.id, 'down')}
            disabled={selectedIndex === widgets.length - 1}
            aria-label="Move widget later"
            className={editorBtnClass}
          >
            <ChevronDown className="w-3.5 h-3.5" />
          </button>
          <span className="flex-1" />
          <button
            type="button"
            onClick={() => onSelect(duplicateLedgerWidget(selected.id))}
            className={editorBtnClass}
          >
            <Copy className="w-3 h-3" /> Duplicate
          </button>
        </div>
      </div>

      <button
        type="button"
        onClick={() => {
          removeLedgerWidget(selected.id)
          onSelect(null)
        }}
        className="w-full rounded-md border py-1.5 text-xs font-sans transition-colors"
        style={{ color: 'var(--ember)', borderColor: 'rgba(200,90,60,0.35)' }}
      >
        Remove from board
      </button>
    </aside>
  )
}

// setPointerCapture can throw (NotFoundError) if the pointer is already gone
// by the time the handler runs — treat capture as best-effort.
function capturePointer(el: Element, pointerId: number) {
  try {
    el.setPointerCapture(pointerId)
  } catch {
    /* ignore */
  }
}

export function Ledger() {
  const widgets = useAppStore((s) => s.ledgerPrefs.widgets)
  const reorderLedgerWidgets = useAppStore((s) => s.reorderLedgerWidgets)
  const addLedgerWidget = useAppStore((s) => s.addLedgerWidget)
  const setLedgerWidgetWidth = useAppStore((s) => s.setLedgerWidgetWidth)
  const resetLedgerPrefs = useAppStore((s) => s.resetLedgerPrefs)
  const friendView = useAppStore((s) => s.friendView)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const canEdit = !friendView && !isSharedView

  const [editing, setEditing] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [paletteHidden, setPaletteHidden] = useState(false)
  const [detailsHidden, setDetailsHidden] = useState(false)
  const itemRefs = useRef(new Map<string, HTMLDivElement>())
  const gridRef = useRef<HTMLDivElement>(null)
  const boardRef = useRef<HTMLDivElement>(null)

  // Reorder drag — the whole panel is the drag surface in edit mode.
  const dragRef = useRef<DragMeta | null>(null)
  const overRef = useRef<string | null>(null)
  const [draggingId, setDraggingId] = useState<string | null>(null)
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 })
  const [overId, setOverId] = useState<string | null>(null)

  // Resize drag — handles live on the panel's side edges.
  const resizeRef = useRef<ResizeMeta | null>(null)
  const [resizingId, setResizingId] = useState<string | null>(null)

  // Palette drag — dropping a panel type from the palette onto the board.
  const paletteDragRef = useRef<PaletteDragMeta | null>(null)
  const paletteOverRef = useRef<string | null>(null)
  const [paletteGhost, setPaletteGhost] = useState<{ panel: LedgerPanelId; x: number; y: number } | null>(null)
  const [paletteOverId, setPaletteOverId] = useState<string | null>(null)

  function widgetById(id: string) {
    return widgets.find((w) => w.id === id)
  }

  /** Select a widget; a fresh selection also reveals the details panel if it
   *  was hidden — selecting is an explicit request to see its details. */
  function selectWidget(id: string | null) {
    setSelectedId(id)
    if (id) setDetailsHidden(false)
  }

  function stopEditing() {
    setEditing(false)
    setSelectedId(null)
  }

  // ── Reorder handlers ──────────────────────────────────────────────────────

  function handlePanelPointerDown(e: React.PointerEvent<HTMLDivElement>, id: string) {
    if (e.button !== 0 || resizeRef.current) return
    // Toolbar buttons and resize handles opt out of starting a reorder drag.
    if ((e.target as HTMLElement).closest('[data-ledger-control]')) return
    dragRef.current = { id, startX: e.clientX, startY: e.clientY, active: false }
    overRef.current = null
    capturePointer(e.currentTarget, e.pointerId)
  }

  function handlePanelPointerMove(e: React.PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current
    if (!drag) return
    const dx = e.clientX - drag.startX
    const dy = e.clientY - drag.startY
    if (!drag.active) {
      if (Math.hypot(dx, dy) < DRAG_THRESHOLD) return
      drag.active = true
      setDraggingId(drag.id)
    }
    setDragOffset({ x: dx, y: dy })
    let hit: string | null = null
    for (const [id, el] of itemRefs.current) {
      if (id === drag.id) continue
      const rect = el.getBoundingClientRect()
      if (e.clientX >= rect.left && e.clientX <= rect.right && e.clientY >= rect.top && e.clientY <= rect.bottom) {
        hit = id
        break
      }
    }
    overRef.current = hit
    setOverId(hit)
  }

  function handlePanelPointerEnd(e: React.PointerEvent<HTMLDivElement>, id: string) {
    const drag = dragRef.current
    dragRef.current = null
    if (!drag) return
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
    const over = overRef.current
    if (!drag.active) {
      // A press without movement is a selection, not a drag.
      selectWidget(id)
    } else if (over && over !== drag.id) {
      const ids = widgets.map((w) => w.id)
      const from = ids.indexOf(drag.id)
      const to = ids.indexOf(over)
      ids.splice(from, 1)
      ids.splice(to, 0, drag.id)
      reorderLedgerWidgets(ids)
    }
    overRef.current = null
    setDraggingId(null)
    setDragOffset({ x: 0, y: 0 })
    setOverId(null)
  }

  // ── Resize handlers (width only — heights are standardized) ──────────────

  function startResize(e: React.PointerEvent<HTMLDivElement>, id: string, edge: ResizeEdge) {
    if (e.button !== 0) return
    e.preventDefault()
    e.stopPropagation()
    const widget = widgetById(id)
    if (!widget) return
    const gridWidth = gridRef.current?.getBoundingClientRect().width ?? 0
    resizeRef.current = {
      id,
      edge,
      startX: e.clientX,
      startSpan: LEDGER_PANEL_WIDTH_SPANS[widget.width],
      colWidth: gridWidth > 0 ? gridWidth / 12 : 100,
    }
    selectWidget(id)
    setResizingId(id)
    capturePointer(e.currentTarget, e.pointerId)
  }

  function moveResize(e: React.PointerEvent<HTMLDivElement>) {
    const r = resizeRef.current
    if (!r) return
    // Snap the dragged column span to the nearest preset.
    const dir = r.edge === 'w' ? -1 : 1
    const span = r.startSpan + (dir * (e.clientX - r.startX)) / r.colWidth
    const width = nearestPanelWidth(span)
    if (width !== widgetById(r.id)?.width) setLedgerWidgetWidth(r.id, width)
  }

  function endResize(e: React.PointerEvent<HTMLDivElement>) {
    const r = resizeRef.current
    resizeRef.current = null
    if (!r) return
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
    setResizingId(null)
  }

  function resizeHandleProps(id: string, edge: ResizeEdge) {
    return {
      'data-ledger-control': true,
      onPointerDown: (e: React.PointerEvent<HTMLDivElement>) => startResize(e, id, edge),
      onPointerMove: moveResize,
      onPointerUp: endResize,
      onPointerCancel: endResize,
      style: { touchAction: 'none' } as React.CSSProperties,
    }
  }

  // ── Palette drag — add a widget by dragging it onto the board ────────────

  function handlePaletteItemPointerDown(e: React.PointerEvent<HTMLDivElement>, panel: LedgerPanelId) {
    if (e.button !== 0) return
    paletteDragRef.current = { panel, startX: e.clientX, startY: e.clientY, active: false }
    paletteOverRef.current = null
    capturePointer(e.currentTarget, e.pointerId)
  }

  function handlePaletteItemPointerMove(e: React.PointerEvent<HTMLDivElement>) {
    const drag = paletteDragRef.current
    if (!drag) return
    const dx = e.clientX - drag.startX
    const dy = e.clientY - drag.startY
    if (!drag.active) {
      if (Math.hypot(dx, dy) < DRAG_THRESHOLD) return
      drag.active = true
    }
    setPaletteGhost({ panel: drag.panel, x: e.clientX, y: e.clientY })
    // Hit-test board cards first; anywhere else over the board appends.
    let hit: string | null = null
    for (const [id, el] of itemRefs.current) {
      const rect = el.getBoundingClientRect()
      if (e.clientX >= rect.left && e.clientX <= rect.right && e.clientY >= rect.top && e.clientY <= rect.bottom) {
        hit = id
        break
      }
    }
    if (!hit) {
      const board = boardRef.current?.getBoundingClientRect()
      if (board && e.clientX >= board.left && e.clientX <= board.right && e.clientY >= board.top) {
        hit = 'end'
      }
    }
    paletteOverRef.current = hit
    setPaletteOverId(hit)
  }

  function handlePaletteItemPointerEnd(e: React.PointerEvent<HTMLDivElement>) {
    const drag = paletteDragRef.current
    paletteDragRef.current = null
    if (!drag) return
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
    const over = paletteOverRef.current
    paletteOverRef.current = null
    setPaletteGhost(null)
    setPaletteOverId(null)
    if (!drag.active) {
      // A plain tap/click adds to the end of the board.
      selectWidget(addLedgerWidget(drag.panel))
      return
    }
    if (!over) return // dropped outside the board — cancel
    const newId = addLedgerWidget(drag.panel)
    if (over !== 'end') {
      const ids = widgets.map((w) => w.id)
      ids.splice(ids.indexOf(over), 0, newId)
      reorderLedgerWidgets(ids)
    }
    selectWidget(newId)
  }

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <div className="flex items-start justify-between gap-4">
        <DashHero />
        {canEdit && (
          <button
            type="button"
            onClick={() => (editing ? stopEditing() : setEditing(true))}
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

      <div ref={boardRef}>
        {widgets.length === 0 && (
          <div
            className="rounded-xl border border-dashed py-16 px-6 text-center"
            style={{ borderColor: paletteOverId === 'end' ? 'var(--amber)' : 'var(--line-2)' }}
          >
            <p className="text-sm text-paper-faint">
              The board is empty.{' '}
              {editing ? 'Drag widgets here from the palette.' : canEdit ? 'Use “Edit layout” to add widgets.' : ''}
            </p>
          </div>
        )}
        <div
          ref={gridRef}
          className="grid grid-cols-12 gap-4"
          style={
            paletteOverId === 'end' && widgets.length > 0
              ? { outline: '2px dashed var(--amber)', outlineOffset: 8, borderRadius: 12 }
              : undefined
          }
        >
          {widgets.map((widget) => {
          const id = widget.id
          const { Component } = PANEL_REGISTRY[widget.panel]
          const isDragging = draggingId === id
          const isResizing = resizingId === id
          const isSelected = editing && selectedId === id
          const isOver = editing && !isDragging && (overId === id || paletteOverId === id)

          return (
            <div
              key={id}
              ref={(el) => {
                if (el) itemRefs.current.set(id, el)
                else itemRefs.current.delete(id)
              }}
              onPointerDown={editing ? (e) => handlePanelPointerDown(e, id) : undefined}
              onPointerMove={editing ? handlePanelPointerMove : undefined}
              onPointerUp={editing ? (e) => handlePanelPointerEnd(e, id) : undefined}
              onPointerCancel={editing ? (e) => handlePanelPointerEnd(e, id) : undefined}
              className={cn(
                'relative flex flex-col',
                WIDTH_GRID_CLASSES[widget.width],
                editing && 'select-none',
                editing && !isDragging && 'cursor-grab',
                isDragging && 'cursor-grabbing',
              )}
              style={{
                height: LEDGER_PANEL_STANDARD_HEIGHT,
                transform: isDragging ? `translate(${dragOffset.x}px, ${dragOffset.y}px)` : undefined,
                transition: isDragging || isResizing ? 'none' : 'transform 180ms ease',
                zIndex: isDragging ? 20 : undefined,
                outline: isOver
                  ? '2px dashed var(--amber)'
                  : isResizing || isSelected
                    ? '2px solid var(--amber)'
                    : undefined,
                outlineOffset: '2px',
                borderRadius: '0.75rem',
                boxShadow: isDragging ? '0 20px 50px -12px rgba(0,0,0,0.6)' : undefined,
              }}
            >
              <div
                className={cn(
                  'flex-1 min-h-0',
                  editing ? 'pointer-events-none overflow-hidden' : 'overflow-y-auto overflow-x-hidden scrollbar-thin',
                )}
              >
                <Component className="min-h-full" />
              </div>
              {editing && (
                <>
                  {/* Side edge handles — width only (desktop; panels are always
                      full-width below lg). Invisible until hovered so the edit
                      preview matches the live board. */}
                  <div
                    {...resizeHandleProps(id, 'e')}
                    title="Drag to resize width"
                    className="hidden lg:flex absolute inset-y-0 -right-2 w-4 z-10 cursor-ew-resize items-center justify-center group/re"
                  >
                    <span className="w-[3px] h-9 rounded-full bg-transparent group-hover/re:bg-amber transition-colors" />
                  </div>
                  <div
                    {...resizeHandleProps(id, 'w')}
                    title="Drag to resize width"
                    className="hidden lg:flex absolute inset-y-0 -left-2 w-4 z-10 cursor-ew-resize items-center justify-center group/rw"
                  >
                    <span className="w-[3px] h-9 rounded-full bg-transparent group-hover/rw:bg-amber transition-colors" />
                  </div>
                </>
              )}
            </div>
          )
        })}
        </div>
        {editing && widgets.length > 0 && (
          <p className="mt-4 font-mono text-[10px] tracking-[0.14em] uppercase text-paper-faint">
            click a widget to select · drag to reorder · drag side edges to resize · drag from the palette to add
          </p>
        )}
      </div>

      {/* ── Floating editor panels ── rendered in a portal: an ancestor
          (main.animate-view-in) keeps a transform, which would otherwise turn
          position:fixed into position:absolute-within-it. */}
      {editing && createPortal(
        <>
          {/* Palette — floats on the left (desktop), collapsible to an edge tab */}
          {paletteHidden ? (
            <button
              type="button"
              onClick={() => setPaletteHidden(false)}
              aria-label="Show widget palette"
              className="hidden lg:flex fixed left-0 top-28 z-40 items-center gap-1.5 rounded-r-md border border-l-0 border-[var(--line)] py-2.5 pl-1.5 pr-2 text-paper-faint hover:text-amber transition-colors"
              style={floatingPanelStyle}
            >
              <PanelLeftOpen className="w-4 h-4" />
              <span className="font-mono text-[9px] tracking-[0.16em] uppercase [writing-mode:vertical-rl]">
                Widgets
              </span>
            </button>
          ) : (
            <div className="hidden lg:flex fixed left-4 xl:left-6 top-24 bottom-6 w-[280px] z-40">
              <WidgetPalette
                className="flex-1 min-h-0"
                onItemPointerDown={handlePaletteItemPointerDown}
                onItemPointerMove={handlePaletteItemPointerMove}
                onItemPointerEnd={handlePaletteItemPointerEnd}
                onItemActivate={(panel) => selectWidget(addLedgerWidget(panel))}
                onClose={stopEditing}
                onReset={() => {
                  resetLedgerPrefs()
                  setSelectedId(null)
                }}
                onHide={() => setPaletteHidden(true)}
              />
            </div>
          )}
          {/* Details — floats on the right when a widget is selected (desktop),
              collapsible to an edge tab without losing the selection */}
          {selectedId && widgetById(selectedId) && (
            detailsHidden ? (
              <button
                type="button"
                onClick={() => setDetailsHidden(false)}
                aria-label="Show widget details"
                className="hidden lg:flex fixed right-0 top-28 z-40 items-center gap-1.5 rounded-l-md border border-r-0 border-[var(--line)] py-2.5 pl-2 pr-1.5 text-paper-faint hover:text-amber transition-colors"
                style={floatingPanelStyle}
              >
                <span className="font-mono text-[9px] tracking-[0.16em] uppercase [writing-mode:vertical-rl]">
                  Details
                </span>
                <PanelRightOpen className="w-4 h-4" />
              </button>
            ) : (
              <div className="hidden lg:block fixed right-4 xl:right-6 top-24 w-[270px] z-40">
                <WidgetDetails
                  selectedId={selectedId}
                  onSelect={selectWidget}
                  onHide={() => setDetailsHidden(true)}
                />
              </div>
            )
          )}
          {/* Mobile — one bottom sheet: details when selected, palette otherwise;
              hideable to a reopen chip */}
          {(selectedId && widgetById(selectedId) ? detailsHidden : paletteHidden) ? (
            <button
              type="button"
              onClick={() =>
                selectedId && widgetById(selectedId) ? setDetailsHidden(false) : setPaletteHidden(false)
              }
              aria-label="Show layout editor panel"
              className="lg:hidden fixed right-3 bottom-20 z-40 inline-flex items-center gap-1.5 rounded-md border border-[var(--line)] px-3 py-2 font-mono text-[10px] tracking-[0.14em] uppercase text-amber"
              style={floatingPanelStyle}
            >
              <ChevronUp className="w-3.5 h-3.5" />
              {selectedId && widgetById(selectedId) ? 'Details' : 'Widgets'}
            </button>
          ) : (
            <div className="lg:hidden fixed inset-x-3 bottom-20 z-40 flex max-h-[46vh]">
              {selectedId && widgetById(selectedId) ? (
                <WidgetDetails
                  className="flex-1"
                  selectedId={selectedId}
                  onSelect={selectWidget}
                  onHide={() => setDetailsHidden(true)}
                />
              ) : (
                <WidgetPalette
                  className="flex-1 min-h-0"
                  onItemPointerDown={handlePaletteItemPointerDown}
                  onItemPointerMove={handlePaletteItemPointerMove}
                  onItemPointerEnd={handlePaletteItemPointerEnd}
                  onItemActivate={(panel) => selectWidget(addLedgerWidget(panel))}
                  onClose={stopEditing}
                  onReset={() => {
                    resetLedgerPrefs()
                    setSelectedId(null)
                  }}
                  onHide={() => setPaletteHidden(true)}
                />
              )}
            </div>
          )}
          {/* Drag ghost following the pointer while adding from the palette */}
          {paletteGhost && (
            <div
              className="fixed z-[60] pointer-events-none -translate-x-1/2 -translate-y-1/2"
              style={{ left: paletteGhost.x, top: paletteGhost.y }}
            >
              <span
                className="rounded-md border border-amber/40 px-3 py-1.5 font-mono text-[10px] uppercase tracking-widest text-amber"
                style={{ background: 'var(--ink-1)', boxShadow: '0 12px 32px -8px rgba(0,0,0,0.7)' }}
              >
                {LEDGER_PANEL_LABELS[paletteGhost.panel]}
              </span>
            </div>
          )}
        </>,
        document.body,
      )}
    </div>
  )
}
