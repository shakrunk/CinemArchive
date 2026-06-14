import {
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  Cell,
  LineChart,
  Line,
  PieChart,
  Pie,
} from 'recharts'
import { Film, Tv, Eye, Star, Clock, CalendarDays } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { StatNumber, StatLabel, SectionHeading } from 'src/components/ui/typography'
import { cn } from 'src/lib/utils'

const AMBER = '#e9b266'
const AMBER_MUTED = '#c8924a'
const MUTED = '#2e2825'
const MUTED_FG = '#6b5f52'

// ─── Shared chart theme ──────────────────────────────────────────────────────

const tooltipStyle = {
  backgroundColor: '#1a1510',
  border: '1px solid #2e2825',
  borderRadius: '6px',
  fontFamily: '"DM Mono", monospace',
  fontSize: '12px',
  color: '#e9b266',
}

// ChartCard: serif title with amber left accent bar
function ChartCard({
  title,
  children,
  className,
}: {
  title: string
  children: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn('card-cinematic p-4 md:p-6', className)}>
      <div className="flex items-center gap-2.5 mb-5">
        <div className="chart-accent-line" />
        <h3 className="font-serif text-base font-light text-foreground/80">
          {title}
        </h3>
      </div>
      {children}
    </div>
  )
}

// ─── Summary Stats Row ────────────────────────────────────────────────────────

function SummaryRow() {
  const stats = useAppStore((s) => s.stats)
  const hours = Math.round(stats.totalMinutes / 60)
  const days = (stats.totalMinutes / 60 / 24).toFixed(1)

  const items = [
    { value: stats.totalMovies, label: 'Films', Icon: Film },
    { value: stats.totalSeries, label: 'Series', Icon: Tv },
    { value: stats.totalViewings, label: 'Viewings', Icon: Eye },
    { value: stats.avgRating.toFixed(1), label: 'Avg Rating', Icon: Star },
    { value: `${hours}h`, label: 'Screen Time', Icon: Clock },
    { value: `${days}d`, label: 'Total Days', Icon: CalendarDays },
  ]

  return (
    <div className="grid grid-cols-3 md:grid-cols-6 gap-3 md:gap-4">
      {items.map((item) => (
        <div key={item.label} className="card-cinematic p-4 text-center">
          <div className="flex justify-center mb-2">
            <item.Icon className="w-4 h-4 text-amber/50" />
          </div>
          <StatNumber className="block text-2xl md:text-3xl">{item.value}</StatNumber>
          <div className="mt-1">
            <StatLabel>{item.label}</StatLabel>
          </div>
        </div>
      ))}
    </div>
  )
}

// ─── Rating Distribution — custom star bars replacing BarChart ────────────────

function renderStarLabel(rating: number): string {
  const full = Math.floor(rating)
  const half = rating % 1 >= 0.5
  return '★'.repeat(full) + (half ? '½' : '')
}

function RatingDistribution() {
  const dist = useAppStore((s) => s.stats.ratingDistribution)
  const data = dist.filter((d) => d.count > 0).sort((a, b) => b.rating - a.rating)
  const maxCount = Math.max(...data.map((d) => d.count), 1)

  return (
    <ChartCard title="The Record — Rating Distribution">
      <div className="space-y-3">
        {data.map((d, i) => (
          <div key={d.rating} className="flex items-center gap-3">
            <div className="font-mono text-sm text-amber shrink-0 w-14 text-right leading-none select-none">
              {renderStarLabel(d.rating)}
            </div>
            <div className="flex-1 h-2 bg-secondary rounded-full overflow-hidden">
              <div
                className="h-full rounded-full transition-all duration-500"
                style={{
                  width: `${(d.count / maxCount) * 100}%`,
                  background: i === 0
                    ? `linear-gradient(90deg, ${AMBER_MUTED}, ${AMBER})`
                    : `linear-gradient(90deg, ${AMBER_MUTED}80, ${AMBER}80)`,
                }}
              />
            </div>
            <span className="font-mono text-xs text-muted-foreground w-4 text-right shrink-0">
              {d.count}
            </span>
          </div>
        ))}
        {data.length === 0 && (
          <p className="text-center text-sm text-muted-foreground font-sans py-8">No ratings yet</p>
        )}
      </div>
    </ChartCard>
  )
}

// ─── Screenings Timeline ──────────────────────────────────────────────────────

function ScreeningsTimeline() {
  const viewingsByMonth = useAppStore((s) => s.stats.viewingsByMonth)

  const data = viewingsByMonth
    .slice()
    .sort((a, b) => a.month.localeCompare(b.month))
    .map((d) => ({
      month: d.month.slice(5, 7) + '/' + d.month.slice(2, 4),
      count: d.count,
    }))

  return (
    <ChartCard title="Screenings Timeline — Viewings by Month">
      <ResponsiveContainer width="100%" height={200}>
        <LineChart data={data} margin={{ top: 5, right: 0, bottom: 0, left: -20 }}>
          <XAxis
            dataKey="month"
            tick={{ fontFamily: '"DM Mono", monospace', fontSize: 10, fill: MUTED_FG }}
            axisLine={false}
            tickLine={false}
          />
          <YAxis
            allowDecimals={false}
            tick={{ fontFamily: '"DM Mono", monospace', fontSize: 11, fill: MUTED_FG }}
            axisLine={false}
            tickLine={false}
          />
          <Tooltip contentStyle={tooltipStyle} cursor={{ stroke: 'rgba(233,178,102,0.2)' }} />
          <Line
            type="monotone"
            dataKey="count"
            stroke={AMBER}
            strokeWidth={2}
            dot={{ fill: AMBER, r: 3 }}
            activeDot={{ r: 5 }}
          />
        </LineChart>
      </ResponsiveContainer>
    </ChartCard>
  )
}

