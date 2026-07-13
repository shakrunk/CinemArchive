import { useState, useEffect, useRef } from 'react'
import { useShallow } from 'zustand/react/shallow'
import {
  Mail, Key, Plus, Trash2, Copy, Check, LogOut, Fingerprint, Shield, Loader2,
  Download, Upload, Eye, EyeOff, Settings2,
  UserCircle, Sun, Moon, Pencil, CalendarDays, Film, Aperture, Terminal, Lock,
  LayoutGrid, GripVertical, Ticket, RefreshCw, Info, ExternalLink,
} from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { ShareScopeEditor } from 'src/components/ShareScopeEditor'
import { useAppStore } from 'src/store/useAppStore'
import { cn, fmtDateShort } from 'src/lib/utils'
import { useCopyFeedback } from 'src/lib/useCopyFeedback'
import {
  isSupabaseConfigured,
  signInWithEmail,
  signInWithPasskey,
  signOut,
  registerPasskey,
  createSharedKey,
  revokeSharedKey,
  listSharedKeys,
  getMyProfile,
  updateMyProfile,
  createInviteCode,
  listMyInviteCodes,
  deleteInviteCode,
  type MyProfile,
  type InviteCode,
} from 'src/lib/auth'
import { exportLibrary, parseImportFile } from 'src/lib/export-import'
import { parseLetterboxdCsv, resolveLetterboxdRows } from 'src/lib/letterboxd-import'
import { insertTitleToDb, insertOutingToDb } from 'src/lib/db'
import { titleToSearchResult, fetchRefreshedTitlePatch } from 'src/lib/refreshMetadata'
import { applyTheme } from 'src/lib/theme'
import type { Theme } from 'src/store/useAppStore'
import { NAV_ITEM_LABELS, type NavItemId } from 'src/lib/navigation'
import { isThemeDiscovered } from 'src/lib/easterEggThemes'
import { InviteRedeemForm } from 'src/components/InviteRedeemForm'
import { MessageBanner, type Message } from 'src/components/ui/message-banner'
import { Section } from 'src/components/ui/section'
import { LoadingRow, EmptyRow } from 'src/components/ui/loading-row'

const SECTION_NAV: { id: string; label: string; Icon: typeof Shield; authOnly: boolean }[] = [
  { id: 'account', label: 'Account', Icon: UserCircle, authOnly: false },
  { id: 'identity', label: 'Identity', Icon: Pencil, authOnly: true },
  { id: 'security', label: 'Security', Icon: Shield, authOnly: true },
  { id: 'appearance', label: 'Appearance', Icon: Sun, authOnly: false },
  { id: 'navigation', label: 'Navigation', Icon: LayoutGrid, authOnly: false },
  { id: 'sharing', label: 'Shared Links', Icon: Key, authOnly: true },
  { id: 'invites', label: 'Invites', Icon: Ticket, authOnly: true },
  { id: 'data', label: 'Data & Portability', Icon: Download, authOnly: false },
  { id: 'maintenance', label: 'Maintenance', Icon: RefreshCw, authOnly: true },
  { id: 'about', label: 'About', Icon: Info, authOnly: false },
]

function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('')
}

// ─── Account ──────────────────────────────────────────────────────────────────

function AuthModeTabs({ mode, onChange }: { mode: 'signin' | 'signup'; onChange: (m: 'signin' | 'signup') => void }) {
  return (
    <div className="grid grid-cols-2 gap-2 max-w-sm" role="tablist" aria-label="Sign in or sign up">
      {(['signin', 'signup'] as const).map((m) => (
        <button
          key={m}
          type="button"
          role="tab"
          aria-selected={mode === m}
          onClick={() => onChange(m)}
          className={cn(
            'rounded-lg border py-2.5 font-sans text-sm font-medium transition-colors',
            mode === m
              ? 'border-amber/50 bg-amber/10 text-amber'
              : 'border-border bg-secondary/20 text-muted-foreground hover:border-amber/25'
          )}
        >
          {m === 'signin' ? 'Sign In' : 'Sign Up'}
        </button>
      ))}
    </div>
  )
}

