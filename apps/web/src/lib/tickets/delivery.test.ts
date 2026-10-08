// @vitest-environment node
import { afterEach, expect, it, vi } from 'vitest'
import { createTicketCommandDelivery } from './delivery'
import { ticketFixture, ticketOwner, ticketOuting, ticketSnapshot, ticketRevision } from './fixtures.test-support'
import { createCommand } from '../offline/commands'
import type { DeliveryContext } from '../offline/coordinator'

afterEach(() => vi.restoreAllMocks())
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
async function setup() {
  const fixture = await ticketFixture()
  const fetch = vi.fn<typeof globalThis.fetch>()
  const readBlob = vi.fn(async () => fixture.record)
  const fetchBase = vi.fn(async () => ({ ...ticketSnapshot(), ticketAttachmentSupport: 'authoritative' as 'authoritative' | 'unsupported', outings: [{ ...ticketOuting, ticketManaged: true, ticketAttachment: fixture.attachment }] }))
  const session = vi.fn(async () => ({ userId: ticketOwner.userId, accessToken: 'owner-token' }))
  const context: DeliveryContext = { scope: ticketOwner, signal: new AbortController().signal, isCurrent: () => true }
  const deliver = createTicketCommandDelivery({ ...ticketOwner, projectId: ticketOwner.projectId, anonKey: 'public-key', session, readBlob, fetchBase, fetch })
  return { ...fixture, fetch, readBlob, fetchBase, session, context, deliver }
}

it('looks up the receipt first and acknowledges with fresh canonical state without old bytes', async () => {
  const s = await setup()
  s.fetch.mockResolvedValue(json(s.receipt))
  const fresh = ticketSnapshot()
  s.fetchBase.mockResolvedValue({ ...fresh, ticketAttachmentSupport: 'authoritative', outings: [{ ...ticketOuting, ticketManaged: true, ticketAttachment: s.attachment }] })
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'success' })
  expect(s.fetch).toHaveBeenCalledOnce()
  expect(s.fetch.mock.calls[0][0]).toContain('get_ticket_command_receipt')
  expect(s.readBlob).not.toHaveBeenCalled()
  expect(s.fetchBase).toHaveBeenCalledOnce()
})

it('uploads original bytes without overwriting, finalizes immutable CAS, and pins every request to the owner', async () => {
  const s = await setup()
  s.fetch.mockResolvedValueOnce(json(null)).mockResolvedValueOnce(json({ attachment: s.attachment, state: 'prepared' }))
    .mockResolvedValueOnce(json({ Key: s.attachment.objectKey })).mockResolvedValueOnce(json(s.receipt))
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'success' })
  const calls = s.fetch.mock.calls
  expect(calls[2][1]).toMatchObject({ body: s.blob, headers: { 'x-upsert': 'false', 'Content-Type': 'image/png' } })
  expect(JSON.parse(calls[3][1]!.body as string)).toEqual({ p_operation_id: s.command.id, p_outing_id: ticketOuting.id, p_attachment_id: s.attachment.id, p_expected_attachment_id: null })
  for (const [, request] of calls) expect(request).toMatchObject({ headers: { Authorization: 'Bearer owner-token' }, signal: s.context.signal })
})

it('checks existing immutable bytes after an unknown upload outcome before finalizing', async () => {
  const s = await setup()
  s.fetch.mockResolvedValueOnce(json(null)).mockResolvedValueOnce(json({ attachment: s.attachment, state: 'prepared' }))
    .mockResolvedValueOnce(json({ error: 'Duplicate' }, 400)).mockResolvedValueOnce(new Response(s.blob, { headers: { 'Content-Type': 'image/png' } }))
    .mockResolvedValueOnce(json(s.receipt))
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'success' })
  expect(s.fetch.mock.calls[3][0]).toContain('/object/authenticated/')
})

