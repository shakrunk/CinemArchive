/// <reference types="vite/client" />
import { expect, test, type Page } from 'playwright/test'
import type { OfflineLibraryRuntime, OfflineLibraryStatus } from '../src/store/offlineLibrary'
import type { OfflineSnapshot } from '../src/lib/offline/snapshot'
import type { Title } from '../src/store/mockData'

declare global {
  interface Window {
    offlineHarness: { runtime: OfflineLibraryRuntime; status: OfflineLibraryStatus | null; snapshot: OfflineSnapshot | null; errors: string[] }
  }
}

const title: Title = { id: 'browser-title', tmdbId: 1, type: 'movie', title: 'Browser durability', year: 2026, genres: [], tags: [], status: 'watchlist', addedAt: '2026-10-08', viewings: [] }

async function boot(page: Page, owner = 'owner-a') {
  await page.goto('/e2e/offline-harness.html')
  await page.evaluate(async (ownerId) => {
    const runtimePath = '/src/store/offlineLibrary.ts'
    const storagePath = '/src/lib/offline/storage.ts'
    const replayPath = '/src/lib/offline/replay.ts'
    const snapshotPath = '/src/lib/offline/snapshot.ts'
    const { OfflineLibraryRuntime } = await import(runtimePath)
    const { IndexedDbOfflineStore } = await import(storagePath)
    const { applyMutation } = await import(replayPath)
    const { emptySnapshot } = await import(snapshotPath)
    const base = (userId: string) => JSON.parse(localStorage.getItem(`test-server:${userId}`) ?? JSON.stringify(emptySnapshot()))
    const runtime = new OfflineLibraryRuntime({
      projectId: 'browser-test', ownerStorage: new IndexedDbOfflineStore({ databaseName: 'browser-owner' }),
      anonymousStorage: new IndexedDbOfflineStore({ databaseName: 'browser-anonymous' }),
      isAuthenticated: async () => true,
      fetchBase: async (context: { scope: { userId: string } }) => base(context.scope.userId),
      onSnapshot: (snapshot: OfflineSnapshot | null) => { window.offlineHarness.snapshot = snapshot },
      onStatus: (status: OfflineLibraryStatus) => { window.offlineHarness.status = status },
      onError: (error: unknown) => { window.offlineHarness.errors.push(String(error)) },
      deliver: async (command: { id: string; mutation: Parameters<typeof applyMutation>[1] }, context: { scope: { userId: string } }) => {
        if (localStorage.getItem('test-server-online') !== 'yes') return { kind: 'retry', message: 'Network unavailable', retryAfterMs: 100 }
        const active = Number(localStorage.getItem('test-active-deliveries') ?? '0') + 1
        localStorage.setItem('test-active-deliveries', String(active))
        localStorage.setItem('test-max-deliveries', String(Math.max(active, Number(localStorage.getItem('test-max-deliveries') ?? '0'))))
        await new Promise((resolve) => setTimeout(resolve, 20))
        const receipts: string[] = JSON.parse(localStorage.getItem('test-receipts') ?? '[]')
        if (!receipts.includes(command.id)) {
          localStorage.setItem(`test-server:${context.scope.userId}`, JSON.stringify(applyMutation(base(context.scope.userId), command.mutation)))
          localStorage.setItem('test-receipts', JSON.stringify([...receipts, command.id]))
        }
        localStorage.setItem('test-active-deliveries', String(active - 1))
        return { kind: 'success', canonicalBase: base(context.scope.userId) }
      },
    })
    window.offlineHarness = { runtime, status: null, snapshot: null, errors: [] }
    await runtime.activate(ownerId)
  }, owner)
}

test.beforeEach(async ({ context }) => {
  await context.route(/^https?:\/\/(?!127\.0\.0\.1:4180(?:\/|$))/, (route) => route.abort())
})

test('real IndexedDB survives reload and reconnect delivers the original identity', async ({ page }) => {
  await boot(page)
  const id = await page.evaluate(async (value) => (await window.offlineHarness.runtime.submit({ kind: 'title.create', title: value })).id, title)
  await boot(page)
  expect(await page.evaluate(() => window.offlineHarness.status?.commands[0].id)).toBe(id)
  expect(await page.evaluate(() => window.offlineHarness.snapshot?.titles[0].title)).toBe(title.title)
  await page.evaluate(() => { localStorage.setItem('test-server-online', 'yes'); window.dispatchEvent(new Event('online')) })
  await expect.poll(() => page.evaluate(() => window.offlineHarness.status?.commands.length)).toBe(0)
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('test-receipts') ?? '[]'))).toEqual([id])
  expect(await page.evaluate(() => window.offlineHarness.errors)).toEqual([])
})

test('two real tabs share Web Locks and converge without duplicate delivery', async ({ page, context }) => {
  await boot(page)
  const second = await context.newPage()
  await boot(second)
  await Promise.all([
    page.evaluate(async (value) => window.offlineHarness.runtime.submit({ kind: 'title.create', title: value }), title),
    second.evaluate(async (value) => window.offlineHarness.runtime.submit({ kind: 'title.create', title: value }), { ...title, id: 'second-title', tmdbId: 2 }),
  ])
  await page.evaluate(() => { localStorage.setItem('test-server-online', 'yes'); window.dispatchEvent(new Event('online')) })
  await second.evaluate(() => window.dispatchEvent(new Event('online')))
  await expect.poll(() => page.evaluate(() => window.offlineHarness.status?.commands.length)).toBe(0)
  await expect.poll(() => second.evaluate(() => window.offlineHarness.snapshot?.titles.length)).toBe(2)
  expect(await page.evaluate(() => localStorage.getItem('test-max-deliveries'))).toBe('1')
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('test-receipts') ?? '[]').length)).toBe(2)
})

