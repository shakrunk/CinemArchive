import { useState, useEffect, useMemo } from 'react'
import { isCatalogTitleOwned } from 'src/lib/catalogIdentity'
import { fetchPersonCredits, fetchRecommendations, type SearchResult } from 'src/lib/media'
import { staggerDelays } from 'src/lib/utils'
import type { Title } from 'src/store/mockData'
import type { FilterType } from './useDiscoverCatalog'

/** One taste-based carousel row: the dropdown's basis choice plus the results it seeds. */
export interface RecommendationRail {
  options: { id: string; label: string }[]
  /** The selected basis option's id, or null when the library offers none. */
  value: string | null
  onChange: (id: string) => void
  loading: boolean
  /** Results for the current basis, already filtered to the active format. */
  results: SearchResult[]
  delays: number[]
}

/** "Because You Watched" — real TMDB recommendations keyed off a library title. */
export function useBecauseWatchedRail(
  titles: Title[],
  ownedCatalogKeys: ReadonlySet<string>,
  filterType: FilterType,
): RecommendationRail {
  const [overrideId, setOverrideId] = useState<string | null>(null)
  const [results, setResults] = useState<SearchResult[]>([])
  const [loadedId, setLoadedId] = useState<string | null>(null)

  // Basis defaults to the first library title unless the user picked one explicitly
  const basisId = overrideId ?? (titles.length > 0 ? titles[0].id : null)

  // ⚡ Bolt: Memoize expensive O(N) array mapping to prevent unnecessary object allocations on every render
  const options = useMemo(() => titles.map((t) => ({ id: t.id, label: t.title })), [titles])

  const basis = useMemo(
    () => titles.find((t) => t.id === basisId) ?? null,
    [titles, basisId]
  )

  // Titles without a TMDB link (tmdbId 0 on manual/migrated adds) have nothing
  // to seed the feed with — derived here rather than cleared from the effect.
  const seedable = Boolean(basis?.tmdbId)

  // Derived rather than tracked separately — mirrors useMoreStarringRail's loading.
  const loading = basisId !== null && seedable && basisId !== loadedId

  // Real similar-title picks from TMDB's /movie|tv/{id}/recommendations, via the
  // media-proxy `recommendations` action.
  useEffect(() => {
    if (!basis?.tmdbId) return
    const { id, tmdbId, type } = basis
    let cancelled = false
    fetchRecommendations(tmdbId, type)
      .then((recs) => {
        if (cancelled) return
        setResults(recs.filter((r) => !isCatalogTitleOwned(ownedCatalogKeys, r)))
        setLoadedId(id)
      })
      .catch((err) => {
        console.error('recommendations error:', err)
        if (cancelled) return
        setResults([])
        setLoadedId(id)
      })
    return () => { cancelled = true }
  }, [basis, ownedCatalogKeys])

  // Hide stale results whenever the current basis isn't the loaded one (or can't
  // be seeded at all). Filtered by filterType client-side since fetchRecommendations
  // is keyed to the seed title's own type, not the active type filter.
  const visible = useMemo(() => {
    if (!(seedable && basisId === loadedId)) return []
    return filterType === 'all' ? results : results.filter((r) => r.type === filterType)
  }, [seedable, basisId, loadedId, results, filterType])

  const delays = useMemo(() => staggerDelays(visible.length), [visible.length])

  return { options, value: basisId, onChange: setOverrideId, loading, results: visible, delays }
}

/** "More starring" — real TMDB filmography via fetchPersonCredits, keyed off library cast. */
export function useMoreStarringRail(
  titles: Title[],
  ownedCatalogKeys: ReadonlySet<string>,
  filterType: FilterType,
): RecommendationRail {
  const [overridePersonId, setOverridePersonId] = useState<number | null>(null)
  const [results, setResults] = useState<SearchResult[]>([])
  const [loadedPersonId, setLoadedPersonId] = useState<number | null>(null)

  const castOptions = useMemo(() => {
    const seen = new Map<number, string>()
    for (const t of titles) {
      for (const c of t.cast ?? []) {
        if (!seen.has(c.tmdbPersonId)) seen.set(c.tmdbPersonId, c.name)
      }
    }
    return Array.from(seen, ([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name))
  }, [titles])

  // Basis defaults to the first known cast member unless the user picked one explicitly
  const personId = overridePersonId ?? (castOptions.length > 0 ? castOptions[0].id : null)

  // ⚡ Bolt: Memoize expensive O(N) array mapping to prevent unnecessary object allocations on every render
  const options = useMemo(() => castOptions.map((c) => ({ id: String(c.id), label: c.name })), [castOptions])

  // Derived rather than tracked separately: loading exactly while we have a basis whose
  // credits haven't landed yet (mirrors DiscoverDetailModal's `hydrating` derivation).
  const loading = personId !== null && personId !== loadedPersonId

  useEffect(() => {
    if (personId === null) return
    let cancelled = false
    fetchPersonCredits(personId)
      .then((credits) => {
        if (cancelled) return
        setResults(credits.filter((r) => !isCatalogTitleOwned(ownedCatalogKeys, r)))
        setLoadedPersonId(personId)
      })
      .catch((err) => {
        console.error('more starring credits error:', err)
        if (cancelled) return
        setResults([])
        setLoadedPersonId(personId)
      })
    return () => { cancelled = true }
  }, [personId, ownedCatalogKeys])

  // Hide stale results the moment the basis reverts to no selection (e.g. an empty library)
  // rather than clearing them from an effect. Filtered by filterType client-side — same
  // reasoning as useBecauseWatchedRail.
  const visible = useMemo(() => {
    if (personId === null) return []
    return filterType === 'all' ? results : results.filter((r) => r.type === filterType)
  }, [personId, results, filterType])

  const delays = useMemo(() => staggerDelays(visible.length), [visible.length])

  return {
    options,
    value: personId != null ? String(personId) : null,
    onChange: (id) => setOverridePersonId(Number(id)),
    loading,
    results: visible,
    delays,
  }
}
