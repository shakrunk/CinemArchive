import { useState, useEffect } from 'react'
import {
  Key, type LucideIcon, Download, UserCircle, Sun, Pencil, Film, LayoutGrid, Ticket, RefreshCw, Info,
  ExternalLink,
} from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { isSupabaseConfigured, getMyProfile, type MyProfile } from 'src/lib/auth'
import { Section } from 'src/components/ui/section'
import { Eyebrow } from 'src/components/ui/typography'
import { AccountSection } from './profile/AccountSection'
import { IdentitySection } from './profile/IdentitySection'
import { AppearanceSection } from './profile/AppearanceSection'
import { NavigationSection } from './profile/NavigationSection'
import { SharingSection } from './profile/SharingSection'
import { InvitesSection } from './profile/InvitesSection'
import { DataSection } from './profile/DataSection'
import { MaintenanceSection } from './profile/MaintenanceSection'

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
