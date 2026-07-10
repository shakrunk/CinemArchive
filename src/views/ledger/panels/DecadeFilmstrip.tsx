// ─── Decade filmstrip ─────────────────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { scopedTitles } from 'src/store/ledgerDerive'
import { describeLedgerSettings, settingsDepKey, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { decadeOf, maxOrOne } from 'src/lib/utils'
import { useChartTip } from 'src/components/ChartTip'
import { MiniLineChart, type SparklinePoint } from 'src/components/LedgerCharts'
import { Panel, PanelEmpty } from '../PanelShell'

const FILMSTRIP_HOLES = Array.from({ length: 28 })

export function DecadeFilmstrip({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const settingsKey = settingsDepKey(settings)
  const tip = useChartTip()

  const decades = useMemo(() => {
    const { titles: scoped } = scopedTitles('decades', titles, settings)
    const counts = new Map<number, number>()
    for (const t of scoped) {
      const decade = decadeOf(t.year)
      counts.set(decade, (counts.get(decade) ?? 0) + 1)
    }
    return [...counts.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([decade, count]) => ({ label: `${decade}s`, count }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [titles, settingsKey])

  const maxCount = maxOrOne(decades.map((d) => d.count))

  const points: SparklinePoint[] = useMemo(
    () =>
      decades.map((d, i) => ({
        x: decades.length === 1 ? 500 : (i / (decades.length - 1)) * 1000,
        y: 190 - (d.count / maxCount) * 164,
        tooltip: { label: d.label, value: `${d.count} title${d.count !== 1 ? 's' : ''}` },
      })),
    [decades, maxCount],
  )

  return (
    <Panel
      title={settings?.title || 'By the era'}
      hint={`decade breakdown${describeLedgerSettings(settings)}`}
      className={className}
    >
      {decades.length === 0 ? (
        <PanelEmpty message="No titles yet" />
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
            <MiniLineChart
              points={points}
              viewBoxHeight={200}
              areaBaseline={190}
              heightPx={150}
              lineColor="var(--amber-bright)"
              areaColor="var(--amber)"
              areaOpacity={0.35}
              dotSize={14}
              tipBind={tip.bind}
            />
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
      {tip.node}
    </Panel>
  )
}
