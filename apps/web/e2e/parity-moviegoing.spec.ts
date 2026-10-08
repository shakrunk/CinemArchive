import { expect, test } from 'playwright/test'

test.beforeEach(async ({ context, page }) => {
  await context.route(/^https?:\/\/(?!127\.0\.0\.1:4178(?:\/|$))/, (route) => route.abort())
  page.on('pageerror', (error) => { throw error })
  await page.goto('/?view=library')
  await page.evaluate(async () => {
    const now = new Date()
    const anniversary = new Date(now.getFullYear() - 4, now.getMonth(), now.getDate(), 20)
    const outings = Array.from({ length: 10 }, (_, index) => ({
      id: `trip-${index}`, titleId: 'parity-film', status: 'completed', completedViewingId: `viewing-${index}`,
      showtime: anniversary.toISOString(), endsAt: new Date(anniversary.getTime() + 7_200_000).toISOString(),
      createdAt: anniversary.toISOString(), venue: index < 5 ? 'Palace Cinema' : `Theater ${index}`,
      ticketPrice: index < 5 ? 10 : 20, format: 'IMAX', companions: [{ name: 'Sam' }],
      seats: [], previewsMinutes: 0, runtimeMinutes: 120,
    }))
    const fixture = {
      version: 2,
      state: {
        titles: [{
          id: 'parity-film', tmdbId: 1, type: 'movie', title: 'Anniversary film', year: 2020,
          genres: [], tags: [], status: 'watched', addedAt: anniversary.toISOString(),
          viewings: outings.map((outing, index) => ({
            id: `viewing-${index}`, titleId: 'parity-film', outingId: outing.id,
            date: `${anniversary.getFullYear()}-01-01`, rating: 4.5, notes: 'A wonderful crowd',
            venue: outing.venue, companions: outing.companions,
          })),
        }],
        outings,
        ledgerPrefs: { widgets: [{ id: 'moviegoing-parity', panel: 'moviegoing', width: 'sm' }] },
      },
    }
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
          titles: fixture.state.titles, outings: fixture.state.outings, lists: [], listMemberships: {}, pinnedModes: {}, ledgerWidgets: fixture.state.ledgerPrefs.widgets,
        } }, JSON.stringify([scope.projectId, scope.userId]))
        tx.oncomplete = () => { db.close(); resolve() }
        tx.onabort = () => { db.close(); reject(tx.error) }
      }
    })
  })
})

test('opens a cinema-memory title from the real Up Next view', async ({ page }) => {
  await page.goto('/?view=upnext')
  await expect(page.getByRole('heading', { name: 'On this day', exact: true })).toBeVisible()
  await expect(page.getByText('4 years ago today', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('“A wonderful crowd”', { exact: true }).first()).toBeVisible()
  await page.getByRole('button', { name: 'Open Anniversary film', exact: true }).first().click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page).toHaveURL(/title=parity-film/)
})

test('fits compact moviegoing summary and exposes full detail with keyboard access', async ({ page }) => {
  await page.goto('/?view=ledger')
  const panel = page.getByRole('article').filter({ has: page.getByRole('heading', { name: 'At the movies', exact: true }) })
  await expect(panel).toBeVisible()
  const trigger = panel.getByRole('button', { name: 'View moviegoing details', exact: true })
  await trigger.scrollIntoViewIfNeeded()
  const cardBox = await panel.boundingBox()
  const triggerBox = await trigger.boundingBox()
  expect(cardBox).not.toBeNull()
  expect(triggerBox).not.toBeNull()
  expect(triggerBox!.y + triggerBox!.height).toBeLessThanOrEqual(cardBox!.y + cardBox!.height)
  expect(await panel.evaluate((element) => element.scrollHeight <= element.clientHeight + 1)).toBe(true)
  await trigger.focus()
  await page.keyboard.press('Enter')
  const dialog = page.getByRole('dialog', { name: 'Moviegoing details', exact: true })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('table', { name: 'Spend by venue' })).toContainText('Palace Cinema')
  await expect(dialog.getByRole('region', { name: 'Theaters', exact: true })).toContainText('Theater 9')
  await expect(dialog.getByRole('region', { name: 'Best value venue' })).toContainText('$10.00')
  await page.keyboard.press('Tab')
  await expect(dialog.locator(':focus')).toHaveCount(1)
  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(trigger).toBeFocused()
})