it('does not finalize corrupt existing remote bytes', async () => {
  const s = await setup()
  s.fetch.mockResolvedValueOnce(json(null)).mockResolvedValueOnce(json({ attachment: s.attachment, state: 'prepared' }))
    .mockResolvedValueOnce(json({}, 409)).mockResolvedValueOnce(new Response('wrong bytes', { headers: { 'Content-Type': 'image/png' } }))
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'retry' })
  expect(s.fetch).toHaveBeenCalledTimes(4)
  expect(s.fetchBase).not.toHaveBeenCalled()
})

it('retries only the receipt after finalization succeeds but refresh fails', async () => {
  const s = await setup()
  s.fetch.mockResolvedValueOnce(json(s.receipt)).mockResolvedValueOnce(json(s.receipt))
  s.fetchBase.mockRejectedValueOnce(new Error('Refresh offline'))
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'retry' })
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'success' })
  expect(s.readBlob).not.toHaveBeenCalled()
  expect(s.fetch).toHaveBeenCalledTimes(2)
})

it('rejects a receipt with a mismatched immutable request even when its UUID matches', async () => {
  const s = await setup()
  s.fetch.mockResolvedValue(json({ ...s.receipt, request: { ...s.receipt.request, expectedAttachmentId: s.attachment.id } }))
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'retry', message: expect.stringContaining('different') })
  expect(s.fetchBase).not.toHaveBeenCalled()
  expect(s.readBlob).not.toHaveBeenCalled()
})

it('retains retired preparations as a conflict unless a concurrent receipt exists', async () => {
  const s = await setup()
  s.fetch.mockResolvedValueOnce(json(null)).mockResolvedValueOnce(json({ attachment: s.attachment, state: 'retired' })).mockResolvedValueOnce(json(null))
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'conflict' })
  expect(s.readBlob).not.toHaveBeenCalled()
  s.fetch.mockResolvedValueOnce(json(null)).mockResolvedValueOnce(json({ attachment: s.attachment, state: 'retired' })).mockResolvedValueOnce(json(s.receipt))
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'success' })
})

it('never uploads under a switched account or accepts its late receipt', async () => {
  const s = await setup()
  s.session.mockResolvedValueOnce({ userId: 'another-owner', accessToken: 'other-token' })
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'auth' })
  expect(s.fetch).not.toHaveBeenCalled()
  let current = true
  s.fetch.mockImplementationOnce(async () => { current = false; return json(s.receipt) })
  expect(await s.deliver(s.command, { ...s.context, isCurrent: () => current })).toMatchObject({ kind: 'auth' })
  expect(s.fetchBase).not.toHaveBeenCalled()
})

it('detaches with the same receipt protocol and never reads or uploads bytes', async () => {
  const s = await setup()
  const command = createCommand(ticketOwner, { kind: 'ticket.detach', outingId: ticketOuting.id, expectedAttachmentId: s.attachment.id })
  const receipt = { ...s.receipt, operationId: command.id, attachment: null,
    request: { kind: 'ticket.detach', outingId: ticketOuting.id, attachmentId: null, expectedAttachmentId: s.attachment.id } }
  s.fetch.mockResolvedValueOnce(json(null)).mockResolvedValueOnce(json(receipt))
  expect(await s.deliver(command, s.context)).toMatchObject({ kind: 'success' })
  expect(s.fetch.mock.calls[1][0]).toContain('detach_ticket_attachment')
  expect(s.readBlob).not.toHaveBeenCalled()
})

it('classifies CAS refusal as conflict while retaining the pending original', async () => {
  const s = await setup()
  s.fetch.mockResolvedValueOnce(json(null)).mockResolvedValueOnce(json({ attachment: s.attachment, state: 'attached' }))
    .mockResolvedValueOnce(json({ code: '40001', message: 'Another device replaced this ticket' }, 409))
  expect(await s.deliver(s.command, s.context)).toEqual({ kind: 'conflict', message: 'Another device replaced this ticket' })
  expect(s.fetchBase).not.toHaveBeenCalled()
})