test('switching accounts hides private work synchronously and retains it for its owner', async ({ page }) => {
  await boot(page)
  await page.evaluate(async (value) => window.offlineHarness.runtime.submit({ kind: 'title.create', title: value }), title)
  const hidden = await page.evaluate(async () => {
    const pending = window.offlineHarness.runtime.activate('owner-b')
    const immediatelyHidden = window.offlineHarness.snapshot === null
    await pending
    return immediatelyHidden && window.offlineHarness.snapshot?.titles.length === 0
  })
  expect(hidden).toBe(true)
  await page.evaluate(() => window.offlineHarness.runtime.activate('owner-a'))
  expect(await page.evaluate(() => window.offlineHarness.snapshot?.titles[0].id)).toBe(title.id)
  expect(await page.evaluate(() => window.offlineHarness.status?.commands.length)).toBe(1)
})

test('ticket revision intent survives real IndexedDB restart and receipt-only recovery without adopting a newer revision', async ({ page }) => {
  await page.goto('/e2e/offline-harness.html')
  const saved = await page.evaluate(async () => {
    const storagePath = '/src/lib/offline/storage.ts', fixturePath = '/src/lib/tickets/fixtures.test-support.ts'
    const { IndexedDbOfflineStore } = await import(storagePath)
    const { ticketFixture, ticketOwner, ticketOuting, ticketSnapshot } = await import(fixturePath)
    const fixture = await ticketFixture(), db = new IndexedDbOfflineStore({ databaseName: 'ticket-guard-browser' })
    await db.replaceBase(ticketOwner, ticketSnapshot())
    const read = await db.attachTicket(ticketOwner, ticketOuting.id, fixture.attachment, fixture.blob)
    const command = read.document.commands[0]
    await db.recordFailure(ticketOwner, command.id, { state: 'pending', message: 'Finalize response lost' })
    await db.close()
    return { id: command.id, mutation: command.mutation }
  })
  await page.reload()
  const recovered = await page.evaluate(async () => {
    const storagePath = '/src/lib/offline/storage.ts', fixturePath = '/src/lib/tickets/fixtures.test-support.ts'
    const deliveryPath = '/src/lib/tickets/delivery.ts', commandsPath = '/src/lib/offline/commands.ts'
    const { IndexedDbOfflineStore } = await import(storagePath)
    const { createTicketCommandDelivery } = await import(deliveryPath)
    const { createCommand } = await import(commandsPath)
    const { ticketFixture, ticketOwner, ticketOuting, ticketSnapshot } = await import(fixturePath)
    const fixture = await ticketFixture(), db = new IndexedDbOfflineStore({ databaseName: 'ticket-guard-browser' })
    const original = (await db.read(ticketOwner)).document.commands[0]
    const canonicalBase = { ...ticketSnapshot(), ticketAttachmentSupport: 'authoritative',
      rowRevisions: { [`cinema_outings:${ticketOuting.id}`]: '2026-10-09T00:00:00.654321Z' },
      outings: [{ ...ticketOuting, venue: 'Newer remote venue', ticketManaged: true, ticketAttachment: fixture.attachment }] }
    await db.replaceBase(ticketOwner, canonicalBase)
    await db.retry(ticketOwner, original.id)
    const calls: string[] = []
    const deliver = createTicketCommandDelivery({ projectId: ticketOwner.projectId, anonKey: 'test-only',
      session: async () => ({ userId: ticketOwner.userId, accessToken: 'test-only' }),
      readBlob: async () => { throw new Error('Receipt recovery must not need bytes') },
      fetchBase: async () => canonicalBase,
      fetch: async (url: string) => {
        calls.push(String(url))
        return new Response(JSON.stringify({ ...fixture.receipt, operationId: original.id, outingRevisionGuarded: true,
          request: { ...fixture.receipt.request, expectedUpdatedAt: original.mutation.expectedUpdatedAt, expectedOperationId: null } }),
        { headers: { 'Content-Type': 'application/json' } })
      },
    })
    const retry = (await db.read(ticketOwner)).document.commands[0]
    const result = await deliver(retry, { scope: ticketOwner, signal: new AbortController().signal, isCurrent: () => true })
    if (result.kind !== 'success') throw new Error(`Unexpected delivery ${result.kind}`)
    await db.acknowledge(ticketOwner, retry.id, undefined, result.canonicalBase)
    const detached = await db.detachTicket(ticketOwner, ticketOuting.id)
    const detach = detached.document.commands[0]
    const read = await db.append(createCommand(ticketOwner, { kind: 'outing.patch', outingId: ticketOuting.id, patch: { notes: 'Next local edit' } }))
    await db.close()
    return { id: retry.id, mutation: retry.mutation, calls, detach, next: read.document.commands[1], venue: read.document.base.outings[0].venue }
  })
  expect(recovered.id).toBe(saved.id)
  expect(recovered.mutation).toEqual(saved.mutation)
  expect(recovered.calls).toHaveLength(1)
  expect(recovered.calls[0]).toContain('get_ticket_command_receipt')
  expect(recovered.venue).toBe('Newer remote venue')
  expect(recovered.detach.mutation).toMatchObject({ expectedUpdatedAt: '2026-10-09T00:00:00.654321Z' })
  expect(recovered.next.preconditions).toEqual([{ table: 'cinema_outings', id: recovered.detach.mutation.outingId, afterCommandId: recovered.detach.id }])
})
