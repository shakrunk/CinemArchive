import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { resolve, sep, extname } from 'node:path'
import type { AddressInfo } from 'node:net'

/** A test-only origin we can really disconnect. A minimal literal-response SW
 * reproduces Firefox NS_ERROR_OFFLINE when routing + setOffline are combined;
 * WebKit rejects it even without routing (Playwright #42775). The same probe
 * passes all three engines after physical origin shutdown. Closing this server
 * tests real network unavailability while preserving external-request blocks. */
export async function startTicketOrigin() {
  const root = resolve('dist-e2e')
  const mime: Record<string, string> = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.wasm': 'application/wasm',
    '.svg': 'image/svg+xml', '.png': 'image/png', '.webmanifest': 'application/manifest+json' }
  let successfulRequests = 0
  const server = createServer(async (request, response) => {
    try {
      const pathname = decodeURIComponent(new URL(request.url ?? '/', 'http://localhost').pathname)
      // Seed IndexedDB on this origin before the application opens its database.
      if (pathname === '/__ticket_fixture') {
        successfulRequests++
        response.writeHead(200, { 'Content-Type': 'text/html', 'Cache-Control': 'no-store' }).end('<!doctype html><title>Ticket fixture</title>')
        return
      }
      const file = resolve(root, `.${pathname === '/' ? '/index.html' : pathname}`)
      if (!file.startsWith(root + sep)) { response.writeHead(403).end(); return }
      const bytes = await readFile(file)
      successfulRequests++
      response.writeHead(200, { 'Content-Type': mime[extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' }).end(bytes)
    } catch { response.writeHead(404).end() }
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  return {
    url: `http://127.0.0.1:${(server.address() as AddressInfo).port}`,
    get successfulRequests() { return successfulRequests },
    stop: async () => {
      if (!server.listening) return
      await new Promise<void>((resolve) => { server.close(() => resolve()); server.closeAllConnections() })
    },
  }
}
