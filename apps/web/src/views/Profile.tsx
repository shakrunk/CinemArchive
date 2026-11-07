import { useState, useEffect } from 'react'
import {
  Key, type LucideIcon, Download, UserCircle, Sun, Pencil, Film, LayoutGrid, Ticket, RefreshCw, Info,
} from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { isSupabaseConfigured, getMyProfile, type MyProfile } from 'src/lib/auth'
import { AccountSection } from './profile/AccountSection'
import { IdentitySection } from './profile/IdentitySection'
import { AppearanceSection } from './profile/AppearanceSection'
import { NavigationSection } from './profile/NavigationSection'
import { SharingSection } from './profile/SharingSection'
import { InvitesSection } from './profile/InvitesSection'
import { DataSection } from './profile/DataSection'
import { MaintenanceSection } from './profile/MaintenanceSection'
import { AboutSection } from './profile/AboutSection'
import { ArchiveGlance } from './profile/ArchiveGlance'

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
