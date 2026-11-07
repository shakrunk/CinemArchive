import { Check, Sun, Moon, Aperture, Terminal, Lock, Coffee } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { isSupabaseConfigured } from 'src/lib/auth'
import { isDevMockUser } from 'src/lib/devAuth'
import { applyTheme } from 'src/lib/theme'
import { CATPPUCCIN_FLAVORS, isLightTheme, isThemeAvailable, type Theme } from 'src/store/useAppStore'
import { ThemeModeToggle } from 'src/components/ThemeModeToggle'
import { isThemeDiscovered } from 'src/lib/easterEggThemes'
import { Section } from 'src/components/ui/section'
import { TextPreferences } from 'src/components/TextPreferences'

/** Labeled on/off switch with a one-line hint, for Appearance's device settings. */
function SettingToggle({ checked, onChange, label, hint, disabled }: {
  checked: boolean
  onChange: (checked: boolean) => void
  label: string
  hint: string
  disabled?: boolean
}) {
  return (
    <label className={cn('flex items-start gap-2.5 pt-1', disabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer')}>
      <input
        type="checkbox"
        role="switch"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className="sr-only peer"
      />
      <span
        className={cn(
          'mt-0.5 w-9 h-5 rounded-full transition-colors relative shrink-0 peer-focus-visible:ring-1 peer-focus-visible:ring-amber/60',
          checked ? 'bg-amber' : 'bg-secondary'
        )}
        aria-hidden="true"
      >
        <span className={cn('absolute top-0.5 left-0.5 w-4 h-4 rounded-full bg-white transition-transform', checked && 'translate-x-4')} />
      </span>
      <span className="min-w-0">
        <span className="block font-sans text-sm text-paper">{label}</span>
        <span className="block font-sans text-xs text-muted-foreground mt-0.5 leading-relaxed">{hint}</span>
      </span>
    </label>
  )
}

export function AppearanceSection() {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const theme = useAppStore((s) => s.theme)
  const themeMode = useAppStore((s) => s.themeMode)
  const setTheme = useAppStore((s) => s.setTheme)
  const unlockedThemes = useAppStore((s) => s.unlockedThemes)
  const titles = useAppStore((s) => s.titles)
  const pureBlack = useAppStore((s) => s.pureBlack)
  const setPureBlack = useAppStore((s) => s.setPureBlack)
  const spoilerProtection = useAppStore((s) => s.spoilerProtection)
  const setSpoilerProtection = useAppStore((s) => s.setSpoilerProtection)
  const themeSyncEnabled = useAppStore((s) => s.themeSyncEnabled)
  const setThemeSyncEnabled = useAppStore((s) => s.setThemeSyncEnabled)
  const user = useAppStore((s) => s.user)
  const canSync = isSupabaseConfigured && !!user && !isDevMockUser(user)

  const activeFlavor = CATPPUCCIN_FLAVORS.find((f) => f.value === theme)

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
    // One card for all four flavors (picked in the row below the grid), so
    // Catppuccin doesn't crowd out the house styles. Choosing the card keeps
    // the active flavor, or starts on Mocha.
    {
      value: activeFlavor?.value ?? 'catppuccin-mocha',
      label: 'Catppuccin',
      hint: activeFlavor
        ? `Soothing pastels — ${activeFlavor.label}. Pick another flavor below.`
        : 'Soothing pastels in four flavors, from light Latte to dark Mocha.',
      Icon: Coffee,
    },
  ]

  // Secret themes stay off this list entirely until their linked title has
  // been added to the library somehow — they're easter eggs to stumble on,
  // not a spoiler list of "locked" cards.
  const visibleOptions = options.filter(
    ({ value }) =>
      isThemeAvailable(value, unlockedThemes) ||
      ((value === 'noir' || value === 'matrix') && isThemeDiscovered(value, titles))
  )

  return (
    <Section
      id="appearance"
      title="Appearance"
      Icon={Sun}
      description="Pick a house style, or leave it on System to follow your OS automatically. The T key and the toggle in the top bar flip dark/light directly, which breaks out of System. A couple of styles are secret — find them by watching the right films the right way."
    >
      <div className="flex items-center justify-between gap-2 mb-4">
        <span className="font-sans text-xs text-muted-foreground">
          {themeMode === 'system' ? `Following your device (currently ${theme}).` : 'Quick toggle'}
        </span>
        <ThemeModeToggle />
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3" role="radiogroup" aria-label="Theme">
        {visibleOptions.map(({ value, label, hint, Icon, lockedHint }) => {
          const locked = !isThemeAvailable(value, unlockedThemes)
          return (
            <button
              key={value}
              role="radio"
              aria-checked={theme === value}
              disabled={locked}
              onClick={() => choose(value)}
              className={cn(
                'text-left rounded-lg border p-4 transition-colors focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60',
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
      {activeFlavor && (
        <div className="mt-3">
          <span className="block font-sans text-xs text-muted-foreground mb-2">Catppuccin flavor</span>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2" role="radiogroup" aria-label="Catppuccin flavor">
            {CATPPUCCIN_FLAVORS.map(({ value, label }) => (
              <button
                key={value}
                type="button"
                role="radio"
                aria-checked={theme === value}
                onClick={() => choose(value)}
                className={cn(
                  'rounded-lg border py-2 font-sans text-sm font-medium transition-colors focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60',
                  theme === value
                    ? 'border-amber/50 bg-amber/10 text-amber'
                    : 'border-border bg-secondary/20 text-muted-foreground hover:border-amber/25'
                )}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
      )}
      <div className="mt-4">
        <SettingToggle
          checked={pureBlack}
          onChange={(next) => {
            setPureBlack(next)
            applyTheme(theme)
          }}
          label="Pure black"
          hint={isLightTheme(theme)
            ? 'True-black background for OLED screens. Takes effect on dark themes.'
            : 'True-black background for OLED screens; cards keep their lifted surfaces.'}
        />
        <SettingToggle
          checked={canSync && themeSyncEnabled}
          disabled={!canSync}
          onChange={setThemeSyncEnabled}
          label="Sync appearance across devices"
          hint={canSync
            ? 'Keeps your theme and Pure black in step on every browser and Android device where this is on.'
            : 'Sign in to keep your theme in step across your browsers and devices.'}
        />
        <SettingToggle
          checked={spoilerProtection}
          onChange={setSpoilerProtection}
          label="Hide episode spoilers"
          hint="Hides the still, title, and synopsis of episodes you haven’t watched yet. Applies on this device."
        />
      </div>
      <TextPreferences />
    </Section>
  )
}
