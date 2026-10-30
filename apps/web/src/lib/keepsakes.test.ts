import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  addKeepsake, canAddKeepsake, isValidKeepsake, keepsakeObjectKey, keepsakeUrl, listKeepsakes, MAX_KEEPSAKE_BYTES, parseKeepsakes,
  removeKeepsake, scaledKeepsakeSize,
} from './keepsakes'

const owner = '10000000-0000-4000-8000-000000000001'
const outing = '30000000-0000-4000-8000-000000000001'
const photoId = '40000000-0000-4000-8000-000000000001'

const { client } = vi.hoisted(() => ({ client: { from: vi.fn(), storage: { from: vi.fn() } } }))
vi.mock('./auth', () => ({ isSupabaseConfigured: true, supabase: client }))

const row = (id = photoId, overrides: Record<string, unknown> = {}) => ({
  id, user_id: owner, outing_id: outing, object_key: `${owner}/${id}`, mime_type: 'image/jpeg', byte_length: 4,
  created_at: '2026-10-30T10:00:00Z', ...overrides,
})
const photo = (size = 4, type = 'image/jpeg') => new Blob([new Uint8Array(size)], { type })

// The same cases as Android's KeepsakeRulesTest and KeepsakeRepositoryTest.
describe('keepsake rules', () => {
  it('accepts only image types inside the size limit', () => {
    expect(isValidKeepsake('image/jpeg', 1)).toBe(true)
    expect(isValidKeepsake('image/webp', MAX_KEEPSAKE_BYTES)).toBe(true)
    expect(isValidKeepsake('image/jpeg', 0)).toBe(false)
    expect(isValidKeepsake('image/jpeg', MAX_KEEPSAKE_BYTES + 1)).toBe(false)
    expect(isValidKeepsake('image/gif', 10)).toBe(false)
  })

  it('keeps at most eight photos per outing and names objects owner folder then id', () => {
    expect(canAddKeepsake(7)).toBe(true)
    expect(canAddKeepsake(8)).toBe(false)
    expect(keepsakeObjectKey('owner', 'id')).toBe('owner/id')
  })

  it('downscales the longest edge, keeping the aspect ratio', () => {
    expect(scaledKeepsakeSize(1000, 800)).toEqual([1000, 800])
    expect(scaledKeepsakeSize(4096, 3072)).toEqual([2048, 1536])
    expect(scaledKeepsakeSize(3000, 4000)).toEqual([1536, 2048])
    expect(scaledKeepsakeSize(100_000, 1)).toEqual([2048, 1])
    expect(() => scaledKeepsakeSize(0, 10)).toThrow('Invalid image size')
  })

  it('parsing skips rows with an unexpected shape', () => {
    const rows = [row(), { id: 'x' }, row(photoId, { mime_type: 'text/html' }), null, row(photoId, { byte_length: 0 })]
    expect(parseKeepsakes(rows).map((k) => k.id)).toEqual([photoId])
    expect(parseKeepsakes({ not: 'a list' })).toEqual([])
  })
})

describe('keepsake requests', () => {
  const upload = vi.fn()
  const insert = vi.fn()
  const select = vi.fn()
  const eq = vi.fn()
  const order = vi.fn()
  const remove = vi.fn()
  const signed = vi.fn()

  beforeEach(() => {
    for (const mock of [upload, insert, select, eq, order, remove, signed, client.from, client.storage.from]) mock.mockReset()
    client.storage.from.mockReturnValue({ upload, createSignedUrl: signed })
    upload.mockResolvedValue({ error: null })
  })

  it('lists an outing\'s photos oldest first', async () => {
    order.mockResolvedValue({ data: [row()], error: null })
    eq.mockReturnValue({ order })
    select.mockReturnValue({ eq })
    client.from.mockReturnValue({ select })
    expect((await listKeepsakes(outing)).map((k) => k.id)).toEqual([photoId])
    expect(client.from).toHaveBeenCalledWith('outing_keepsakes')
    expect(eq).toHaveBeenCalledWith('outing_id', outing)
    expect(order).toHaveBeenCalledWith('created_at', { ascending: true })
    await expect(listKeepsakes('1&user_id=neq.x')).rejects.toThrow('Invalid outing')
  })

  it('uploads under the owner\'s folder without overwriting, then records the row', async () => {
    const single = vi.fn().mockResolvedValue({ data: row(), error: null })
    insert.mockReturnValue({ select: () => ({ single }) })
    client.from.mockReturnValue({ insert })
    const saved = await addKeepsake(owner, outing, photo())
    const [key, blob, options] = upload.mock.calls[0]
    expect(client.storage.from).toHaveBeenCalledWith('outing-keepsakes')
    expect(key).toMatch(new RegExp(`^${owner}/[0-9a-f-]{36}$`))
    expect(blob.size).toBe(4)
    expect(options).toEqual({ contentType: 'image/jpeg', upsert: false })
    const inserted = insert.mock.calls[0][0]
    expect(inserted).toMatchObject({ user_id: owner, outing_id: outing, object_key: key, mime_type: 'image/jpeg', byte_length: 4 })
    expect(key).toBe(`${owner}/${inserted.id}`)
    expect(saved.id).toBe(photoId)
  })

  it('a failed upload never inserts a row', async () => {
    upload.mockResolvedValue({ error: new Error('storage down') })
    await expect(addKeepsake(owner, outing, photo())).rejects.toThrow('storage down')
    expect(client.from).not.toHaveBeenCalled()
  })

  it('refuses unsupported or oversized photos and malformed ids before any request', async () => {
    await expect(addKeepsake(owner, outing, photo(4, 'text/html'))).rejects.toThrow("can't be kept")
    await expect(addKeepsake(owner, outing, photo(0))).rejects.toThrow("can't be kept")
    await expect(addKeepsake('not-a-uuid', outing, photo())).rejects.toThrow('Invalid id')
    await expect(removeKeepsake(owner, 'not-a-uuid')).rejects.toThrow('Invalid id')
    expect(upload).not.toHaveBeenCalled()
    expect(client.from).not.toHaveBeenCalled()
  })

  it('removes only the owner\'s row', async () => {
    const second = vi.fn().mockResolvedValue({ error: null })
    const first = vi.fn().mockReturnValue({ eq: second })
    remove.mockReturnValue({ eq: first })
    client.from.mockReturnValue({ delete: remove })
    await removeKeepsake(owner, photoId)
    expect(first).toHaveBeenCalledWith('id', photoId)
    expect(second).toHaveBeenCalledWith('user_id', owner)
  })

  it('asks for a short-lived signed link to the private object', async () => {
    signed.mockResolvedValue({ data: { signedUrl: 'https://signed.example/photo' }, error: null })
    const [keepsake] = parseKeepsakes([row()])
    expect(await keepsakeUrl(owner, keepsake)).toBe('https://signed.example/photo')
    expect(signed).toHaveBeenCalledWith(`${owner}/${photoId}`, 600)
  })
})
