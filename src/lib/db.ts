import { supabase } from './auth'
import type { Title, WatchStatus, MediaType } from '../store/mockData'

// ─── Mapping Helpers ─────────────────────────────────────────────────────────

function mapDbTitleToLocal(row: any): Title {
  // Group episodes by season_number so we can attach them to the right season
  const episodesBySeason: Record<number, any[]> = {}
  for (const ep of (row.episodes || [])) {
    if (!episodesBySeason[ep.season_number]) episodesBySeason[ep.season_number] = []
    episodesBySeason[ep.season_number].push(ep)
  }

  return {
    id: row.id,
    tmdbId: row.tmdb_id,
    type: row.type as MediaType,
    title: row.title,
    year: row.year,
    director: row.director || undefined,
    genres: row.genres || [],
    posterUrl: row.poster_url || undefined,
    backdropUrl: row.backdrop_url || undefined,
    synopsis: row.synopsis || undefined,
    runtime: row.runtime || undefined,
    network: row.network || undefined,
    status: row.status as WatchStatus,
    rating: row.rating ? parseFloat(row.rating) : undefined,
    notes: row.notes || undefined,
    tags: row.tags || [],
    addedAt: row.added_at
      ? new Date(row.added_at).toISOString().slice(0, 10)
      : new Date().toISOString().slice(0, 10),
    imdbRating: row.imdb_rating ? parseFloat(row.imdb_rating) : undefined,
    rtScore: row.rt_score || undefined,
    metacriticScore: row.metacritic_score || undefined,
    seasons: (row.seasons || [])
      .map((s: any) => ({
        id: s.id,
        seasonNumber: s.season_number,
        episodeCount: s.episode_count,
        episodesWatched: s.episodes_watched,
        airYear: s.air_year || undefined,
        episodes: (episodesBySeason[s.season_number] || [])
          .sort((a: any, b: any) => a.episode_number - b.episode_number)
          .map((ep: any) => ({
            id: ep.id,
            episodeNumber: ep.episode_number,
            episodeName: ep.episode_name || undefined,
            airDate: ep.air_date || undefined,
            runtime: ep.runtime || undefined,
            synopsis: ep.synopsis || undefined,
            watchEvents: (ep.episode_watch_events || [])
              .sort(
                (a: any, b: any) =>
                  new Date(a.watched_at).getTime() - new Date(b.watched_at).getTime()
              )
              .map((we: any) => ({
                id: we.id,
                watchedAt: we.watched_at,
                notes: we.notes || undefined,
              })),
            ratings: (ep.episode_ratings || [])
              .sort(
                (a: any, b: any) =>
                  new Date(a.rated_at).getTime() - new Date(b.rated_at).getTime()
              )
              .map((er: any) => ({
                id: er.id,
                rating: parseFloat(er.rating),
                ratedAt: er.rated_at,
              })),
            reviews: (ep.episode_reviews || [])
              .sort(
                (a: any, b: any) =>
                  new Date(a.reviewed_at).getTime() - new Date(b.reviewed_at).getTime()
              )
              .map((rv: any) => ({
                id: rv.id,
                reviewText: rv.review_text,
                reviewedAt: rv.reviewed_at,
              })),
          })),
      }))
      .sort((a: any, b: any) => a.seasonNumber - b.seasonNumber),
    viewings: (row.viewings || [])
      .map((v: any) => ({
        id: v.id,
        titleId: v.title_id,
        date: v.viewed_at,
        rating: v.rating ? parseFloat(v.rating) : undefined,
        notes: v.notes || undefined,
      }))
      .sort((a: any, b: any) => new Date(b.date).getTime() - new Date(a.date).getTime()),
  }
}

// ─── Database Operations ─────────────────────────────────────────────────────

