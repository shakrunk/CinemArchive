import { expect, test } from 'playwright/test'

test.use({ timezoneId: 'America/Denver' })

test.beforeEach(async ({ context, page }) => {
  await context.route(/^https?:\/\/(?!127\.0\.0\.1:4178(?:\/|$))/, (route) => route.abort())
  page.on('pageerror', (error) => { throw error })
  await page.goto('/?view=library')
  await page.evaluate(async () => {
    const scope = { projectId: 'unconfigured-local', userId: 'anonymous-local-only' }
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open('cinemarchive-anonymous-v1', 1)
      request.onupgradeneeded = () => {
        request.result.createObjectStore('owners')
        request.result.createObjectStore('quarantine', { keyPath: 'id' }).createIndex('scopeKey', 'scopeKey')
      }
      request.onerror = () => reject(request.error)
      request.onsuccess = () => {
        const db = request.result
        const tx = db.transaction('owners', 'readwrite')
        tx.objectStore('owners').put({ version: 1, scope, revision: 1, nextSequence: 1, commands: [], base: {
          titles: [{ id: 'durable-film', tmdbId: 1, type: 'movie', title: 'A durable memory', year: 2026,
            genres: [], tags: [], status: 'watched', addedAt: '2026-01-01',
            viewings: [{ id: 'durable-viewing', titleId: 'durable-film', date: '2026-10-01', notes: 'Original memory', rating: 4 }],
          }], outings: [], lists: [], listMemberships: {}, pinnedModes: {}, ledgerWidgets: [],
        } }, JSON.stringify([scope.projectId, scope.userId]))
        tx.oncomplete = () => { db.close(); resolve() }
        tx.onabort = () => { db.close(); reject(tx.error) }
      }
    })
  })
  await page.goto('/?view=library&title=durable-film')
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Edit viewing from Oct 1, 2026', exact: true })).toBeVisible()
  await page.getByRole('button', { name: /^Edit viewing from/ }).click()
})

test('a real viewing edit survives a page reload', async ({ page }) => {
  await page.locator('#viewing-edit-notes').fill('Saved through the real editor')
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.locator('#viewing-edit-notes')).toHaveCount(0)
  await page.reload()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByText('"Saved through the real editor"', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: /^Edit viewing from/ }).click()
  await expect(page.locator('#viewing-edit-notes')).toHaveValue('Saved through the real editor')
})

test('an IndexedDB write failure retains the actual edit form and can be retried', async ({ page }) => {
  await page.locator('#viewing-edit-notes').fill('Keep this unsaved draft')
  await page.evaluate(() => {
    const original = IDBObjectStore.prototype.put
    IDBObjectStore.prototype.put = function (...args: Parameters<typeof original>) {
      if (this.name === 'owners') throw new DOMException('Quota exhausted for test', 'QuotaExceededError')
      return original.apply(this, args)
    }
    window.addEventListener('restore-test-storage', () => { IDBObjectStore.prototype.put = original }, { once: true })
  })
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByText('Could not persist offline changes; they are not saved on this device', { exact: true }).first()).toBeVisible()
  await expect(page.locator('#viewing-edit-notes')).toHaveValue('Keep this unsaved draft')
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeVisible()
  await page.evaluate(() => window.dispatchEvent(new Event('restore-test-storage')))
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.locator('#viewing-edit-notes')).toHaveCount(0)
  await page.reload()
  await expect(page.getByText('"Keep this unsaved draft"', { exact: true })).toBeVisible()
})