it('retains a confirmed command until the descriptor refresh is authoritative', async () => {
  const s = await setup()
  s.fetch.mockResolvedValue(json(s.receipt))
  s.fetchBase.mockResolvedValueOnce({ ...ticketSnapshot(), ticketAttachmentSupport: 'unsupported', outings: [{ ...ticketOuting, ticketManaged: true, ticketAttachment: s.attachment }] })
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'retry', message: expect.stringContaining('authoritative') })
  expect(s.readBlob).not.toHaveBeenCalled()
})

it('sends the immutable literal outing guard and accepts equivalent offset timestamps at microsecond precision', async () => {
  const s = await setup()
  const command = createCommand(ticketOwner, { kind: 'ticket.attach', outingId: ticketOuting.id, attachment: s.attachment,
    expectedAttachmentId: null, expectedUpdatedAt: ticketRevision }, { id: s.command.id })
  const receipt = { ...s.receipt, outingRevisionGuarded: true,
    request: { ...s.receipt.request, expectedUpdatedAt: '2026-10-08T13:00:00.123456-06:00', expectedOperationId: null } }
  s.fetch.mockResolvedValueOnce(json(null)).mockResolvedValueOnce(json({ attachment: s.attachment, state: 'attached' })).mockResolvedValueOnce(json(receipt))
  expect(await s.deliver(command, s.context)).toMatchObject({ kind: 'success' })
  expect(JSON.parse(s.fetch.mock.calls[2][1]!.body as string)).toMatchObject({ p_expected_updated_at: ticketRevision, p_expected_operation_id: null })
  s.fetch.mockResolvedValue(json({ ...receipt, request: { ...receipt.request, expectedUpdatedAt: '2026-10-08T19:00:00.123457Z' } }))
  expect(await s.deliver(command, s.context)).toMatchObject({ kind: 'retry', message: expect.stringContaining('different') })
})

it('sends a detach predecessor guard and rejects unproven or mismatched guarded receipts', async () => {
  const s = await setup(), predecessor = '40000000-0000-4000-8000-000000000002'
  const command = createCommand(ticketOwner, { kind: 'ticket.detach', outingId: ticketOuting.id, expectedAttachmentId: s.attachment.id, expectedOperationId: predecessor })
  const receipt = { ...s.receipt, operationId: command.id, attachment: null, outingRevisionGuarded: true,
    request: { kind: 'ticket.detach', outingId: ticketOuting.id, attachmentId: null, expectedAttachmentId: s.attachment.id, expectedUpdatedAt: null, expectedOperationId: predecessor } }
  s.fetch.mockResolvedValueOnce(json(null)).mockResolvedValueOnce(json(receipt))
  expect(await s.deliver(command, s.context)).toMatchObject({ kind: 'success' })
  expect(JSON.parse(s.fetch.mock.calls[1][1]!.body as string)).toMatchObject({ p_expected_updated_at: null, p_expected_operation_id: predecessor })
  for (const broken of [
    { ...receipt, outingRevisionGuarded: false }, { ...receipt, outingRevisionGuarded: undefined },
    { ...receipt, request: { ...receipt.request, expectedOperationId: s.command.id } },
    { ...receipt, request: { ...receipt.request, expectedUpdatedAt: ticketRevision } },
  ]) {
    s.fetch.mockResolvedValueOnce(json(broken))
    expect(await s.deliver(command, s.context)).toMatchObject({ kind: 'retry' })
  }
  expect(s.readBlob).not.toHaveBeenCalled()
})

it('never retrofits a legacy command to acknowledge a guarded receipt with the same operation ID', async () => {
  const s = await setup()
  s.fetch.mockResolvedValueOnce(json({ ...s.receipt, outingRevisionGuarded: true,
    request: { ...s.receipt.request, expectedUpdatedAt: ticketRevision, expectedOperationId: null } }))
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'retry' })
  s.fetch.mockResolvedValueOnce(json({ ...s.receipt, outingRevisionGuarded: false }))
  expect(await s.deliver(s.command, s.context)).toMatchObject({ kind: 'success' })
})