export async function fetchUserLibrary(userId: string): Promise<Title[]> {
  if (!supabase) return []

  const { data, error } = await supabase
    .from('titles')
    .select(`
      *,
      seasons (*),
      viewings (*),
      episodes (
        *,
        episode_watch_events (*),
        episode_ratings (*),
        episode_reviews (*)
      )
    `)
    .eq('user_id', userId)

  if (error) {
    console.error('Error fetching user library:', error)
    throw error
  }

  return (data || []).map(mapDbTitleToLocal)
}

export async function fetchSharedLibrary(token: string): Promise<Title[]> {
  if (!supabase) return []

  const { error: rpcError } = await supabase.rpc('set_shared_token', { token })
  if (rpcError) {
    console.error('Error setting shared token RPC:', rpcError)
    throw rpcError
  }

  const { data, error } = await supabase
    .from('titles')
    .select(`
      *,
      seasons (*),
      viewings (*),
      episodes (
        *,
        episode_watch_events (*),
        episode_ratings (*),
        episode_reviews (*)
      )
    `)

  if (error) {
    console.error('Error fetching shared library:', error)
    throw error
  }

  return (data || []).map(mapDbTitleToLocal)
}

export async function insertTitleToDb(userId: string, title: Title): Promise<void> {
  if (!supabase) return

  // 1. Insert core title
  const { error: titleError } = await supabase.from('titles').insert({
    id: title.id,
    user_id: userId,
    tmdb_id: title.tmdbId,
    type: title.type,
    title: title.title,
    year: title.year,
    director: title.director,
    genres: title.genres,
    poster_url: title.posterUrl,
    backdrop_url: title.backdropUrl,
    synopsis: title.synopsis,
    runtime: title.runtime,
    network: title.network,
    status: title.status,
    rating: title.rating,
    notes: title.notes,
    tags: title.tags,
    imdb_rating: title.imdbRating,
    rt_score: title.rtScore,
    metacritic_score: title.metacriticScore,
    added_at: new Date(title.addedAt).toISOString(),
  })

  if (titleError) {
    console.error('Error inserting title:', titleError)
    throw titleError
  }

  // 2. Insert seasons and episodes together
  if (title.type === 'tv' && title.seasons && title.seasons.length > 0) {
    const { error: seasonsError } = await supabase.from('seasons').insert(
      title.seasons.map((s) => ({
        id: s.id,
        title_id: title.id,
        user_id: userId,
        season_number: s.seasonNumber,
        episode_count: s.episodeCount,
        episodes_watched: s.episodesWatched,
        air_year: s.airYear,
      }))
    )
    if (seasonsError) {
      console.error('Error inserting seasons:', seasonsError)
      throw seasonsError
    }

    // Flatten all episodes across all seasons and insert in one call
    const allEpisodes = title.seasons.flatMap((s) =>
      (s.episodes || []).map((ep) => ({
        id: ep.id,
        title_id: title.id,
        user_id: userId,
        season_number: s.seasonNumber,
        episode_number: ep.episodeNumber,
        episode_name: ep.episodeName,
        air_date: ep.airDate,
        runtime: ep.runtime,
        synopsis: ep.synopsis,
      }))
    )

    if (allEpisodes.length > 0) {
      const { error: episodesError } = await supabase.from('episodes').insert(allEpisodes)
      if (episodesError) {
        console.error('Error inserting episodes:', episodesError)
        throw episodesError
      }
    }
  }

  // 3. Insert viewings if present
  if (title.viewings && title.viewings.length > 0) {
    const { error: viewingsError } = await supabase.from('viewings').insert(
      title.viewings.map((v) => ({
        id: v.id,
        title_id: title.id,
        user_id: userId,
        viewed_at: v.date,
        rating: v.rating,
        notes: v.notes,
      }))
    )
    if (viewingsError) {
      console.error('Error inserting viewings:', viewingsError)
      throw viewingsError
    }
  }
}

