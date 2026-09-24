import { useMemo, useState } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { deriveRatingNormalization, type RatingBaseline } from 'src/store/ratingNormalization'
import type { LedgerScope } from 'src/lib/ledgerPanels'
import { LIST_ROW_HOVER } from '../PanelShell'

function formatZ(value: number | null) {
  if (value === null) return '—'
  const rounded = Number(value.toFixed(2))
  return `${rounded > 0 ? '+' : ''}${rounded.toFixed(2)}`
}

export function RatingStandards({ scope = 'all' }: { scope?: LedgerScope }) {
  const titles = useAppStore((s) => s.titles)
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const [baseline, setBaseline] = useState<RatingBaseline>('all')
  const [search, setSearch] = useState('')
  const { groups, rows } = useMemo(
    () => deriveRatingNormalization(titles, baseline, scope),
    [titles, baseline, scope],
  )
  const matches = rows.filter((row) => row.title.title.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()))

  return (
    <div className="min-h-0 flex-1 overflow-y-auto space-y-3 pr-1">
      <label className="flex flex-wrap items-center gap-2 text-xs text-paper-dim">
        Compare against
        <select
          value={baseline}
          onChange={(event) => setBaseline(event.target.value as RatingBaseline)}
          className="min-w-0 rounded border border-[var(--line)] bg-[var(--ink-2)] px-2 py-1 text-paper focus-visible:outline-amber"
        >
          <option value="all">All rated titles</option>
          <option value="media">Same media type</option>
        </select>
      </label>
      <div className="space-y-1 text-xs text-paper-dim" aria-live="polite">
        {groups.filter((group) => group.count > 0).map((group) => (
          <p key={group.key}>
            {group.label}: {group.count} rated · mean {group.mean!.toFixed(2)} · SD {group.deviation!.toFixed(2)}
            {group.count < 5 && <span className="text-amber"> · small sample</span>}
          </p>
        ))}
      </div>
      <details className="text-xs text-paper-faint">
        <summary className="cursor-pointer text-paper-dim">How to read these scores</summary>
        <p className="mt-2 leading-relaxed">
          Z = (rating − baseline mean) / standard deviation. +1 means one standard deviation above
          this library’s usual rating; negative means below. Rank is the percentage of baseline
          ratings below this score, plus half of tied ratings. It does not assume a bell curve.
        </p>
        <p className="mt-2 leading-relaxed">
          Each rated title counts once, using its current 0–5 rating. Baselines use all available
          titles in this library; shared libraries may be partial. Search and widget scope only
          filter the results. Small samples are descriptive and can shift sharply. A dash means
          fewer than two ratings or no variation. Original ratings stay unchanged.
        </p>
      </details>
      {rows.length === 0 ? <p className="text-sm text-paper-faint">No rated titles in this scope yet.</p> : (
        <>
          <input
            type="search"
            aria-label="Find a rated title"
            placeholder="Find a rated title…"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            className="w-full rounded border border-[var(--line)] bg-[var(--ink-2)] px-2 py-1.5 text-xs text-paper placeholder:text-paper-faint focus-visible:outline-amber"
          />
          <table className="w-full table-fixed text-xs">
            <caption className="sr-only">Personal rating normalization, highest z-score first</caption>
            <thead className="text-paper-faint">
              <tr>
                <th scope="col" className="text-left font-normal">Title</th>
                <th scope="col" className="w-11 text-right font-normal">Stars</th>
                <th scope="col" className="w-12 text-right font-normal">Z</th>
                <th scope="col" className="w-14 text-right font-normal">Rank %</th>
              </tr>
            </thead>
            <tbody>
              {matches.slice(0, 50).map((row) => (
                <tr key={row.title.id}>
                  <td className="py-1">
                    <button type="button" onClick={() => openDetailDrawer(row.title.id)} className={`${LIST_ROW_HOVER} w-full truncate text-paper`} title={row.title.title}>
                      {row.title.title}
                    </button>
                  </td>
                  <td className="text-right font-mono text-paper-dim">{Number(row.title.rating.toFixed(2))}</td>
                  <td className="text-right font-mono text-amber" title={row.zScore === null ? 'Z-score unavailable: fewer than two ratings or no variation' : `${row.baseline.label} baseline`}>
                    {formatZ(row.zScore)}
                  </td>
                  <td className="text-right font-mono text-paper-dim">{row.percentile.toFixed(1)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {matches.length === 0 && <p className="text-xs text-paper-faint">No matching rated titles.</p>}
          {matches.length > 50 && <p className="text-xs text-paper-faint">Showing 50 of {matches.length}. Search to find any rated title.</p>}
        </>
      )}
    </div>
  )
}
