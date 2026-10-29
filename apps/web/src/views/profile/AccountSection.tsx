import { LogOut, UserCircle, CalendarDays } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { useAppStore } from 'src/store/useAppStore'
import { isSupabaseConfigured, signOut, type MyProfile } from 'src/lib/auth'
import { Section } from 'src/components/ui/section'
import { SignInCard } from './SignInCard'

function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('')
}

export function AccountSection({ profile }: { profile: MyProfile | null }) {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
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
          style={{ borderColor: 'var(--line)', background: 'rgb(var(--amber-rgb) / 0.08)' }}
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
