import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { User } from '@supabase/supabase-js'
import { VenueNoteEditor } from './VenueNoteEditor'
import { useAppStore } from '../store/useAppStore'
import { deferred } from '../lib/offline/fixtures.test-support'
import { captureVenueDraft, venueChange } from '../lib/venueNotes'
import { createCommand } from '../lib/offline/commands'
import type { VenueNote } from '../lib/moviegoingPreferences'

const initial = useAppStore.getState()
const user = { id: '10000000-0000-4000-8000-000000000001' } as User
const note: VenueNote = { id: '20000000-0000-4000-8000-000000000001', userId: user.id, venue: 'Cinema', notes: 'Old parking', createdAt: '2026-10-08T00:00:00Z', updatedAt: '2026-10-08T00:00:00Z' }
const opening = () => ({ ...captureVenueDraft(useAppStore.getState(), [], user.id, note.venue), session: 1 })
beforeEach(() => useAppStore.setState({ user, librarySession: 1, venueNotes: [note], isSharedView: false,
  viewerContext: { kind: 'owner' }, moviegoingPreferencesSupport: 'authoritative', openVenueNote: vi.fn(opening),
  offlineStatus: { ownerId: user.id, hydrated: true, commands: [], quarantined: [] } }))
afterEach(() => { cleanup(); useAppStore.setState(initial, true) })

it('keeps the editor and exact text after failed durability without suggesting a server comparison', async () => {
  const saved = deferred<void>()
  useAppStore.setState({ saveVenueNote: vi.fn(() => saved.promise) })
  render(<VenueNoteEditor venue=" Cinema " />)
  fireEvent.click(screen.getByRole('button', { name: 'Edit venue note' }))
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'My unsaved parking note' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save venue note' }))
  expect(screen.getByRole('textbox')).toBeDisabled()
  await act(async () => { saved.reject(new Error('Device storage is full')); await saved.promise.catch(() => {}) })
  expect(screen.getByRole('textbox')).toHaveValue('My unsaved parking note')
  expect(screen.getByRole('alert')).toHaveTextContent('Device storage is full')
  expect(screen.queryByRole('button', { name: /compare the latest/ })).not.toBeInTheDocument()
  expect(useAppStore.getState().venueNotes).toEqual([note])
})

it('retains the opening proof through updates and recaptures only after explicit comparison', async () => {
  const save = vi.fn().mockRejectedValueOnce(new Error('This venue note changed while the editor was open.')).mockResolvedValue(undefined)
  useAppStore.setState({ saveVenueNote: save })
  render(<VenueNoteEditor venue={note.venue} />)
  fireEvent.click(screen.getByRole('button', { name: 'Edit venue note' }))
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'My draft' } })
  const latest = { ...note, notes: 'New parking', updatedAt: '2026-10-09T00:00:00Z' }
  act(() => useAppStore.setState({ venueNotes: [latest] }))
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Save venue note' })))
  expect(save.mock.calls[0]).toEqual([{ ...captureVenueDraft({ venueNotes: [note] }, [], user.id, note.venue), session: 1 }, 'My draft'])
  expect(screen.getByRole('textbox')).toHaveValue('My draft')
  expect(useAppStore.getState().openVenueNote).toHaveBeenCalledTimes(1)
  fireEvent.click(screen.getByRole('button', { name: 'Keep my draft and compare the latest note' }))
  expect(screen.getByText('Latest saved note: New parking')).toBeInTheDocument()
  expect(screen.getByText('Your unsaved draft: My draft')).toBeInTheDocument()
  expect(save).toHaveBeenCalledTimes(1)
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Save my draft instead' })))
  expect(save.mock.calls[1][0].baseline).toEqual(latest)
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
})

it('distinguishes empty saves from explicit removal and keeps unsupported cached notes visible', async () => {
  const save = vi.fn().mockResolvedValue(undefined)
  useAppStore.setState({ saveVenueNote: save })
  render(<VenueNoteEditor venue={note.venue} />)
  fireEvent.click(screen.getByRole('button', { name: 'Edit venue note' }))
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '' } })
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Save venue note' })))
  expect(save.mock.calls[0][1]).toBe('')
  fireEvent.click(screen.getByRole('button', { name: 'Edit venue note' }))
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Remove venue note' })))
  expect(save.mock.calls[1][1]).toBeNull()
  act(() => useAppStore.setState({ moviegoingPreferencesSupport: 'unsupported' }))
  expect(screen.getByText('Old parking')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Edit venue note' })).toBeDisabled()
})

it('hides private notes in friend and anonymous views and drops drafts on owner session changes', () => {
  render(<VenueNoteEditor venue={note.venue} />)
  fireEvent.click(screen.getByRole('button', { name: 'Edit venue note' }))
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Private draft' } })
  act(() => useAppStore.setState({ librarySession: 2, venueNotes: [] }))
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  act(() => useAppStore.setState({ viewerContext: { kind: 'friend', userId: 'friend', displayName: 'Friend' } }))
  expect(screen.queryByRole('region')).not.toBeInTheDocument()
  act(() => useAppStore.setState({ viewerContext: { kind: 'owner' }, user: null }))
  expect(screen.queryByText('Private venue notes')).not.toBeInTheDocument()
})

it('offers comparison only after definite rejection and keeps the review visible on failed resolution', async () => {
  const command = createCommand({ projectId: 'https://owner.test', userId: user.id }, venueChange(captureVenueDraft({ venueNotes: [note] }, [], user.id, note.venue), 'Draft'))
  useAppStore.setState({ offlineStatus: { ownerId: user.id, hydrated: true, commands: [command], quarantined: [] } })
  render(<VenueNoteEditor venue={note.venue} />)
  expect(screen.queryByRole('button', { name: 'Compare server note' })).not.toBeInTheDocument()
  const rejected = { ...command, state: 'conflict' as const, venueRejection: true as const }
  const review = { commands: [rejected], current: note, notes: 'Draft' }
  const resolve = vi.fn().mockRejectedValue(new Error('Device storage is full'))
  act(() => useAppStore.setState({ reviewVenueNote: vi.fn().mockResolvedValue(review), resolveVenueNote: resolve,
    offlineStatus: { ownerId: user.id, hydrated: true, commands: [rejected], quarantined: [] } }))
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Compare server note' })))
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Use my saved change' })))
  expect(resolve).toHaveBeenCalledWith(review, true)
  expect(screen.getByText('Your latest saved change: Draft')).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('Device storage is full')
})
