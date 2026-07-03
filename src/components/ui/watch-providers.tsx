import { useState } from 'react'
import { Link2, Pencil, Check, X } from 'lucide-react'
import type { WatchProviders } from 'src/lib/media'

function ProviderRow({ label, providers }: { label: string; providers: WatchProviders['flatrate'] }) {
  if (providers.length === 0) return null
  return (
    <div className="flex items-center gap-2.5 flex-wrap">
      <span
        className="font-mono shrink-0"
        style={{ width: '40px', color: 'var(--paper-faint)', fontSize: '10px' }}
      >
        {label}
      </span>
      {providers.map((p) => (
        <div
          key={p.providerId}
          className="w-8 h-8 rounded-md overflow-hidden shrink-0"
          style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}
          title={p.name}
        >
          {p.logoUrl ? (
            <img src={p.logoUrl} alt={p.name} className="w-full h-full object-cover" />
          ) : (
            <div className="w-full h-full flex items-center justify-center">
              <span className="font-mono text-[9px]" style={{ color: 'var(--paper-faint)' }}>
                {p.name.charAt(0)}
              </span>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

interface WatchProvidersSectionProps {
  providers: WatchProviders | null
  customUrl?: string
  isSharedView: boolean
  onSaveCustomUrl: (url: string | undefined) => void
}

export function WatchProvidersSection({ providers, customUrl, isSharedView, onSaveCustomUrl }: WatchProvidersSectionProps) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(customUrl ?? '')

  const streaming = [...(providers?.flatrate ?? []), ...(providers?.free ?? []), ...(providers?.ads ?? [])]
  const rent = providers?.rent ?? []
  const buy = providers?.buy ?? []
  const hasTmdbData = streaming.length > 0 || rent.length > 0 || buy.length > 0

  function commit() {
    const trimmed = draft.trim()
    onSaveCustomUrl(trimmed || undefined)
    setEditing(false)
  }

  // Shared view with an owner-set override: show just that link, no TMDB noise.
  if (isSharedView && customUrl) {
    let hostname = customUrl
    try {
      hostname = new URL(customUrl).hostname.replace(/^www\./, '')
    } catch {
      // not a valid URL — fall back to showing the raw string
    }
    return (
      <div>
        <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim mb-4">Where to Watch</h4>
        <a
          href={customUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-2 rounded-md px-3 py-2 font-sans text-sm transition-colors hover:text-amber"
          style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }}
        >
          <Link2 className="w-3.5 h-3.5" />
          Watch via {hostname}
        </a>
      </div>
    )
  }

  if (isSharedView && !hasTmdbData) return null
  if (!isSharedView && !hasTmdbData && !customUrl) return null

  return (
    <div>
      <div className="flex items-center gap-2 mb-4">
        <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim">Where to Watch</h4>
        {!isSharedView && !editing && (
          <button
            onClick={() => { setDraft(customUrl ?? ''); setEditing(true) }}
            className="text-xs font-mono text-amber/50 hover:text-amber transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm px-1 flex items-center gap-1"
          >
            <Pencil className="w-2.5 h-2.5" />
            {customUrl ? 'edit link for friends' : 'set link for friends'}
          </button>
        )}
      </div>

      {!isSharedView && editing && (
        <div className="flex items-center gap-2 mb-3">
          <input
            autoFocus
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') commit()
              if (e.key === 'Escape') setEditing(false)
            }}
            placeholder="https://…"
            aria-label="Custom where-to-watch URL for friends"
            className="flex-1 rounded-md px-2.5 py-1.5 font-mono text-xs focus:outline-none focus:ring-1 focus:ring-amber/40"
            style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }}
          />
          <button onClick={commit} aria-label="Save custom watch link" className="text-amber hover:text-amber-bright transition-colors">
            <Check className="w-4 h-4" />
          </button>
          <button onClick={() => setEditing(false)} aria-label="Cancel" className="text-paper-faint hover:text-paper transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {!isSharedView && customUrl && !editing && (
        <div className="mb-3 font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
          Friends will see:{' '}
          <a href={customUrl} target="_blank" rel="noopener noreferrer" className="hover:text-amber transition-colors underline decoration-dotted">
            {customUrl}
          </a>
        </div>
      )}

      {hasTmdbData && (
        <div className="space-y-2">
          <ProviderRow label="Stream" providers={streaming} />
          <ProviderRow label="Rent" providers={rent} />
          <ProviderRow label="Buy" providers={buy} />
          {providers?.link && (
            <a
              href={providers.link}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-block font-mono mt-1 transition-colors hover:text-amber"
              style={{ fontSize: '9px', color: 'var(--paper-faint)' }}
            >
              Streaming data provided by JustWatch
            </a>
          )}
        </div>
      )}
    </div>
  )
}
