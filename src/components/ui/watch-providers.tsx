import { useState } from 'react'
import { Link2, Pencil, Check, X, Home, Disc3, Plus, Trash2 } from 'lucide-react'
import type { WatchProviders } from 'src/lib/media'
import { PHYSICAL_MEDIA_FORMATS, type PhysicalMediaFormat, type PhysicalMediaItem } from 'src/store/mockData'
import { SubsectionLabel } from 'src/components/ui/typography'

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

// ─── Home Collection (KP-002) — owned-locally source row ────────────────────

function HomeCollectionRow() {
  return (
    <div className="flex items-center gap-2.5 flex-wrap">
      <span
        className="font-mono shrink-0"
        style={{ width: '40px', color: 'var(--paper-faint)', fontSize: '10px' }}
      >
        Owned
      </span>
      <span
        className="inline-flex items-center gap-1.5 h-8 rounded-md px-2.5 font-sans text-xs text-amber shrink-0"
        style={{ background: 'rgba(233,178,102,0.08)', border: '1px solid rgba(233,178,102,0.3)' }}
        title="This title is part of your home collection"
      >
        <Home className="w-3.5 h-3.5" />
        Home Collection
      </span>
    </div>
  )
}

// ─── Physical media shelf (KP-003) — cataloged physical copies ──────────────

function PhysicalMediaShelf({
  items,
  isSharedView,
  onChange,
}: {
  items: PhysicalMediaItem[]
  isSharedView: boolean
  onChange: (items: PhysicalMediaItem[]) => void
}) {
  const [adding, setAdding] = useState(false)
  const [draftFormat, setDraftFormat] = useState<PhysicalMediaFormat>('Blu-ray')
  const [draftEdition, setDraftEdition] = useState('')

  function commitAdd() {
    onChange([
      ...items,
      {
        id: crypto.randomUUID(),
        format: draftFormat,
        edition: draftEdition.trim() || undefined,
      },
    ])
    setDraftEdition('')
    setAdding(false)
  }

  if (isSharedView && items.length === 0) return null

  return (
    <div className="flex items-start gap-2.5 flex-wrap">
      <span
        className="font-mono shrink-0 leading-8"
        style={{ width: '40px', color: 'var(--paper-faint)', fontSize: '10px' }}
      >
        Shelf
      </span>
      <div className="flex-1 min-w-0 flex items-center gap-1.5 flex-wrap">
        {items.map((item) => (
          <span
            key={item.id}
            className="inline-flex items-center gap-1.5 h-8 rounded-md px-2.5 font-sans text-xs shrink-0"
            style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }}
            title={item.notes}
          >
            <Disc3 className="w-3.5 h-3.5" style={{ color: 'var(--paper-faint)' }} />
            {item.format}
            {item.edition && (
              <span style={{ color: 'var(--paper-faint)' }}>· {item.edition}</span>
            )}
            {!isSharedView && (
              <button
                onClick={() => onChange(items.filter((i) => i.id !== item.id))}
                aria-label={`Remove ${item.format}${item.edition ? ` (${item.edition})` : ''} from shelf`}
                className="ml-0.5 text-paper-faint hover:text-ember transition-colors"
              >
                <Trash2 className="w-3 h-3" />
              </button>
            )}
          </span>
        ))}

        {!isSharedView && !adding && (
          <button
            onClick={() => setAdding(true)}
            className="inline-flex items-center gap-1 h-8 rounded-md px-2.5 font-mono text-[11px] text-amber/60 hover:text-amber border border-dashed transition-colors"
            style={{ borderColor: 'var(--line)' }}
          >
            <Plus className="w-3 h-3" />
            {items.length === 0 ? 'catalog a copy' : 'add'}
          </button>
        )}

        {!isSharedView && adding && (
          <span className="inline-flex items-center gap-1.5 flex-wrap">
            <select
              autoFocus
              value={draftFormat}
              onChange={(e) => setDraftFormat(e.target.value as PhysicalMediaFormat)}
              aria-label="Physical media format"
              className="h-8 rounded-md px-2 font-sans text-xs focus:outline-none focus:ring-1 focus:ring-amber/40"
              style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }}
            >
              {PHYSICAL_MEDIA_FORMATS.map((f) => (
                <option key={f} value={f}>{f}</option>
              ))}
            </select>
            <input
              value={draftEdition}
              onChange={(e) => setDraftEdition(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') commitAdd()
                if (e.key === 'Escape') setAdding(false)
              }}
              placeholder="Edition (optional)"
              aria-label="Edition or packaging details"
              className="h-8 w-36 rounded-md px-2 font-sans text-xs focus:outline-none focus:ring-1 focus:ring-amber/40"
              style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }}
            />
            <button onClick={commitAdd} aria-label="Add physical copy" className="text-amber hover:text-amber-bright transition-colors">
              <Check className="w-4 h-4" />
            </button>
            <button onClick={() => setAdding(false)} aria-label="Cancel" className="text-paper-faint hover:text-paper transition-colors">
              <X className="w-4 h-4" />
            </button>
          </span>
        )}
      </div>
    </div>
  )
}