function SignInCard() {
  const [mode, setMode] = useState<'signin' | 'signup'>('signin')
  const [email, setEmail] = useState('')
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)

  function switchMode(next: 'signin' | 'signup') {
    setMode(next)
    setMessage(null)
  }

  async function handleEmailSignIn(e: React.FormEvent) {
    e.preventDefault()
    if (!email.trim()) return
    setLoading(true)
    setMessage(null)
    try {
      await signInWithEmail(email)
      setMessage({ type: 'success', text: 'Magic link sent! Check your inbox to complete sign-in.' })
    } catch (err: any) {
      console.error(err)
      setMessage({ type: 'error', text: err.message || 'Failed to send magic link.' })
    } finally {
      setLoading(false)
    }
  }

  async function handlePasskeySignIn() {
    if (!email.trim()) {
      setMessage({ type: 'error', text: 'Enter your email first to authenticate with a passkey.' })
      return
    }
    setLoading(true)
    setMessage(null)
    try {
      await signInWithPasskey(email)
      setMessage({ type: 'success', text: 'Passkey verification initiated. Check your browser prompt.' })
    } catch (err: any) {
      console.error(err)
      setMessage({ type: 'error', text: err.message || 'Failed to sign in with passkey.' })
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="space-y-4">
      <AuthModeTabs mode={mode} onChange={switchMode} />

      {mode === 'signin' ? (
        <form onSubmit={handleEmailSignIn} className="space-y-4">
          <p className="font-sans text-xs text-muted-foreground leading-relaxed">
            Enter your email to sign in. You will receive a passwordless magic link to log in securely.
          </p>
          <div>
            <label htmlFor="settings-email-input" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
              Email Address
            </label>
            <div className="relative max-w-sm">
              <Mail className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground pointer-events-none" />
              <Input
                id="settings-email-input"
                aria-label="Email address"
                required
                type="email"
                placeholder="name@domain.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="pl-9 bg-secondary/50 border-border"
              />
            </div>
          </div>

          <MessageBanner message={message} />

          <div className="flex gap-2 max-w-sm">
            <Button
              type="submit"
              disabled={loading}
              className="flex-1 bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
            >
              {loading ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Mail className="w-4 h-4 mr-2" />}
              Send Magic Link
            </Button>
            <Button
              type="button"
              onClick={handlePasskeySignIn}
              disabled={loading}
              variant="outline"
              className="border-border text-muted-foreground hover:text-foreground"
              title="Sign In with Passkey"
              aria-label="Sign In with Passkey"
            >
              <Fingerprint className="w-4 h-4" />
            </Button>
          </div>
        </form>
      ) : (
        <div className="space-y-4 max-w-sm">
          <p className="font-sans text-xs text-muted-foreground leading-relaxed">
            This is a private, invite-only archive. Enter the email and invite code you were given to create an account.
          </p>
          <MessageBanner message={message} />
          <InviteRedeemForm
            onRedeemed={(text) => setMessage({ type: 'success', text })}
            onError={(text) => setMessage({ type: 'error', text })}
          />
        </div>
      )}
    </div>
  )
}

function AccountSection({ profile }: { profile: MyProfile | null }) {
  const { user, setUser, isSharedView } = useAppStore(
    useShallow((s) => ({
      user: s.user,
      setUser: s.setUser,
      isSharedView: s.isSharedView,
    }))
  )

  async function handleSignOut() {
    try {
      await signOut()
      setUser(null)
    } catch (err) {
      console.error('Failed to sign out:', err)
    }
  }

  if (!isSupabaseConfigured) {
    return (
      <Section id="account" title="Account" Icon={UserCircle}>
        <p className="font-sans text-xs text-muted-foreground leading-relaxed">
          Running in local mode — Supabase isn't configured, so there's no account to manage.
          Your library lives in this browser's storage. Appearance and data export still work below.
        </p>
      </Section>
    )
  }

  if (isSharedView) {
    return (
      <Section id="account" title="Account" Icon={UserCircle}>
        <p className="font-sans text-xs text-muted-foreground leading-relaxed">
          You're browsing a shared, read-only archive. Account settings belong to its owner.
        </p>
      </Section>
    )
  }

  if (!user) {
    return (
      <Section id="account" title="Account" Icon={UserCircle}>
        <SignInCard />
      </Section>
    )
  }

  const displayName = profile?.display_name || user.email?.split('@')[0] || 'Archivist'
  const memberSince = profile?.created_at ?? user.created_at

  return (
    <Section id="account" title="Account" Icon={UserCircle}>
      <div className="flex items-center gap-4">
        <div
          className="w-14 h-14 rounded-full shrink-0 flex items-center justify-center font-serif text-lg text-amber border"
          style={{ borderColor: 'var(--line)', background: 'rgba(233,178,102,0.08)' }}
          aria-hidden="true"
        >
          {initialsOf(displayName) || 'A'}
        </div>
        <div className="min-w-0 flex-1">
          <p className="font-serif text-lg text-paper truncate">{displayName}</p>
          <p className="font-mono text-xs text-muted-foreground truncate mt-0.5">{user.email}</p>
          {memberSince && (
            <p className="font-mono text-[10px] text-muted-foreground mt-1 flex items-center gap-1">
              <CalendarDays className="w-3 h-3" />
              Member since {new Date(memberSince).toLocaleDateString(undefined, { year: 'numeric', month: 'long' })}
            </p>
          )}
        </div>
        <Button
          size="sm"
          onClick={handleSignOut}
          className="bg-secondary/80 hover:bg-destructive hover:text-destructive-foreground text-muted-foreground gap-1.5 shrink-0"
        >
          <LogOut className="w-3.5 h-3.5" />
          Sign Out
        </Button>
      </div>
    </Section>
  )
}

