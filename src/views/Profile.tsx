import { useState, useEffect, useRef } from 'react'
import { useShallow } from 'zustand/react/shallow'
import {
  Mail, Key, Plus, Trash2, Copy, Check, LogOut, Fingerprint, Shield, Loader2,
  Download, Upload, Users, UserPlus, Ban, Eye, EyeOff, Inbox, X, Activity, Star,
  UserCircle, Sun, Moon, Pencil, CalendarDays, Film, Aperture, Terminal, Lock,
  LayoutGrid, ChevronUp, ChevronDown,
} from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import {
  isSupabaseConfigured,
  signInWithEmail,
  signInWithPasskey,
  signOut,
  registerPasskey,
  createSharedKey,
  revokeSharedKey,
  listSharedKeys,
  findUserByEmail,
  sendFriendRequest,
  acceptFriendRequest,
  declineFriendRequest,
  blockFriend,
  listFriendships,
  getMyProfile,
  updateMyProfile,
  type FriendshipView,
  type MyProfile,
} from 'src/lib/auth'
import { exportLibrary, parseImportFile } from 'src/lib/export-import'
import {
  insertTitleToDb,
  fetchRecommendations, markRecommendationRead, dismissRecommendation, type Recommendation,
  fetchFriendActivityFeed, type ActivityEvent,
} from 'src/lib/db'
import { applyTheme } from 'src/lib/theme'
import type { Theme } from 'src/store/useAppStore'
import { NAV_ITEM_LABELS, type NavItemId } from 'src/lib/navigation'
import { isThemeDiscovered } from 'src/lib/easterEggThemes'

// ─── Shared bits ──────────────────────────────────────────────────────────────

interface Message {
  type: 'success' | 'error'
  text: string
}

function MessageBanner({ message }: { message: Message | null }) {
  if (!message) return null
  return (
    <div
      className={cn(
        'p-3 rounded-lg text-xs font-sans leading-normal border',
        message.type === 'success'
          ? 'bg-amber/10 border-amber/30 text-amber'
          : 'bg-destructive/10 border-destructive/30 text-destructive'
      )}
    >
      {message.text}
    </div>
  )
}

const SECTION_NAV: { id: string; label: string; Icon: typeof Shield; authOnly: boolean }[] = [
  { id: 'account', label: 'Account', Icon: UserCircle, authOnly: false },
  { id: 'identity', label: 'Identity', Icon: Pencil, authOnly: true },
  { id: 'security', label: 'Security', Icon: Shield, authOnly: true },
  { id: 'appearance', label: 'Appearance', Icon: Sun, authOnly: false },
  { id: 'navigation', label: 'Navigation', Icon: LayoutGrid, authOnly: false },
  { id: 'sharing', label: 'Shared Links', Icon: Key, authOnly: true },
  { id: 'friends', label: 'Friends', Icon: Users, authOnly: true },
  { id: 'inbox', label: 'Recommendations', Icon: Inbox, authOnly: true },
  { id: 'activity', label: 'Friend Activity', Icon: Activity, authOnly: true },
  { id: 'data', label: 'Data & Portability', Icon: Download, authOnly: false },
]

/** Section shell: anchor target + uniform heading/description/card framing. */
function Section({
  id,
  title,
  Icon,
  description,
  children,
}: {
  id: string
  title: string
  Icon: typeof Shield
  description?: string
  children: React.ReactNode
}) {
  return (
    <section id={`settings-${id}`} className="scroll-mt-24">
      <h2 className="font-sans text-xs uppercase tracking-widest text-muted-foreground flex items-center gap-1.5 mb-2">
        <Icon className="w-3.5 h-3.5 text-amber" />
        {title}
      </h2>
      {description && (
        <p className="font-sans text-xs text-muted-foreground leading-relaxed mb-3 max-w-[60ch]">
          {description}
        </p>
      )}
      <div
        className="rounded-xl border p-4 sm:p-5 space-y-4"
        style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
      >
        {children}
      </div>
    </section>
  )
}

function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('')
}

