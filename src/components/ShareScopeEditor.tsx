import { useEffect, useRef, useState } from 'react'
import { X, Loader2, Check } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { cn } from 'src/lib/utils'
import { useAllGenres } from 'src/store/useAppStore'
import { getShareScope, setShareScope, type ShareScopeTarget } from 'src/lib/auth'
import type { WatchStatus } from 'src/store/mockData'

const STATUS_OPTIONS: { value: WatchStatus; label: string }[] = [
  { value: 'watched', label: 'Watched' },
  { value: 'watchlist', label: 'Watchlist' },
  { value: 'watching', label: 'Watching' },
  { value: 'dropped', label: 'Dropped' },
]

interface ShareScopeEditorProps {
  target: ShareScopeTarget
  label: string
  onClose: () => void
}

export function ShareScopeEditor({ target, label, onClose }: ShareScopeEditorProps) {
  const allGenres = useAllGenres()
  const closeButtonRef = useRef<HTMLButtonElement>(null)

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [mode, setMode] = useState<'full' | 'custom'>('full')
  const [genres, setGenres] = useState<string[]>([])
  const [statuses, setStatuses] = useState<WatchStatus[]>([])

  useEffect(() => {
    let cancelled = false
    getShareScope(target)
      .then((scope) => {
        if (cancelled) return
        if (scope) {
          setMode('custom')
          setGenres(scope.allowed_genres ?? [])
          setStatuses((scope.allowed_statuses ?? []) as WatchStatus[])
        }
      })
      .catch((err) => console.error('Failed to load share scope:', err))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const returnEl = document.activeElement as HTMLElement
    closeButtonRef.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      returnEl?.focus()
    }
  }, [onClose])

  function toggleGenre(g: string) {
    setGenres((prev) => (prev.includes(g) ? prev.filter((x) => x !== g) : [...prev, g]))
  }

  function toggleStatus(s: WatchStatus) {
    setStatuses((prev) => (prev.includes(s) ? prev.filter((x) => x !== s) : [...prev, s]))
  }

  async function handleSave() {
    setSaving(true)
    try {
      if (mode === 'full') {
        await setShareScope(target, null)
      } else {
        // No selections on a dimension = unrestricted on that dimension,
        // rather than forcing a separate per-dimension toggle.
        await setShareScope(target, {
          allowed_genres: genres.length > 0 ? genres : null,
          allowed_statuses: statuses.length > 0 ? statuses : null,
        })
      }
      onClose()
    } catch (err) {
      console.error('Failed to save share scope:', err)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Edit access for ${label}`}
      className="fixed inset-0 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.82)', zIndex: 215 }}
      onClick={onClose}
    >
      <div
        className="relative w-full max-w-sm rounded-xl overflow-hidden flex flex-col"
        style={{ background: 'rgb(var(--ink-1-rgb))', border: '1px solid var(--line)', maxHeight: '85vh' }}
        onClick={(e) => e.stopPropagation()}
      >
        <button
          ref={closeButtonRef}
          onClick={onClose}
          className="absolute top-3 right-3 flex items-center justify-center w-7 h-7 rounded-full transition-colors z-10"
          style={{ color: 'var(--paper-faint)' }}
          aria-label="Close edit access"
        >
          <X className="w-4 h-4" />
        </button>

        <div className="px-5 pt-5 pb-4 shrink-0">
          <div
            className="font-mono uppercase tracking-widest"
            style={{ fontSize: '9px', color: 'var(--paper-faint)', letterSpacing: '0.14em' }}
          >
            Edit access
          </div>
          <div className="font-serif text-base leading-snug mt-0.5 truncate" style={{ color: 'var(--paper)' }}>
            {label}
          </div>
        </div>

        <div className="overflow-y-auto flex-1 px-5 pb-5 space-y-4">
          {loading ? (
            <div className="text-center py-6 text-xs font-mono text-muted-foreground">Loading...</div>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-2" role="tablist" aria-label="Access level">
                {(['full', 'custom'] as const).map((m) => (
                  <button
                    key={m}
                    type="button"
                    role="tab"
                    aria-selected={mode === m}
                    onClick={() => setMode(m)}
                    className={cn(
                      'rounded-lg border py-2.5 font-sans text-sm font-medium transition-colors',
                      mode === m
                        ? 'border-amber/50 bg-amber/10 text-amber'
                        : 'border-border bg-secondary/20 text-muted-foreground hover:border-amber/25'
                    )}
                  >
                    {m === 'full' ? 'Full library' : 'Custom'}
                  </button>
                ))}
              </div>

              {mode === 'custom' && (
                <>
                  <p className="font-sans text-[11px] text-muted-foreground leading-relaxed">
                    Leave a section empty to allow everything in it. Combining selections narrows to titles matching both.
                  </p>

                  {allGenres.length > 0 && (
                    <div>
                      <h4 className="font-mono text-[10px] uppercase tracking-[0.18em] text-paper-faint mb-2">Genres</h4>
                      <div className="flex flex-wrap gap-1.5">
                        {allGenres.map((g) => (
                          <button
                            key={g}
                            type="button"
                            onClick={() => toggleGenre(g)}
                            className={cn('chip', genres.includes(g) && 'is-active')}
                          >
                            {genres.includes(g) && <Check className="w-3 h-3 mr-1 inline" />}
                            {g}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}

                  <div>
                    <h4 className="font-mono text-[10px] uppercase tracking-[0.18em] text-paper-faint mb-2">Watch status</h4>
                    <div className="flex flex-wrap gap-1.5">
                      {STATUS_OPTIONS.map((opt) => (
                        <button
                          key={opt.value}
                          type="button"
                          onClick={() => toggleStatus(opt.value)}
                          className={cn('chip', statuses.includes(opt.value) && 'is-active')}
                        >
                          {statuses.includes(opt.value) && <Check className="w-3 h-3 mr-1 inline" />}
                          {opt.label}
                        </button>
                      ))}
                    </div>
                  </div>
                </>
              )}

              <Button
                onClick={handleSave}
                disabled={saving}
                className="w-full bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
              >
                {saving ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : null}
                Save
              </Button>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
