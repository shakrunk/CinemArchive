import { expect, test } from 'playwright/test'

test('compact chart widgets expose complete keyboard-readable data and restore focus', async ({ context, page }) => {
  await context.route(/^https?:\/\/(?!127\.0\.0\.1:4178(?:\/|$))/, (route) => route.abort())
  page.on('pageerror', (error) => { throw error })
  await page.goto('/?view=library')
  const panels = [
    ['activity', 'Time in the dark'], ['run', 'The run'], ['decades', 'By the era'],
    ['verdicts', 'Second opinions'], ['weekdays', 'Screening nights'], ['trajectory', 'Shifting standards'],
    ['revivals', 'Premieres & revivals'], ['streaks', 'The marathon'],
  ]
  await page.evaluate(async (panels) => {
    const date = new Date()
    const day = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    const title = {
      id: 'chart-film', tmdbId: 1, type: 'movie', title: 'A film with a complete accessible title', year: 2000,
      genres: ['Drama'], tags: [], status: 'watched', addedAt: date.toISOString(), rating: 4, imdbRating: 7,
      viewings: [{ id: 'chart-viewing', titleId: 'chart-film', date: day, rating: 4 }],
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
          titles: [title], outings: [], lists: [], listMemberships: {}, pinnedModes: {},
          ledgerWidgets: panels.map(([panel], index) => ({ id: `chart-${index}`, panel, width: 'sm' })),
        } }, JSON.stringify([scope.projectId, scope.userId]))
        tx.oncomplete = () => { db.close(); resolve() }
        tx.onabort = () => { db.close(); reject(tx.error) }
      }
    })
  }, panels)
  await page.goto('/?view=ledger')
  for (const [, title] of panels) {
    const article = page.getByRole('article').filter({ has: page.getByRole('heading', { name: title, exact: true }) })
    const trigger = article.getByRole('button', { name: `View data for ${title}`, exact: true })
    await trigger.scrollIntoViewIfNeeded()
    await expect(trigger).toBeVisible()
    const bounds = await article.boundingBox(), action = await trigger.boundingBox()
    expect(action!.y + action!.height).toBeLessThanOrEqual(bounds!.y + bounds!.height)
    await trigger.focus()
    await page.keyboard.press('Enter')
    const dialog = page.getByRole('dialog', { name: `${title} data`, exact: true })
    await expect(dialog).toBeVisible()
    const table = dialog.getByRole('table')
    await expect(table.getByRole('rowheader').first()).toBeVisible()
    expect(await table.getByRole('columnheader').count()).toBeGreaterThanOrEqual(2)
    const region = dialog.getByRole('region', { name: `${title} data table`, exact: true })
    await region.focus()
    await page.keyboard.press('End')
    await expect(region).toBeFocused()
    const viewport = page.viewportSize()!, modal = await dialog.boundingBox()
    expect(modal!.x).toBeGreaterThanOrEqual(-1)
    expect(modal!.x + modal!.width).toBeLessThanOrEqual(viewport.width + 1)
    await page.keyboard.press('Tab')
    await expect(dialog.locator(':focus')).toHaveCount(1)
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(trigger).toBeFocused()
  }
})
