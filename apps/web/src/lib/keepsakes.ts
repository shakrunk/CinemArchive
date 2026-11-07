import { isSupabaseConfigured, supabase } from './auth'

// Keepsake photos for cinema outings (#208): a ticket stub, a poster board, a popcorn selfie.
// Owner-private and separate from the single scannable ticket attachment. Mirrors Android's
// KeepsakeRules.kt / KeepsakeRepository.kt; the contract is docs/android-contracts/outing-keepsakes.md.
// Online-only: there is no offline queue for edits, so adding, replacing or viewing a photo needs a connection;
// the one thing remembered on the device is an uploaded photo whose row did not save, so it can be finished.

export interface OutingKeepsake {
  id: string
  outingId: string
  mimeType: string
  byteLength: number
  createdAt: string
}

export const MAX_KEEPSAKES = 8
export const MAX_KEEPSAKE_BYTES = 10 * 1024 * 1024
export const KEEPSAKE_MAX_EDGE = 2048
export const KEEPSAKE_MIME_TYPES = ['image/jpeg', 'image/png', 'image/webp']

const BUCKET = 'outing-keepsakes'
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

const isUuid = (value: unknown): value is string => typeof value === 'string' && UUID_RE.test(value)

/** Storage object name: `<owner id>/<keepsake id>`, the only shape the bucket accepts. */
export const keepsakeObjectKey = (ownerId: string, id: string) => `${ownerId}/${id}`

export const isValidKeepsake = (mimeType: string, byteLength: number) =>
  KEEPSAKE_MIME_TYPES.includes(mimeType) && Number.isInteger(byteLength) && byteLength >= 1 && byteLength <= MAX_KEEPSAKE_BYTES

export const canAddKeepsake = (count: number) => count < MAX_KEEPSAKES

/** The size to draw a photo at so its longest edge is at most `KEEPSAKE_MAX_EDGE`, keeping the aspect ratio. */
export function scaledKeepsakeSize(width: number, height: number): [number, number] {
  if (!(width > 0 && height > 0)) throw new Error('Invalid image size')
  const longest = Math.max(width, height)
  if (longest <= KEEPSAKE_MAX_EDGE) return [width, height]
  const scale = KEEPSAKE_MAX_EDGE / longest
  return [Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))]
}

/** Rows with an unexpected shape are skipped, never shown. */
export function parseKeepsakes(rows: unknown): OutingKeepsake[] {
  if (!Array.isArray(rows)) return []
  return rows.flatMap((row): OutingKeepsake[] => {
    if (!row || typeof row !== 'object') return []
    const r = row as Record<string, unknown>
    if (!isUuid(r.id) || !isUuid(r.outing_id) || typeof r.mime_type !== 'string' || typeof r.byte_length !== 'number') return []
    if (!isValidKeepsake(r.mime_type, r.byte_length)) return []
    return [{ id: r.id, outingId: r.outing_id, mimeType: r.mime_type, byteLength: r.byte_length, createdAt: String(r.created_at ?? '') }]
  })
}

/**
 * Decodes a photo (applying its EXIF rotation), downscales it so its longest edge is at most
 * `KEEPSAKE_MAX_EDGE`, and re-encodes it as JPEG, lowering the quality until it fits
 * `MAX_KEEPSAKE_BYTES`. Re-encoding also drops embedded metadata such as GPS position.
 */
export async function prepareKeepsake(file: Blob): Promise<Blob> {
  const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  try {
    const [width, height] = scaledKeepsakeSize(bitmap.width, bitmap.height)
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const context = canvas.getContext('2d')
    if (!context) throw new Error('Could not process that photo.')
    context.drawImage(bitmap, 0, 0, width, height)
    for (const quality of [0.85, 0.7, 0.5]) {
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
      if (blob && isValidKeepsake(blob.type, blob.size)) return blob
    }
    throw new Error('That photo is too large to keep.')
  } finally {
    bitmap.close()
  }
}

function connection() {
  if (!isSupabaseConfigured || !supabase) throw new Error('Sign in to keep photos with your outings.')
  return supabase
}

export async function listKeepsakes(outingId: string): Promise<OutingKeepsake[]> {
  if (!isUuid(outingId)) throw new Error('Invalid outing')
  const { data, error } = await connection().from('outing_keepsakes').select('*').eq('outing_id', outingId).order('created_at', { ascending: true })
  if (error) throw error
  return parseKeepsakes(data)
}

