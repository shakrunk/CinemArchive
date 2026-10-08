import { expect, test as base } from 'playwright/test'
import { readFile } from 'node:fs/promises'
import { prepareZXingModule, writeBarcode, type WriterOptions } from 'zxing-wasm/full'
import { startTicketOrigin } from './static-ticket-origin'

const test = base.extend<{ ticketOrigin: Awaited<ReturnType<typeof startTicketOrigin>> }>({
  ticketOrigin: async ({ browserName }, run) => {
    if (!browserName) throw new Error('A browser target is required')
    const origin = await startTicketOrigin()
    try { await run(origin) } finally { await origin.stop() }
  },
})

const outingId = '20000000-0000-4000-8000-000000000001'
async function barcode(format: WriterOptions['format'] = 'QRCode', payload = 'REAL-TICKET-12345') {
  await prepareZXingModule({ overrides: { wasmBinary: await readFile('node_modules/zxing-wasm/dist/full/zxing_full.wasm') }, fireImmediately: true })
  const result = await writeBarcode(payload, { format, scale: 8, addQuietZones: true })
  if (!result.image || result.error) throw new Error(result.error || 'Barcode fixture generation failed')
  return { name: 'ticket.png', mimeType: 'image/png', buffer: Buffer.from(await result.image.arrayBuffer()) }
}

test.beforeEach(async ({ context, page, ticketOrigin }) => {
  await context.route((url) => /^https?:$/.test(url.protocol) && url.origin !== ticketOrigin.url, (route) => route.abort())
  page.on('pageerror', (error) => { throw error })
  await page.goto(`${ticketOrigin.url}/__ticket_fixture`)
  await page.evaluate(async (id) => {
    const scope = { projectId: 'unconfigured-local', userId: 'anonymous-local-only' }
    const showtime = new Date(Date.now() + 86_400_000).toISOString()
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open('cinemarchive-anonymous-v1')
      request.onupgradeneeded = () => {
        const db = request.result
        db.createObjectStore('owners')
        db.createObjectStore('quarantine', { keyPath: 'id' }).createIndex('scopeKey', 'scopeKey')
        db.createObjectStore('ticketBlobs')
      }
      request.onerror = () => reject(request.error)
      request.onsuccess = () => {
        const db = request.result, tx = db.transaction('owners', 'readwrite')
        tx.objectStore('owners').put({ version: 1, scope, revision: 1, nextSequence: 1, commands: [], base: {
          titles: [{ id: 'ticket-film', tmdbId: 1, type: 'movie', title: 'Ticket test film', year: 2026, genres: [], tags: [], status: 'watchlist', addedAt: '2026-01-01', viewings: [] }],
          outings: [{ id, titleId: 'ticket-film', showtime, endsAt: new Date(Date.now() + 90_000_000).toISOString(), createdAt: '2026-01-01', status: 'scheduled', previewsMinutes: 0, runtimeMinutes: 120,
            companions: [], auditorium: '7', seatRow: 'F', seats: ['12', '13'], venue: 'Private Cinema', bookingRef: 'BOOKING-REFERENCE-ONLY', ticketImagePath: '/native/private/original.jpg' }],
          lists: [], listMemberships: {}, pinnedModes: {}, ledgerWidgets: [],
        } }, JSON.stringify([scope.projectId, scope.userId]))
        tx.oncomplete = () => { db.close(); resolve() }; tx.onabort = () => { db.close(); reject(tx.error) }
      }
    })
  }, outingId)
  await page.goto(`${ticketOrigin.url}/?view=upnext`)
  await page.getByRole('button', { name: 'Ticket & seats', exact: true }).click()
})

