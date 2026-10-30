import { isSupabaseConfigured, supabase } from './auth'

// Keepsake photos for cinema outings (#208): a ticket stub, a poster board, a popcorn selfie.
// Owner-private and separate from the single scannable ticket attachment. Mirrors Android's
// KeepsakeRules.kt / KeepsakeRepository.kt; the contract is docs/android-contracts/outing-keepsakes.md.
// Online-only: there is no offline queue, so adding or viewing a photo needs a connection.

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

/** Uploads the (already prepared) photo under the owner's folder, then records it against the outing. */
export async function addKeepsake(ownerId: string, outingId: string, photo: Blob): Promise<OutingKeepsake> {
  if (!isUuid(ownerId) || !isUuid(outingId)) throw new Error('Invalid id')
  if (!isValidKeepsake(photo.type, photo.size)) throw new Error("That photo can't be kept: use a JPEG, PNG or WebP under 10 MB.")
  const client = connection()
  const id = crypto.randomUUID()
  const key = keepsakeObjectKey(ownerId, id)
  const upload = await client.storage.from(BUCKET).upload(key, photo, { contentType: photo.type, upsert: false })
  if (upload.error) throw upload.error
  // A failed insert leaves an unreferenced object; the daily cleanup removes it after a day.
  const { data, error } = await client.from('outing_keepsakes')
    .insert({ id, user_id: ownerId, outing_id: outingId, object_key: key, mime_type: photo.type, byte_length: photo.size })
    .select().single()
  if (error) throw error
  const saved = parseKeepsakes([data])[0]
  if (!saved) throw new Error('The photo could not be saved.')
  return saved
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