interface WatchProvidersSectionProps {
  providers: WatchProviders | null
  customUrl?: string
  inHomeCollection?: boolean
  physicalMedia?: PhysicalMediaItem[]
  isSharedView: boolean
  onSaveCustomUrl: (url: string | undefined) => void
  onToggleHomeCollection: (value: boolean) => void
  onChangePhysicalMedia: (items: PhysicalMediaItem[] | undefined) => void
}

export function WatchProvidersSection({
  providers,
  customUrl,
  inHomeCollection = false,
  physicalMedia = [],
  isSharedView,
  onSaveCustomUrl,
  onToggleHomeCollection,
  onChangePhysicalMedia,
}: WatchProvidersSectionProps) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(customUrl ?? '')

  const streaming = [...(providers?.flatrate ?? []), ...(providers?.free ?? []), ...(providers?.ads ?? [])]
  const rent = providers?.rent ?? []
  const buy = providers?.buy ?? []
  const hasTmdbData = streaming.length > 0 || rent.length > 0 || buy.length > 0
  const hasHomeData = inHomeCollection || physicalMedia.length > 0

  function commit() {
    const trimmed = draft.trim()
    onSaveCustomUrl(trimmed || undefined)
    setEditing(false)
  }

  // Shared view with an owner-set override: show just that link (plus the
  // owner's home-collection sources, if any) — no TMDB noise.
  if (isSharedView && customUrl) {
    let hostname = customUrl
    try {
      hostname = new URL(customUrl).hostname.replace(/^www\./, '')
    } catch {
      // not a valid URL — fall back to showing the raw string
    }
    return (
      <div>
        <SubsectionLabel>Where to Watch</SubsectionLabel>
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
        {hasHomeData && (
          <div className="space-y-2 mt-3">
            {inHomeCollection && <HomeCollectionRow />}
            <PhysicalMediaShelf items={physicalMedia} isSharedView onChange={() => {}} />
          </div>
        )}
      </div>
    )
  }

  if (isSharedView && !hasTmdbData && !hasHomeData) return null

  return (
    <div>
      <div className="flex items-center gap-2 mb-4 flex-wrap">
        <SubsectionLabel className="mb-0">Where to Watch</SubsectionLabel>
        {!isSharedView && !editing && (
          <button
            onClick={() => { setDraft(customUrl ?? ''); setEditing(true) }}
            className="text-xs font-mono text-amber/50 hover:text-amber transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm px-1 flex items-center gap-1"
          >
            <Pencil className="w-2.5 h-2.5" />
            {customUrl ? 'edit link for friends' : 'set link for friends'}
          </button>
        )}
        {!isSharedView && (
          <label className="text-xs font-mono transition-colors rounded-sm px-1 flex items-center gap-1.5 cursor-pointer select-none text-amber/50 hover:text-amber has-[:checked]:text-amber">
            <input
              type="checkbox"
              checked={inHomeCollection}
              onChange={(e) => onToggleHomeCollection(e.target.checked)}
              className="sr-only"
            />
            {inHomeCollection ? (
              <Check className="w-3 h-3" aria-hidden />
            ) : (
              <Home className="w-3 h-3" aria-hidden />
            )}
            in my home collection
          </label>
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

      <div className="space-y-2">
        {hasTmdbData && (
          <>
            <ProviderRow label="Stream" providers={streaming} />
            <ProviderRow label="Rent" providers={rent} />
            <ProviderRow label="Buy" providers={buy} />
          </>
        )}
        {inHomeCollection && <HomeCollectionRow />}
        {(inHomeCollection || physicalMedia.length > 0) && (
          <PhysicalMediaShelf
            items={physicalMedia}
            isSharedView={isSharedView}
            onChange={(items) => onChangePhysicalMedia(items.length > 0 ? items : undefined)}
          />
        )}
        {hasTmdbData && providers?.link && (
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
    </div>
  )
}
