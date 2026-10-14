import type { MediaType } from 'src/store/mockData'

interface CatalogIdentity { type: MediaType; tmdbId?: number | null }

/** TMDB movie and television IDs use separate namespaces. Unlinked titles own neither. */
function catalogKey(title: CatalogIdentity): string | null {
  return title.tmdbId == null ? null : `${title.type}:${title.tmdbId}`
}

export function libraryCatalogKeys(titles: readonly CatalogIdentity[]): Set<string> {
  return new Set(titles.map(catalogKey).filter((key): key is string => key !== null))
}

export function isCatalogTitleOwned(keys: ReadonlySet<string>, title: CatalogIdentity | null): boolean {
  const key = title ? catalogKey(title) : null
  return key !== null && keys.has(key)
}
