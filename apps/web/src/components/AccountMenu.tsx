import { useRef, useState } from 'react'
import { User, Users, Sun, Moon, LogOut } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { signOut } from 'src/lib/auth'
import { toggleTheme } from 'src/lib/theme'
import { useClickOutside } from 'src/lib/useClickOutside'
import type { AppView } from 'src/lib/navigation'

interface AccountMenuProps {
  currentView: AppView
  onNavigate: (view: AppView) => void
}

export function AccountMenu({ currentView, onNavigate }: AccountMenuProps) {
  const theme = useAppStore((s) => s.theme)
  const setUser = useAppStore((s) => s.setUser)

  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  useClickOutside(containerRef, () => setOpen(false), open, { escape: true })

  function go(view: AppView) {
    setOpen(false)
    onNavigate(view)
  }

  async function handleSignOut() {
    setOpen(false)
    try {
      await signOut()
      setUser(null)
    } catch (err) {
      console.error('Failed to sign out:', err)
    }
  }

  const isActive = currentView === 'profile' || currentView === 'friends'

  return (
    <div className="relative" ref={containerRef}>
      <button
        onClick={() => setOpen((v) => !v)}
        aria-label="Account menu"
        aria-expanded={open}
        className={cn(
          'icon-btn relative w-9 h-9 border rounded-md text-amber border-amber/30 bg-amber/5 hover:bg-amber/10 transition-colors flex items-center justify-center focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60',
          (isActive || open) && '!bg-amber/15 border-amber/50'
        )}
      >
        <User className="w-[17px] h-[17px]" />
      </button>

      {open && (
        <div
          role="menu"
          aria-label="Account"
          className="absolute right-0 mt-2 w-52 rounded-xl overflow-hidden z-[220] shadow-xl py-1"
          style={{ background: 'rgb(var(--ink-1-rgb))', border: '1px solid var(--line)' }}
        >
          <button
            role="menuitem"
            onClick={(e) => {
              setOpen(false)
              toggleTheme({ clientX: e.clientX, clientY: e.clientY })
            }}
            className="w-full text-left flex items-center gap-2.5 px-3.5 py-2.5 font-sans text-[13px] text-paper-dim hover:text-amber hover:bg-secondary/30 transition-colors focus-visible:outline-none focus-visible:bg-secondary/30"
          >
            {theme === 'dark' ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
            {theme === 'dark' ? 'Light mode' : 'Dark mode'}
          </button>

          <button
            role="menuitem"
            aria-current={currentView === 'friends' ? 'page' : undefined}
            onClick={() => go('friends')}
            className={cn(
              'w-full text-left flex items-center gap-2.5 px-3.5 py-2.5 font-sans text-[13px] hover:bg-secondary/30 transition-colors focus-visible:outline-none focus-visible:bg-secondary/30',
              currentView === 'friends' ? 'text-amber' : 'text-paper-dim hover:text-amber'
            )}
          >
            <Users className="w-4 h-4" />
            Friends
          </button>

          <button
            role="menuitem"
            aria-current={currentView === 'profile' ? 'page' : undefined}
            onClick={() => go('profile')}
            className={cn(
              'w-full text-left flex items-center gap-2.5 px-3.5 py-2.5 font-sans text-[13px] hover:bg-secondary/30 transition-colors focus-visible:outline-none focus-visible:bg-secondary/30',
              currentView === 'profile' ? 'text-amber' : 'text-paper-dim hover:text-amber'
            )}
          >
            <User className="w-4 h-4" />
            Profile &amp; Settings
          </button>

          <div className="h-px my-1" style={{ background: 'var(--line)' }} />

          <button
            role="menuitem"
            onClick={handleSignOut}
            className="w-full text-left flex items-center gap-2.5 px-3.5 py-2.5 font-sans text-[13px] text-paper-dim hover:text-ember hover:bg-secondary/30 transition-colors focus-visible:outline-none focus-visible:bg-secondary/30"
          >
            <LogOut className="w-4 h-4" />
            Sign out
          </button>
        </div>
      )}
    </div>
  )
}
