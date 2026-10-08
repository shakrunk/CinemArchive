import { useSyncExternalStore } from 'react'

export const TEXT_PREFERENCES_KEY = 'cinemarchive-text-preferences'
export const TEXT_SIZES = [
  { value: 'small', label: 'Small', scale: 0.85 },
  { value: 'default', label: 'Default', scale: 1 },
  { value: 'large', label: 'Large', scale: 1.15 },
  { value: 'extra-large', label: 'Extra large', scale: 1.3 },
] as const
export type TextSize = (typeof TEXT_SIZES)[number]['value']
export interface TextPreferences { family: 'default' | 'lexend'; size: TextSize }
export const DEFAULT_TEXT_PREFERENCES: TextPreferences = { family: 'default', size: 'default' }

export function parseTextPreferences(raw: string | null): TextPreferences {
  try {
    const value: unknown = JSON.parse(raw ?? 'null')
    if (!value || typeof value !== 'object') return DEFAULT_TEXT_PREFERENCES
    const candidate = value as Partial<TextPreferences>
    return {
      family: candidate.family === 'lexend' ? 'lexend' : 'default',
      size: TEXT_SIZES.some((option) => option.value === candidate.size) ? candidate.size! : 'default',
    }
  } catch { return DEFAULT_TEXT_PREFERENCES }
}

function readPreferences(): TextPreferences {
  try { return parseTextPreferences(localStorage.getItem(TEXT_PREFERENCES_KEY)) }
  catch { return DEFAULT_TEXT_PREFERENCES }
}

export function textScaleFor(size: TextSize): number {
  return TEXT_SIZES.find((option) => option.value === size)!.scale
}

export function applyTextPreferences(preferences: TextPreferences): void {
  const root = document.documentElement
  root.dataset.textFamily = preferences.family
  root.dataset.textSize = preferences.size
  root.style.setProperty('--text-scale', String(textScaleFor(preferences.size)))
}

let current = readPreferences()
const listeners = new Set<() => void>()
function publish(preferences: TextPreferences) {
  applyTextPreferences(preferences)
  if (current.family === preferences.family && current.size === preferences.size) return
  current = preferences
  listeners.forEach((listener) => listener())
}

/** Applies in memory even when storage is blocked. The UI can explain that reload won't save it. */
export function saveTextPreferences(preferences: TextPreferences): boolean {
  const normalized = parseTextPreferences(JSON.stringify(preferences))
  let persisted = true
  try { localStorage.setItem(TEXT_PREFERENCES_KEY, JSON.stringify(normalized)) }
  catch { persisted = false }
  publish(normalized)
  return persisted
}

export function watchTextPreferences(): () => void {
  publish(current)
  const onStorage = (event: StorageEvent) => {
    if (event.key === TEXT_PREFERENCES_KEY || event.key === null) {
      publish(parseTextPreferences(event.key === null ? null : event.newValue))
    }
  }
  window.addEventListener('storage', onStorage)
  return () => window.removeEventListener('storage', onStorage)
}

const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}
export function useTextPreferences(): TextPreferences {
  return useSyncExternalStore(subscribe, () => current, () => DEFAULT_TEXT_PREFERENCES)
}
