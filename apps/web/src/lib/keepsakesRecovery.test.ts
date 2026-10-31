import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  addKeepsake, discardPendingKeepsake, finishKeepsake, KeepsakeSaveError, pendingKeepsakes, replaceKeepsake,
} from './keepsakes'

// Interrupted-upload recovery and replace (#394); the album rules themselves are in keepsakes.test.ts.

const owner = '10000000-0000-4000-8000-000000000001'
const outing = '30000000-0000-4000-8000-000000000001'
const photoId = '40000000-0000-4000-8000-000000000001'
const newId = '40000000-0000-4000-8000-0000000000aa'

const { client } = vi.hoisted(() => ({ client: { from: vi.fn(), storage: { from: vi.fn() } } }))
vi.mock('./auth', () => ({ isSupabaseConfigured: true, supabase: client }))

const row = (id: string) => ({
  id, user_id: owner, outing_id: outing, object_key: `${owner}/${id}`, mime_type: 'image/jpeg', byte_length: 4,
  created_at: '2026-10-30T10:00:00Z',
})
const photo = () => new Blob([new Uint8Array(4)], { type: 'image/jpeg' })
const old = { id: photoId, outingId: outing, mimeType: 'image/jpeg', byteLength: 4, createdAt: '2026-10-30T10:00:00Z' }

const upload = vi.fn()
const insert = vi.fn()
const remove = vi.fn()
const maybeSingle = vi.fn()

beforeEach(() => {
  for (const mock of [upload, insert, remove, maybeSingle, client.from, client.storage.from]) mock.mockReset()
  localStorage.clear()
  vi.spyOn(crypto, 'randomUUID').mockReturnValue(newId)
  upload.mockResolvedValue({ error: null })
  remove.mockResolvedValue({ error: null })
  client.storage.from.mockReturnValue({ upload })
  client.from.mockReturnValue({
    // `insert` answers each call in turn; the chained select().single() reads the same answer.
    insert: (...args: unknown[]) => {
      const result = insert(...args)
      return { select: () => ({ single: () => result }), then: (resolve: (r: unknown) => unknown) => Promise.resolve(result).then(resolve) }
    },
    delete: () => ({ eq: () => ({ eq: () => remove() }) }),
    select: () => ({ eq: () => ({ maybeSingle }) }),
  })
})

describe('interrupted uploads', () => {
  it('remembers an uploaded photo whose row failed to save, and finishes it later with the same id', async () => {
    insert.mockResolvedValueOnce({ data: null, error: { message: 'fetch failed', code: '' } })
    const failure = await addKeepsake(owner, outing, photo()).catch((e) => e)
    expect(failure).toBeInstanceOf(KeepsakeSaveError)
    expect(failure.pending).toMatchObject({ id: newId, outingId: outing, mimeType: 'image/jpeg', byteLength: 4 })
    expect(pendingKeepsakes(owner, outing)).toHaveLength(1)
    expect(pendingKeepsakes(owner, '30000000-0000-4000-8000-0000000000bb')).toEqual([])

    insert.mockResolvedValueOnce({ data: row(newId), error: null })
    const saved = await finishKeepsake(owner, failure.pending)
    expect(saved.id).toBe(newId)
    expect(upload).toHaveBeenCalledTimes(1) // the retry never uploads again
    expect(insert.mock.calls[1][0]).toMatchObject({ id: newId, object_key: `${owner}/${newId}` })
    expect(pendingKeepsakes(owner, outing)).toEqual([])
  })

  it('counts a row that already exists as saved when a lost reply is retried', async () => {
    insert.mockResolvedValueOnce({ data: null, error: { message: 'duplicate key', code: '23505' } })
    maybeSingle.mockResolvedValue({ data: row(newId), error: null })
    const pending = { id: newId, outingId: outing, mimeType: 'image/jpeg', byteLength: 4 }
    expect((await finishKeepsake(owner, pending)).id).toBe(newId)
    expect(pendingKeepsakes(owner, outing)).toEqual([])
  })

  it('gives up on a photo the database can never accept', async () => {
    for (const code of ['22023', '54000', '42501']) {
      insert.mockResolvedValueOnce({ data: null, error: { message: 'refused', code } })
      const failure = await addKeepsake(owner, outing, photo()).catch((e) => e)
      expect(failure).toBeInstanceOf(KeepsakeSaveError)
      expect(failure.pending).toBeNull()
      expect(pendingKeepsakes(owner, outing)).toEqual([])
    }
  })

  it('keeps pending photos per account and forgets a discarded one', async () => {
    insert.mockResolvedValueOnce({ data: null, error: { message: 'offline', code: '' } })
    await addKeepsake(owner, outing, photo()).catch(() => undefined)
    expect(pendingKeepsakes('10000000-0000-4000-8000-0000000000ff', outing)).toEqual([])
    discardPendingKeepsake(owner, newId)
    expect(pendingKeepsakes(owner, outing)).toEqual([])
  })

  it('ignores a damaged pending list', () => {
    localStorage.setItem(`cinemarchive:keepsake-pending:v1:${owner}`, '{broken')
    expect(pendingKeepsakes(owner, outing)).toEqual([])
    localStorage.setItem(`cinemarchive:keepsake-pending:v1:${owner}`, JSON.stringify([{ id: 'x' }, null]))
    expect(pendingKeepsakes(owner, outing)).toEqual([])
  })
})

