/** Exact public snapshot returned by the receipt-backed share RPC. No private
 * notes, booking reference, ticket data or companion email addresses. */
export interface SharedOutingSnapshot {
  tmdb_id: number
  type: 'movie' | 'tv'
  title: string
  year: number
  poster_url: string | null
  showtime: string
  ends_at: string
  venue: string | null
  format: string | null
  seat: string | null
  companions: string[]
}

export function assertSharedOutingSnapshot(value: unknown): asserts value is SharedOutingSnapshot {
  if (!value || typeof value !== 'object') throw new Error('The server did not confirm the shared plan. Retry to check the same send.')
  const row = value as Record<string, unknown>
  if (!Number.isSafeInteger(row.tmdb_id) || (row.tmdb_id as number) <= 0 ||
      (row.type !== 'movie' && row.type !== 'tv') || !Number.isSafeInteger(row.year) ||
      !['poster_url', 'venue', 'format', 'seat'].every((key) => row[key] === null || typeof row[key] === 'string') ||
      typeof row.title !== 'string' || typeof row.showtime !== 'string' || typeof row.ends_at !== 'string' ||
      !Number.isFinite(Date.parse(row.showtime)) || !Number.isFinite(Date.parse(row.ends_at)) ||
      !Array.isArray(row.companions) || !row.companions.every((name) => typeof name === 'string')) {
    throw new Error('The server did not confirm the shared plan. Retry to check the same send.')
  }
}
