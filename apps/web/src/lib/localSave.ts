/** Store actions surface local failures in the persistent sync status. Forms
 * retain their input and only perform success transitions after this resolves. */
export async function saveSucceeded(operation: Promise<unknown>): Promise<boolean> {
  try { await operation; return true } catch { return false }
}
import { useAppStore } from '../store/useAppStore'

/** Fence slow file/metadata/provider work before it starts a store action. */
export function captureLibrarySession(): () => void {
  const session = useAppStore.getState().librarySession
  return () => {
    const state = useAppStore.getState()
    if (state.librarySession !== session || state.isSharedView) throw new Error('Account or library changed while preparing this save')
  }
}
