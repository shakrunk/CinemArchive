import { prepareZXingModule, readBarcodes, writeBarcode, type ReadResult, type WriterOptions } from 'zxing-wasm/full'
import wasmUrl from 'zxing-wasm/full/zxing_full.wasm?url'
import type { TicketBarcode, TicketCapture } from './types'
import { TICKET_MAX_BYTES } from './types'
import { assertTicketBytes, ticketDigest } from './validation'

// Never fall back to the package CDN. Vite fingerprints this same-origin asset
// and the service worker precaches it for capture and viewing without a network.
prepareZXingModule({ overrides: { locateFile: () => wasmUrl } })
const formats: Record<Exclude<TicketBarcode['format'], 'OTHER'>, WriterOptions['format']> = {
  QR_CODE: 'QRCode', CODE_128: 'Code128', PDF_417: 'PDF417', AZTEC: 'Aztec', ITF: 'ITF', CODABAR: 'Codabar',
}

export function safeTicketBarcode(results: ReadResult[]): TicketBarcode | null {
  const valid = results.filter((result) => result.isValid)
  if (valid.length !== 1) return null
  const result = valid[0]
  const format = Object.entries(formats).find(([, name]) => name === result.format)?.[0] as TicketBarcode['format'] | undefined
  const bytes = new TextEncoder().encode(result.text)
  if (!format || !result.text || bytes.length > 32768 || result.sequenceSize >= 0 || result.contentType === 'GS1' || result.readerInit ||
      bytes.length !== result.bytes.length || !bytes.every((byte, index) => byte === result.bytes[index])) return null
  return { payload: result.text, format }
}

export async function decodeTicketBarcode(blob: Blob): Promise<TicketBarcode | null> {
  return safeTicketBarcode(await readBarcodes(blob, { textMode: 'Plain', maxNumberOfSymbols: 2 }))
}

export async function prepareTicketCapture(blob: Blob): Promise<TicketCapture> {
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(blob.type)) throw new Error('Choose a JPEG, PNG or WebP ticket photo')
  if (blob.size <= 0 || blob.size > TICKET_MAX_BYTES) throw new Error('Ticket photos must be no larger than 20 MiB')
  // The browser must be able to display the original. Preserve its original
  // bytes rather than re-encoding a camera image or altering scanner content.
  const image = await createImageBitmap(blob)
  if (!image.width || !image.height) { image.close(); throw new Error('This ticket photo could not be opened') }
  image.close()
  const capture: TicketCapture = { id: crypto.randomUUID(), mimeType: blob.type as TicketCapture['mimeType'], byteLength: blob.size,
    sha256: await ticketDigest(blob), barcode: null }
  await assertTicketBytes(blob, { ...capture, objectKey: `anonymous-local-only/${capture.id}/original` })
  try { capture.barcode = await decodeTicketBarcode(blob) } catch { /* Original remains usable if scanning is unavailable. */ }
  return capture
}

/** Only render the actual format and payload verified against the original.
 * Binary, multi-symbol and special encodings continue to use the photo. */
export async function renderTicketBarcode(blob: Blob, saved: TicketBarcode | null): Promise<Blob | null> {
  if (!saved || saved.format === 'OTHER') return null
  const decoded = await decodeTicketBarcode(blob)
  if (!decoded || decoded.format !== saved.format || decoded.payload !== saved.payload) return null
  const result = await writeBarcode(saved.payload, { format: formats[saved.format], scale: -640, addQuietZones: true })
  if (result.error || !result.image) return null
  const roundTrip = await decodeTicketBarcode(result.image)
  return roundTrip?.format === saved.format && roundTrip.payload === saved.payload ? result.image : null
}