// ─── Identity ─────────────────────────────────────────────────────────────────

const USERNAME_RE = /^[a-z0-9](?:[a-z0-9_-]{1,22}[a-z0-9])?$/

function IdentitySection({
  profile,
  onProfileChange,
}: {
  profile: MyProfile | null
  onProfileChange: (p: MyProfile) => void
}) {
  // Seeded from the profile on mount — the parent remounts this section (via
  // key) when the profile row first arrives, so lazy init is sufficient.
  const [displayName, setDisplayName] = useState(profile?.display_name ?? '')
  const [username, setUsername] = useState(profile?.username ?? '')
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)

  const dirty =
    (profile?.display_name ?? '') !== displayName.trim() ||
    (profile?.username ?? '') !== username.trim().toLowerCase()

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    const nextUsername = username.trim().toLowerCase()
    if (nextUsername && !USERNAME_RE.test(nextUsername)) {
      setMessage({
        type: 'error',
        text: 'Usernames are 3–24 characters: lowercase letters, numbers, hyphens or underscores, starting and ending with a letter or number.',
      })
      return
    }
    setSaving(true)
    setMessage(null)
    try {
      const updated = await updateMyProfile({
        display_name: displayName.trim() || null,
        username: nextUsername || null,
      })
      onProfileChange(updated)
      // Reflect the normalized (trimmed/lowercased) values back into the form.
      setDisplayName(updated.display_name ?? '')
      setUsername(updated.username ?? '')
      setMessage({ type: 'success', text: 'Profile saved.' })
    } catch (err: any) {
      console.error('Failed to save profile:', err)
      setMessage({ type: 'error', text: err.message || 'Failed to save profile.' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Section
      id="identity"
      title="Identity"
      Icon={Pencil}
      description="How you appear to friends — in their friend lists, activity feeds, and recommendation inboxes."
    >
      <form onSubmit={handleSave} className="space-y-4 max-w-sm">
        <div>
          <label htmlFor="settings-display-name" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
            Display Name
          </label>
          <Input
            id="settings-display-name"
            aria-label="Display name"
            placeholder="e.g. Norma Desmond"
            value={displayName}
            maxLength={60}
            onChange={(e) => setDisplayName(e.target.value)}
            className="bg-secondary/50 border-border"
          />
        </div>
        <div>
          <label htmlFor="settings-username" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
            Username
          </label>
          <Input
            id="settings-username"
            aria-label="Username"
            placeholder="e.g. norma-d"
            value={username}
            maxLength={24}
            onChange={(e) => setUsername(e.target.value)}
            className="bg-secondary/50 border-border font-mono"
          />
          <p className="font-sans text-[11px] text-muted-foreground mt-1.5">
            Optional, unique across CinemArchive. Shown when you haven't set a display name.
          </p>
        </div>

        <MessageBanner message={message} />

        <Button
          type="submit"
          disabled={saving || !dirty}
          className="bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium disabled:opacity-40"
        >
          {saving ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Check className="w-4 h-4 mr-2" />}
          Save Changes
        </Button>
      </form>
    </Section>
  )
}

// ─── Security ─────────────────────────────────────────────────────────────────

function SecuritySection() {
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)

  async function handleRegisterPasskey() {
    setLoading(true)
    setMessage(null)
    try {
      await registerPasskey()
      setMessage({ type: 'success', text: 'Passkey registered successfully! You can now use it to sign in.' })
    } catch (err: any) {
      console.error(err)
      setMessage({ type: 'error', text: err.message || 'Failed to register passkey.' })
    } finally {
      setLoading(false)
    }
  }

  return (
    <Section
      id="security"
      title="Passkey Security"
      Icon={Shield}
      description="Add a biometric passkey (face lock, fingerprint, or PIN) to log in instantly on this device next time without waiting for email links."
    >
      <MessageBanner message={message} />
      <Button
        onClick={handleRegisterPasskey}
        disabled={loading}
        className="bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2"
      >
        {loading ? (
          <Loader2 className="w-3.5 h-3.5 animate-spin" />
        ) : (
          <Fingerprint className="w-3.5 h-3.5 text-amber" />
        )}
        Register new Passkey
      </Button>
    </Section>
  )
}

