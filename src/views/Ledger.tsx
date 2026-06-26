import { Film, Tv, Eye, Star, Clock, CalendarDays } from 'lucide-react'
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

// ─── Stat strip ───────────────────────────────────────────────────────────────

function StatStrip() {
  const stats = useAppStore((s) => s.stats)
  const hours = Math.round(stats.totalMinutes / 60)
  const days = (stats.totalMinutes / 60 / 24).toFixed(1)

  const items = [
    { value: stats.totalMovies, unit: '', label: 'Films', Icon: Film },
    { value: stats.totalSeries, unit: '', label: 'Series', Icon: Tv },
    { value: stats.totalViewings, unit: '', label: 'Screenings', Icon: Eye },
    { value: stats.avgRating.toFixed(1), unit: '★', label: 'Avg Rating', Icon: Star },
    { value: hours, unit: 'h', label: 'Screen Time', Icon: Clock },
    { value: days, unit: 'd', label: 'In the Dark', Icon: CalendarDays },
  ]

  return (
    <div
      className="grid gap-3.5 mb-[clamp(24px,4vw,40px)]"
      style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))' }}
    >
      {items.map((item) => (
        <div key={item.label} className="stat-card px-5 pt-5 pb-[18px]">
          <item.Icon className="w-[18px] h-[18px] text-amber-deep mb-2.5" />
          <div className="stat-num text-[clamp(30px,4vw,44px)]">
            {item.value}
            {item.unit && <span className="unit">{item.unit}</span>}
          </div>
          <div className="mt-2 font-mono text-[10px] tracking-[0.18em] uppercase text-paper-faint">
            {item.label}
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
  const data = dist.filter((d) => d.count > 0).sort((a, b) => b.rating - a.rating)
  const maxCount = Math.max(...data.map((d) => d.count), 1)

  return (
    <Panel title="Critical record" hint="rating distribution" className={className}>
      <div className="flex flex-col gap-3">
        {data.map((d, i) => (
          <div key={d.rating} className="grid items-center gap-3" style={{ gridTemplateColumns: '60px 1fr 28px' }}>
            <div className="font-mono text-[13px] text-amber text-right leading-none select-none">
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
            <span className="font-mono text-[13px] text-paper-dim text-right">{d.count}</span>
          </div>
        ))}
        {data.length === 0 && <p className="text-center text-sm text-paper-faint py-8">No ratings yet</p>}
      </div>
    </Panel>
  )
}

// ─── Genre bars ───────────────────────────────────────────────────────────────

function GenreBars({ className }: { className?: string }) {
  const genres = useAppStore((s) => s.stats.topGenres)
  const maxCount = genres[0]?.count ?? 1

  return (
    <Panel title="By the genre" hint="top of the marquee" className={className}>
      <div className="flex flex-col gap-3">
        {genres.map((g, i) => {
          const pct = Math.round((g.count / maxCount) * 100)
          return (
            <div key={g.genre} className="grid items-center gap-3 group" style={{ gridTemplateColumns: '120px 1fr 36px' }}>
              <span className="text-[13px] text-paper-dim truncate transition-colors group-hover:text-amber-bright">
                {g.genre}
              </span>
              <div className="h-2.5 rounded-md overflow-hidden bg-[var(--wash)]">
                <div
                  className="bar-fill h-full rounded-md"
                  style={{
                    width: `${pct}%`,
                    background:
                      i === 0
                        ? 'linear-gradient(90deg, var(--amber-deep), var(--amber-bright))'
                        : 'linear-gradient(90deg, rgba(128,115,95,0.4), rgba(128,115,95,0.7))',
                    animationDelay: `${i * 50}ms`,
                  }}
                />
              </div>
              <span className="font-mono text-xs text-paper-faint text-right">{pct}%</span>
            </div>
          )
        })}
      </div>
    </Panel>
  )
}

// ─── Screenings timeline (column chart) ───────────────────────────────────────

function Timeline({ className }: { className?: string }) {
  const viewingsByMonth = useAppStore((s) => s.stats.viewingsByMonth)
  const data = viewingsByMonth.slice().sort((a, b) => a.month.localeCompare(b.month))
  const maxCount = Math.max(...data.map((d) => d.count), 1)

  return (
    <Panel title="Time in the dark" hint="screenings by month" className={className}>
      <div className="flex items-end gap-1.5 h-[150px] overflow-x-auto pb-7">
        {data.map((d, i) => {
          const label = `${d.month.slice(5, 7)}/${d.month.slice(2, 4)}`
          return (
            <div
              key={d.month}
              className="group relative flex flex-col items-center justify-end gap-1.5 flex-1 min-w-[24px] h-full"
              title={`${label}: ${d.count}`}
            >
              <span className="font-mono text-[10px] text-amber opacity-0 transition-opacity group-hover:opacity-100">
                {d.count}
              </span>
              <div
                className="w-full max-w-[26px] rounded-t origin-bottom transition-[filter] group-hover:brightness-125"
                style={{
                  height: `${(d.count / maxCount) * 100}%`,
                  minHeight: '3px',
                  background:
                    d.count > 0
                      ? 'linear-gradient(180deg, var(--amber-bright), var(--amber-deep))'
                      : 'var(--wash)',
                  animation: 'col-grow 0.7s var(--ease) forwards',
                  transform: 'scaleY(0)',
                  animationDelay: `${i * 30}ms`,
                }}
              />
              <span className="absolute -bottom-6 font-mono text-[9px] text-paper-faint whitespace-nowrap tracking-wide">
                {label}
              </span>
            </div>
          )
        })}
        {data.length === 0 && <p className="text-center text-sm text-paper-faint w-full self-center">No screenings logged</p>}
      </div>
    </Panel>
  )
}

// ─── The auteurs (directors) ──────────────────────────────────────────────────

function TheAuteurs({ className }: { className?: string }) {
  const directors = useAppStore((s) => s.stats.topDirectors)
  if (directors.length === 0) return null

  return (
    <Panel title="The auteurs" hint="most-watched directors" className={className}>
      <ol className="flex flex-col gap-1">
        {directors.map((d, i) => (
          <li
            key={d.director}
            className="grid items-center gap-3 px-1.5 py-2.5 rounded-md transition-colors hover:bg-[var(--wash)]"
            style={{ gridTemplateColumns: '26px 1fr auto' }}
          >
            <span className="font-mono text-xs text-amber-deep">{String(i + 1).padStart(2, '0')}</span>
            <span
              className="font-serif text-base font-medium text-paper truncate"
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
          </li>
        ))}
      </ol>
    </Panel>
  )
}

// ─── Media breakdown donut ────────────────────────────────────────────────────

function MediaBreakdown({ className }: { className?: string }) {
  const stats = useAppStore((s) => s.stats)
  const total = stats.totalMovies + stats.totalSeries
  const movieShare = total > 0 ? Math.round((stats.totalMovies / total) * 100) : 0
  const tvShare = 100 - movieShare
  const hours = Math.round(stats.totalMinutes / 60)

  return (
    <Panel title="The bill" hint="film vs. series" className={className}>
      <div className="flex items-center gap-6">
        <div
          className="w-[120px] h-[120px] rounded-full shrink-0 grid place-items-center"
          style={{
            background: `conic-gradient(var(--amber) 0% ${movieShare}%, var(--ink-3) ${movieShare}% 100%)`,
          }}
        >
          <div
            className="w-[72px] h-[72px] rounded-full grid place-items-center"
            style={{ background: 'var(--ink-1)' }}
          >
            <span className="font-mono text-[13px] text-amber">{movieShare}%</span>
          </div>
        </div>
        <div className="space-y-4">
          <div>
            <div className="stat-num text-2xl">
              {hours}
              <span className="unit">h</span>
            </div>
            <div className="mt-0.5 font-mono text-[10px] tracking-[0.18em] uppercase text-paper-faint">
              Total Screen Time
            </div>
          </div>
          <div className="space-y-1.5 font-mono text-xs">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-amber" />
              <span className="text-paper-dim">{movieShare}% Films</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full" style={{ background: 'var(--ink-3)' }} />
              <span className="text-paper-dim">{tvShare}% Series</span>
            </div>
          </div>
        </div>
      </div>
    </Panel>
  )
}

// ─── Ledger View ─────────────────────────────────────────────────────────────

export function Ledger() {
  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <DashHero />
      <StatStrip />

      <div className="grid grid-cols-12 gap-4">
        <RatingDistribution className="col-span-12 lg:col-span-5" />
        <GenreBars className="col-span-12 lg:col-span-7" />
        <Timeline className="col-span-12 lg:col-span-8" />
        <TheAuteurs className="col-span-12 lg:col-span-4" />
        <MediaBreakdown className="col-span-12" />
      </div>
    </div>
  )
}
