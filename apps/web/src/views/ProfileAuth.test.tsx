import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { User } from '@supabase/supabase-js'
import { ProfileModal } from "src/components/ProfileModal"
import { Profile } from './Profile'
import { useAppStore } from 'src/store/useAppStore'
import { getMyProfile, listMyInviteCodes, listSharedKeys, signInWithEmail, type MyProfile } from 'src/lib/auth'

vi.mock('src/lib/auth', async (original) => ({
  ...await original<typeof import('src/lib/auth')>(), isSupabaseConfigured: true,
  getMyProfile: vi.fn(), listMyInviteCodes: vi.fn(), listSharedKeys: vi.fn(), signInWithEmail: vi.fn(),
}))
vi.mock('src/components/SyncConnections', () => ({ SyncConnections: () => null }))
const initial = useAppStore.getState()
beforeEach(() => {
  vi.stubGlobal('__APP_VERSION__', 'test')
  vi.mocked(listMyInviteCodes).mockResolvedValue([])
  vi.mocked(listSharedKeys).mockResolvedValue([])
  useAppStore.setState({ user: null, isSharedView: false, titles: [] })
})
afterEach(() => { cleanup(); useAppStore.setState(initial, true); vi.resetAllMocks(); vi.unstubAllGlobals() })

it('offers the implemented email flow and confirms a link only after sending succeeds', async () => {
  let resolve!: () => void
  vi.mocked(signInWithEmail).mockReturnValue(new Promise((yes) => { resolve = () => yes({ user: null, session: null }) }))
  render(<Profile />)
  expect(screen.queryByRole('button', { name: /passkey/i })).not.toBeInTheDocument()
  fireEvent.change(screen.getByRole('textbox', { name: 'Email address' }), { target: { value: 'viewer@example.test' } })
  fireEvent.click(screen.getByRole('button', { name: 'Send Magic Link' }))
  expect(signInWithEmail).toHaveBeenCalledWith('viewer@example.test')
  expect(screen.queryByText(/Magic link sent!/)).not.toBeInTheDocument()
  await act(async () => resolve())
  expect(screen.getByText(/Magic link sent!/)).toBeInTheDocument()
}, 15000)

it('masks the previous profile and reloads private sections on an account switch', async () => {
  const oldProfile = { user_id: 'owner-a', display_name: 'Private A Name', username: 'private-a' } as MyProfile
  let finish!: (profile: MyProfile) => void
  vi.mocked(getMyProfile).mockResolvedValueOnce(oldProfile).mockReturnValueOnce(new Promise((yes) => { finish = yes }))
  useAppStore.setState({ user: { id: 'owner-a', email: 'a@example.test' } as User })
  render(<Profile />)
  await waitFor(() => expect(screen.getByRole('textbox', { name: 'Display name' })).toHaveValue('Private A Name'))
  act(() => useAppStore.setState({ user: { id: 'owner-b', email: 'b@example.test' } as User }))
  expect(screen.getByRole('textbox', { name: 'Display name' })).toHaveValue('')
  expect(screen.queryByText('Private A Name')).not.toBeInTheDocument()
  expect(getMyProfile).toHaveBeenCalledTimes(2)
  await waitFor(() => expect(listSharedKeys).toHaveBeenCalledTimes(2))
  await act(async () => finish({ user_id: 'owner-b', display_name: 'B Name', username: 'b' } as MyProfile))
  expect(screen.getByRole('textbox', { name: 'Display name' })).toHaveValue('B Name')
})

it("landing sign-in modal offers email links without an unsupported passkey action", async () => {
  vi.mocked(signInWithEmail).mockResolvedValue({ user: null, session: null })
  render(<ProfileModal open onClose={() => undefined} />)
  expect(screen.queryByRole("button", { name: /passkey/i })).not.toBeInTheDocument()
  fireEvent.change(screen.getByRole("textbox", { name: "Email address" }), { target: { value: "viewer@example.test" } })
  fireEvent.click(screen.getByRole("button", { name: "Send Magic Link" }))
  await screen.findByText(/Magic link sent!/)
  expect(signInWithEmail).toHaveBeenCalledWith("viewer@example.test")
})