test('captures the original, renders the actual code, reloads offline, and removes without reviving legacy paths', async ({ page, ticketOrigin }) => {
  test.setTimeout(60_000)
  const dialog = page.getByRole('dialog', { name: 'Ticket & seats · Ticket test film' })
  await expect(dialog.getByText(/stored on your original device/)).toBeVisible()
  await dialog.getByLabel('Replace ticket photo', { exact: true }).setInputFiles(await barcode())
  await expect(dialog.getByAltText('Original ticket photo')).toBeVisible()
  await dialog.getByRole('button', { name: 'Ticket barcode', exact: true }).click()
  await expect(dialog.getByAltText('Ticket QR CODE barcode')).toBeVisible()
  await dialog.getByRole('button', { name: 'Seats', exact: true }).click()
  await expect(dialog.getByText('Theatre 7 · Row F · Seats 12, 13')).toBeVisible()
  await page.evaluate(async () => { await navigator.serviceWorker.ready })
  await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true)
  await expect.poll(() => page.evaluate(async () => {
    const names = await caches.keys()
    for (const name of names) {
      const cache = await caches.open(name)
      if ((await cache.keys()).some((request) => /zxing_full.*\.wasm/.test(request.url))) return true
    }
    return false
  })).toBe(true)
  await ticketOrigin.stop()
  const requestsBeforeReload = ticketOrigin.successfulRequests
  expect(await page.evaluate(async () => {
    try { await fetch('/network-probe', { cache: 'no-store' }); return false } catch { return true }
  })).toBe(true)
  await page.reload()
  await page.getByRole('button', { name: 'Ticket & seats', exact: true }).click()
  await expect(dialog.getByAltText('Original ticket photo')).toBeVisible()
  await dialog.getByRole('button', { name: 'Ticket barcode', exact: true }).click()
  await expect(dialog.getByAltText('Ticket QR CODE barcode')).toBeVisible()
  expect(ticketOrigin.successfulRequests).toBe(requestsBeforeReload)
  await dialog.getByRole('button', { name: 'Remove photo', exact: true }).click()
  await dialog.getByRole('button', { name: 'Confirm removal', exact: true }).click()
  await expect(dialog.getByLabel('Add ticket photo', { exact: true })).toBeEnabled()
  await expect(dialog.getByAltText('Original ticket photo')).toHaveCount(0)
  await expect(dialog.getByText(/Older device-local ticket data is retained/)).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: 'Ticket & seats', exact: true }).click()
  await expect(dialog.getByText(/Add the original ticket photo to keep it handy/)).toBeVisible()
  await expect(dialog.getByText(/stored on your original device/)).toHaveCount(0)
  expect(ticketOrigin.successfulRequests).toBe(requestsBeforeReload)
})

test('retains the actual current photo on capture quota failure, then retries successfully', async ({ page }) => {
  const dialog = page.getByRole('dialog', { name: 'Ticket & seats · Ticket test film' })
  await dialog.getByLabel('Replace ticket photo', { exact: true }).setInputFiles(await barcode())
  await expect(dialog.getByAltText('Original ticket photo')).toBeVisible()
  const original = await dialog.getByAltText('Original ticket photo').getAttribute('src')
  await page.evaluate(() => {
    const add = IDBObjectStore.prototype.add
    IDBObjectStore.prototype.add = function (...args: Parameters<typeof add>) {
      if (this.name === 'ticketBlobs') throw new DOMException('Quota exhausted for test', 'QuotaExceededError')
      return add.apply(this, args)
    }
    window.addEventListener('restore-ticket-storage', () => { IDBObjectStore.prototype.add = add }, { once: true })
  })
  await dialog.getByLabel('Replace ticket photo', { exact: true }).setInputFiles(await barcode('Code128', 'REPLACEMENT-67890'))
  await expect(dialog.getByRole('button', { name: 'Retry saving photo' })).toBeVisible()
  await expect(dialog.getByAltText('Original ticket photo')).toHaveAttribute('src', original!)
  await page.evaluate(() => window.dispatchEvent(new Event('restore-ticket-storage')))
  await dialog.getByRole('button', { name: 'Retry saving photo' }).click()
  await expect(dialog.getByRole('button', { name: 'Retry saving photo' })).toHaveCount(0)
  await dialog.getByRole('button', { name: 'Ticket barcode', exact: true }).click()
  await expect(dialog.getByAltText('Ticket CODE 128 barcode')).toBeVisible()
})

test('decodes and renders each supported ticket symbology using the bundled WASM', async ({ page }) => {
  test.setTimeout(60_000)
  const dialog = page.getByRole('dialog', { name: 'Ticket & seats · Ticket test film' })
  for (const [format, stored, payload] of [['QRCode', 'QR CODE', 'QR-TICKET'], ['Code128', 'CODE 128', 'BAR-TICKET'], ['PDF417', 'PDF 417', 'PDF-TICKET'], ['Aztec', 'AZTEC', 'AZTEC-TICKET'], ['ITF', 'ITF', '1234567890'], ['Codabar', 'CODABAR', 'A123456B']] as const) {
    await dialog.getByLabel('Replace ticket photo', { exact: true }).setInputFiles(await barcode(format, payload))
    await expect(dialog.getByAltText('Original ticket photo')).toBeVisible()
    await dialog.getByRole('button', { name: 'Ticket barcode', exact: true }).click()
    await expect(dialog.getByAltText(`Ticket ${stored} barcode`)).toBeVisible()
  }
})
