import { useAppStore } from 'src/store/useAppStore'
import { Eyebrow } from 'src/components/ui/typography'

export function ArchiveGlance() {
  const stats = useAppStore((s) => s.stats)
  const total = stats.totalMovies + stats.totalSeries
  const hours = Math.round(stats.totalMinutes / 60)

  const items: { value: string; sub: string }[] = [
    { value: String(total), sub: `${stats.totalMovies} films · ${stats.totalSeries} series` },
    { value: String(stats.totalViewings), sub: 'screenings' },
    { value: stats.avgRating.toFixed(1), sub: 'avg rating' },
    { value: `${hours}h`, sub: 'screen time' },
  ]

  return (
    <div className="flex items-start overflow-x-auto pb-3 mb-[clamp(24px,4vw,40px)] border-b border-(--line)">
      {items.map((item, i) => (
        <div key={item.sub} className="flex items-stretch shrink-0">
          {i > 0 && <div className="w-px bg-(--line-2) mx-6 sm:mx-8 self-stretch" />}
          <div className="flex flex-col">
            <div className="stat-num text-[clamp(22px,2.6vw,34px)]">{item.value}</div>
            <Eyebrow as="div" size="md" className="mt-1.5 whitespace-nowrap">
              {item.sub}
            </Eyebrow>
          </div>
        </div>
      ))}
    </div>
  )
}
