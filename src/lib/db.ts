import { supabase } from './auth'
import type { CastMember, CrewMember, EpisodeCrew, Title, WatchStatus, MediaType } from '../store/mockData'

// ─── Mapping Helpers ─────────────────────────────────────────────────────────

function mapDbTitleToLocal(row: any): Title {
  const episodesBySeason: Record<number, any[]> = {}
  for (const ep of (row.episodes || [])) {
    if (!episodesBySeason[ep.season_number]) episodesBySeason[ep.season_number] = []
    episodesBySeason[ep.season_number].push(ep)
  }

  function mapCastRow(c: any): CastMember {
    return {
      tmdbPersonId: c.tmdb_person_id,
      name: c.name,
      character: c.character_name || undefined,
      episodeCount: c.episode_count ?? undefined,
      profileUrl: c.profile_url || undefined,
      order: c.cast_order,
    }
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
    releaseDate: row.release_date || undefined,
    originalLanguage: row.original_language || undefined,
    contentRating: row.content_rating || undefined,
    imdbId: row.imdb_id || undefined,
    customWatchUrl: row.custom_watch_url || undefined,
    imdbRating: row.imdb_rating ? parseFloat(row.imdb_rating) : undefined,
    rtScore: row.rt_score || undefined,
    metacriticScore: row.metacritic_score || undefined,
    studios: row.studios || [],
    cast: (row.title_cast || [])
      .sort((a: any, b: any) => a.cast_order - b.cast_order)
      .map(mapCastRow),
    crew: (row.title_crew || []).map((c: any): CrewMember => ({
      tmdbPersonId: c.tmdb_person_id,
      name: c.name,
      job: c.job,
      department: c.department || undefined,
      profileUrl: c.profile_url || undefined,
    })),
    seasons: (row.seasons || [])
      .map((s: any) => {
        const episodes = (episodesBySeason[s.season_number] || [])
          .sort((a: any, b: any) => a.episode_number - b.episode_number)
          .map((ep: any) => {
            const epCrewRaw: any[] = ep.episode_crew || []
            const epCrew: EpisodeCrew[] = epCrewRaw.map((c) => ({
              tmdbPersonId: c.tmdb_person_id,
              name: c.name,
              job: c.job,
            }))
            return {
              id: ep.id,
              episodeNumber: ep.episode_number,
              episodeName: ep.episode_name || undefined,
              airDate: ep.air_date || undefined,
              runtime: ep.runtime || undefined,
              synopsis: ep.synopsis || undefined,
              stillUrl: ep.still_url || undefined,
              director: epCrew.find((c) => c.job === 'Director')?.name,
              writers: epCrew
                .filter((c) => ['Writer', 'Teleplay', 'Story'].includes(c.job))
                .map((c) => c.name),
              crew: epCrew.length > 0 ? epCrew : undefined,
              watchEvents: (ep.episode_watch_events || [])
                .sort(
                  (a: any, b: any) =>
                    new Date(a.watched_at).getTime() - new Date(b.watched_at).getTime()
                )
                .map((we: any) => ({
                  id: we.id,
                  watchedAt: we.watched_at,
                  notes: we.notes || undefined,
                  colorMode: we.color_mode || undefined,
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
                  colorMode: rv.color_mode || undefined,
                })),
            }
          })
        return {
          id: s.id,
          seasonNumber: s.season_number,
          episodeCount: s.episode_count,
          episodesWatched: s.episodes_watched,
          airYear: s.air_year || undefined,
          cast: (s.season_cast || [])
            .sort((a: any, b: any) => a.cast_order - b.cast_order)
            .map(mapCastRow),
          episodes,
        }
      })
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
      title_cast (*),
      title_crew (*),
      seasons (
        *,
        season_cast (*)
      ),
      viewings (*),
      episodes (
        *,
        episode_crew (*),
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
      title_cast (*),
      title_crew (*),
      seasons (
        *,
        season_cast (*)
      ),
      viewings (*),
      episodes (
        *,
        episode_crew (*),
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

// Reads an accepted friend's library. Relies on the "friend read" RLS
// policies (is_friend(auth.uid(), user_id)) rather than a bearer token — no
// RPC setup call needed, since auth.uid() is already the caller's session.
export async function fetchFriendLibrary(friendUserId: string): Promise<Title[]> {
  if (!supabase) return []

  const { data, error } = await supabase
    .from('titles')
    .select(`
      *,
      title_cast (*),
      title_crew (*),
      seasons (
        *,
        season_cast (*)
      ),
      viewings (*),
      episodes (
        *,
        episode_crew (*),
        episode_watch_events (*),
        episode_ratings (*),
        episode_reviews (*)
      )
    `)
    .eq('user_id', friendUserId)

  if (error) {
    console.error('Error fetching friend library:', error)
    throw error
  }

  return (data || []).map(mapDbTitleToLocal)
}

export interface Recommendation {
  id: string
  senderUserId: string
  senderDisplayName: string | null
  senderUsername: string | null
  tmdbId: number
  type: MediaType
  title: string
  year: number | null
  posterUrl: string | null
  status: 'unread' | 'read' | 'dismissed'
  createdAt: string
}

function mapDbRecommendationToLocal(row: any): Recommendation {
  return {
    id: row.id,
    senderUserId: row.sender_user_id,
    senderDisplayName: row.sender_display_name,
    senderUsername: row.sender_username,
    tmdbId: row.tmdb_id,
    type: row.type as MediaType,
    title: row.title,
    year: row.year,
    posterUrl: row.poster_url,
    status: row.status,
    createdAt: row.created_at,
  }
}

// Sends a friend a denormalized snapshot of a title (title/year/poster) so the
// recommendation stays legible even if the sender's own library entry later
// changes. Requires an accepted friendship — enforced by the send_recommendation
// SQL function, not the client. Resending the same title to the same friend
// just bumps the existing row back to unread (see recommendations_unique_idx).
export async function sendRecommendation(recipientUserId: string, title: Title): Promise<void> {
  if (!supabase) return

  const { error } = await supabase.rpc('send_recommendation', {
    recipient_id: recipientUserId,
    p_tmdb_id: title.tmdbId,
    p_type: title.type,
    p_title: title.title,
    p_year: title.year,
    p_poster_url: title.posterUrl ?? null,
  })

  if (error) {
    console.error('Error sending recommendation:', error)
    throw error
  }
}

// Reads the current user's recommendation inbox (sent by friends), newest first.
export async function fetchRecommendations(): Promise<Recommendation[]> {
  if (!supabase) return []

  const { data, error } = await supabase.rpc('list_recommendations')

  if (error) {
    console.error('Error fetching recommendations:', error)
    throw error
  }

  return (data || []).map(mapDbRecommendationToLocal)
}

export async function markRecommendationRead(id: string): Promise<void> {
  if (!supabase) return
  const { error } = await supabase.rpc('mark_recommendation_read', { rec_id: id })
  if (error) {
    console.error('Error marking recommendation read:', error)
    throw error
  }
}

export async function dismissRecommendation(id: string): Promise<void> {
  if (!supabase) return
  const { error } = await supabase.rpc('dismiss_recommendation', { rec_id: id })
  if (error) {
    console.error('Error dismissing recommendation:', error)
    throw error
  }
}

export interface ActivityEvent {
  type: 'title_added' | 'viewing_logged'
  eventAt: string
  friendUserId: string
  friendDisplayName: string | null
  friendUsername: string | null
  titleId: string
  tmdbId: number
  mediaType: MediaType
  title: string
  year: number | null
  posterUrl: string | null
  rating: number | null
}

function mapDbActivityEventToLocal(row: any): ActivityEvent {
  return {
    type: row.event_type,
    eventAt: row.event_at,
    friendUserId: row.friend_user_id,
    friendDisplayName: row.friend_display_name,
    friendUsername: row.friend_username,
    titleId: row.title_id,
    tmdbId: row.tmdb_id,
    mediaType: row.type as MediaType,
    title: row.title,
    year: row.year,
    posterUrl: row.poster_url,
    rating: row.rating ? parseFloat(row.rating) : null,
  }
}

// Recent library-add and viewing activity across the user's accepted friends,
// newest first. See friend_activity_feed() for the merge/cap logic.
export async function fetchFriendActivityFeed(): Promise<ActivityEvent[]> {
  if (!supabase) return []

  const { data, error } = await supabase.rpc('friend_activity_feed')

  if (error) {
    console.error('Error fetching friend activity feed:', error)
    throw error
  }

  return (data || []).map(mapDbActivityEventToLocal)
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
    studios: title.studios ?? [],
    added_at: new Date(title.addedAt).toISOString(),
    release_date: title.releaseDate ?? null,
    original_language: title.originalLanguage ?? null,
    content_rating: title.contentRating ?? null,
    imdb_id: title.imdbId ?? null,
    custom_watch_url: title.customWatchUrl ?? null,
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
        still_url: ep.stillUrl,
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

  // 4. Insert title-level cast and crew (fire-and-forget)
  if (title.cast && title.cast.length > 0) {
    void (async () => {
      const { error } = await supabase.from('title_cast').insert(
        title.cast!.map((c) => ({
          user_id: userId,
          title_id: title.id,
          tmdb_person_id: c.tmdbPersonId,
          name: c.name,
          character_name: c.character ?? null,
          profile_url: c.profileUrl ?? null,
          cast_order: c.order,
        }))
      )
      if (error) console.error('Failed to insert title cast:', error)
    })()
  }

  if (title.crew && title.crew.length > 0) {
    void (async () => {
      const { error } = await supabase.from('title_crew').insert(
        title.crew!.map((c) => ({
          user_id: userId,
          title_id: title.id,
          tmdb_person_id: c.tmdbPersonId,
          name: c.name,
          job: c.job,
          department: c.department ?? null,
          profile_url: c.profileUrl ?? null,
        }))
      )
      if (error) console.error('Failed to insert title crew:', error)
    })()
  }

  // 5. Insert season cast and episode crew (fire-and-forget)
  // ⚡ Bolt: Bulk insert season cast and episode crew to resolve N+1 query problem
  if (title.type === 'tv' && title.seasons) {
    const seasons = title.seasons
    void (async () => {
      const allSeasonCast = seasons.flatMap((season) =>
        (season.cast ?? []).map((c) => ({
          user_id: userId,
          title_id: title.id,
          season_id: season.id,
          tmdb_person_id: c.tmdbPersonId,
          name: c.name,
          character_name: c.character ?? null,
          profile_url: c.profileUrl ?? null,
          cast_order: c.order,
        }))
      )

      if (allSeasonCast.length > 0) {
        const { error } = await supabase.from('season_cast').insert(allSeasonCast)
        if (error) console.error('Failed to insert season cast:', error)
      }

      const allEpisodeCrew = seasons.flatMap((season) =>
        (season.episodes ?? []).flatMap((ep) =>
          (ep.crew ?? []).map((c) => ({
            user_id: userId,
            title_id: title.id,
            episode_id: ep.id,
            tmdb_person_id: c.tmdbPersonId,
            name: c.name,
            job: c.job,
          }))
        )
      )

      if (allEpisodeCrew.length > 0) {
        const { error } = await supabase.from('episode_crew').insert(allEpisodeCrew)
        if (error) console.error('Failed to insert episode crew:', error)
      }
    })()
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
  ['releaseDate', 'release_date'],
  ['originalLanguage', 'original_language'],
  ['contentRating', 'content_rating'],
  ['imdbId', 'imdb_id'],
  ['customWatchUrl', 'custom_watch_url'],
  ['imdbRating', 'imdb_rating'],
  ['rtScore', 'rt_score'],
  ['metacriticScore', 'metacritic_score'],
  ['studios', 'studios'],
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

  if (patch.seasons && patch.seasons.length > 0) {
    const { error } = await supabase
      .from('seasons')
      .upsert(
        patch.seasons.map((s) => ({
          title_id: titleId,
          user_id: userId,
          season_number: s.seasonNumber,
          episode_count: s.episodeCount,
          episodes_watched: s.episodesWatched,
          air_year: s.airYear,
        })),
        { onConflict: 'title_id,season_number' }
      )

    if (error) {
      console.error('Error bulk updating seasons:', error)
      throw error
    }
  }

  // Upsert all viewings: client-generated UUIDs insert as new rows, DB UUIDs update in-place
  if (patch.viewings && patch.viewings.length > 0) {
    const { error } = await supabase.from('viewings').upsert(
      patch.viewings.map((v) => ({
        id: v.id,
        title_id: titleId,
        user_id: userId,
        viewed_at: v.date,
        rating: v.rating,
        notes: v.notes,
      }))
    )

    if (error) {
      console.error('Error upserting viewings:', error)
      throw error
    }
  }

  // Cast refresh: delete existing rows, re-insert new set (removes stale members)
  if ('cast' in patch && patch.cast !== undefined) {
    await supabase.from('title_cast').delete().eq('title_id', titleId).eq('user_id', userId)
    if (patch.cast.length > 0) {
      const { error } = await supabase.from('title_cast').insert(
        patch.cast.map((c) => ({
          user_id: userId,
          title_id: titleId,
          tmdb_person_id: c.tmdbPersonId,
          name: c.name,
          character_name: c.character ?? null,
          profile_url: c.profileUrl ?? null,
          cast_order: c.order,
        }))
      )
      if (error) console.error('Error re-inserting title cast:', error)
    }
  }

  // Crew refresh: same delete-reinsert pattern
  if ('crew' in patch && patch.crew !== undefined) {
    await supabase.from('title_crew').delete().eq('title_id', titleId).eq('user_id', userId)
    if (patch.crew.length > 0) {
      const { error } = await supabase.from('title_crew').insert(
        patch.crew.map((c) => ({
          user_id: userId,
          title_id: titleId,
          tmdb_person_id: c.tmdbPersonId,
          name: c.name,
          job: c.job,
          department: c.department ?? null,
          profile_url: c.profileUrl ?? null,
        }))
      )
      if (error) console.error('Error re-inserting title crew:', error)
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
    colorMode?: 'bw' | 'color'
    watchEventId?: string // client-supplied uuid so the optimistic store id matches the DB row (enables reliable delete/undo)
  }
): Promise<void> {
  if (!supabase) return

  if (opts.watchedAt) {
    const { error } = await supabase.from('episode_watch_events').insert({
      ...(opts.watchEventId ? { id: opts.watchEventId } : {}),
      episode_id: episodeId,
      user_id: userId,
      watched_at: opts.watchedAt,
      notes: opts.watchNotes || undefined,
      color_mode: opts.colorMode ?? null,
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
      color_mode: opts.colorMode ?? null,
    })
    if (error) {
      console.error('Error inserting episode review:', error)
      throw error
    }
  }
}

export async function upsertEpisodeMetadataInDb(
  userId: string,
  titleId: string,
  episodes: Array<{
    id: string
    seasonNumber: number
    episodeNumber: number
    episodeName?: string
    airDate?: string
    runtime?: number
    synopsis?: string
    stillUrl?: string
  }>
): Promise<void> {
  if (!supabase || episodes.length === 0) return

  const rows = episodes.map((ep) => ({
    id: ep.id,
    title_id: titleId,
    user_id: userId,
    season_number: ep.seasonNumber,
    episode_number: ep.episodeNumber,
    episode_name: ep.episodeName ?? null,
    air_date: ep.airDate ?? null,
    runtime: ep.runtime ?? null,
    synopsis: ep.synopsis ?? null,
    still_url: ep.stillUrl ?? null,
  }))

  const { error } = await supabase
    .from('episodes')
    .upsert(rows, { onConflict: 'title_id,season_number,episode_number' })

  if (error) {
    console.error('Error upserting episode metadata:', error)
    throw error
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

export async function upsertTitleCastInDb(userId: string, titleId: string, cast: CastMember[]): Promise<void> {
  if (!supabase || cast.length === 0) return
  const { error } = await supabase.from('title_cast').upsert(
    cast.map((c) => ({
      user_id: userId,
      title_id: titleId,
      tmdb_person_id: c.tmdbPersonId,
      name: c.name,
      character_name: c.character ?? null,
      episode_count: c.episodeCount ?? null,
      profile_url: c.profileUrl ?? null,
      cast_order: c.order,
    })),
    { onConflict: 'title_id,tmdb_person_id' }
  )
  if (error) console.error('Error upserting title cast:', error)
}

export async function upsertTitleCrewInDb(userId: string, titleId: string, crew: CrewMember[]): Promise<void> {
  if (!supabase || crew.length === 0) return
  const { error } = await supabase.from('title_crew').upsert(
    crew.map((c) => ({
      user_id: userId,
      title_id: titleId,
      tmdb_person_id: c.tmdbPersonId,
      name: c.name,
      job: c.job,
      department: c.department ?? null,
      profile_url: c.profileUrl ?? null,
    })),
    { onConflict: 'title_id,tmdb_person_id,job' }
  )
  if (error) console.error('Error upserting title crew:', error)
}

export async function upsertSeasonCastInDb(
  userId: string,
  titleId: string,
  seasonId: string,
  cast: CastMember[]
): Promise<void> {
  if (!supabase || cast.length === 0) return
  const { error } = await supabase.from('season_cast').upsert(
    cast.map((c) => ({
      user_id: userId,
      title_id: titleId,
      season_id: seasonId,
      tmdb_person_id: c.tmdbPersonId,
      name: c.name,
      character_name: c.character ?? null,
      episode_count: c.episodeCount ?? null,
      profile_url: c.profileUrl ?? null,
      cast_order: c.order,
    })),
    { onConflict: 'season_id,tmdb_person_id' }
  )
  if (error) console.error('Error upserting season cast:', error)
}

export async function upsertEpisodeCrewInDb(
  userId: string,
  titleId: string,
  episodeId: string,
  crew: EpisodeCrew[]
): Promise<void> {
  if (!supabase || crew.length === 0) return
  const { error } = await supabase.from('episode_crew').upsert(
    crew.map((c) => ({
      user_id: userId,
      title_id: titleId,
      episode_id: episodeId,
      tmdb_person_id: c.tmdbPersonId,
      name: c.name,
      job: c.job,
    })),
    { onConflict: 'episode_id,tmdb_person_id,job' }
  )
  if (error) console.error('Error upserting episode crew:', error)
}

export async function deleteViewingFromDb(userId: string, viewingId: string): Promise<void> {
  if (!supabase) return
  const { error } = await supabase
    .from('viewings')
    .delete()
    .eq('id', viewingId)
    .eq('user_id', userId)
  if (error) {
    console.error('Error deleting viewing:', error)
    throw error
  }
}

export async function deleteEpisodeWatchEventFromDb(userId: string, watchEventId: string): Promise<void> {
  if (!supabase) return
  const { error } = await supabase
    .from('episode_watch_events')
    .delete()
    .eq('id', watchEventId)
    .eq('user_id', userId)
  if (error) {
    console.error('Error deleting episode watch event:', error)
    throw error
  }
}

// ─── User Title Pins ──────────────────────────────────────────────────────────

export async function fetchAllTitlePins(
  userId: string
): Promise<Array<{ titleId: string; easterEggKey: string; pinnedVariant: 'bw' | 'color' }>> {
  if (!supabase) return []
  const { data, error } = await supabase
    .from('user_title_pins')
    .select('title_id, easter_egg_key, pinned_variant')
    .eq('user_id', userId)
  if (error) {
    console.error('fetchAllTitlePins:', error)
    return []
  }
  return (data ?? []).map((row) => ({
    titleId: row.title_id as string,
    easterEggKey: row.easter_egg_key as string,
    pinnedVariant: row.pinned_variant as 'bw' | 'color',
  }))
}

export async function upsertTitlePin(
  userId: string,
  titleId: string,
  easterEggKey: string,
  pinnedVariant: 'bw' | 'color'
): Promise<void> {
  if (!supabase) return
  const { error } = await supabase
    .from('user_title_pins')
    .upsert({
      user_id: userId,
      title_id: titleId,
      easter_egg_key: easterEggKey,
      pinned_variant: pinnedVariant,
      updated_at: new Date().toISOString(),
    })
  if (error) console.error('upsertTitlePin:', error)
}

export async function deleteTitlePin(
  userId: string,
  titleId: string,
  easterEggKey: string
): Promise<void> {
  if (!supabase) return
  const { error } = await supabase
    .from('user_title_pins')
    .delete()
    .eq('user_id', userId)
    .eq('title_id', titleId)
    .eq('easter_egg_key', easterEggKey)
  if (error) console.error('deleteTitlePin:', error)
}
