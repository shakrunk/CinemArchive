import { useState, useEffect, useRef } from 'react'
import {
  Key, Plus, Trash2, Copy, Check, Loader2, type LucideIcon, Download, Upload, Settings2, UserCircle, Sun,
  Pencil, Film, LayoutGrid, Ticket, RefreshCw, Info, ExternalLink,
} from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { ShareScopeEditor } from 'src/components/ShareScopeEditor'
import { useAppStore } from 'src/store/useAppStore'
import { fmtDateShort, getErrorMessage } from 'src/lib/utils'
import { useCopyFeedback } from 'src/lib/useCopyFeedback'
import {
  isSupabaseConfigured,
  createSharedKey,
  revokeSharedKey,
  listSharedKeys,
  getMyProfile,
  createInviteCode,
  listMyInviteCodes,
  deleteInviteCode,
  type MyProfile,
  type InviteCode,
} from 'src/lib/auth'
import { exportLibrary, parseImportFile } from 'src/lib/export-import'
import { parseLetterboxdCsv, letterboxdToSyncItems } from 'src/lib/letterboxd-import'
import { resolveSyncItems } from 'src/lib/sync/core'
import { applySyncOutcome } from 'src/lib/sync/apply'
import { titleToSearchResult, fetchRefreshedTitlePatch } from 'src/lib/refreshMetadata'
import { captureLibrarySession } from 'src/lib/localSave'
import { MessageBanner, type Message } from 'src/components/ui/message-banner'
import { SyncConnections } from 'src/components/SyncConnections'
import { useLibrarySyncNeedsReview } from 'src/lib/useLibrarySyncNeedsReview'
import { Section } from 'src/components/ui/section'
import { LoadingRow, EmptyRow } from 'src/components/ui/loading-row'
import { Eyebrow } from 'src/components/ui/typography'
import { AccountSection } from './profile/AccountSection'
import { IdentitySection } from './profile/IdentitySection'
import { AppearanceSection } from './profile/AppearanceSection'
import { NavigationSection } from './profile/NavigationSection'

const SECTION_NAV: { id: string; label: string; Icon: LucideIcon; authOnly: boolean }[] = [
  { id: 'account', label: 'Account', Icon: UserCircle, authOnly: false },
  { id: 'identity', label: 'Identity', Icon: Pencil, authOnly: true },
  { id: 'appearance', label: 'Appearance', Icon: Sun, authOnly: false },
  { id: 'navigation', label: 'Navigation', Icon: LayoutGrid, authOnly: false },
  { id: 'sharing', label: 'Shared Links', Icon: Key, authOnly: true },
  { id: 'invites', label: 'Invites', Icon: Ticket, authOnly: true },
  { id: 'data', label: 'Data & Portability', Icon: Download, authOnly: false },
  { id: 'maintenance', label: 'Maintenance', Icon: RefreshCw, authOnly: true },
  { id: 'about', label: 'About', Icon: Info, authOnly: false },
]

// ─── Shared access links ──────────────────────────────────────────────────────

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

