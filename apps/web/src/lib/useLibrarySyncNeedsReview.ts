import { useAppStore } from 'src/store/useAppStore'

/** True when this device holds library work or recovery data the owner should review in LibrarySyncDialog. */
export function useLibrarySyncNeedsReview(): boolean {
  return useAppStore((s) => !s.isSharedView && (s.offlineStatus.commands.length > 0 || s.offlineStatus.quarantined.length > 0
    || !!s.offlineStorageError || !!s.offlineSyncError || s.legacyCacheAvailable))
}