// ─── Appearance ───────────────────────────────────────────────────────────────

function AppearanceSection() {
  const { theme, setTheme, unlockedThemes, titles } = useAppStore(
    useShallow((s) => ({
      theme: s.theme,
      setTheme: s.setTheme,
      unlockedThemes: s.unlockedThemes,
      titles: s.titles,
    }))
  )

  function choose(next: Theme) {
    if (next === theme) return
    applyTheme(next)
    setTheme(next)
  }

  const options: { value: Theme; label: string; hint: string; Icon: typeof Sun; lockedHint?: string }[] = [
    { value: 'dark', label: 'Screening Room', hint: 'Dark — the classic projection-booth look.', Icon: Moon },
    { value: 'light', label: 'Matinée', hint: 'Light — parchment and daylight.', Icon: Sun },
    {
      value: 'noir',
      label: 'Spider-Man Noir',
      hint: 'Black & white, high-contrast — a detective’s screening room.',
      Icon: Aperture,
      lockedHint: 'Unlock it: watch Spider-Man: Noir in Black & White.',
    },
    {
      value: 'matrix',
      label: 'The Construct',
      hint: 'Green phosphor on black glass — welcome to the desert of the real.',
      Icon: Terminal,
      lockedHint: 'Unlock it: take the red pill while logging a viewing of The Matrix.',
    },
  ]

  // Secret themes stay off this list entirely until their linked title has
  // been added to the library somehow — they're easter eggs to stumble on,
  // not a spoiler list of "locked" cards.
  const visibleOptions = options.filter(
    ({ value }) =>
      value === 'dark' ||
      value === 'light' ||
      unlockedThemes.includes(value) ||
      isThemeDiscovered(value as 'noir' | 'matrix', titles)
  )

  return (
    <Section
      id="appearance"
      title="Appearance"
      Icon={Sun}
      description="Pick a house style. You can also flip dark/light any time with the T key or the toggle in the top bar. A couple of styles are secret — find them by watching the right films the right way."
    >
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3" role="radiogroup" aria-label="Theme">
        {visibleOptions.map(({ value, label, hint, Icon, lockedHint }) => {
          const locked = !unlockedThemes.includes(value)
          return (
            <button
              key={value}
              role="radio"
              aria-checked={theme === value}
              disabled={locked}
              onClick={() => choose(value)}
              className={cn(
                'text-left rounded-lg border p-4 transition-colors',
                locked && 'opacity-50 cursor-not-allowed',
                !locked && theme === value && 'border-amber/50 bg-amber/10',
                !locked && theme !== value && 'border-border bg-secondary/20 hover:border-amber/25',
                locked && 'border-border bg-secondary/10'
              )}
            >
              <span className="flex items-center gap-2 font-sans text-sm text-paper font-medium">
                <Icon className={cn('w-4 h-4', !locked && theme === value ? 'text-amber' : 'text-muted-foreground')} />
                {label}
                {locked ? (
                  <Lock className="w-3.5 h-3.5 text-muted-foreground ml-auto" />
                ) : (
                  theme === value && <Check className="w-3.5 h-3.5 text-amber ml-auto" />
                )}
              </span>
              <span className="block font-sans text-xs text-muted-foreground mt-1.5 leading-relaxed">
                {locked ? lockedHint : hint}
              </span>
            </button>
          )
        })}
      </div>
    </Section>
  )
}

// ─── Navigation ─────────────────────────────────────────────────────────────

