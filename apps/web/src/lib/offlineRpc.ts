import { supabase } from './auth'
import type { CastMember, CrewMember, Episode, EpisodeCrew, Season } from '../store/mockData'
import { sameScope, type PendingCommand, type TrackingMutation } from './offline/commands'
import type { DeliveryContext, DeliveryResult } from './offline/coordinator'
import type { OfflineSnapshot } from './offline/snapshot'

export interface LibraryOperation {
  table: string
  action: 'insert' | 'update' | 'delete' | 'put'
  key: Record<string, string | number>
  values?: Record<string, unknown>
  expectedUpdatedAt?: string
  expectedOperationId?: string
}

const titleFields = {
  tmdbId:'tmdb_id', type:'type', title:'title', year:'year', director:'director', genres:'genres',
  posterUrl:'poster_url', backdropUrl:'backdrop_url', synopsis:'synopsis', runtime:'runtime',
  network:'network', status:'status', rating:'rating', notes:'notes', tags:'tags', addedAt:'added_at',
  imdbRating:'imdb_rating', rtScore:'rt_score', metacriticScore:'metacritic_score', studios:'studios',
  releaseDate:'release_date', originalLanguage:'original_language', contentRating:'content_rating',
  imdbId:'imdb_id', rtUrl:'rt_url', awardsCount:'awards_count', bechdelOutcome:'bechdel_outcome',
  bechdelScore:'bechdel_score', customWatchUrl:'custom_watch_url', inHomeCollection:'in_home_collection',
  physicalMedia:'physical_media', collectionId:'collection_id', collectionName:'collection_name',
}
const viewingFields = {date:'viewed_at',rating:'rating',notes:'notes',venue:'venue',companions:'companions',outingId:'outing_id'}
const episodeFields = {episodeName:'episode_name',airDate:'air_date',runtime:'runtime',synopsis:'synopsis',stillUrl:'still_url'}
const outingFields = {
  showtime:'showtime', previewsMinutes:'previews_minutes', runtimeMinutes:'runtime_minutes', endsAt:'ends_at',
  venue:'venue', companions:'companions', format:'format', ticketPrice:'ticket_price', seat:'seat', auditorium:'auditorium',
  seatRow:'seat_row', seats:'seats', bookingRef:'booking_ref', notes:'notes', status:'status', previousStatus:'previous_status',
  completedViewingId:'completed_viewing_id', followUpDismissedAt:'follow_up_dismissed_at', createdAt:'created_at',
}
function mapped(source: object, columns: Record<string,string>): Record<string,unknown> {
  const input = source as Record<string,unknown>
  return Object.fromEntries(Object.entries(columns).filter(([key]) => Object.hasOwn(input,key) && input[key] !== undefined)
    .map(([key,column]) => [column,input[key] === null && ['studios','physical_media','companions'].includes(column)
      ? [] : input[key] === null && column === 'in_home_collection' ? false : input[key]]))
}
function insert(table: string, id: string, values: Record<string,unknown>): LibraryOperation {
  return {table,action:'insert',key:{id},values}
}
function update(table: string, id: string, values: Record<string,unknown>): LibraryOperation {
  return {table,action:'update',key:{id},values}
}
function remove(table: string, key: LibraryOperation['key']): LibraryOperation { return {table,action:'delete',key} }
function castOperations(table: 'title_cast'|'season_cast', parent: Record<string,string>, cast: CastMember[]): LibraryOperation[] {
  const {title_id,season_id} = parent
  return cast.map(person => ({table,action:'insert',key:{...(season_id ? {season_id} : {title_id}),tmdb_person_id:person.tmdbPersonId},
    values:{...(season_id ? {title_id} : {}),...mapped(person,{name:'name',character:'character_name',episodeCount:'episode_count',profileUrl:'profile_url',order:'cast_order'})}}))
}
function crewOperations(table: 'title_crew'|'episode_crew', parent: Record<string,string>, crew: (CrewMember|EpisodeCrew)[]): LibraryOperation[] {
  const {title_id,episode_id} = parent
  return crew.map(person => ({table,action:'insert',key:{...(episode_id ? {episode_id} : {title_id}),tmdb_person_id:person.tmdbPersonId,job:person.job},
    values:{...(episode_id ? {title_id} : {}),...mapped(person,episode_id ? {name:'name'} : {name:'name',department:'department',profileUrl:'profile_url'})}}))
}
function episodeLogs(episodeId: string, episode: Pick<Episode,'watchEvents'|'ratings'|'reviews'>, recordedAt: string): LibraryOperation[] {
  return [
    ...episode.watchEvents.map(event=>insert('episode_watch_events',event.id,{episode_id:episodeId,watched_at:event.watchedAt ?? null,
      ...mapped(event,{notes:'notes',colorMode:'color_mode'}),created_at:recordedAt})),
    ...episode.ratings.map(event=>insert('episode_ratings',event.id,{episode_id:episodeId,rating:event.rating,rated_at:event.ratedAt})),
    ...episode.reviews.map(event=>insert('episode_reviews',event.id,{episode_id:episodeId,review_text:event.reviewText,reviewed_at:event.reviewedAt,
      ...mapped(event,{colorMode:'color_mode'})})),
  ]
}
function seasonOperations(titleId: string, season: Season, recordedAt: string, refresh = false): LibraryOperation[] {
  const values=mapped(season,{episodeCount:'episode_count',episodesWatched:'episodes_watched',airYear:'air_year'})
  const operations=[insert('seasons',season.id,{title_id:titleId,season_number:season.seasonNumber,...values})]
  if (refresh) operations.push(update('seasons',season.id,mapped(season,{episodeCount:'episode_count',airYear:'air_year'})))
  if (season.cast) {
    if (refresh) operations.push(remove('season_cast',{season_id:season.id}))
    operations.push(...castOperations('season_cast',{title_id:titleId,season_id:season.id},season.cast))
  }
  for (const episode of season.episodes ?? []) {
    operations.push(insert('episodes',episode.id,{title_id:titleId,season_number:season.seasonNumber,episode_number:episode.episodeNumber,...mapped(episode,episodeFields)}))
    if (refresh) operations.push(update('episodes',episode.id,mapped(episode,episodeFields)))
    if (!refresh) operations.push(...episodeLogs(episode.id,episode,recordedAt))
    if (episode.crew) {
      if (refresh) operations.push(remove('episode_crew',{episode_id:episode.id}))
      operations.push(...crewOperations('episode_crew',{title_id:titleId,episode_id:episode.id},episode.crew))
    }
  }
  return operations
}

