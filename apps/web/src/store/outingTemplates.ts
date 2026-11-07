import { CINEMA_FORMATS, type CinemaFormat } from './mockData'

// Reusable outing shapes (#204): the venue, company and format of a recurring habit, plus an
// optional usual weekday and showtime. They mirror Android's OutingTemplate.kt case for case;
// the contract is docs/android-contracts/moviegoing-prefs.md. Companions are names only, as on
// a saved outing without a linked friend.

export const MAX_TEMPLATES = 12
export const MAX_TEMPLATE_NAME = 40
export const MAX_TEMPLATE_VENUE = 100
export const MAX_TEMPLATE_COMPANIONS = 6
export const MAX_TEMPLATE_COMPANION_NAME = 40

/** The showtime used when a template has a weekday but no time. */
export const DEFAULT_TEMPLATE_TIME = '19:00'

const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/

export interface OutingTemplate {
  id: string
  name: string
  venue?: string
  companions: string[]
  format?: CinemaFormat
  /** ISO weekday: 1 = Monday ... 7 = Sunday. */
  weekday?: number
  /** `HH:mm`, 24-hour. */
  time?: string
}

/** The stored spelling of a format, matched the way Android's `CinemaFormat.fromWire` does. */
export function templateFormat(value: unknown): CinemaFormat | null {
  if (typeof value !== 'string') return null
  return CINEMA_FORMATS.find((f) => f.toLowerCase() === value.toLowerCase()) ?? null
}

export const isValidTemplateWeekday = (value: unknown): value is number =>
  typeof value === 'number' && Number.isInteger(value) && value >= 1 && value <= 7

export const isValidTemplateTime = (value: unknown): value is string => typeof value === 'string' && TIME_RE.test(value)

/** Builds a template from trimmed text: a blank name is rejected, a blank venue or companion is
 *  dropped, an over-long value is rejected rather than silently cut. */
export function createOutingTemplate(input: {
  id: string
  name: string
  venue?: string | null
  companions?: string[]
  format?: CinemaFormat | null
  weekday?: number | null
  time?: string | null
}): OutingTemplate | null {
  const name = input.name.trim()
  if (!input.id.trim() || !name || name.length > MAX_TEMPLATE_NAME) return null
  const venue = input.venue?.trim() || undefined
  if (venue && venue.length > MAX_TEMPLATE_VENUE) return null
  const seen = new Set<string>()
  const companions: string[] = []
  for (const raw of input.companions ?? []) {
    const companion = raw.trim()
    if (!companion || seen.has(companion.toLowerCase())) continue
    seen.add(companion.toLowerCase())
    companions.push(companion)
  }
  if (companions.length > MAX_TEMPLATE_COMPANIONS || companions.some((c) => c.length > MAX_TEMPLATE_COMPANION_NAME)) return null
  if (input.weekday != null && !isValidTemplateWeekday(input.weekday)) return null
  if (input.time != null && !isValidTemplateTime(input.time)) return null
  const template: OutingTemplate = { id: input.id, name, companions }
  if (venue) template.venue = venue
  if (input.format) template.format = input.format
  if (input.weekday != null) template.weekday = input.weekday
  if (input.time != null) template.time = input.time
  return template
}

const pad = (n: number) => String(n).padStart(2, '0')

/** ISO weekday (1 = Monday ... 7 = Sunday) of a local date. */
export const isoWeekday = (date: Date): number => (date.getDay() === 0 ? 7 : date.getDay())

/** The first showtime strictly after `now` that fits the template: its weekday (today counts
 *  while its time is still ahead), else today, else tomorrow once today's time has gone. */
export function nextTemplateShowtime(template: OutingTemplate, now: Date): { date: string; time: string } {
  const time = template.time ?? DEFAULT_TEMPLATE_TIME
  const [hours, minutes] = time.split(':').map(Number)
  for (let offset = 0; offset <= 8; offset++) {
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset, hours, minutes)
    if (template.weekday != null && isoWeekday(day) !== template.weekday) continue
    if (day.getTime() > now.getTime()) {
      return { date: `${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`, time }
    }
  }
  // Unreachable for a valid template: any weekday recurs within 8 days.
  return { date: `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`, time }
}

const WEEKDAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

/** A short line for a template row: "Tue 19:00 · AMC Georgetown · IMAX · with Sam". */
export function templateSummary(template: OutingTemplate): string {
  const parts: string[] = []
  const when = [template.weekday != null ? WEEKDAY_LABELS[template.weekday - 1] : null, template.time ?? null]
    .filter((p): p is string => !!p)
    .join(' ')
  if (when) parts.push(when)
  if (template.venue) parts.push(template.venue)
  if (template.format) parts.push(template.format)
  if (template.companions.length) parts.push(`with ${template.companions.join(', ')}`)
  return parts.join(' · ')
}
