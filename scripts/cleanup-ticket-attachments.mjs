import { pathToFileURL } from 'node:url'

/** Removes only server-claimed, unreferenced attachments. Never delete a Storage
 * database row directly: the Storage API must remove the actual object first. */
export async function cleanupTicketAttachments({ url, serviceKey, fetchImpl = fetch, limit = 100 }) {
  if (!url || !serviceKey) throw new Error('SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required')
  const base = new URL(url)
  if (base.protocol !== 'https:' && !['localhost', '127.0.0.1', '[::1]'].includes(base.hostname)) {
    throw new Error('Ticket cleanup requires HTTPS outside local development')
  }
  if (base.username || base.password || base.search || base.hash || base.pathname !== '/') throw new Error('Use the Supabase project origin')
  const headers = { Authorization: `Bearer ${serviceKey}`, apikey: serviceKey, 'Content-Type': 'application/json' }
  async function request(path, method, body) {
    const response = await fetchImpl(new URL(path, base), {
      method, headers, body: JSON.stringify(body), signal: AbortSignal.timeout(30_000), redirect: 'error',
    })
    if (!response.ok) throw new Error(`Ticket cleanup request failed (${response.status})`)
    return response
  }
  const claimed = await (await request('/rest/v1/rpc/claim_ticket_attachment_cleanup', 'POST', { p_limit: limit })).json()
  if (!Array.isArray(claimed) || claimed.length > 100) throw new Error('Invalid ticket cleanup claim response')
  const result = { claimed: claimed.length, removed: 0, failed: 0 }
  const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
  const objectKey = new RegExp(`^(${uuid})/(${uuid})/original$`)
  for (const claim of claimed) {
    const match = typeof claim?.objectKey === 'string' ? objectKey.exec(claim.objectKey) : null
    if (!match || match[2] !== claim.attachmentId) { result.failed++; continue }
    try {
      await request('/storage/v1/object/ticket-attachments', 'DELETE', { prefixes: [claim.objectKey] })
      await request('/rest/v1/rpc/finish_ticket_attachment_cleanup', 'POST', { p_attachment_id: claim.attachmentId })
      result.removed++
    } catch {
      // The server retains the claim and makes it retryable after an hour.
      // Do not print keys, ticket metadata, raw service responses or credentials.
      result.failed++
    }
  }
  return result
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const result = await cleanupTicketAttachments({ url: process.env.SUPABASE_URL, serviceKey: process.env.SUPABASE_SERVICE_ROLE_KEY })
    console.log(JSON.stringify(result))
    if (result.failed) process.exitCode = 1
  } catch (error) {
    console.error(error instanceof Error ? error.message : 'Ticket cleanup failed')
    process.exitCode = 1
  }
}
