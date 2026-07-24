import { RefreshCw, X } from 'lucide-react'
import { useRegisterSW } from 'virtual:pwa-register/react'

// registerType: 'prompt' (vite.config.ts) leaves a downloaded service worker
// in the "waiting" state instead of silently activating it. Clicking Reload
// calls updateServiceWorker(true), which skips waiting and reloads once the
// new worker is actually in control — a plain page refresh would still be
// served by the old worker.
export function PWAUpdateToast() {
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW()

  if (!needRefresh) return null

  return (
    <div
      role="status"
      className="fixed top-4 inset-x-0 z-[240] flex justify-center px-4 pointer-events-none"
    >
      <div className="pointer-events-auto bg-void rounded-lg p-3 shadow-xl flex items-center gap-3 border border-amber/40 animate-view-in">
        <RefreshCw className="w-4 h-4 text-amber shrink-0" />
        <p className="font-sans text-xs text-paper leading-normal">
          A new version of CinemArchive is available.
        </p>
        <button
          onClick={() => updateServiceWorker(true)}
          className="font-sans text-[10px] uppercase tracking-widest text-amber hover:text-amber/70 transition-opacity shrink-0 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm"
        >
          Reload
        </button>
        <button
          onClick={() => setNeedRefresh(false)}
          className="text-muted-foreground hover:text-foreground shrink-0 transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm"
          aria-label="Dismiss"
        >
          <X className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  )
}