// Gap between rows in the reorderable nav list (matches the container's space-y-2).
const NAV_ROW_GAP = 8

interface NavDragMeta {
  id: NavItemId
  startIndex: number
  itemHeight: number
}

function NavigationSection() {
  const { navPrefs, moveNavItem, reorderNav, toggleNavItemHidden, setNavCompact } = useAppStore(
    useShallow((s) => ({
      navPrefs: s.navPrefs,
      moveNavItem: s.moveNavItem,
      reorderNav: s.reorderNav,
      toggleNavItemHidden: s.toggleNavItemHidden,
      setNavCompact: s.setNavCompact,
    }))
  )

  const order = navPrefs.order
  const itemRefs = useRef(new Map<NavItemId, HTMLDivElement>())
  // Only read inside event handlers, never during render — safe as a ref.
  const startYRef = useRef(0)
  // Read during render (to offset non-dragged rows), so these live in state.
  const [dragMeta, setDragMeta] = useState<NavDragMeta | null>(null)
  const [dragOffset, setDragOffset] = useState(0)
  const [dropIndex, setDropIndex] = useState<number | null>(null)

  function handlePointerDown(e: React.PointerEvent<HTMLButtonElement>, id: NavItemId) {
    const el = itemRefs.current.get(id)
    if (!el) return
    const startIndex = order.indexOf(id)
    const itemHeight = el.getBoundingClientRect().height + NAV_ROW_GAP
    startYRef.current = e.clientY
    setDragMeta({ id, startIndex, itemHeight })
    setDragOffset(0)
    setDropIndex(startIndex)
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  function handlePointerMove(e: React.PointerEvent<HTMLButtonElement>) {
    if (!dragMeta) return
    const offset = e.clientY - startYRef.current
    setDragOffset(offset)
    const rawIndex = dragMeta.startIndex + Math.round(offset / dragMeta.itemHeight)
    setDropIndex(Math.max(0, Math.min(order.length - 1, rawIndex)))
  }

  function endDrag(e: React.PointerEvent<HTMLButtonElement>) {
    if (!dragMeta) return
    e.currentTarget.releasePointerCapture(e.pointerId)
    // Recompute from the live offset rather than trusting `dropIndex` state,
    // which may not have committed yet if pointerup follows pointermove
    // within the same tick.
    const finalOffset = e.clientY - startYRef.current
    const rawIndex = dragMeta.startIndex + Math.round(finalOffset / dragMeta.itemHeight)
    const target = Math.max(0, Math.min(order.length - 1, rawIndex))
    if (target !== dragMeta.startIndex) {
      const next = [...order]
      const [moved] = next.splice(dragMeta.startIndex, 1)
      next.splice(target, 0, moved)
      reorderNav(next)
    }
    setDragMeta(null)
    setDragOffset(0)
    setDropIndex(null)
  }

  function handleGripKeyDown(e: React.KeyboardEvent, id: NavItemId) {
    if (e.key === 'ArrowUp') { e.preventDefault(); moveNavItem(id, 'up') }
    if (e.key === 'ArrowDown') { e.preventDefault(); moveNavItem(id, 'down') }
  }

  return (
    <Section
      id="navigation"
      title="Navigation"
      Icon={LayoutGrid}
      description="Drag to reorder tabs in the top bar and bottom nav — number-key shortcuts (1, 2, 3…) follow the new order. Hidden tabs stay reachable from the command palette (⌘K)."
    >
      <div className="space-y-2" role="list" aria-label="Navigation tabs">
        {order.map((id: NavItemId, i) => {
          const hidden = navPrefs.hidden.includes(id)
          const isDragging = dragMeta?.id === id
          let translateY = 0
          if (dragMeta && dropIndex !== null) {
            if (isDragging) {
              translateY = dragOffset
            } else if (dragMeta.startIndex < dropIndex && i > dragMeta.startIndex && i <= dropIndex) {
              translateY = -dragMeta.itemHeight
            } else if (dragMeta.startIndex > dropIndex && i >= dropIndex && i < dragMeta.startIndex) {
              translateY = dragMeta.itemHeight
            }
          }
          return (
            <div
              key={id}
              ref={(el) => {
                if (el) itemRefs.current.set(id, el)
                else itemRefs.current.delete(id)
              }}
              role="listitem"
              className={cn('flex items-center gap-3 rounded-lg border p-2.5', isDragging && 'shadow-lg')}
              style={{
                borderColor: 'var(--line)',
                background: 'var(--inset)',
                transform: translateY ? `translateY(${translateY}px)` : undefined,
                transition: isDragging ? 'none' : 'transform 180ms ease',
                position: isDragging ? 'relative' : undefined,
                zIndex: isDragging ? 10 : undefined,
              }}
            >
              <button
                onPointerDown={(e) => handlePointerDown(e, id)}
                onPointerMove={handlePointerMove}
                onPointerUp={endDrag}
                onPointerCancel={endDrag}
                onKeyDown={(e) => handleGripKeyDown(e, id)}
                aria-label={`Reorder ${NAV_ITEM_LABELS[id]} — drag, or use arrow keys`}
                className="text-paper-faint hover:text-amber cursor-grab active:cursor-grabbing -my-1 p-1 rounded"
                style={{ touchAction: 'none' }}
              >
                <GripVertical className="w-4 h-4" />
              </button>
              <span className={cn('flex-1 font-sans text-sm', hidden ? 'text-muted-foreground' : 'text-paper')}>
                {NAV_ITEM_LABELS[id]}
              </span>
              <button
                onClick={() => toggleNavItemHidden(id)}
                aria-pressed={!hidden}
                aria-label={hidden ? `Show ${NAV_ITEM_LABELS[id]} in navigation` : `Hide ${NAV_ITEM_LABELS[id]} from navigation`}
                className={cn(
                  'icon-btn w-8 h-8 border rounded-md flex items-center justify-center shrink-0',
                  hidden
                    ? 'text-muted-foreground border-border'
                    : 'text-amber border-amber/30 bg-amber/5'
                )}
              >
                {hidden ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
          )
        })}
      </div>

      <label className="flex items-center gap-2.5 cursor-pointer pt-1">
        <input
          type="checkbox"
          checked={navPrefs.compact}
          onChange={(e) => setNavCompact(e.target.checked)}
          className="sr-only"
        />
        <span
          className={cn('w-9 h-5 rounded-full transition-colors relative shrink-0', navPrefs.compact ? 'bg-amber' : 'bg-secondary')}
          aria-hidden="true"
        >
          <span
            className={cn(
              'absolute top-0.5 left-0.5 w-4 h-4 rounded-full bg-white transition-transform',
              navPrefs.compact && 'translate-x-4'
            )}
          />
        </span>
        <span className="font-sans text-sm text-paper">Compact top bar (icons only)</span>
      </label>
    </Section>
  )
}

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
          className="bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] shrink-0"
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
            className="font-sans text-[11px] uppercase tracking-widest text-muted-foreground hover:text-paper transition-colors"
          >
            {showRevoked ? 'Hide' : 'Show'} revoked links ({revokedKeys.length})
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
    } catch (err: any) {
      console.error('Failed to generate invite code:', err)
      setMessage({ type: 'error', text: err.message || 'Failed to generate invite code.' })
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
        className="bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium w-fit gap-1.5"
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
  const { user, titles, setTitles, outings, setOutings } = useAppStore(
    useShallow((s) => ({ user: s.user, titles: s.titles, setTitles: s.setTitles, outings: s.outings, setOutings: s.setOutings }))
  )
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
    const file = e.target.files?.[0]
    if (!file) return
    if (fileInputRef.current) fileInputRef.current.value = ''

    setImporting(true)
    setMessage(null)
    try {
      const { titles: imported, outings: importedOutings } = await parseImportFile(file)
      const existingKeys = new Set(titles.map((t) => `${t.tmdbId}:${t.type}`))
      const newTitles = imported.filter((t) => !existingKeys.has(`${t.tmdbId}:${t.type}`))
      const skipped = imported.length - newTitles.length

      if (newTitles.length > 0) {
        setTitles([...newTitles, ...titles])
        // Only outings belonging to a title that actually got imported (not
        // skipped as a duplicate) are kept — matches the newTitles filtering
        // above and rule §5.13's outing⇄viewing link scope.
        const newTitleIds = new Set(newTitles.map((t) => t.id))
        const outingsToInsert = importedOutings.filter((o) => newTitleIds.has(o.titleId))
        if (outingsToInsert.length > 0) setOutings([...outingsToInsert, ...outings])
        if (user) {
          // Outings first: a kept title's viewings may carry an outing_id
          // back-reference, which needs its cinema_outings row to already
          // exist before insertTitleToDb writes them (rule §5.13).
          await Promise.all(outingsToInsert.map((o) => insertOutingToDb(user.id, o)))
          await Promise.all(newTitles.map((t) => insertTitleToDb(user.id, t)))
        }
      }

      const added = newTitles.length
      setMessage({
        type: 'success',
        text: `Imported ${added} title${added !== 1 ? 's' : ''}${skipped > 0 ? `, skipped ${skipped} duplicate${skipped !== 1 ? 's' : ''}` : ''}.`,
      })
    } catch (err: any) {
      setMessage({ type: 'error', text: err.message || 'Import failed.' })
    } finally {
      setImporting(false)
    }
  }

  // Letterboxd CSV import (KP-045 prototype) — accepts one file from the
  // Letterboxd data-export zip (watched.csv, ratings.csv, diary.csv,
  // watchlist.csv). Each film resolves to TMDB by name+year, so large
  // histories take a while; progress + cancel keep it honest.
  async function handleLetterboxdFile(e: React.ChangeEvent<HTMLInputElement>) {
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
      const existingMovieIds = new Set(
        titles.filter((t) => t.type === 'movie' && t.tmdbId != null).map((t) => t.tmdbId)
      )
      const { imported, unmatched, duplicates } = await resolveLetterboxdRows(rows, {
        status,
        isDuplicate: (tmdbId) => existingMovieIds.has(tmdbId),
        onProgress: (done, total) => setLbProgress({ done, total }),
        isCancelled: () => lbCancelRef.current,
      })

      if (imported.length > 0) {
        setTitles([...imported, ...titles])
        if (user) await Promise.all(imported.map((t) => insertTitleToDb(user.id, t)))
      }

      const parts = [`Imported ${imported.length} film${imported.length !== 1 ? 's' : ''}`]
      if (duplicates > 0) parts.push(`skipped ${duplicates} already in your library`)
      if (unmatched.length > 0) {
        const shown = unmatched.slice(0, 5).join(', ')
        parts.push(`couldn't match ${unmatched.length}: ${shown}${unmatched.length > 5 ? `, +${unmatched.length - 5} more` : ''}`)
      }
      if (lbCancelRef.current) parts.push('(cancelled early)')
      setMessage({ type: 'success', text: `${parts.join(' · ')}.` })
    } catch (err: any) {
      setMessage({ type: 'error', text: err.message || 'Letterboxd import failed.' })
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
      description="Export your entire library as a JSON file, import a previously exported archive, or bring your watch history and ratings over from a Letterboxd CSV export. Duplicates are skipped on import."
    >
      <MessageBanner message={message} />
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
    </Section>
  )
}

