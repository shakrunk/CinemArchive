import { useEffect, useState } from 'react'
import { fetchSeasonDetails, TMDB_STILL_BASE, type RawTmdbEpisode, type RawTmdbSeason } from 'src/lib/media'
import { fmtReleaseDate, fmtRuntime } from 'src/lib/utils'
import { orderSeasonsForDisplay, seasonLabel, seasonShortLabel } from 'src/store/episodeUtils'
import { useAppStore } from 'src/store/useAppStore'
import { SubsectionLabel } from 'src/components/ui/typography'

interface DiscoverEpisodeBrowserProps {
  tmdbId: number
  seasons: RawTmdbSeason[]
}

type SeasonState = RawTmdbEpisode[] | 'error' | undefined

/** Read-only season/episode guide for a series that is not in the library yet. */
export function DiscoverEpisodeBrowser({ tmdbId, seasons }: DiscoverEpisodeBrowserProps) {
  const ordered = orderSeasonsForDisplay(seasons.map((s) => ({ ...s, seasonNumber: s.season_number })))
  const [selected, setSelected] = useState(ordered[0]?.seasonNumber)
  const [loaded, setLoaded] = useState<Record<number, SeasonState>>({})
  const [openEpisode, setOpenEpisode] = useState<number | null>(null)
  // Nothing here is in the library, so nothing has been watched: spoiler
  // protection hides every episode's still, name and synopsis.
  const spoilerHidden = useAppStore((s) => s.spoilerProtection)

  useEffect(() => {
    if (selected === undefined || loaded[selected] !== undefined) return
    let cancelled = false
    fetchSeasonDetails(tmdbId, selected).then(({ episodes }) => {
      if (!cancelled) setLoaded((prev) => ({ ...prev, [selected]: episodes.length > 0 ? episodes : 'error' }))
    })
    return () => { cancelled = true }
  }, [tmdbId, selected, loaded])

  if (ordered.length === 0 || selected === undefined) return null
  const state = loaded[selected]
  const meta = ordered.find((s) => s.seasonNumber === selected)

  return (
    <div>
      <SubsectionLabel>Episodes</SubsectionLabel>
      <div className="flex gap-1.5 overflow-x-auto scrollbar-none mb-3" role="tablist" aria-label="Seasons">
        {ordered.map((s) => (
          <button
            key={s.seasonNumber}
            type="button"
            role="tab"
            aria-selected={s.seasonNumber === selected}
            aria-label={seasonLabel(s.seasonNumber)}
            onClick={() => { setSelected(s.seasonNumber); setOpenEpisode(null) }}
            className={`shrink-0 px-3 py-1.5 rounded-lg font-mono text-xs border transition-colors ${
              s.seasonNumber === selected ? 'border-amber/40 bg-amber/10 text-amber' : 'border-transparent text-paper-faint hover:border-(--line)'
            }`}
          >
            {seasonShortLabel(s.seasonNumber)}
          </button>
        ))}
      </div>

      {state === undefined ? (
        <p role="status" className="text-sm text-paper-faint">Loading episodes…</p>
      ) : state === 'error' ? (
        <p className="text-sm text-paper-faint">
          {meta ? `${meta.episode_count} episodes` : 'Episodes'} — details are not available right now.
        </p>
      ) : (
        <ul className="space-y-2">
          {state.map((ep) => {
            const open = openEpisode === ep.episode_number
            const still = ep.still_path && !spoilerHidden ? `${TMDB_STILL_BASE}${ep.still_path}` : null
            const info = [ep.air_date ? fmtReleaseDate(ep.air_date) : null, ep.runtime ? fmtRuntime(ep.runtime) : null].filter(Boolean).join(' · ')
            return (
              <li key={ep.episode_number} className="rounded-lg border border-(--line)" style={{ background: 'var(--inset)' }}>
                <button
                  type="button"
                  onClick={() => setOpenEpisode(open ? null : ep.episode_number)}
                  aria-expanded={open}
                  className="w-full flex gap-3 p-2 text-left focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-amber/60 rounded-lg"
                >
                  {still && <img src={still} alt="" loading="lazy" className="w-24 aspect-video object-cover rounded-md shrink-0" />}
                  <div className="min-w-0 flex-1">
                    <p className="font-sans text-sm text-paper leading-tight">
                      <span className="font-mono text-xs text-amber mr-1.5">{seasonShortLabel(selected)} E{ep.episode_number}</span>
                      {spoilerHidden ? `Episode ${ep.episode_number}` : ep.name}
                    </p>
                    {info && <p className="font-mono text-[11px] text-paper-faint mt-0.5">{info}</p>}
                    {ep.overview && !spoilerHidden && (
                      <p className={`font-sans text-xs text-paper/70 mt-1 ${open ? '' : 'line-clamp-2'}`}>{ep.overview}</p>
                    )}
                  </div>
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
