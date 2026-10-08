import { expect, test } from 'playwright/test'

// Exercise the real production bundle and service worker, using the app's existing
// unconfigured/local mode. These tests never authenticate or mutate remote data.
test.beforeEach(async ({ context, page }) => {
  await context.route(/^https?:\/\/(?!127\.0\.0\.1:4178(?:\/|$))/, (route) => route.abort())
  page.on('pageerror', (error) => { throw error })
})

test('opens a deep link and restores the active view on reload', async ({ page }) => {
  await page.goto('/?view=library')
  await expect(page.getByRole('heading', { name: 'The Library', exact: true })).toBeVisible()
  await expect(page.getByText('Your archive is empty.', { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'The Library', exact: true })).toBeVisible()
  await expect(page).toHaveURL(/\?view=library$/)
})

test('creates a local list, restores its deep link, goes back, and confirms deletion', async ({ page }) => {
  await page.goto('/?view=lists')
  await page.getByRole('button', { name: 'Create your first list', exact: true }).click()
  await page.getByRole('textbox', { name: 'New list name', exact: true }).fill('Release rehearsal')
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await page.getByRole('button', { name: /Release rehearsal/ }).click()
  await expect(page.getByRole('heading', { name: 'Release rehearsal', exact: true })).toBeVisible()
  await expect(page).toHaveURL(/view=lists&list=/)
  await page.goBack()
  await expect(page).toHaveURL(/\?view=lists$/)
  await page.getByRole('button', { name: /Release rehearsal/ }).click()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Release rehearsal', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Delete list', exact: true }).click()
  await page.getByRole('button', { name: 'Cancel delete', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Release rehearsal', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Delete list', exact: true }).click()
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await expect(page.getByRole('button', { name: /Release rehearsal/ })).toHaveCount(0)
  await page.reload()
  await expect(page.getByRole('button', { name: /Release rehearsal/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /My List/ })).toBeVisible()
})

test('persists an explicit theme and follows system changes only in system mode', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.goto('/?view=library')
  await page.getByRole('radio', { name: 'Light mode', exact: true }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await page.reload()
  await expect(page.getByRole('radio', { name: 'Light mode', exact: true })).toBeChecked()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await page.getByRole('radio', { name: 'Match system', exact: true }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.emulateMedia({ colorScheme: 'light' })
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
})

test('opens the command palette from the keyboard and restores trigger focus', async ({ page }) => {
  await page.goto('/?view=library')
  const trigger = page.getByRole('button', { name: 'Search (open command palette)', exact: true })
  await trigger.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('dialog', { name: 'Command palette', exact: true })).toBeVisible()
  await expect(page.getByRole('combobox')).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(trigger).toBeFocused()
})

test('keeps focus in the new dialog when a palette command opens it', async ({ page }) => {
  await page.goto('/?view=library')
  await page.getByRole('button', { name: 'Search (open command palette)', exact: true }).click()
  await page.getByRole('combobox').fill('Add a title')
  await page.keyboard.press('Enter')
  const dialog = page.getByRole('dialog', { name: 'Add to Library', exact: true })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator(':focus')).toHaveCount(1)
  await page.keyboard.press('Tab')
  await expect(dialog.locator(':focus')).toHaveCount(1)
})

test('reloads the cached shell and opens a lazy view while offline', async ({ page, context, browserName }) => {
  // https://playwright.dev/docs/service-workers: Chromium-only automation support.
  // WebKit offline navigation: https://github.com/microsoft/playwright/issues/42775
  test.skip(browserName !== 'chromium', 'Offline service-worker navigation requires manual Firefox/Safari verification')
  await page.goto('/?view=library')
  await expect(page.getByRole('heading', { name: 'The Library', exact: true })).toBeVisible()
  await page.evaluate(async () => { await navigator.serviceWorker.ready })
  // Prompt-mode workers control the next navigation after their first install.
  await page.reload()
  await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true)
  await context.setOffline(true)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'The Library', exact: true })).toBeVisible()
  await page.goto('/?view=lists')
  await expect(page.getByRole('button', { name: 'Create your first list', exact: true })).toBeVisible()
})
