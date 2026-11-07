// The country whose theatrical "now playing" list the schedule flow shows (#202), as an
// ISO 3166-1 alpha-2 code. A region, not a location: no permission and no geolocation, and TMDB
// has no per-theater data to narrow it further. Mirrors Android's MoviegoingRegion.kt.

export const FALLBACK_REGION = 'US'

const CODE_RE = /^[A-Z]{2}$/

/** The synced wire value: exactly two uppercase ASCII letters. */
export const isValidRegion = (code: unknown): code is string => typeof code === 'string' && CODE_RE.test(code)

/** What a user typed or a locale reported, tidied to the wire form, or null when it cannot be one. */
export function normalizeRegion(input: string | null | undefined): string | null {
  const code = input?.trim().toUpperCase()
  return isValidRegion(code) ? code : null
}

/** The country in a BCP 47 tag such as `en-GB` or `zh-Hant-TW`, or null when it carries none. */
export function regionFromLanguage(language: string | null | undefined): string | null {
  const region = language?.split('-').slice(1).find((part) => /^[A-Za-z]{2}$/.test(part))
  return normalizeRegion(region)
}

/** The saved region, else the browser language's country, else the fallback. */
export function effectiveRegion(saved: string | null | undefined, language?: string | null): string {
  return (isValidRegion(saved) ? saved : null) ?? regionFromLanguage(language) ?? FALLBACK_REGION
}