// ─── Interrupted uploads ─────────────────────────────────────────────────────
// Adding is two steps: the bytes go to storage, then a row ties them to the outing. A photo whose
// bytes arrived but whose row did not (the connection dropped, or the outing was still syncing) is
// remembered in this browser, per account, so "Finish saving" can retry just the row insert. The
// photo itself is not kept: a failed upload starts over from the picker.

/** An uploaded object that has no row yet. */
export interface PendingKeepsake {
  id: string
  outingId: string
  mimeType: string
  byteLength: number
}

/** The row insert failed after the bytes uploaded. `pending` is set when finishing later can still
 *  work, and null when it cannot (the outing is full, gone or not the caller's). */
export class KeepsakeSaveError extends Error {
  readonly pending: PendingKeepsake | null
  /** Set by `replaceKeepsake` when the old photo had to be removed and could not be put back, so the new one is all that is left to finish. */
  originalRemoved = false
  constructor(message: string, pending: PendingKeepsake | null) {
    super(message)
    this.name = 'KeepsakeSaveError'
    this.pending = pending
  }
}

/** Row-insert failures a retry can never fix: the guard trigger's mismatched object (22023), ninth
 *  photo (54000) and someone else's outing (42501). */
const FINAL_INSERT_CODES = ['22023', '54000', '42501']

const pendingKey = (ownerId: string) => `cinemarchive:keepsake-pending:v1:${ownerId}`

function readPending(ownerId: string): PendingKeepsake[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(pendingKey(ownerId)) ?? '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.filter((p): p is PendingKeepsake => !!p && typeof p === 'object' && isUuid(p.id) && isUuid(p.outingId) &&
      typeof p.mimeType === 'string' && typeof p.byteLength === 'number' && isValidKeepsake(p.mimeType, p.byteLength))
  } catch {
    return []
  }
}

function writePending(ownerId: string, list: PendingKeepsake[]): void {
  try {
    if (list.length === 0) localStorage.removeItem(pendingKey(ownerId))
    else localStorage.setItem(pendingKey(ownerId), JSON.stringify(list))
  } catch {
    // Without storage a reload forgets the object; the daily cleanup still removes it.
  }
}

/** Uploaded-but-unsaved photos for one outing, oldest first. */
export const pendingKeepsakes = (ownerId: string, outingId: string): PendingKeepsake[] =>
  readPending(ownerId).filter((p) => p.outingId === outingId)

/** Forgets a pending photo (saved, given up on, or already a row). Its object is left to the daily cleanup. */
export function discardPendingKeepsake(ownerId: string, id: string): void {
  writePending(ownerId, readPending(ownerId).filter((p) => p.id !== id))
}

const errorCode = (error: unknown) => (error && typeof error === 'object' && 'code' in error ? String((error as { code: unknown }).code) : '')
const errorText = (error: unknown, fallback: string) => error instanceof Error && error.message ? error.message : (error as { message?: string } | null)?.message || fallback

async function fetchKeepsake(client: ReturnType<typeof connection>, id: string): Promise<OutingKeepsake | null> {
  const { data, error } = await client.from('outing_keepsakes').select('*').eq('id', id).maybeSingle()
  return error ? null : parseKeepsakes([data])[0] ?? null
}

/** Step one: uploads the (already prepared) photo under the owner's folder without overwriting and
 *  remembers it as pending. A failure here changes nothing. */
export async function uploadKeepsake(ownerId: string, outingId: string, photo: Blob): Promise<PendingKeepsake> {
  if (!isUuid(ownerId) || !isUuid(outingId)) throw new Error('Invalid id')
  if (!isValidKeepsake(photo.type, photo.size)) throw new Error("That photo can't be kept: use a JPEG, PNG or WebP under 10 MB.")
  const client = connection()
  const id = crypto.randomUUID()
  const upload = await client.storage.from(BUCKET).upload(keepsakeObjectKey(ownerId, id), photo, { contentType: photo.type, upsert: false })
  if (upload.error) throw upload.error
  const pending = { id, outingId, mimeType: photo.type, byteLength: photo.size }
  writePending(ownerId, [...readPending(ownerId), pending])
  return pending
}

/** Step two, safe to repeat: records an uploaded photo against its outing. A row that already exists
 *  (an earlier attempt whose reply was lost) counts as saved. Throws `KeepsakeSaveError`. */
