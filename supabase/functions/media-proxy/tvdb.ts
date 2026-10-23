// TVDB v4 adapter. Keep TMDB identities/order so existing watch history stays stable.
type RecordData = Record<string, unknown>
const record = (value: unknown): RecordData =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value as RecordData : {}
const text = (value: unknown) => typeof value === 'string' && value.trim() ? value : undefined
const positiveId = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0
const day = (date: string) => Date.parse(`${date}T00:00:00Z`) / (24 * 60 * 60 * 1000)

/** Known [TMDB, TVDB] air-date pairs agree, or all differ by the same single
 *  day: TMDB often lists a release on the evening before TVDB's US Eastern or
 *  official release date. A one-day shift is only trusted when no two releases
 *  are a day apart, since on a daily show it is what misnumbering looks like. */
function airDatesAgree(pairs: [string, string][]): boolean {
  const shifts = pairs.map(([tmdb, tvdb]) => day(tvdb) - day(tmdb))
  if (shifts.every(shift => shift === 0)) return true
  if (Math.abs(shifts[0]) !== 1 || shifts.some(shift => shift !== shifts[0])) return false
  const releases = [...new Set(pairs.map(([tmdb]) => day(tmdb)))].sort((a, b) => a - b)
  return releases.every((release, i) => i === 0 || release - releases[i - 1] > 1)
}

interface Options {
  apiKey?: string
  pin?: string
  getCached: (key: string) => Promise<unknown | null>
  setCached: (key: string, value: unknown) => Promise<void>
  fetcher?: typeof fetch
}

export function createTvdb(options: Options) {
  const fetcher = options.fetcher ?? fetch
  let token: string | undefined
  let expires = 0
  let loginPending: Promise<string> | undefined
  let loginBlockedUntil = 0

  async function login(): Promise<string> {
    if (token && Date.now() < expires) return token
    // Bad credentials or an outage must not add a five-second wait to every TV request.
    if (Date.now() < loginBlockedUntil) throw new Error('TVDB login cooling down')
    if (!loginPending) {
      loginPending = (async () => {
        const response = await fetcher('https://api4.thetvdb.com/v4/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ apikey: options.apiKey, ...(options.pin ? { pin: options.pin } : {}) }),
          signal: AbortSignal.timeout(5000),
        })
        const body = record(await response.json())
        const value = text(record(body.data).token)
        if (!response.ok || body.status !== 'success' || !value) throw new Error('TVDB login failed')
        token = value
        expires = Date.now() + 12 * 60 * 60 * 1000
        return value
      })().catch(error => {
        loginBlockedUntil = Date.now() + 60 * 1000
        throw error
      }).finally(() => { loginPending = undefined })
    }
    return loginPending
  }

  async function request(path: string): Promise<RecordData> {
    const key = `tvdb:v1:${path}`
    const cached = await options.getCached(key)
    if (cached !== null) return record(cached)
    for (let attempt = 0; attempt < 2; attempt++) {
      const response = await fetcher(`https://api4.thetvdb.com/v4${path}`, {
        headers: { Authorization: `Bearer ${await login()}` },
        signal: AbortSignal.timeout(5000),
      })
      if (response.status === 401 && attempt === 0) {
        token = undefined
        continue
      }
      const body = record(await response.json())
      if (!response.ok || body.status !== 'success' || !body.data) throw new Error('TVDB request failed')
      await options.setCached(key, body)
      return body
    }
    throw new Error('TVDB authentication failed')
  }

  async function details(base: unknown): Promise<unknown> {
    const tmdb = record(base)
    const id = record(tmdb.external_ids).tvdb_id
    if (!options.apiKey || !positiveId(id)) return base
    try {
      const series = record((await request(`/series/${id}/extended?meta=translations`)).data)
      if (series.id !== id) return base
      const translations = record(series.translations)
      const english = (value: unknown, field: string) => Array.isArray(value)
        ? text(record(value.find(item => record(item).language === 'eng'))[field]) : undefined
      const name = english(translations.nameTranslations, 'name') ?? (series.originalLanguage === 'eng' ? text(series.name) : undefined)
      const overview = english(translations.overviewTranslations, 'overview') ?? (series.originalLanguage === 'eng' ? text(series.overview) : undefined)
      return {
        ...tmdb,
        name: name ?? tmdb.name,
        overview: overview ?? tmdb.overview,
        first_air_date: text(series.firstAired) ?? tmdb.first_air_date,
        episode_run_time: positiveId(series.averageRuntime) ? [series.averageRuntime] : tmdb.episode_run_time,
        metadata_source: 'tvdb',
        tvdb_id: id,
      }
    } catch {
      // Never persist a fallback over TVDB data, or expose upstream credentials/errors.
      return base
    }
  }

  async function season(base: unknown, show: unknown, seasonNumber: number): Promise<unknown> {
    const tmdb = record(base)
    const id = record(record(show).external_ids).tvdb_id
    if (!options.apiKey || !positiveId(id) || !Array.isArray(tmdb.episodes)) return base
    try {
      const episodes: RecordData[] = []
      for (let page = 0; page < 20; page++) {
        const body = await request(`/series/${id}/episodes/official/eng?season=${seasonNumber}&page=${page}`)
        const rows = record(body.data).episodes
        if (!Array.isArray(rows)) return base
        // The translated episode listing ignores `season` and returns every season.
        episodes.push(...rows.map(record).filter(episode => episode.seasonNumber === seasonNumber))
        if (!record(body.links).next) break
        if (page === 19) return base // Never apply a truncated season.
      }
      // A numbering disagreement can attach metadata to the wrong watched episode.
      // Require equal episode sets and agreeing known air dates before applying it.
      const mapped = new Map(episodes.map(episode => [episode.number, episode]))
      if (mapped.size !== episodes.length || episodes.length !== tmdb.episodes.length) return base
      const dated: [string, string][] = []
      for (const value of tmdb.episodes) {
        const episode = record(value)
        const match = mapped.get(episode.episode_number)
        if (!match) return base
        const tmdbDate = text(episode.air_date)
        const tvdbDate = text(match.aired)
        if (tmdbDate && tvdbDate) dated.push([tmdbDate, tvdbDate])
      }
      if (!airDatesAgree(dated)) return base
      return {
        ...tmdb,
        metadata_source: 'tvdb',
        tvdb_id: id,
        episodes: tmdb.episodes.map(value => {
          const episode = record(value)
          const match = mapped.get(episode.episode_number)!
          return {
            ...episode,
            name: text(match.name) ?? episode.name,
            overview: text(match.overview) ?? episode.overview,
            air_date: text(match.aired) ?? episode.air_date,
            runtime: positiveId(match.runtime) ? match.runtime : episode.runtime,
          }
        }),
      }
    } catch {
      return base
    }
  }

  return { details, season, enabled: Boolean(options.apiKey) }
}