// Title columns that a metadata refresh may overwrite. Keyed by the client-side
// field; mapped to the snake_case DB column. Presence in the patch (even when
// undefined) writes the column, so local `undefined` and DB `null` stay in sync.
const META_COLUMNS: Array<[keyof Title, string]> = [
  ['tmdbId', 'tmdb_id'],
  ['title', 'title'],
  ['year', 'year'],
  ['director', 'director'],
  ['genres', 'genres'],
  ['posterUrl', 'poster_url'],
  ['backdropUrl', 'backdrop_url'],
  ['synopsis', 'synopsis'],
  ['runtime', 'runtime'],
  ['network', 'network'],
  ['imdbRating', 'imdb_rating'],
  ['rtScore', 'rt_score'],
  ['metacriticScore', 'metacritic_score'],
]

export async function updateTitleInDb(userId: string, titleId: string, patch: Partial<Title>): Promise<void> {
  if (!supabase) return

  const mappedPatch: any = {}
  if (patch.status !== undefined) mappedPatch.status = patch.status
  if (patch.rating !== undefined) mappedPatch.rating = patch.rating
  if (patch.notes !== undefined) mappedPatch.notes = patch.notes
  if (patch.tags !== undefined) mappedPatch.tags = patch.tags

  // Metadata refresh: only touch a column when its key is present in the patch.
  for (const [field, column] of META_COLUMNS) {
    if (field in patch) mappedPatch[column] = patch[field] ?? null
  }

  if (Object.keys(mappedPatch).length > 0) {
    const { error } = await supabase
      .from('titles')
      .update(mappedPatch)
      .eq('id', titleId)
      .eq('user_id', userId)

    if (error) {
      console.error('Error updating title:', error)
      throw error
    }
  }

  if (patch.seasons) {
    for (const s of patch.seasons) {
      const { error } = await supabase
        .from('seasons')
        .upsert(
          {
            title_id: titleId,
            user_id: userId,
            season_number: s.seasonNumber,
            episode_count: s.episodeCount,
            episodes_watched: s.episodesWatched,
            air_year: s.airYear,
          },
          { onConflict: 'title_id,season_number' }
        )

      if (error) {
        console.error(`Error updating season ${s.seasonNumber}:`, error)
        throw error
      }
    }
  }

  // Upsert all viewings: client-generated UUIDs insert as new rows, DB UUIDs update in-place
  if (patch.viewings) {
    for (const v of patch.viewings) {
      const { error } = await supabase.from('viewings').upsert({
        id: v.id,
        title_id: titleId,
        user_id: userId,
        viewed_at: v.date,
        rating: v.rating,
        notes: v.notes,
      })

      if (error) {
        console.error('Error upserting viewing:', error)
        throw error
      }
    }
  }
}

export async function logEpisodeToDb(
  userId: string,
  episodeId: string,
  opts: {
    watchedAt?: string
    watchNotes?: string
    rating?: number
    reviewText?: string
  }
): Promise<void> {
  if (!supabase) return

  if (opts.watchedAt) {
    const { error } = await supabase.from('episode_watch_events').insert({
      episode_id: episodeId,
      user_id: userId,
      watched_at: opts.watchedAt,
      notes: opts.watchNotes || undefined,
    })
    if (error) {
      console.error('Error inserting episode watch event:', error)
      throw error
    }
  }

  if (opts.rating && opts.rating > 0) {
    const { error } = await supabase.from('episode_ratings').insert({
      episode_id: episodeId,
      user_id: userId,
      rating: opts.rating,
    })
    if (error) {
      console.error('Error inserting episode rating:', error)
      throw error
    }
  }

  if (opts.reviewText?.trim()) {
    const { error } = await supabase.from('episode_reviews').insert({
      episode_id: episodeId,
      user_id: userId,
      review_text: opts.reviewText.trim(),
    })
    if (error) {
      console.error('Error inserting episode review:', error)
      throw error
    }
  }
}

export async function deleteTitleFromDb(userId: string, titleId: string): Promise<void> {
  if (!supabase) return

  const { error } = await supabase
    .from('titles')
    .delete()
    .eq('id', titleId)
    .eq('user_id', userId)

  if (error) {
    console.error('Error deleting title:', error)
    throw error
  }
}