/** Pure mapping: retry uses exactly the same IDs, payload and recorded timestamps. */
export function libraryOperations(command: PendingCommand): LibraryOperation[] {
  const leaf = (mutation: TrackingMutation): LibraryOperation[] => {
    switch (mutation.kind) {
      case 'title.create': {
        const title=mutation.title
        return [insert('titles',title.id,mapped(title,titleFields)),
          ...(title.seasons ?? []).flatMap(season=>seasonOperations(title.id,season,command.createdAt)),
          ...title.viewings.map(viewing=>insert('viewings',viewing.id,{title_id:title.id,viewed_at:viewing.date ?? null,...mapped(viewing,viewingFields),created_at:command.createdAt})),
          ...castOperations('title_cast',{title_id:title.id},title.cast ?? []),
          ...crewOperations('title_crew',{title_id:title.id},title.crew ?? [])]
      }
      case 'title.patch': {
        const operations=[update('titles',mutation.titleId,mapped(mutation.patch,titleFields))]
        if ('cast' in mutation.patch) operations.push(remove('title_cast',{title_id:mutation.titleId}),...castOperations('title_cast',{title_id:mutation.titleId},mutation.patch.cast ?? []))
        if ('crew' in mutation.patch) operations.push(remove('title_crew',{title_id:mutation.titleId}),...crewOperations('title_crew',{title_id:mutation.titleId},mutation.patch.crew ?? []))
        return operations
      }
      case 'title.delete': return [remove('titles',{id:mutation.titleId})]
      case 'season.put': return seasonOperations(mutation.titleId,mutation.season,command.createdAt,true)
      case 'season.progress': return [update('seasons',mutation.seasonId,{episodes_watched:mutation.episodesWatched})]
      case 'episode.metadata': {
        const operations=[update('episodes',mutation.episodeId,mapped(mutation.patch,episodeFields))]
        if ('crew' in mutation.patch) operations.push(remove('episode_crew',{episode_id:mutation.episodeId}),...crewOperations('episode_crew',{title_id:mutation.titleId,episode_id:mutation.episodeId},mutation.patch.crew ?? []))
        return operations
      }
      case 'episode.log': return episodeLogs(mutation.episodeId,{watchEvents:mutation.watchEvent ? [mutation.watchEvent] : [],ratings:mutation.rating ? [mutation.rating] : [],reviews:mutation.review ? [mutation.review] : []},command.createdAt)
      case 'episodeWatch.delete': return [remove('episode_watch_events',{id:mutation.watchEventId})]
      case 'viewing.put': return [insert('viewings',mutation.viewing.id,{title_id:mutation.titleId,viewed_at:mutation.viewing.date ?? null,...mapped(mutation.viewing,viewingFields),created_at:command.createdAt})]
      case 'viewing.patch': return [update('viewings',mutation.viewingId,mapped(mutation.patch,viewingFields))]
      case 'viewing.delete': return [remove('viewings',{id:mutation.viewingId})]
      case 'outing.create': return [insert('cinema_outings',mutation.outing.id,{title_id:mutation.outing.titleId,...mapped(mutation.outing,outingFields)})]
      case 'outing.patch': return [update('cinema_outings',mutation.outingId,mapped(mutation.patch,outingFields))]
      case 'outing.delete': return [remove('cinema_outings',{id:mutation.outingId})]
      case 'list.create': return [insert('lists',mutation.list.id,mapped(mutation.list,{name:'name',description:'description',createdAt:'created_at'}))]
      case 'list.patch': return [update('lists',mutation.listId,mapped(mutation.patch,{name:'name',description:'description'}))]
      case 'list.delete': return [remove('lists',{id:mutation.listId})]
      case 'membership.set': return [{table:'list_items',action:mutation.present ? 'insert' : 'delete',key:{list_id:mutation.listId,title_id:mutation.titleId},...(mutation.present ? {values:{added_at:command.createdAt}} : {})}]
      case 'pin.set': return [{table:'user_title_pins',action:mutation.variant === null ? 'delete' : 'put',key:{title_id:mutation.titleId,easter_egg_key:mutation.easterEggKey},...(mutation.variant === null ? {} : {values:{pinned_variant:mutation.variant}})}]
      case 'ledger.set': return [{table:'user_prefs',action:'put',key:{},values:{ledger_layout:mutation.widgets}}]
    }
  }
  const operations=(command.mutation.kind === 'batch' ? command.mutation.mutations : [command.mutation]).flatMap(leaf)
  if (command.baseRevision) {
    if (operations.length !== 1 || !['update','delete'].includes(operations[0].action)) throw new Error('A revision precondition requires one record patch or deletion')
    operations[0].expectedUpdatedAt=command.baseRevision
  }
  const guarded = new Set<string>()
  for (const precondition of command.preconditions ?? []) {
    const key = `${precondition.table}:${precondition.id}`
    if (guarded.has(key)) throw new Error('Duplicate row precondition')
    guarded.add(key)
    const operation = operations.find((op) => op.table === precondition.table && op.key.id === precondition.id && ['update', 'delete'].includes(op.action))
    if (!operation || operation.expectedUpdatedAt) throw new Error('Row precondition must match one patch or deletion')
    if (precondition.afterCommandId) operation.expectedOperationId = precondition.afterCommandId
    else operation.expectedUpdatedAt = precondition.updatedAt
  }
  return operations
}

