/**
 * Runtime logic verification for content-certification parsing and external-link
 * building (mirrors src/lib/media.ts: extractCertification and
 * src/components/TitleDetailDrawer.tsx: buildExternalLinks).
 * Run with: node scripts/verify-certification-logic.mjs
 */
let pass = 0, fail = 0
function assert(label, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected)
  if (a === e) { console.log(`  ✓ ${label}: ${a}`); pass++ }
  else { console.error(`  ✗ ${label}: expected ${e}, got ${a}`); fail++ }
}

// ── Mirror of extractCertification in src/lib/media.ts ──
function extractCertification(data, type) {
  if (type === 'movie') {
    const us = (data.release_dates?.results ?? []).find((r) => r.iso_3166_1 === 'US')
    const cert = (us?.release_dates ?? [])
      .map((rd) => rd.certification)
      .find((c) => c && c.trim() !== '')
    return cert ? cert.trim() : undefined
  }
  const us = (data.content_ratings?.results ?? []).find((r) => r.iso_3166_1 === 'US')
  const rating = us?.rating
  return rating && rating.trim() !== '' ? rating.trim() : undefined
}

// ── Mirror of buildExternalLinks in TitleDetailDrawer.tsx ──
function buildExternalLinks(title) {
  const links = []
  const q = encodeURIComponent(title.title)
  if (title.tmdbId > 0) {
    links.push({ brand: 'tmdb', href: `https://www.themoviedb.org/${title.type}/${title.tmdbId}` })
  }
  links.push({
    brand: 'imdb',
    href: title.imdbId ? `https://www.imdb.com/title/${title.imdbId}/` : `https://www.imdb.com/find/?q=${q}&s=tt`,
  })
  links.push({ brand: 'rt', href: `https://www.rottentomatoes.com/search?search=${q}` })
  links.push({ brand: 'metacritic', href: `https://www.metacritic.com/search/${q}/` })
  return links
}

console.log('— Certification: movies —')
// Realistic movie payload: theatrical entry has empty cert, digital entry carries PG-13.
const movieMulti = {
  release_dates: {
    results: [
      { iso_3166_1: 'GB', release_dates: [{ certification: '12A' }] },
      { iso_3166_1: 'US', release_dates: [
        { type: 3, certification: '' },          // theatrical, blank
        { type: 4, certification: 'PG-13' },     // digital, real value
      ] },
    ],
  },
}
assert('picks first non-empty US cert (not [0])', extractCertification(movieMulti, 'movie'), 'PG-13')

assert('no US entry → undefined', extractCertification(
  { release_dates: { results: [{ iso_3166_1: 'FR', release_dates: [{ certification: 'U' }] }] } }, 'movie'), undefined)

assert('all blank US certs → undefined', extractCertification(
  { release_dates: { results: [{ iso_3166_1: 'US', release_dates: [{ certification: '' }, { certification: '   ' }] }] } }, 'movie'), undefined)

assert('missing release_dates → undefined', extractCertification({}, 'movie'), undefined)
assert('trims whitespace', extractCertification(
  { release_dates: { results: [{ iso_3166_1: 'US', release_dates: [{ certification: ' R ' }] }] } }, 'movie'), 'R')

console.log('— Certification: TV —')
const tvPayload = { content_ratings: { results: [
  { iso_3166_1: 'AU', rating: 'MA15+' },
  { iso_3166_1: 'US', rating: 'TV-MA' },
] } }
assert('picks US rating', extractCertification(tvPayload, 'tv'), 'TV-MA')
assert('no US rating → undefined', extractCertification(
  { content_ratings: { results: [{ iso_3166_1: 'JP', rating: 'G' }] } }, 'tv'), undefined)
assert('empty US rating → undefined', extractCertification(
  { content_ratings: { results: [{ iso_3166_1: 'US', rating: '' }] } }, 'tv'), undefined)
assert('missing content_ratings → undefined', extractCertification({}, 'tv'), undefined)

console.log('— External links —')
const withImdb = buildExternalLinks({ title: 'Inception', type: 'movie', tmdbId: 27205, imdbId: 'tt1375666' })
assert('TMDB exact', withImdb.find((l) => l.brand === 'tmdb').href, 'https://www.themoviedb.org/movie/27205')
assert('IMDb exact id', withImdb.find((l) => l.brand === 'imdb').href, 'https://www.imdb.com/title/tt1375666/')
assert('RT search', withImdb.find((l) => l.brand === 'rt').href, 'https://www.rottentomatoes.com/search?search=Inception')
assert('Metacritic search', withImdb.find((l) => l.brand === 'metacritic').href, 'https://www.metacritic.com/search/Inception/')

const noImdb = buildExternalLinks({ title: 'Some Show', type: 'tv', tmdbId: 0 })
assert('no TMDB link when tmdbId 0', noImdb.some((l) => l.brand === 'tmdb'), false)
assert('IMDb search fallback (encoded)', noImdb.find((l) => l.brand === 'imdb').href, 'https://www.imdb.com/find/?q=Some%20Show&s=tt')

console.log(`\n${pass} passed, ${fail} failed`)
process.exit(fail > 0 ? 1 : 0)
