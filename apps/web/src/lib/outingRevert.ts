import type { PendingCommand } from './offline/commands'
import { assertCommand } from './offline/validation'

export function outingRevertBody(command: PendingCommand): Record<string, unknown> {
  assertCommand(command)
  const mutation = command.mutation
  if (mutation.kind !== 'outing.revert') throw new Error('Expected an outing reversal')
  const outing = command.preconditions?.find((row) => row.table === 'cinema_outings' && row.id === mutation.outingId)
  const viewing = command.preconditions?.find((row) => row.table === 'viewings' && row.id === mutation.viewingId)
  if (!outing || mutation.viewingPresent && !viewing) {
    throw new Error('The saved completion has no confirmed revision. Refresh and review this change before retrying.')
  }
  return {
    p_outing_id: mutation.outingId, p_operation_id: command.id,
    p_expected_updated_at: outing.updatedAt ?? null,
    p_expected_viewing_id: mutation.viewingId,
    p_expected_viewing_updated_at: viewing?.updatedAt ?? null,
    p_expected_operation_id: outing.afterCommandId ?? null,
    p_expected_viewing_operation_id: viewing?.afterCommandId ?? null,
  }
}

function record(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}
// Receipt JSON normalizes timezones/fraction widths. Preserve PostgreSQL's
// microseconds; Date.parse alone would accept a different sub-millisecond guard.
function micros(value: unknown): bigint | null {
  if (typeof value !== 'string') return null
  const match = /^(.*T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?(Z|[+-]\d{2}:\d{2})$/.exec(value)
  if (!match) return null
  const seconds = Date.parse(`${match[1]}${match[3]}`)
  return Number.isFinite(seconds) ? BigInt(seconds) * 1000n + BigInt((match[2] ?? '').padEnd(6, '0')) : null
}
function sameTimestamp(a: unknown, b: unknown): boolean {
  if (a === null || b === null) return a === b
  const first = micros(a), second = micros(b)
  return first !== null && first === second
}

export function outingRevertReceiptStatus(command: PendingCommand, body: Record<string, unknown>, value: unknown): 'applied' | 'conflict' | 'missing' | null {
  const mutation = command.mutation
  if (mutation.kind !== 'outing.revert' || !record(value) || value.operationId !== command.id || value.outingId !== mutation.outingId || !record(value.request)) return null
  const request = value.request
  const expectedKeys = ['kind', 'outingId', 'expectedUpdatedAt', 'expectedViewingId', 'expectedViewingUpdatedAt',
    ...(body.p_expected_operation_id ? ['expectedOperationId'] : []),
    ...(body.p_expected_viewing_operation_id ? ['expectedViewingOperationId'] : [])]
  if (Object.keys(request).length !== expectedKeys.length || !expectedKeys.every((key) => Object.hasOwn(request, key)) ||
    request.kind !== 'outing.revert' || request.outingId !== mutation.outingId || request.expectedViewingId !== mutation.viewingId ||
    !sameTimestamp(request.expectedUpdatedAt, body.p_expected_updated_at) ||
    !sameTimestamp(request.expectedViewingUpdatedAt, body.p_expected_viewing_updated_at) ||
    (request.expectedOperationId ?? null) !== body.p_expected_operation_id ||
    (request.expectedViewingOperationId ?? null) !== body.p_expected_viewing_operation_id) return null
  if (value.status === 'conflict' || value.status === 'missing') return value.status
  if (value.status !== 'applied' || value.canonicalViewingId !== mutation.viewingId || !Array.isArray(value.rows)) return null
  const outing = value.rows.find((entry) => record(entry) && entry.table === 'cinema_outings' && record(entry.key) && entry.key.id === mutation.outingId)
  const title = value.rows.find((entry) => record(entry) && entry.table === 'titles' && record(entry.key) && entry.key.id === mutation.titleId)
  if (!record(outing) || !record(outing.row) || outing.row.id !== mutation.outingId || outing.row.user_id !== command.scope.userId ||
    outing.row.title_id !== mutation.titleId || outing.row.status !== 'missed' || outing.row.completed_viewing_id !== null ||
    !record(title) || !record(title.row) || title.row.id !== mutation.titleId || title.row.user_id !== command.scope.userId) return null
  return 'applied'
}