function SharingSection() {
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

// ─── Invites ──────────────────────────────────────────────────────────────────

function InvitesSection({ profile }: { profile: MyProfile | null }) {
  const isOwner = profile?.is_owner ?? false
  const [codes, setCodes] = useState<InviteCode[]>([])
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState(false)
  const { copiedId, copy: copyCode } = useCopyFeedback()
  const [message, setMessage] = useState<Message | null>(null)

  useEffect(() => {
    void loadCodes()
  }, [])

  async function loadCodes() {
    setLoading(true)
    try {
      setCodes(await listMyInviteCodes())
    } catch (err) {
      console.error('Failed to load invite codes:', err)
    } finally {
      setLoading(false)
    }
  }

  async function handleGenerate() {
    setGenerating(true)
    setMessage(null)
    try {
      await createInviteCode()
      await loadCodes()
    } catch (err) {
      console.error('Failed to generate invite code:', err)
      setMessage({ type: 'error', text: getErrorMessage(err, 'Failed to generate invite code.') })
    } finally {
      setGenerating(false)
    }
  }

  async function handleDelete(id: string) {
    try {
      await deleteInviteCode(id)
      await loadCodes()
    } catch (err) {
      console.error('Failed to delete invite code:', err)
    }
  }

  function handleCopy(code: string, id: string) {
    copyCode(code, id)
  }

  const unredeemedCount = codes.filter((c) => !c.redeemed_by).length
  const atCap = !isOwner && codes.length >= 2

  return (
    <Section
      id="invites"
      title="Invites"
      Icon={Ticket}
      description={
        isOwner
          ? 'This is a private, invite-only archive. Generate codes for people you want to give access to.'
          : `This is a private, invite-only archive. You can generate up to 2 invite codes${unredeemedCount > 0 ? ` (${unredeemedCount} unredeemed)` : ''}.`
      }
    >
      <Button
        onClick={handleGenerate}
        disabled={generating || atCap}
        className="bg-amber hover:bg-amber-muted text-(--on-amber) font-sans font-medium w-fit gap-1.5"
        title={atCap ? "You've used both of your invites" : undefined}
      >
        {generating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
        Generate Invite Code
      </Button>

      <MessageBanner message={message} />

      <div className="space-y-2">
        {loading ? (
          <LoadingRow label="Loading invites..." />
        ) : codes.length === 0 ? (
          <EmptyRow label="No invite codes generated yet." />
        ) : (
          codes.map((c) => (
            <div key={c.id} className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="font-mono text-sm text-paper font-medium tracking-wider">{c.code}</p>
                <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                  {c.redeemed_by
                    ? `Redeemed ${c.redeemed_at ? fmtDateShort(c.redeemed_at) : ''}`
                    : `Created ${fmtDateShort(c.created_at)}`}
                </p>
              </div>
              <div className="flex gap-1 shrink-0">
                {!c.redeemed_by && (
                  <>
                    <Button
                      size="sm"
                      onClick={() => handleCopy(c.code, c.id)}
                      className="bg-secondary hover:bg-secondary-muted text-muted-foreground hover:text-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="Copy Invite Code"
                      aria-label="Copy Invite Code"
                    >
                      {copiedId === c.id ? <Check className="w-3.5 h-3.5 text-amber" /> : <Copy className="w-3.5 h-3.5" />}
                    </Button>
                    <Button
                      size="sm"
                      onClick={() => handleDelete(c.id)}
                      className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="Delete Invite Code"
                      aria-label="Delete Invite Code"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </Button>
                  </>
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </Section>
  )
}

// ─── Data & portability ───────────────────────────────────────────────────────

function DataSection() {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const user = useAppStore((s) => s.user)
  const titles = useAppStore((s) => s.titles)
  const setTitles = useAppStore((s) => s.setTitles)
  const updateTitle = useAppStore((s) => s.updateTitle)
  const outings = useAppStore((s) => s.outings)
  const importLibrary = useAppStore((s) => s.importLibrary)
  const openLibrarySync = useAppStore((s) => s.openLibrarySync)
  const syncNeedsReview = useLibrarySyncNeedsReview()
  const [importing, setImporting] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const lbFileInputRef = useRef<HTMLInputElement>(null)
  const [lbImporting, setLbImporting] = useState(false)
  const [lbProgress, setLbProgress] = useState<{ done: number; total: number } | null>(null)
  const lbCancelRef = useRef(false)

  function handleExport() {
    exportLibrary(titles, outings)
  }

  async function handleImportFile(e: React.ChangeEvent<HTMLInputElement>) {
    const checkSession = captureLibrarySession()
    const file = e.target.files?.[0]
    if (!file) return
    if (fileInputRef.current) fileInputRef.current.value = ''

    setImporting(true)
    setMessage(null)
    try {
      const { titles: imported, outings: importedOutings } = await parseImportFile(file)
      checkSession()
      const existingKeys = new Set(titles.map((t) => `${t.tmdbId}:${t.type}`))
      const newTitles = imported.filter((t) => !existingKeys.has(`${t.tmdbId}:${t.type}`))
      const skipped = imported.length - newTitles.length

      if (newTitles.length > 0) {
        const newTitleIds = new Set(newTitles.map((title) => title.id))
        const outingsToInsert = importedOutings.filter((outing) => newTitleIds.has(outing.titleId))
        await importLibrary(newTitles, outingsToInsert)
      }

      const added = newTitles.length
      setMessage({
        type: 'success',
        text: `Imported ${added} title${added !== 1 ? 's' : ''}${skipped > 0 ? `, skipped ${skipped} duplicate${skipped !== 1 ? 's' : ''}` : ''}.`,
      })
    } catch (err) {
      setMessage({ type: 'error', text: getErrorMessage(err, 'Import failed.') })
    } finally {
      setImporting(false)
    }
  }

  // Letterboxd CSV import (KP-045 prototype) — accepts one file from the
  // Letterboxd data-export zip (watched.csv, ratings.csv, diary.csv,
  // watchlist.csv). Each film resolves to TMDB by name+year, so large
  // histories take a while; progress + cancel keep it honest.
  async function handleLetterboxdFile(e: React.ChangeEvent<HTMLInputElement>) {
    const checkSession = captureLibrarySession()
    const file = e.target.files?.[0]
    if (!file) return
    if (lbFileInputRef.current) lbFileInputRef.current.value = ''

    setLbImporting(true)
    setMessage(null)
    setLbProgress(null)
    lbCancelRef.current = false
    try {
      const rows = parseLetterboxdCsv(await file.text())
      if (rows.length === 0) throw new Error('No films found in that CSV.')

      // watchlist.csv rows land on the watchlist; everything else is history.
      const status = /watchlist/i.test(file.name) ? ('watchlist' as const) : ('watched' as const)
      const outcome = await resolveSyncItems(letterboxdToSyncItems(rows, status), {
        library: titles,
        onProgress: (done, total) => setLbProgress({ done, total }),
        isCancelled: () => lbCancelRef.current,
      })
      let added = 0
      let updated = 0
      checkSession()
      if (user) ({ added, updated } = await applySyncOutcome({ userId: user.id, outcome, titles, setTitles, updateTitle }))
      else ({ added, updated } = await useAppStore.getState().applySyncOutcome(outcome))

      const parts = [`Added ${added} film${added !== 1 ? 's' : ''}`]
      if (updated > 0) parts.push(`updated ${updated}`)
      if (outcome.unchanged > 0) parts.push(`${outcome.unchanged} already up to date`)
      if (outcome.unmatched.length > 0) {
        const shown = outcome.unmatched.slice(0, 5).join(', ')
        parts.push(`couldn't match ${outcome.unmatched.length}: ${shown}${outcome.unmatched.length > 5 ? `, +${outcome.unmatched.length - 5} more` : ''}`)
      }
      if (lbCancelRef.current) parts.push('(cancelled early)')
      setMessage({ type: 'success', text: `${parts.join(' · ')}.` })
    } catch (err) {
      setMessage({ type: 'error', text: getErrorMessage(err, 'Letterboxd import failed.') })
    } finally {
      setLbImporting(false)
      setLbProgress(null)
    }
  }

  return (
    <Section
      id="data"
      title="Data & Portability"
      Icon={Download}
      description="Export your entire library as a JSON file, import a previously exported archive, bring your watch history and ratings over from a Letterboxd CSV export, or import from Simkl, Plex and Emby. Duplicates are skipped on import."
    >
      <MessageBanner message={message} />
      {syncNeedsReview && (
        <div className="flex items-center gap-3 max-w-md rounded-lg border border-amber/30 p-3">
          <RefreshCw className="w-3.5 h-3.5 text-amber shrink-0" aria-hidden="true" />
          <p className="flex-1 font-sans text-xs text-paper">This device has library changes or recovery data to review.</p>
          <Button
            onClick={openLibrarySync}
            className="bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors"
          >
            Review sync
          </Button>
        </div>
      )}
      <div className="flex gap-2 max-w-md">
        <Button
          onClick={handleExport}
          className="flex-1 bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2"
        >
          <Download className="w-3.5 h-3.5 text-amber" />
          Export
        </Button>
        <Button
          onClick={() => fileInputRef.current?.click()}
          disabled={importing}
          className="flex-1 bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2"
        >
          {importing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Upload className="w-3.5 h-3.5 text-amber" />}
          Import
        </Button>
        <input
          ref={fileInputRef}
          type="file"
          accept=".json,application/json"
          className="hidden"
          onChange={handleImportFile}
        />
      </div>
      <div className="flex gap-2 max-w-md">
        <Button
          onClick={() => lbFileInputRef.current?.click()}
          disabled={lbImporting}
          className="flex-1 bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2"
        >
          {lbImporting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Film className="w-3.5 h-3.5 text-amber" />}
          {lbImporting && lbProgress
            ? `Matching ${lbProgress.done}/${lbProgress.total}…`
            : lbImporting
              ? 'Reading CSV…'
              : 'Import from Letterboxd (CSV)'}
        </Button>
        {lbImporting && (
          <Button
            onClick={() => { lbCancelRef.current = true }}
            className="bg-secondary/60 hover:bg-red-500/20 hover:text-red-400 text-paper font-sans text-xs border border-border transition-colors"
          >
            Cancel
          </Button>
        )}
        <input
          ref={lbFileInputRef}
          type="file"
          accept=".csv,text/csv"
          className="hidden"
          onChange={handleLetterboxdFile}
        />
      </div>
      <p className="font-mono text-[10px] text-muted-foreground max-w-md">
        Accepts one file from your Letterboxd data export (watched.csv, ratings.csv,
        diary.csv, or watchlist.csv). Films are matched to TMDB by name and year;
        anything that can't be matched confidently is reported, not guessed.
      </p>
      <SyncConnections />
    </Section>
  )
}

// ─── Maintenance ────────────────────────────────────────────────────────────

function MaintenanceSection() {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const user = useAppStore((s) => s.user)
  const titles = useAppStore((s) => s.titles)
  const updateTitle = useAppStore((s) => s.updateTitleMetadata)
  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState(0)
  const [message, setMessage] = useState<Message | null>(null)
  const cancelRef = useRef(false)

  const eligible = titles.filter((t) => t.tmdbId)

  async function handleRefreshAll() {
    const checkSession = captureLibrarySession()
    if (
      !confirm(
        `Refresh metadata for ${eligible.length} title${eligible.length !== 1 ? 's' : ''} from TMDB/OMDb? This re-pulls posters, synopses, and ratings for your whole library and can take a few minutes.`
      )
    ) {
      return
    }

    setRunning(true)
    setProgress(0)
    setMessage(null)
    cancelRef.current = false

    const failed: string[] = []
    let done = 0

    for (const title of eligible) {
      if (cancelRef.current) break
      try {
        const patch = await fetchRefreshedTitlePatch(title, titleToSearchResult(title), user?.id)
        checkSession()
        await updateTitle(title.id, patch)
      } catch (err) {
        console.error(`Failed to refresh metadata for "${title.title}":`, err)
        failed.push(title.title)
      }
      done++
      setProgress(done)
    }

    setRunning(false)
    const cancelled = cancelRef.current
    const skipped = titles.length - eligible.length
    const parts = [`Refreshed ${done - failed.length}/${eligible.length} titles${cancelled ? ' (cancelled)' : ''}.`]
    if (failed.length > 0) parts.push(`Failed: ${failed.slice(0, 5).join(', ')}${failed.length > 5 ? '…' : ''}.`)
    if (skipped > 0) parts.push(`Skipped ${skipped} title${skipped !== 1 ? 's' : ''} not linked to TMDB.`)
    setMessage({ type: failed.length > 0 ? 'error' : 'success', text: parts.join(' ') })
  }

  function handleCancel() {
    cancelRef.current = true
  }

  return (
    <Section
      id="maintenance"
      title="Maintenance"
      Icon={RefreshCw}
      description="Re-pull metadata for every title in your library at once — useful after a schema change to backfill new fields, or when data has just gone stale. Your ratings, notes, and viewing history are untouched."
    >
      <MessageBanner message={message} />
      <div className="flex items-center gap-2 flex-wrap">
        <Button
          onClick={handleRefreshAll}
          disabled={running || eligible.length === 0}
          className="bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2 disabled:opacity-40"
        >
          {running ? (
            <Loader2 className="w-3.5 h-3.5 animate-spin" />
          ) : (
            <RefreshCw className="w-3.5 h-3.5 text-amber" />
          )}
          {running ? `Refreshing ${progress}/${eligible.length}…` : 'Refresh All Metadata'}
        </Button>
        {running && (
          <Button
            onClick={handleCancel}
            className="bg-secondary/40 hover:bg-destructive hover:text-destructive-foreground text-muted-foreground font-sans text-xs"
          >
            Cancel
          </Button>
        )}
      </div>
      {eligible.length === 0 && (
        <p className="font-sans text-[11px] text-muted-foreground">
          No titles are linked to TMDB yet — link one from its Refresh Metadata panel first.
        </p>
      )}
    </Section>
  )
}

// ─── About ────────────────────────────────────────────────────────────────────

const ABOUT_LINKS: { label: string; href: string }[] = [
  { label: 'Source on GitHub', href: 'https://github.com/shakrunk/CinemArchive' },
  { label: 'Release notes', href: 'https://github.com/shakrunk/CinemArchive/releases' },
]

function AboutSection() {
  return (
    <Section
      id="about"
      title="About"
      Icon={Info}
      description="What this app is, which release you're running, and where its data comes from."
    >
      <div className="flex items-baseline gap-2.5 flex-wrap">
        <span className="font-serif text-lg font-semibold text-paper">CinemArchive</span>
        <span className="font-mono text-xs text-amber">v{__APP_VERSION__}</span>
        <Eyebrow as="span" size="md">
          The Projection Room
        </Eyebrow>
      </div>
      <p className="font-sans text-xs text-muted-foreground leading-relaxed max-w-[60ch]">
        A private film archive — track the movies and series you watch, rate and
        revisit them, chart your habits in the Ledger, and share a read-only
        window with friends.
      </p>
      <p className="font-sans text-[11px] text-muted-foreground leading-relaxed max-w-[60ch]">
        Metadata and artwork from{' '}
        <a href="https://www.themoviedb.org/" target="_blank" rel="noopener noreferrer" className="text-amber/80 hover:text-amber underline underline-offset-2">TMDB</a>
        , critic scores via{' '}
        <a href="https://www.omdbapi.com/" target="_blank" rel="noopener noreferrer" className="text-amber/80 hover:text-amber underline underline-offset-2">OMDb</a>
        , awards and Bechdel data from{' '}
        <a href="https://www.wikidata.org/" target="_blank" rel="noopener noreferrer" className="text-amber/80 hover:text-amber underline underline-offset-2">Wikidata</a>
        . This product uses the TMDB API but is not endorsed or certified by TMDB.
      </p>
      <div className="flex items-center gap-3 flex-wrap">
        {ABOUT_LINKS.map((l) => (
          <a
            key={l.href}
            href={l.href}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 font-sans text-xs text-paper-dim hover:text-amber transition-colors"
          >
            <ExternalLink className="w-3.5 h-3.5 text-amber/70" />
            {l.label}
          </a>
        ))}
      </div>
    </Section>
  )
}

// ─── Archive at a glance ──────────────────────────────────────────────────────

function ArchiveGlance() {
  const stats = useAppStore((s) => s.stats)
  const total = stats.totalMovies + stats.totalSeries
  const hours = Math.round(stats.totalMinutes / 60)

  const items: { value: string; sub: string }[] = [
    { value: String(total), sub: `${stats.totalMovies} films · ${stats.totalSeries} series` },
    { value: String(stats.totalViewings), sub: 'screenings' },
    { value: stats.avgRating.toFixed(1), sub: 'avg rating' },
    { value: `${hours}h`, sub: 'screen time' },
  ]

  return (
    <div className="flex items-start overflow-x-auto pb-3 mb-[clamp(24px,4vw,40px)] border-b border-(--line)">
      {items.map((item, i) => (
        <div key={item.sub} className="flex items-stretch shrink-0">
          {i > 0 && <div className="w-px bg-(--line-2) mx-6 sm:mx-8 self-stretch" />}
          <div className="flex flex-col">
            <div className="stat-num text-[clamp(22px,2.6vw,34px)]">{item.value}</div>
            <Eyebrow as="div" size="md" className="mt-1.5 whitespace-nowrap">
              {item.sub}
            </Eyebrow>
          </div>
        </div>
      ))}
    </div>
  )
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export function Profile() {
  const user = useAppStore((s) => s.user)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const [profile, setProfile] = useState<MyProfile | null>(null)

  const authed = Boolean(user) && isSupabaseConfigured && !isSharedView
  // Mask rather than reset on sign-out: a fresh fetch overwrites it on the
  // next sign-in, and effects must not set state synchronously.
  const effectiveProfile = authed && profile?.user_id === user?.id ? profile : null

  useEffect(() => {
    if (!authed) return
    let cancelled = false
    getMyProfile()
      .then((p) => {
        if (!cancelled) setProfile(p)
      })
      .catch((err) => console.error('Failed to load profile:', err))
    return () => {
      cancelled = true
    }
  }, [authed, user?.id])

  const visibleNav = SECTION_NAV.filter((s) => authed || !s.authOnly)

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      {/* Hero */}
      <div className="mb-[clamp(24px,3.5vw,40px)]">
        <p className="kicker">
          <span className="dot" /> the projectionist's booth
        </p>
        <h1 className="display-title text-[clamp(36px,6.5vw,72px)] mt-3.5">
          Profile &amp; <em>Settings.</em>
        </h1>
        <p className="mt-4 max-w-[60ch] text-[clamp(15px,1.6vw,18px)] text-paper-dim">
          {authed
            ? 'Your account, your look, and your shared links. Friends and activity live in their own room now.'
            : 'Sign in to unlock sharing and cross-device sync — appearance and data tools work right here.'}
        </p>
      </div>

      <ArchiveGlance />

      <div className="grid grid-cols-12 gap-8 pb-16">
        {/* Section nav (desktop) */}
        <nav className="hidden lg:block col-span-3" aria-label="Settings sections">
          <div className="sticky top-24 space-y-0.5">
            {visibleNav.map(({ id, label, Icon }) => (
              <a
                key={id}
                href={`#settings-${id}`}
                onClick={(e) => {
                  e.preventDefault()
                  document.getElementById(`settings-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
                }}
                className="flex items-center gap-2.5 px-3 py-2 rounded-md font-sans text-[13px] text-paper-dim hover:text-amber hover:bg-amber/5 transition-colors"
              >
                <Icon className="w-4 h-4 text-amber/70" />
                {label}
              </a>
            ))}
          </div>
        </nav>

        {/* Sections */}
        <div key={user?.id ?? 'anonymous'} className="col-span-12 lg:col-span-9 xl:col-span-7 space-y-10">
          <AccountSection profile={effectiveProfile} />

          {authed && (
            <IdentitySection
              key={effectiveProfile?.user_id ?? 'pending'}
              profile={effectiveProfile}
              onProfileChange={setProfile}
            />
          )}

          <AppearanceSection />

          <NavigationSection />

          {authed && <SharingSection />}
          {authed && <InvitesSection profile={effectiveProfile} />}

          <DataSection />

          {authed && <MaintenanceSection />}

          <AboutSection />

          {!authed && isSupabaseConfigured && !isSharedView && (
            <p className="font-sans text-xs text-muted-foreground flex items-center gap-1.5">
              <Film className="w-3.5 h-3.5 text-amber" />
              Shared links unlock once you sign in — friends, recommendations, and activity live in the Friends tab.
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