export function classifyLibraryError(status: number, code: string | undefined, message: string): DeliveryResult {
  if (status===401 || code==='PGRST301' || code==='PGRST302') return {kind:'auth',message}
  if (code==='40001' || code==='23505' || code==='P0002') return {kind:'conflict',message}
  if (status===408 || status===429 || status>=500 || status===0) return {kind:'retry',message}
  return {kind:'failed',message}
}

export function createLibraryCommandDelivery(fetchBase: (context: DeliveryContext)=>Promise<OfflineSnapshot>) {
  return async (command: PendingCommand, context: DeliveryContext): Promise<DeliveryResult> => {
    const project=import.meta.env.VITE_SUPABASE_URL as string | undefined
    const key=import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined
    if (!supabase || !project || !key) return {kind:'failed',message:'Library sync is not configured.'}
    if (!sameScope(command.scope,context.scope) || context.scope.projectId !== project) return {kind:'failed',message:'Library command belongs to another account or project.'}
    if (!context.isCurrent() || context.signal.aborted) return {kind:'auth',message:'Account changed before sync.'}
    const {data,error}=await supabase.auth.getSession()
    if (error || !data.session || data.session.user.id!==context.scope.userId) return {kind:'auth',message:'Sign in to sync this account.'}
    let operations: LibraryOperation[]
    try { operations=libraryOperations(command) } catch (error) { return {kind:'failed',message:error instanceof Error ? error.message : 'Invalid command.'} }
    if (!context.isCurrent() || context.signal.aborted) return {kind:'auth',message:'Account changed before sync.'}
    // Capture the owner token: a concurrent account change must never cause the
    // auth client's automatic headers to send this owner's command as another user.
    const response=await fetch(`${project.replace(/\/$/,'')}/rest/v1/rpc/apply_library_command`,{
      method:'POST', signal:context.signal,
      headers:{apikey:key,Authorization:`Bearer ${data.session.access_token}`,'Content-Type':'application/json'},
      body:JSON.stringify({p_operation_id:command.id,p_operations:operations}),
    })
    const result: unknown=await response.json()
    if (!response.ok) {
      const failure=result as {code?:string;message?:string}
      return classifyLibraryError(response.status,failure?.code,failure?.message ?? 'Library sync failed.')
    }
    if (!result || typeof result!=='object' || !('operationId' in result) || result.operationId!==command.id) return {kind:'retry',message:'Sync returned an unrecognized receipt.'}
    if (!context.isCurrent() || context.signal.aborted) return {kind:'auth',message:'Account changed during sync.'}
    // Receipts intentionally describe the ORIGINAL result. A fresh snapshot
    // preserves edits made on another device after that command was committed.
    const canonicalBase=await fetchBase(context)
    if (!context.isCurrent() || context.signal.aborted) return {kind:'auth',message:'Account changed during refresh.'}
    return {kind:'success',canonicalBase}
  }
}
