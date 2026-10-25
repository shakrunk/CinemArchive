import { describe, expect, it } from 'vitest'
import fixture from '../../../../docs/android-contracts/fixtures/theme-sync.json'
import { applyThemePrefsToWeb, encodeWebAppearance, parseThemePrefs } from './themeSync'
import type { Theme, ThemeMode } from 'src/store/useAppStore'

// The cases are shared with Android's ThemeSyncParityTest; see docs/android-contracts/theme-sync.md.
describe('theme sync contract', () => {
  for (const c of fixture.encode.filter((e) => 'web' in e)) {
    it(`encodes ${c.name}`, () => {
      const web = c.web as { theme: Theme; themeMode: ThemeMode; pureBlack: boolean }
      expect(encodeWebAppearance(web)).toEqual(c.expected)
    })
  }

  for (const c of fixture.apply) {
    it(`applies ${c.name}`, () => {
      const prefs = parseThemePrefs(c.remote)
      expect(prefs).not.toBeNull()
      const current = c.web.current as { theme: Theme; themeMode: ThemeMode; unlocked: Theme[] }
      expect(applyThemePrefsToWeb(prefs!, { ...current, unlockedThemes: ['dark', 'light', ...current.unlocked] }, c.web.systemTheme as Theme))
        .toEqual(c.web.expected)
    })
  }

  for (const c of fixture.invalid) {
    it(`rejects ${c.name}`, () => {
      expect(parseThemePrefs(c.remote)).toBeNull()
    })
  }
})
