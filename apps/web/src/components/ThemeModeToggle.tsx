import { Sun, Moon, Monitor } from 'lucide-react'
import { useAppStore, type ThemeMode } from 'src/store/useAppStore'
import { chooseThemeMode } from 'src/lib/theme'
import { cn } from 'src/lib/utils'

const OPTIONS: { value: ThemeMode; label: string; Icon: typeof Sun }[] = [
  { value: 'light', label: 'Light mode', Icon: Sun },
  { value: 'dark', label: 'Dark mode', Icon: Moon },
  { value: 'system', label: 'Match system', Icon: Monitor },
]

interface ThemeModeToggleProps {
  className?: string
}

/** 3-way Light / Dark / System segmented control — the quick-access theme
 *  switch shown in TopBar (signed-out/shared) and AccountMenu (signed-in).
 *  Reflects `themeMode` directly, so it never disagrees with Settings →
 *  Appearance's full grid: picking noir/matrix there (or `T`/this control's
 *  own Light/Dark buttons) is an explicit override that breaks out of
 *  'system', same as everywhere else theme mode changes. */
export function ThemeModeToggle({ className }: ThemeModeToggleProps) {
  const themeMode = useAppStore((s) => s.themeMode)

  return (
    <div
      role="radiogroup"
      aria-label="Theme"
      className={cn('inline-flex items-center rounded-md border p-0.5 gap-0.5', className)}
      style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
    >
      {OPTIONS.map(({ value, label, Icon }) => {
        const active = themeMode === value
        return (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={active}
            aria-label={label}
            title={label}
            onClick={(e) => chooseThemeMode(value, { clientX: e.clientX, clientY: e.clientY })}
            className={cn(
              'flex items-center justify-center w-7 h-7 rounded transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60',
              active ? 'bg-amber/15 text-amber' : 'text-paper-dim hover:text-amber hover:bg-secondary/30'
            )}
          >
            <Icon className="w-[15px] h-[15px]" />
          </button>
        )
      })}
    </div>
  )
}