describe('replace', () => {
  it('with room adds the new photo, then removes the old one', async () => {
    insert.mockResolvedValueOnce({ data: row(newId), error: null })
    const result = await replaceKeepsake(owner, old, photo(), 3)
    expect(result).toMatchObject({ saved: { id: newId }, oldRemoved: true })
    expect(remove).toHaveBeenCalledTimes(1)
    expect(insert.mock.invocationCallOrder[0]).toBeLessThan(remove.mock.invocationCallOrder[0])
  })

  it('never touches the old photo when the upload fails', async () => {
    upload.mockResolvedValue({ error: new Error('storage down') })
    await expect(replaceKeepsake(owner, old, photo(), 3)).rejects.toThrow('storage down')
    expect(insert).not.toHaveBeenCalled()
    expect(remove).not.toHaveBeenCalled()
  })

  it('keeps the old photo when the new row cannot be saved, and lets the new one be finished', async () => {
    insert.mockResolvedValueOnce({ data: null, error: { message: 'offline', code: '' } })
    await expect(replaceKeepsake(owner, old, photo(), 3)).rejects.toBeInstanceOf(KeepsakeSaveError)
    expect(remove).not.toHaveBeenCalled()
    expect(pendingKeepsakes(owner, outing)).toHaveLength(1)
  })

  it('reports both photos when the old one cannot be removed', async () => {
    insert.mockResolvedValueOnce({ data: row(newId), error: null })
    remove.mockResolvedValue({ error: new Error('offline') })
    expect(await replaceKeepsake(owner, old, photo(), 3)).toMatchObject({ saved: { id: newId }, oldRemoved: false })
  })

  it('in a full album removes the old row first, then adds the new one', async () => {
    insert.mockResolvedValueOnce({ data: row(newId), error: null })
    const result = await replaceKeepsake(owner, old, photo(), 8)
    expect(result.oldRemoved).toBe(true)
    expect(remove.mock.invocationCallOrder[0]).toBeLessThan(insert.mock.invocationCallOrder[0])
  })

  it('in a full album puts the old photo back, same id and object, when the new row fails', async () => {
    insert.mockResolvedValueOnce({ data: null, error: { message: 'offline', code: '' } })
      .mockResolvedValueOnce({ data: null, error: null })
    await expect(replaceKeepsake(owner, old, photo(), 8)).rejects.toBeInstanceOf(KeepsakeSaveError)
    expect(insert).toHaveBeenCalledTimes(2)
    expect(insert.mock.calls[1][0]).toEqual({
      id: photoId, user_id: owner, outing_id: outing, object_key: `${owner}/${photoId}`, mime_type: 'image/jpeg', byte_length: 4,
    })
    expect(pendingKeepsakes(owner, outing)).toEqual([]) // the album is whole again, nothing left to finish
  })

  it('in a full album says so when the old photo cannot be put back, and keeps the new one to finish', async () => {
    insert.mockResolvedValueOnce({ data: null, error: { message: 'offline', code: '' } })
      .mockResolvedValueOnce({ data: null, error: { message: 'offline', code: '' } })
    const failure = await replaceKeepsake(owner, old, photo(), 8).catch((e) => e)
    expect(failure).toBeInstanceOf(KeepsakeSaveError)
    expect(failure.originalRemoved).toBe(true)
    expect(pendingKeepsakes(owner, outing)).toHaveLength(1)
  })

  it('in a full album leaves everything alone when the old row cannot be removed', async () => {
    remove.mockResolvedValue({ error: new Error('offline') })
    await expect(replaceKeepsake(owner, old, photo(), 8)).rejects.toThrow('offline')
    expect(insert).not.toHaveBeenCalled()
    expect(pendingKeepsakes(owner, outing)).toEqual([])
  })
})