// ─── Account ──────────────────────────────────────────────────────────────────

function SignInCard() {
  const [email, setEmail] = useState('')
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)

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
        >
          <Fingerprint className="w-4 h-4" />
        </Button>
      </div>
    </form>
  )
}

function AccountSection({ profile }: { profile: MyProfile | null }) {
  const user = useAppStore((s) => s.user)
  const setUser = useAppStore((s) => s.setUser)
  const isSharedView = useAppStore((s) => s.isSharedView)

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
  const theme = useAppStore((s) => s.theme)
  const setTheme = useAppStore((s) => s.setTheme)
  const unlockedThemes = useAppStore((s) => s.unlockedThemes)
  const titles = useAppStore((s) => s.titles)

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

function NavigationSection() {
  const navPrefs = useAppStore((s) => s.navPrefs)
  const moveNavItem = useAppStore((s) => s.moveNavItem)
  const toggleNavItemHidden = useAppStore((s) => s.toggleNavItemHidden)
  const setNavCompact = useAppStore((s) => s.setNavCompact)

  return (
    <Section
      id="navigation"
      title="Navigation"
      Icon={LayoutGrid}
      description="Reorder or hide tabs in the top bar and bottom nav. Hidden tabs stay reachable from the command palette (⌘K)."
    >
      <div className="space-y-2" role="list" aria-label="Navigation tabs">
        {navPrefs.order.map((id: NavItemId, i) => {
          const hidden = navPrefs.hidden.includes(id)
          return (
            <div
              key={id}
              role="listitem"
              className="flex items-center gap-3 rounded-lg border p-2.5"
              style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
            >
              <div className="flex flex-col -my-1">
                <button
                  disabled={i === 0}
                  onClick={() => moveNavItem(id, 'up')}
                  aria-label={`Move ${NAV_ITEM_LABELS[id]} up`}
                  className="text-paper-faint hover:text-amber disabled:opacity-25 disabled:hover:text-paper-faint transition-colors"
                >
                  <ChevronUp className="w-3.5 h-3.5" />
                </button>
                <button
                  disabled={i === navPrefs.order.length - 1}
                  onClick={() => moveNavItem(id, 'down')}
                  aria-label={`Move ${NAV_ITEM_LABELS[id]} down`}
                  className="text-paper-faint hover:text-amber disabled:opacity-25 disabled:hover:text-paper-faint transition-colors"
                >
                  <ChevronDown className="w-3.5 h-3.5" />
                </button>
              </div>
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

function SharingSection() {
  const [sharedKeys, setSharedKeys] = useState<SharedKey[]>([])
  const [loading, setLoading] = useState(false)
  const [newLabel, setNewLabel] = useState('')
  const [generating, setGenerating] = useState(false)
  const [copiedKeyId, setCopiedKeyId] = useState<string | null>(null)

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
    setGenerating(true)
    try {
      await createSharedKey(newLabel.trim() || undefined)
      setNewLabel('')
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
    navigator.clipboard.writeText(link)
    setCopiedKeyId(keyId)
    setTimeout(() => setCopiedKeyId(null), 2000)
  }

  const activeKeys = sharedKeys.filter((k) => k.is_active)

  return (
    <Section
      id="sharing"
      title="Shared Access Links"
      Icon={Key}
      description="Generate read-only links to share your movie diary. People using these links see a live poster wall of your archive, but cannot edit."
    >
      <form onSubmit={handleGenerate} className="flex gap-2 max-w-md">
        <Input
          aria-label="Label for new shared link"
          placeholder="Label (e.g. My Website, Friends)"
          value={newLabel}
          onChange={(e) => setNewLabel(e.target.value)}
          className="bg-secondary/50 border-border text-xs"
        />
        <Button
          type="submit"
          disabled={generating}
          className="bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] shrink-0"
          aria-label="Generate shared link"
        >
          {generating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
        </Button>
      </form>

      <div className="space-y-2">
        {loading ? (
          <div className="text-center py-4 text-xs font-mono text-muted-foreground">Loading links...</div>
        ) : activeKeys.length === 0 ? (
          <div className="text-center py-4 text-xs font-sans text-muted-foreground italic">No active sharing keys generated.</div>
        ) : (
          activeKeys.map((k) => (
            <div key={k.id} className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="font-sans text-xs text-paper font-medium truncate">{k.label || 'Unnamed Link'}</p>
                <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                  Created: {new Date(k.created_at).toLocaleDateString()}
                </p>
                {k.last_used_at && (
                  <p className="font-mono text-[9px] text-amber-muted mt-0.5">
                    Last used: {new Date(k.last_used_at).toLocaleDateString()}
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
    </Section>
  )
}

// ─── Friends ──────────────────────────────────────────────────────────────────

function FriendsSection() {
  const user = useAppStore((s) => s.user)
  const loadFriendLibrary = useAppStore((s) => s.loadFriendLibrary)

  const [friendEmail, setFriendEmail] = useState('')
  const [sending, setSending] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  const [friendships, setFriendships] = useState<FriendshipView[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    void loadFriendships()
  }, [])

  async function loadFriendships() {
    setLoading(true)
    try {
      const list = await listFriendships()
      setFriendships(list)
    } catch (err) {
      console.error('Failed to load friendships:', err)
    } finally {
      setLoading(false)
    }
  }

  async function handleSendRequest(e: React.FormEvent) {
    e.preventDefault()
    if (!friendEmail.trim()) return
    setSending(true)
    setMessage(null)
    try {
      const found = await findUserByEmail(friendEmail)
      if (!found) {
        setMessage({ type: 'error', text: 'No user found with that email.' })
        return
      }
      await sendFriendRequest(found.user_id)
      setFriendEmail('')
      setMessage({ type: 'success', text: 'Friend request sent.' })
      await loadFriendships()
    } catch (err: any) {
      console.error(err)
      setMessage({ type: 'error', text: err.message || 'Failed to send friend request.' })
    } finally {
      setSending(false)
    }
  }

  async function handleAccept(requesterId: string) {
    try {
      await acceptFriendRequest(requesterId)
      await loadFriendships()
    } catch (err) {
      console.error('Failed to accept friend request:', err)
    }
  }

  async function handleDecline(requesterId: string) {
    try {
      await declineFriendRequest(requesterId)
      await loadFriendships()
    } catch (err) {
      console.error('Failed to decline friend request:', err)
    }
  }

  async function handleBlock(targetId: string) {
    if (!confirm('Block this user? They will no longer be able to send you friend requests or view your library.')) return
    try {
      await blockFriend(targetId)
      await loadFriendships()
    } catch (err) {
      console.error('Failed to block user:', err)
    }
  }

  return (
    <Section
      id="friends"
      title="Friends"
      Icon={Users}
      description="Add a friend by email to share your ledger and send them recommendations."
    >
      <form onSubmit={handleSendRequest} className="flex gap-2 max-w-md">
        <Input
          aria-label="Friend's email"
          type="email"
          placeholder="friend@example.com"
          value={friendEmail}
          onChange={(e) => setFriendEmail(e.target.value)}
          className="bg-secondary/50 border-border text-xs"
        />
        <Button
          type="submit"
          disabled={sending}
          className="bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] shrink-0"
          aria-label="Send friend request"
        >
          {sending ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <UserPlus className="w-3.5 h-3.5" />}
        </Button>
      </form>

      <MessageBanner message={message} />

      <div className="space-y-2">
        {loading ? (
          <div className="text-center py-4 text-xs font-mono text-muted-foreground">Loading friends...</div>
        ) : friendships.length === 0 ? (
          <div className="text-center py-4 text-xs font-sans text-muted-foreground italic">No friends yet.</div>
        ) : (
          friendships.map((f) => (
            <div key={f.friend_user_id} className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="font-sans text-xs text-paper font-medium truncate">
                  {f.display_name || f.username || 'Unknown user'}
                </p>
                <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                  {f.status === 'pending' && f.requested_by === user?.id && 'Request sent'}
                  {f.status === 'pending' && f.requested_by !== user?.id && 'Wants to be friends'}
                  {f.status === 'accepted' && 'Friends'}
                  {f.status === 'blocked' && (f.blocked_by === user?.id ? 'Blocked' : 'Unavailable')}
                </p>
              </div>
              <div className="flex gap-1 shrink-0">
                {f.status === 'pending' && f.requested_by !== user?.id && (
                  <>
                    <Button
                      size="sm"
                      onClick={() => handleAccept(f.friend_user_id)}
                      className="bg-secondary hover:bg-amber/20 hover:text-amber text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="Accept"
                      aria-label="Accept friend request"
                    >
                      <Check className="w-3.5 h-3.5" />
                    </Button>
                    <Button
                      size="sm"
                      onClick={() => handleDecline(f.friend_user_id)}
                      className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="Decline"
                      aria-label="Decline friend request"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </Button>
                  </>
                )}
                {f.status === 'accepted' && (
                  <>
                    <Button
                      size="sm"
                      onClick={() => void loadFriendLibrary(f.friend_user_id, f.display_name || f.username || 'Friend')}
                      className="bg-secondary hover:bg-amber/20 hover:text-amber text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="View Library"
                      aria-label={`View ${f.display_name || f.username || 'friend'}'s library`}
                    >
                      <Eye className="w-3.5 h-3.5" />
                    </Button>
                    <Button
                      size="sm"
                      onClick={() => handleBlock(f.friend_user_id)}
                      className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="Block"
                      aria-label="Block user"
                    >
                      <Ban className="w-3.5 h-3.5" />
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

// ─── Recommendations inbox ────────────────────────────────────────────────────

function InboxSection() {
  const [recommendations, setRecommendations] = useState<Recommendation[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    void load()
  }, [])

  async function load() {
    setLoading(true)
    try {
      const list = await fetchRecommendations()
      setRecommendations(list)
    } catch (err) {
      console.error('Failed to load recommendations:', err)
    } finally {
      setLoading(false)
    }
  }

  async function handleOpen(rec: Recommendation) {
    if (rec.status !== 'unread') return
    setRecommendations((prev) => prev.map((r) => (r.id === rec.id ? { ...r, status: 'read' } : r)))
    try {
      await markRecommendationRead(rec.id)
    } catch (err) {
      console.error('Failed to mark recommendation read:', err)
    }
  }

  async function handleDismiss(id: string) {
    setRecommendations((prev) => prev.filter((r) => r.id !== id))
    try {
      await dismissRecommendation(id)
    } catch (err) {
      console.error('Failed to dismiss recommendation:', err)
      await load()
    }
  }

  const unreadCount = recommendations.filter((r) => r.status === 'unread').length

  return (
    <Section
      id="inbox"
      title="Recommendations"
      Icon={Inbox}
      description={unreadCount > 0 ? `${unreadCount} new recommendation${unreadCount !== 1 ? 's' : ''} from friends.` : 'Titles friends have sent your way.'}
    >
      <div className="space-y-2">
        {loading ? (
          <div className="text-center py-4 text-xs font-mono text-muted-foreground">Loading recommendations...</div>
        ) : recommendations.length === 0 ? (
          <div className="text-center py-4 text-xs font-sans text-muted-foreground italic">Nothing sent your way yet.</div>
        ) : (
          recommendations.map((r) => (
            <div
              key={r.id}
              onClick={() => handleOpen(r)}
              className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center gap-3 cursor-default"
            >
              {r.posterUrl && (
                <img src={r.posterUrl} alt="" className="w-8 h-12 object-cover rounded shrink-0" />
              )}
              <div className="min-w-0 flex-1">
                <p className="font-sans text-xs text-paper font-medium truncate flex items-center gap-1.5">
                  {r.status === 'unread' && <span className="w-1.5 h-1.5 rounded-full bg-amber shrink-0" />}
                  {r.title}
                  {r.year ? ` (${r.year})` : ''}
                </p>
                <p className="font-mono text-[9px] text-muted-foreground mt-0.5 truncate">
                  from {r.senderDisplayName || r.senderUsername || 'a friend'}
                </p>
              </div>
              <Button
                size="sm"
                onClick={(e) => {
                  e.stopPropagation()
                  handleDismiss(r.id)
                }}
                className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center shrink-0"
                title="Dismiss"
                aria-label="Dismiss recommendation"
              >
                <X className="w-3.5 h-3.5" />
              </Button>
            </div>
          ))
        )}
      </div>
    </Section>
  )
}

// ─── Friend activity ──────────────────────────────────────────────────────────

function ActivitySection() {
  const markActivityFeedSeen = useAppStore((s) => s.markActivityFeedSeen)
  const [feed, setFeed] = useState<ActivityEvent[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    fetchFriendActivityFeed()
      .then((f) => {
        if (cancelled) return
        setFeed(f)
        markActivityFeedSeen()
      })
      .catch((err) => console.error('Failed to load friend activity feed:', err))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <Section id="activity" title="Friend Activity" Icon={Activity} description="What your friends have been adding and watching lately.">
      <div className="space-y-2">
        {loading ? (
          <div className="text-center py-4 text-xs font-mono text-muted-foreground">Loading activity...</div>
        ) : feed.length === 0 ? (
          <div className="text-center py-4 text-xs font-sans text-muted-foreground italic">No friend activity yet.</div>
        ) : (
          feed.map((e) => (
            <div
              key={`${e.type}:${e.titleId}:${e.eventAt}`}
              className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center gap-3"
            >
              {e.posterUrl && <img src={e.posterUrl} alt="" className="w-8 h-12 object-cover rounded shrink-0" />}
              <div className="min-w-0 flex-1">
                <p className="font-sans text-xs text-paper truncate">
                  <span className="font-medium">{e.friendDisplayName || e.friendUsername || 'A friend'}</span>{' '}
                  {e.type === 'title_added' ? 'added' : 'watched'}{' '}
                  <span className="font-medium">
                    {e.title}
                    {e.year ? ` (${e.year})` : ''}
                  </span>
                  {e.type === 'viewing_logged' && e.rating != null && (
                    <span className="inline-flex items-center gap-0.5 ml-1.5 text-amber">
                      <Star className="w-3 h-3 fill-current" />
                      {e.rating}
                    </span>
                  )}
                </p>
                <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                  {new Date(e.eventAt).toLocaleDateString()}
                </p>
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
  const { user, titles, setTitles } = useAppStore(
    useShallow((s) => ({ user: s.user, titles: s.titles, setTitles: s.setTitles }))
  )
  const [importing, setImporting] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  function handleExport() {
    exportLibrary(titles)
  }

  async function handleImportFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    if (fileInputRef.current) fileInputRef.current.value = ''

    setImporting(true)
    setMessage(null)
    try {
      const imported = await parseImportFile(file)
      const existingKeys = new Set(titles.map((t) => `${t.tmdbId}:${t.type}`))
      const newTitles = imported.filter((t) => !existingKeys.has(`${t.tmdbId}:${t.type}`))
      const skipped = imported.length - newTitles.length

      if (newTitles.length > 0) {
        setTitles([...newTitles, ...titles])
        if (user) {
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

  return (
    <Section
      id="data"
      title="Data & Portability"
      Icon={Download}
      description="Export your entire library as a JSON file, or import a previously exported archive. Duplicates are skipped on import."
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
            ? 'Your account, your look, and who gets a seat in your screening room.'
            : 'Sign in to unlock sharing, friends, and cross-device sync — appearance and data tools work right here.'}
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
          {authed && <FriendsSection />}
          {authed && <InboxSection />}
          {authed && <ActivitySection />}

          <DataSection />

          {!authed && isSupabaseConfigured && !isSharedView && (
            <p className="font-sans text-xs text-muted-foreground flex items-center gap-1.5">
              <Film className="w-3.5 h-3.5 text-amber" />
              Shared links, friends, recommendations, and activity unlock once you sign in.
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
