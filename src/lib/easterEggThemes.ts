import type { Title } from 'src/store/mockData'

// Canonical TMDB ids for the titles that gate the secret app themes. Shared so
// TitleDetailDrawer/UpNext (which trigger the unlock) and Settings (which
// decides whether to reveal the theme at all) stay in sync.
export const SPIDER_NOIR_TMDB_ID = 220102
export const THE_MATRIX_TMDB_ID = 603

const THEME_TMDB_ID: Record<'noir' | 'matrix', number> = {
  noir: SPIDER_NOIR_TMDB_ID,
  matrix: THE_MATRIX_TMDB_ID,
}

/**
 * Locked easter-egg themes stay hidden from Settings entirely until the
 * linked title has been added to the library in some form (watchlist,
 * watching, dropped, watched) — they're meant to be stumbled on, not
 * spoiled by a "locked" card before the user has ever heard of the film.
 * Once discovered they render as a locked card; fully watching the title
 * still unlocks the theme via `unlockTheme`.
 */
export function isThemeDiscovered(theme: 'noir' | 'matrix', titles: Title[]): boolean {
  return titles.some((t) => t.tmdbId === THEME_TMDB_ID[theme])
}
