import {
  BarChart,
  Bar,
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
      <h3 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-4">
        {title}
      </h3>
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
    { value: stats.totalMovies, label: 'Films' },
    { value: stats.totalSeries, label: 'Series' },
    { value: stats.totalViewings, label: 'Viewings' },
    { value: stats.avgRating.toFixed(1), label: 'Avg Rating' },
    { value: `${hours}h`, label: 'Screen Time' },
    { value: `${days}d`, label: 'Total Days' },
  ]

  return (
    <div className="grid grid-cols-3 md:grid-cols-6 gap-3 md:gap-4">
      {items.map((item) => (
        <div key={item.label} className="card-cinematic p-4 text-center">
          <StatNumber className="block text-2xl md:text-3xl">{item.value}</StatNumber>
          <div className="mt-1">
            <StatLabel>{item.label}</StatLabel>
          </div>
        </div>
      ))}
    </div>
  )
}

// ─── Rating Distribution ("Critical Record") ─────────────────────────────────

function RatingDistribution() {
  const dist = useAppStore((s) => s.stats.ratingDistribution)

  const data = dist
    .filter((d) => d.count > 0)
    .map((d) => ({ name: `★${d.rating}`, count: d.count }))

  return (
    <ChartCard title="Critical Record — Rating Distribution">
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={data} margin={{ top: 0, right: 0, bottom: 0, left: -20 }}>
          <XAxis
            dataKey="name"
            tick={{ fontFamily: '"DM Mono", monospace', fontSize: 11, fill: MUTED_FG }}
            axisLine={false}
            tickLine={false}
          />
          <YAxis
            tick={{ fontFamily: '"DM Mono", monospace', fontSize: 11, fill: MUTED_FG }}
            axisLine={false}
            tickLine={false}
          />
          <Tooltip contentStyle={tooltipStyle} cursor={{ fill: 'rgba(233,178,102,0.05)' }} />
          <Bar dataKey="count" radius={[3, 3, 0, 0]}>
            {data.map((_, i) => (
              <Cell
                key={i}
                fill={i === data.length - 1 ? AMBER : AMBER_MUTED}
                opacity={0.4 + (i / data.length) * 0.6}
              />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
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

// ─── Genre Marquee ────────────────────────────────────────────────────────────

function GenreMarquee() {
  const genres = useAppStore((s) => s.stats.topGenres)

  return (
    <ChartCard title="Genre Marquee — Top Categories">
      <div className="space-y-2.5">
        {genres.map((g, i) => {
          const maxCount = genres[0]?.count ?? 1
          const pct = (g.count / maxCount) * 100

          return (
            <div key={g.genre} className="flex items-center gap-3">
              <span className="font-mono text-xs text-muted-foreground w-4 text-right shrink-0">
                {i + 1}
              </span>
              <span className="font-sans text-sm text-foreground w-28 shrink-0 truncate">{g.genre}</span>
              <div className="flex-1 h-1.5 bg-secondary rounded-full overflow-hidden">
                <div
                  className="h-full rounded-full transition-all"
                  style={{
                    width: `${pct}%`,
                    background: `linear-gradient(90deg, ${AMBER_MUTED}, ${AMBER})`,
                  }}
                />
              </div>
              <span className="font-mono text-xs text-muted-foreground w-4 shrink-0">{g.count}</span>
            </div>
          )
        })}
      </div>
    </ChartCard>
  )
}

// ─── The Auteurs ──────────────────────────────────────────────────────────────

function TheAuteurs() {
  const directors = useAppStore((s) => s.stats.topDirectors)

  if (directors.length === 0) return null

  return (
    <ChartCard title="The Auteurs — Top Directors">
      <div className="space-y-3">
        {directors.map((d, i) => (
          <div key={d.director} className="flex items-center gap-4">
            <span
              className="font-serif text-2xl font-light shrink-0"
              style={{ color: i === 0 ? AMBER : MUTED_FG }}
            >
              {i + 1}
            </span>
            <div className="flex-1 min-w-0">
              <p className="font-sans text-sm text-foreground truncate">{d.director}</p>
              <p className="font-mono text-xs text-muted-foreground">
                {d.count} film{d.count !== 1 ? 's' : ''}
              </p>
            </div>
            {i === 0 && (
              <span className="text-amber text-lg shrink-0">★</span>
            )}
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
  const movieShare = Math.round((stats.totalMovies / (stats.totalMovies + stats.totalSeries)) * 100)
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
