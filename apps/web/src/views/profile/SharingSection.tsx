import { useState, useEffect } from 'react'
import { Key, Plus, Trash2, Copy, Check, Loader2, Settings2 } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { ShareScopeEditor } from 'src/components/ShareScopeEditor'
import { fmtDateShort } from 'src/lib/utils'
import { useCopyFeedback } from 'src/lib/useCopyFeedback'
import { createSharedKey, revokeSharedKey, listSharedKeys } from 'src/lib/auth'
import { Section } from 'src/components/ui/section'
import { LoadingRow, EmptyRow } from 'src/components/ui/loading-row'
import { Eyebrow } from 'src/components/ui/typography'

interface SharedKey {
  id: string
  token: string
  label?: string
  expires_at?: string
  is_active: boolean
  created_at: string
  last_used_at?: string
}

const MAX_ACTIVE_SHARED_KEYS = 10

const EXPIRY_OPTIONS: { label: string; hours: number | null }[] = [
  { label: 'Never expires', hours: null },
  { label: '24 hours', hours: 24 },
  { label: '7 days', hours: 24 * 7 },
  { label: '30 days', hours: 24 * 30 },
]

export function SharingSection() {
  const [sharedKeys, setSharedKeys] = useState<SharedKey[]>([])
  const [loading, setLoading] = useState(false)
  const [newLabel, setNewLabel] = useState('')
  const [expiryHours, setExpiryHours] = useState<string>('')
  const [generating, setGenerating] = useState(false)
  const [editingScopeFor, setEditingScopeFor] = useState<SharedKey | null>(null)
  const { copiedId: copiedKeyId, copy: copyLink } = useCopyFeedback()
  const [showRevoked, setShowRevoked] = useState(false)

  useEffect(() => {
    void loadKeys()
  }, [])

  async function loadKeys() {
    setLoading(true)
    try {
      const keys = await listSharedKeys()
      setSharedKeys(keys || [])
    } catch (err) {
      console.error('Failed to load shared keys:', err)
    } finally {
      setLoading(false)
    }
  }

  async function handleGenerate(e: React.FormEvent) {
    e.preventDefault()
    if (activeKeys.length >= MAX_ACTIVE_SHARED_KEYS) return
    setGenerating(true)
    try {
      const hours = expiryHours ? Number(expiryHours) : null
      const expiresAt = hours ? new Date(Date.now() + hours * 60 * 60 * 1000) : undefined
      await createSharedKey(newLabel.trim() || undefined, expiresAt)
      setNewLabel('')
      setExpiryHours('')
      await loadKeys()
    } catch (err) {
      console.error('Failed to generate shared key:', err)
    } finally {
      setGenerating(false)
    }
  }

  async function handleRevoke(id: string) {
    if (!confirm('Are you sure you want to revoke this key? Anyone using this link will immediately lose access.')) return
    try {
      await revokeSharedKey(id)
      await loadKeys()
    } catch (err) {
      console.error('Failed to revoke key:', err)
    }
  }

  function handleCopyLink(token: string, keyId: string) {
    const link = `${window.location.origin}${window.location.pathname}?share=${token}`
    copyLink(link, keyId)
  }

  const activeKeys = sharedKeys.filter((k) => k.is_active)
  const revokedKeys = sharedKeys.filter((k) => !k.is_active)
  const atCap = activeKeys.length >= MAX_ACTIVE_SHARED_KEYS

  return (
    <Section
      id="sharing"
      title="Shared Access Links"
      Icon={Key}
      description="Generate read-only links to share your movie diary. People using these links see a live poster wall of your archive, but cannot edit."
    >
      <form onSubmit={handleGenerate} className="flex flex-wrap gap-2 max-w-md">
        <Input
          aria-label="Label for new shared link"
          placeholder="Label (e.g. My Website, Friends)"
          value={newLabel}
          onChange={(e) => setNewLabel(e.target.value)}
          className="bg-secondary/50 border-border text-xs flex-1 min-w-[140px]"
        />
        <select
          aria-label="Link expiry"
          value={expiryHours}
          onChange={(e) => setExpiryHours(e.target.value)}
          className="bg-secondary/50 border border-border rounded-md text-xs text-paper px-2"
        >
          {EXPIRY_OPTIONS.map((o) => (
            <option key={o.label} value={o.hours ?? ''}>{o.label}</option>
          ))}
        </select>
        <Button
          type="submit"
          disabled={generating || atCap}
          title={atCap ? `Limit of ${MAX_ACTIVE_SHARED_KEYS} active links reached — revoke one to create another.` : undefined}
          className="bg-amber hover:bg-amber-muted text-(--on-amber) shrink-0"
          aria-label="Generate shared link"
        >
          {generating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
        </Button>
      </form>
      {atCap && (
        <p className="font-sans text-[11px] text-muted-foreground">
          Limit of {MAX_ACTIVE_SHARED_KEYS} active links reached — revoke one below to create another.
        </p>
      )}

      <div className="space-y-2">
        {loading ? (
          <LoadingRow label="Loading links..." />
        ) : activeKeys.length === 0 ? (
          <EmptyRow label="No active sharing keys generated." />
        ) : (
          activeKeys.map((k) => (
            <div key={k.id} className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="font-sans text-xs text-paper font-medium truncate">{k.label || 'Unnamed Link'}</p>
                <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                  Created: {fmtDateShort(k.created_at)}
                  {k.expires_at && ` · Expires ${fmtDateShort(k.expires_at)}`}
                </p>
                {k.last_used_at && (
                  <p className="font-mono text-[9px] text-amber-muted mt-0.5">
                    Last used: {fmtDateShort(k.last_used_at)}
                  </p>
                )}
              </div>
              <div className="flex gap-1 shrink-0">
                <Button
                  size="sm"
                  onClick={() => handleCopyLink(k.token, k.id)}
                  className="bg-secondary hover:bg-secondary-muted text-muted-foreground hover:text-foreground w-7 h-7 p-0 flex items-center justify-center"
                  title="Copy Sharing Link"
                  aria-label="Copy Sharing Link"
                >
                  {copiedKeyId === k.id ? <Check className="w-3.5 h-3.5 text-amber" /> : <Copy className="w-3.5 h-3.5" />}
                </Button>
                <Button
                  size="sm"
                  onClick={() => setEditingScopeFor(k)}
                  className="bg-secondary hover:bg-amber/20 hover:text-amber text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                  title="Edit access"
                  aria-label="Edit access"
                >
                  <Settings2 className="w-3.5 h-3.5" />
                </Button>
                <Button
                  size="sm"
                  onClick={() => handleRevoke(k.id)}
                  className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                  title="Revoke Link"
                  aria-label="Revoke Link"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </Button>
              </div>
            </div>
          ))
        )}
      </div>

      {revokedKeys.length > 0 && (
        <div className="pt-1">
          <button
            type="button"
            onClick={() => setShowRevoked((v) => !v)}
            className="text-muted-foreground hover:text-paper transition-colors rounded-sm focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
          >
            <Eyebrow size="lg" tone="inherit" font="sans">
              {showRevoked ? 'Hide' : 'Show'} revoked links ({revokedKeys.length})
            </Eyebrow>
          </button>
          {showRevoked && (
            <div className="space-y-2 mt-2">
              {revokedKeys.map((k) => (
                <div key={k.id} className="bg-secondary/10 rounded-lg p-3 border border-border/50 flex items-center justify-between gap-3 opacity-60">
                  <div className="min-w-0">
                    <p className="font-sans text-xs text-paper font-medium truncate">{k.label || 'Unnamed Link'}</p>
                    <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                      Created: {fmtDateShort(k.created_at)} · Revoked
                    </p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {editingScopeFor && (
        <ShareScopeEditor
          target={{ sharedKeyId: editingScopeFor.id }}
          label={editingScopeFor.label || 'Unnamed Link'}
          onClose={() => setEditingScopeFor(null)}
        />
      )}
    </Section>
  )
}
