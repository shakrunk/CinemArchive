import { afterEach, expect, it, vi } from 'vitest'
import { readBarcodes, writeBarcode, type ReadResult } from 'zxing-wasm/full'
import { decodeTicketBarcode, renderTicketBarcode, safeTicketBarcode } from './barcode'
import type { TicketBarcode } from './types'

vi.mock('zxing-wasm/full', () => ({ prepareZXingModule: vi.fn(), readBarcodes: vi.fn(), writeBarcode: vi.fn() }))
const result = (format = 'QRCode', text = 'ticket-content'): ReadResult => ({ isValid: true, text, bytes: new TextEncoder().encode(text), format,
  sequenceSize: -1, contentType: 'Text', readerInit: false } as ReadResult)
afterEach(() => vi.resetAllMocks())

it.each([['QRCode', 'QR_CODE'], ['Code128', 'CODE_128'], ['PDF417', 'PDF_417'], ['Aztec', 'AZTEC'], ['ITF', 'ITF'], ['Codabar', 'CODABAR']])('keeps the actual %s symbology', (source, stored) => {
  expect(safeTicketBarcode([result(source)])).toEqual({ payload: 'ticket-content', format: stored })
})

it('keeps binary, ambiguous, structured, GS1 and unsupported codes as original-photo fallbacks', () => {
  for (const rows of [[], [result(), result()], [{ ...result(), bytes: new Uint8Array([255]) }],
    [{ ...result(), sequenceSize: 2 }], [{ ...result(), contentType: 'GS1' }], [result('DataMatrix')],
    [{ ...result(), readerInit: true }], [result('QRCode', 'x'.repeat(32769))]]) {
    expect(safeTicketBarcode(rows as ReadResult[])).toBeNull()
  }
})

it('uses plain decoded bytes and does not silently choose one of several codes', async () => {
  vi.mocked(readBarcodes).mockResolvedValue([result(), result('Code128')])
  expect(await decodeTicketBarcode(new Blob())).toBeNull()
  expect(readBarcodes).toHaveBeenCalledWith(expect.any(Blob), { textMode: 'Plain', maxNumberOfSymbols: 2 })
})

it('renders only a saved code verified against the original and its generated image', async () => {
  const original = new Blob(['original']), rendered = new Blob(['rendered'])
  vi.mocked(readBarcodes).mockResolvedValue([result('Code128')])
  vi.mocked(writeBarcode).mockResolvedValue({ image: rendered, error: '', svg: '', utf8: '', symbol: {} } as Awaited<ReturnType<typeof writeBarcode>>)
  expect(await renderTicketBarcode(original, { payload: 'ticket-content', format: 'CODE_128' })).toBe(rendered)
  expect(writeBarcode).toHaveBeenCalledWith('ticket-content', expect.objectContaining({ format: 'Code128', addQuietZones: true }))
  vi.mocked(readBarcodes).mockResolvedValueOnce([result('Code128')]).mockResolvedValueOnce([result('Code128', 'changed')])
  expect(await renderTicketBarcode(original, { payload: 'ticket-content', format: 'CODE_128' })).toBeNull()
})

it.each([null, { format: 'OTHER', payload: 'unknown' }, { format: 'CODE_128', payload: 'different' }])('never synthesizes a code from an absent or mismatched ticket payload', async (saved) => {
  vi.mocked(readBarcodes).mockResolvedValue([result()])
  expect(await renderTicketBarcode(new Blob(), saved as TicketBarcode | null)).toBeNull()
  expect(writeBarcode).not.toHaveBeenCalled()
})
