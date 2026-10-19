// Deterministic IDs for season/episode rows discovered from TMDB after a title
// was added (a newly announced season, episodes appended to an airing one).
//
// Rows are unique per (title, season[, episode]) number, and the library
// command RPC rejects an insert whose natural key already exists under a
// different id. Two tabs or devices discovering the same new episode therefore
// must agree on its id: derive it (RFC 4122 v5, SHA-1) from the title id and
// the row's numbers instead of generating a random one.

async function uuidV5(namespace: string, name: string): Promise<string> {
  const hex = namespace.replace(/-/g, '')
  // Only local mock/sample titles have non-UUID ids; they never reach the server.
  if (!/^[0-9a-f]{32}$/i.test(hex)) return crypto.randomUUID()
  const ns = Uint8Array.from(hex.match(/../g)!, (byte) => parseInt(byte, 16))
  const nameBytes = new TextEncoder().encode(name)
  const input = new Uint8Array(ns.length + nameBytes.length)
  input.set(ns)
  input.set(nameBytes, ns.length)
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-1', input)).slice(0, 16)
  hash[6] = (hash[6] & 0x0f) | 0x50
  hash[8] = (hash[8] & 0x3f) | 0x80
  const out = Array.from(hash, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${out.slice(0, 8)}-${out.slice(8, 12)}-${out.slice(12, 16)}-${out.slice(16, 20)}-${out.slice(20)}`
}

export function catalogSeasonId(titleId: string, seasonNumber: number): Promise<string> {
  return uuidV5(titleId, `season:${seasonNumber}`)
}

export function catalogEpisodeId(titleId: string, seasonNumber: number, episodeNumber: number): Promise<string> {
  return uuidV5(titleId, `episode:${seasonNumber}:${episodeNumber}`)
}