// ─── Maintenance ────────────────────────────────────────────────────────────

function MaintenanceSection() {
  const { user, titles, updateTitle } = useAppStore(
    useShallow((s) => ({ user: s.user, titles: s.titles, updateTitle: s.updateTitle }))
  )
  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState(0)
  const [message, setMessage] = useState<Message | null>(null)
  const cancelRef = useRef(false)

  const eligible = titles.filter((t) => t.tmdbId)

  async function handleRefreshAll() {
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
        updateTitle(title.id, patch)
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
        <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-paper-faint">
          The Projection Room
        </span>
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
    <div className="flex items-start overflow-x-auto pb-3 mb-[clamp(24px,4vw,40px)] border-b border-[var(--line)]">
      {items.map((item, i) => (
        <div key={item.sub} className="flex items-stretch shrink-0">
          {i > 0 && <div className="w-px bg-[var(--line-2)] mx-6 sm:mx-8 self-stretch" />}
          <div className="flex flex-col">
            <div className="stat-num text-[clamp(22px,2.6vw,34px)]">{item.value}</div>
            <div className="font-mono text-[10px] tracking-[0.18em] uppercase text-paper-faint mt-1.5 whitespace-nowrap">
              {item.sub}
            </div>
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
  const effectiveProfile = authed ? profile : null

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
  }, [authed])

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
        <div className="col-span-12 lg:col-span-9 xl:col-span-7 space-y-10">
          <AccountSection profile={effectiveProfile} />

          {authed && (
            <IdentitySection
              key={effectiveProfile?.user_id ?? 'pending'}
              profile={effectiveProfile}
              onProfileChange={setProfile}
            />
          )}
          {authed && <SecuritySection />}

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