// ─── Genre Marquee — with percentage labels + highlighted #1 ──────────────────

function GenreMarquee() {
  const genres = useAppStore((s) => s.stats.topGenres)

  return (
    <ChartCard title="Genre Marquee — Top Categories">
      <div className="space-y-3">
        {genres.map((g, i) => {
          const maxCount = genres[0]?.count ?? 1
          const pct = Math.round((g.count / maxCount) * 100)

          return (
            <div key={g.genre} className="flex items-center gap-3">
              <span className={cn(
                'font-serif text-sm shrink-0 w-5 text-right',
                i === 0 ? 'text-amber' : 'text-muted-foreground/30'
              )}>
                {i + 1}
              </span>
              <span className="font-sans text-sm text-foreground w-28 shrink-0 truncate">{g.genre}</span>
              <div className="flex-1 h-2 bg-secondary rounded-full overflow-hidden">
                <div
                  className="h-full rounded-full transition-all"
                  style={{
                    width: `${pct}%`,
                    background: i === 0
                      ? `linear-gradient(90deg, ${AMBER_MUTED}, ${AMBER})`
                      : `linear-gradient(90deg, ${MUTED_FG}50, ${MUTED_FG}90)`,
                  }}
                />
              </div>
              <span className="font-mono text-xs text-muted-foreground w-8 text-right shrink-0">
                {pct}%
              </span>
            </div>
          )
        })}
      </div>
    </ChartCard>
  )
}

// ─── The Auteurs — card-style ranking with serif numbers ──────────────────────

function TheAuteurs() {
  const directors = useAppStore((s) => s.stats.topDirectors)

  if (directors.length === 0) return null

  return (
    <ChartCard title="The Auteurs — Directors">
      <div className="space-y-2">
        {directors.map((d, i) => (
          <div
            key={d.director}
            className={cn(
              'flex items-center gap-3 p-3 rounded-lg border transition-colors',
              i === 0
                ? 'bg-amber/5 border-amber/15'
                : 'bg-secondary/20 border-border/40 hover:border-border/70'
            )}
          >
            <span className={cn(
              'font-serif text-2xl font-light w-7 text-center shrink-0 leading-none',
              i === 0 ? 'text-amber' : 'text-muted-foreground/25'
            )}>
              {i + 1}
            </span>
            <div className="flex-1 min-w-0">
              <p className={cn(
                'font-serif text-sm',
                i === 0 ? 'text-foreground' : 'text-foreground/70'
              )}>
                {d.director}
              </p>
              <p className="font-mono text-xs text-muted-foreground">
                {d.count} film{d.count !== 1 ? 's' : ''}
              </p>
            </div>
            <div className="shrink-0 font-mono text-xs text-amber/40 select-none">
              {'★'.repeat(Math.min(d.count, 5))}
            </div>
          </div>
        ))}
      </div>
    </ChartCard>
  )
}

// ─── Time in the Dark ─────────────────────────────────────────────────────────

function TimeInTheDark() {
  const stats = useAppStore((s) => s.stats)
  const hours = Math.round(stats.totalMinutes / 60)
  const total = stats.totalMovies + stats.totalSeries
  const movieShare = total > 0 ? Math.round((stats.totalMovies / total) * 100) : 0
  const tvShare = 100 - movieShare

  const pieData = [
    { name: 'Movies', value: movieShare },
    { name: 'TV', value: tvShare },
  ]

  return (
    <ChartCard title="Time in the Dark — Media Breakdown">
      <div className="flex items-center gap-6">
        <ResponsiveContainer width={120} height={120}>
          <PieChart>
            <Pie
              data={pieData}
              cx="50%"
              cy="50%"
              innerRadius={35}
              outerRadius={55}
              dataKey="value"
              strokeWidth={0}
            >
              <Cell fill={AMBER} />
              <Cell fill={MUTED} />
            </Pie>
          </PieChart>
        </ResponsiveContainer>
        <div className="space-y-4">
          <div>
            <StatNumber className="text-xl">{hours}h</StatNumber>
            <div className="mt-0.5">
              <StatLabel>Total Screen Time</StatLabel>
            </div>
          </div>
          <div className="flex gap-4 text-xs font-mono">
            <div className="flex items-center gap-1.5">
              <div className="w-2 h-2 rounded-full" style={{ background: AMBER }} />
              <span className="text-muted-foreground">{movieShare}% Movies</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-2 h-2 rounded-full" style={{ background: MUTED }} />
              <span className="text-muted-foreground">{tvShare}% TV</span>
            </div>
          </div>
        </div>
      </div>
    </ChartCard>
  )
}

// ─── Ledger View ─────────────────────────────────────────────────────────────

export function Ledger() {
  return (
    <div className="max-w-7xl mx-auto px-4 py-6 pb-24 space-y-6">
      <div className="mb-8">
        <SectionHeading>The Ledger</SectionHeading>
        <p className="font-sans text-sm text-muted-foreground mt-1">
          Your cinematic record
        </p>
      </div>

      <SummaryRow />

      <div className="grid md:grid-cols-2 gap-4 md:gap-6">
        <RatingDistribution />
        <TimeInTheDark />
      </div>

      <ScreeningsTimeline />

      <div className="grid md:grid-cols-2 gap-4 md:gap-6">
        <GenreMarquee />
        <TheAuteurs />
      </div>
    </div>
  )
}
