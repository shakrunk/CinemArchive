import { expect, test } from 'playwright/test'

test.beforeEach(async ({ context, page }) => {
  await context.route(/^https?:\/\/(?!127\.0\.0\.1:4178(?:\/|$))/, route => route.abort())
  page.on('pageerror', error => { throw error })
})

test('previews, applies all sizes, restores on reload, and loads bundled Lexend', async ({ page }) => {
  // Four successive layout changes plus a font swap take longer in WebKit.
  test.setTimeout(60_000)
  await page.goto('/?view=profile')
  const root = page.locator('html')
  const preview = page.getByLabel('Text preview')
  await page.getByLabel('Lexend · dyslexia-friendly').check()
  await page.getByLabel('Extra large', { exact: true }).check()
  await expect(preview).toHaveAttribute('data-text-family', 'lexend')
  await expect(root).toHaveAttribute('data-text-family', 'default')
  await page.getByRole('button', { name: 'Cancel preview' }).click()
  await expect(page.getByLabel('Default · cinematic')).toBeChecked()

  // Existing arbitrary-pixel typography outside the new controls must scale too.
  const metadata = page.getByText('Metadata and artwork from', { exact: false })
  for (const [label, scale] of [['Small', 0.85], ['Default', 1], ['Large', 1.15], ['Extra large', 1.3]] as const) {
    await page.getByLabel(label, { exact: true }).check()
    await page.getByRole('button', { name: 'Apply text preferences' }).click()
    await expect.poll(() => metadata.evaluate(el => parseFloat(getComputedStyle(el).fontSize))).toBeCloseTo(11 * scale, 2)
    await expect.poll(() => root.evaluate(el => getComputedStyle(el).fontSize)).toBe('16px')
  }
  await page.getByLabel('Lexend · dyslexia-friendly').check()
  await page.getByRole('button', { name: 'Apply text preferences' }).click()
  await page.reload()
  await expect(root).toHaveAttribute('data-text-family', 'lexend')
  await expect(root).toHaveAttribute('data-text-size', 'extra-large')
  expect(await page.evaluate(async () => {
    const loaded = await document.fonts.load('16px Lexend')
    return loaded.length > 0 && loaded.every(font => font.status === 'loaded')
  })).toBe(true)
  expect(await metadata.evaluate(el => getComputedStyle(el).fontFamily)).toContain('Lexend')
  expect(await page.evaluate(() => performance.getEntriesByType('resource').some(entry => entry.name.includes('/fonts/lexend/')))).toBe(true)

  // Scaling text does not replace the user's browser base size.
  await root.evaluate(el => { el.style.fontSize = '20px' })
  await expect.poll(() => metadata.evaluate(el => parseFloat(getComputedStyle(el).fontSize))).toBeCloseTo(11 * 1.3 * 1.25, 2)
})

test('applies saved preferences before the application bundle and syncs tabs', async ({ context, page }) => {
  await page.addInitScript(() => localStorage.setItem('cinemarchive-text-preferences', JSON.stringify({ family: 'lexend', size: 'large' })))
  await page.route('**/assets/*.js', route => route.abort())
  await page.goto('/?view=profile')
  await expect(page.locator('html')).toHaveAttribute('data-text-family', 'lexend')
  await expect(page.locator('html')).toHaveAttribute('data-text-size', 'large')
  expect(await page.locator('html').evaluate(el => el.style.getPropertyValue('--text-scale'))).toBe('1.15')
  // Use a fresh page: WebKit retains a failed ES module load across a reload.
  const writer = await context.newPage()
  await writer.goto('/?view=profile')
  const second = await context.newPage()
  await second.goto('/?view=profile')
  await writer.bringToFront()
  await writer.getByLabel('Small', { exact: true }).check()
  await writer.getByRole('button', { name: 'Apply text preferences' }).click()
  await expect(second.locator('html')).toHaveAttribute('data-text-size', 'small')
  await expect(second.getByLabel('Small', { exact: true })).toBeChecked()
})

test('keeps readability controls usable at 320px with extra large Lexend', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await page.addInitScript(() => localStorage.setItem('cinemarchive-text-preferences', JSON.stringify({ family: 'lexend', size: 'extra-large' })))
  await page.goto('/?view=profile')
  const controls = page.getByLabel('Text preferences', { exact: true })
  await controls.scrollIntoViewIfNeeded()
  for (const button of await controls.getByRole('button').all()) {
    const box = await button.boundingBox()
    expect(box).not.toBeNull()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(320)
  }
  expect(await controls.evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
  await page.getByLabel('Small', { exact: true }).check()
  await page.getByRole('button', { name: 'Apply text preferences' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-text-size', 'small')
})
