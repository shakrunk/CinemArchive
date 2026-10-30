import { pathToFileURL } from 'node:url'

/** Removes outing keepsake objects that no row references any more (removed photos, uploads whose
 * row never landed, and the bytes of deleted outings). The server picks the candidates and only
 * the Storage API may delete bytes: never delete a Storage database row directly. A removed
 * object needs no acknowledgement, since it disappears from the claim by being gone. */
export async function cleanupKeepsakePhotos({ url, serviceKey, fetchImpl = fetch, limit = 100 }) {
  if (!url || !serviceKey) throw new Error('SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required')
  const base = new URL(url)
  if (base.protocol !== 'https:' && !['localhost', '127.0.0.1', '[::1]'].includes(base.hostname)) {
    throw new Error('Keepsake cleanup requires HTTPS outside local development')
  }
  if (base.username || base.password || base.search || base.hash || base.pathname !== '/') throw new Error('Use the Supabase project origin')
  const headers = { Authorization: `Bearer ${serviceKey}`, apikey: serviceKey, 'Content-Type': 'application/json' }
  async function request(path, method, body) {
    const response = await fetchImpl(new URL(path, base), {
      method, headers, body: JSON.stringify(body), signal: AbortSignal.timeout(30_000), redirect: 'error',
    })
    if (!response.ok) throw new Error(`Keepsake cleanup request failed (${response.status})`)
    return response
  }
  const claimed = await (await request('/rest/v1/rpc/claim_keepsake_cleanup', 'POST', { p_limit: limit })).json()
  if (!Array.isArray(claimed) || claimed.length > 100) throw new Error('Invalid keepsake cleanup claim response')
  const result = { claimed: claimed.length, removed: 0, failed: 0 }
  const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
  const objectKey = new RegExp(`^${uuid}/${uuid}$`)
  for (const claim of claimed) {
    if (typeof claim?.object_key !== 'string' || !objectKey.test(claim.object_key)) { result.failed++; continue }
    try {
      await request('/storage/v1/object/outing-keepsakes', 'DELETE', { prefixes: [claim.object_key] })
      result.removed++
    } catch {
      // Still unreferenced, so the next run claims it again. Do not print keys, raw service
      // responses or credentials.
      result.failed++
    }
  }
  return result
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const result = await cleanupKeepsakePhotos({ url: process.env.SUPABASE_URL, serviceKey: process.env.SUPABASE_SERVICE_ROLE_KEY })
    console.log(JSON.stringify(result))
    if (result.failed) process.exitCode = 1
  } catch (error) {
    console.error(error instanceof Error ? error.message : 'Keepsake cleanup failed')
    process.exitCode = 1
  }
}