export async function finishKeepsake(ownerId: string, pending: PendingKeepsake): Promise<OutingKeepsake> {
  if (!isUuid(ownerId) || !isUuid(pending.id) || !isUuid(pending.outingId)) throw new Error('Invalid id')
  const client = connection()
  const { data, error } = await client.from('outing_keepsakes')
    .insert({ id: pending.id, user_id: ownerId, outing_id: pending.outingId, object_key: keepsakeObjectKey(ownerId, pending.id), mime_type: pending.mimeType, byte_length: pending.byteLength })
    .select().single()
  if (!error) {
    const saved = parseKeepsakes([data])[0]
    if (!saved) throw new KeepsakeSaveError('The photo could not be saved.', pending)
    discardPendingKeepsake(ownerId, pending.id)
    return saved
  }
  const code = errorCode(error)
  if (code === '23505') {
    const existing = await fetchKeepsake(client, pending.id)
    if (existing) { discardPendingKeepsake(ownerId, pending.id); return existing }
  }
  const message = errorText(error, "The photo uploaded but couldn't be saved to this trip.")
  if (FINAL_INSERT_CODES.includes(code)) {
    discardPendingKeepsake(ownerId, pending.id)
    throw new KeepsakeSaveError(message, null)
  }
  throw new KeepsakeSaveError(message, pending)
}

/** Uploads the (already prepared) photo, then records it against the outing. When only the second
 *  step fails, the thrown `KeepsakeSaveError` carries the pending photo to finish later. */
export async function addKeepsake(ownerId: string, outingId: string, photo: Blob): Promise<OutingKeepsake> {
  return finishKeepsake(ownerId, await uploadKeepsake(ownerId, outingId, photo))
}

/** Puts a removed row back (same id, same object), used to undo a replace that could not finish. */
async function restoreKeepsake(ownerId: string, old: OutingKeepsake): Promise<void> {
  const { error } = await connection().from('outing_keepsakes')
    .insert({ id: old.id, user_id: ownerId, outing_id: old.outingId, object_key: keepsakeObjectKey(ownerId, old.id), mime_type: old.mimeType, byte_length: old.byteLength })
  if (error) throw error
}

export interface KeepsakeReplacement {
  saved: OutingKeepsake
  /** False when the new photo is saved but the old row could not be removed yet. */
  oldRemoved: boolean
}

/**
 * Swaps `old` for a new photo without ever leaving the trip with neither. The new bytes upload first,
 * so a failed upload changes nothing. With room for a ninth row the new row is added, then the old one
 * removed (if that last step fails both stay, reported by `oldRemoved`). A full album cannot hold both
 * rows, so the old row is removed first and put back, with the same id and object, if the new row
 * cannot be saved.
 */
export async function replaceKeepsake(ownerId: string, old: OutingKeepsake, photo: Blob, count: number): Promise<KeepsakeReplacement> {
  const pending = await uploadKeepsake(ownerId, old.outingId, photo)
  if (canAddKeepsake(count)) {
    const saved = await finishKeepsake(ownerId, pending)
    try {
      await removeKeepsake(ownerId, old.id)
      return { saved, oldRemoved: true }
    } catch {
      return { saved, oldRemoved: false }
    }
  }
  try {
    await removeKeepsake(ownerId, old.id)
  } catch (cause) {
    discardPendingKeepsake(ownerId, pending.id)
    throw cause
  }
  try {
    return { saved: await finishKeepsake(ownerId, pending), oldRemoved: true }
  } catch (cause) {
    try {
      await restoreKeepsake(ownerId, old)
      discardPendingKeepsake(ownerId, pending.id)
    } catch {
      // The old row stays removed; its object lasts about a day, and the new photo can still be finished.
      if (cause instanceof KeepsakeSaveError) cause.originalRemoved = true
    }
    throw cause
  }
}

/** Deletes the row, which makes the bytes unreadable at once; cleanup removes them later. */
export async function removeKeepsake(ownerId: string, id: string): Promise<void> {
  if (!isUuid(ownerId) || !isUuid(id)) throw new Error('Invalid id')
  const { error } = await connection().from('outing_keepsakes').delete().eq('id', id).eq('user_id', ownerId)
  if (error) throw error
}

/** A short-lived link to the private object, valid for ten minutes. */
export async function keepsakeUrl(ownerId: string, keepsake: OutingKeepsake): Promise<string> {
  if (!isUuid(ownerId) || !isUuid(keepsake.id)) throw new Error('Invalid id')
  const { data, error } = await connection().storage.from(BUCKET).createSignedUrl(keepsakeObjectKey(ownerId, keepsake.id), 600)
  if (error) throw error
  return data.signedUrl
}
