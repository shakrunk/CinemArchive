import { supabase } from './auth'
import type { Title, WatchStatus, MediaType } from '../store/mockData'

// ─── Mapping Helpers ─────────────────────────────────────────────────────────

function mapDbTitleToLocal(row: any): Title {
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
    addedAt: row.added_at ? new Date(row.added_at).toISOString().slice(0, 10) : new Date().toISOString().slice(0, 10),
    imdbRating: row.imdb_rating ? parseFloat(row.imdb_rating) : undefined,
    rtScore: row.rt_score || undefined,
    metacriticScore: row.metacritic_score || undefined,
    seasons: (row.seasons || []).map((s: any) => ({
      id: s.id,
      seasonNumber: s.season_number,
      episodeCount: s.episode_count,
      episodesWatched: s.episodes_watched,
      airYear: s.air_year || undefined,
    })).sort((a: any, b: any) => a.seasonNumber - b.seasonNumber),
    viewings: (row.viewings || []).map((v: any) => ({
      id: v.id,
      titleId: v.title_id,
      date: v.viewed_at,
      rating: v.rating ? parseFloat(v.rating) : undefined,
      notes: v.notes || undefined,
    })).sort((a: any, b: any) => new Date(b.date).getTime() - new Date(a.date).getTime()),
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
      viewings (*)
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

  // Set the session token so RLS permits the select
  const { error: rpcError } = await supabase.rpc('set_shared_token', { token })
  if (rpcError) {
    console.error('Error setting shared token RPC:', rpcError)
    throw rpcError
  }

  // RLS policy will automatically filter to only rows visible to this token
  const { data, error } = await supabase
    .from('titles')
    .select(`
      *,
      seasons (*),
      viewings (*)
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

  // 2. Insert seasons if present
  if (title.type === 'tv' && title.seasons && title.seasons.length > 0) {
    const seasonsToInsert = title.seasons.map((s) => ({
      id: s.id.startsWith('new-') || s.id.startsWith('local-') ? undefined : s.id, // let db generate UUID if not valid
      title_id: title.id,
      user_id: userId,
      season_number: s.seasonNumber,
      episode_count: s.episodeCount,
      episodes_watched: s.episodesWatched,
      air_year: s.airYear,
    }))

    const { error: seasonsError } = await supabase.from('seasons').insert(seasonsToInsert)
    if (seasonsError) {
      console.error('Error inserting seasons:', seasonsError)
      throw seasonsError
    }
  }

  // 3. Insert viewings if present
  if (title.viewings && title.viewings.length > 0) {
    const viewingsToInsert = title.viewings.map((v) => ({
      id: v.id.startsWith('new-') || v.id.startsWith('local-') || v.id.startsWith('v-') ? undefined : v.id,
      title_id: title.id,
      user_id: userId,
      viewed_at: v.date,
      rating: v.rating,
      notes: v.notes,
    }))

    const { error: viewingsError } = await supabase.from('viewings').insert(viewingsToInsert)
    if (viewingsError) {
      console.error('Error inserting viewings:', viewingsError)
      throw viewingsError
    }
  }
}

export async function updateTitleInDb(userId: string, titleId: string, patch: Partial<Title>): Promise<void> {
  if (!supabase) return

  const mappedPatch: any = {}
  if (patch.status !== undefined) mappedPatch.status = patch.status
  if (patch.rating !== undefined) mappedPatch.rating = patch.rating
  if (patch.notes !== undefined) mappedPatch.notes = patch.notes
  if (patch.tags !== undefined) mappedPatch.tags = patch.tags
  
  // Update core title properties if present
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

  // Handle seasons updates if present in patch
  if (patch.seasons) {
    for (const s of patch.seasons) {
      const dbSeason = {
        title_id: titleId,
        user_id: userId,
        season_number: s.seasonNumber,
        episode_count: s.episodeCount,
        episodes_watched: s.episodesWatched,
        air_year: s.airYear,
      }
      
      const { error } = await supabase
        .from('seasons')
        .upsert(dbSeason, { onConflict: 'title_id,season_number' })

      if (error) {
        console.error(`Error updating season ${s.seasonNumber}:`, error)
        throw error
      }
    }
  }

  // Handle viewings updates if present in patch
  // Since viewings in the store are full lists, we'll upsert all of them
  if (patch.viewings) {
    const viewingsToUpsert = patch.viewings.map((v) => ({
      id: v.id.startsWith('v-') || v.id.startsWith('local-') ? undefined : v.id, // let db generate UUID if it's a temp client ID
      title_id: titleId,
      user_id: userId,
      viewed_at: v.date,
      rating: v.rating,
      notes: v.notes,
    }))

    for (const v of viewingsToUpsert) {
      const { error } = await supabase
        .from('viewings')
        .upsert(v)
      
      if (error) {
        console.error('Error upserting viewing:', error)
        throw error
      }
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
