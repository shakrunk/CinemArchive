import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function fmtDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

/** Format a YYYY-MM-DD date as e.g. "Jul 16, 2010", parsing as local-naive to avoid TZ drift. */
export function fmtReleaseDate(ymd: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(ymd)
  if (!m) return ymd
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

/** Map an ISO 639-1 language code to its English display name, falling back to the upper-cased code. */
export function languageName(code: string): string {
  try {
    const name = new Intl.DisplayNames(['en'], { type: 'language' }).of(code)
    if (name && name.toLowerCase() !== code.toLowerCase()) return name
  } catch {
    // Intl.DisplayNames unsupported — fall through to the raw code.
  }
  return code.toUpperCase()
}

/** '⌘' on macOS/iOS, 'Ctrl' everywhere else. */
export const modKey: '⌘' | 'Ctrl' =
  typeof navigator !== 'undefined' && /Mac|iPhone|iPad|iPod/.test(navigator.platform)
    ? '⌘'
    : 'Ctrl'

export function fmtDateTime(iso: string): { date: string; time: string } {
  const d = new Date(iso)
  return {
    date: d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }),
    time: d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true }),
  }
}
