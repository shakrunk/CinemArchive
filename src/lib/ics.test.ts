import { describe, expect, it, vi, afterEach } from 'vitest'
import { buildOutingIcs, buildOutingIcsFromSharePayload, outingIcsFilename, formatOutingShareSnippet, shareOutingSnippet } from './ics'
import type { OutingSharePayload } from 'src/store/outings'
import type { CinemaOuting } from 'src/store/mockData'

function makeOuting(overrides: Partial<CinemaOuting> = {}): CinemaOuting {
  return {
    id: 'outing-1',
    titleId: 't1',
    showtime: '2026-07-17T19:30:00.000-06:00',
    previewsMinutes: 20,
    runtimeMinutes: 166,
    endsAt: '2026-07-17T22:36:00.000-06:00',
    companions: [],
    status: 'scheduled',
    createdAt: '2026-07-16T00:00:00.000Z',
    ...overrides,
  }
}

describe('outingIcsFilename', () => {
  it('slugifies the title and appends the showtime local date', () => {
    expect(outingIcsFilename('Dune: Part Three', '2026-07-17T19:30:00.000-06:00')).toBe(
      'dune-part-three-2026-07-17.ics'
    )
  })

  it('falls back to "outing" when the title has no alphanumeric characters', () => {
    expect(outingIcsFilename('!!!', '2026-07-17T19:30:00.000Z')).toBe('outing-2026-07-17.ics')
  })
})

describe('buildOutingIcs', () => {
  it('includes DTSTART/DTEND/SUMMARY/LOCATION and a 2-hour VALARM', () => {
    const outing = makeOuting({ venue: 'AMC Georgetown', companions: [{ name: 'Alex' }, { name: 'Sam' }] })
    const ics = buildOutingIcs(outing, 'Dune Part Three')

    expect(ics).toContain('BEGIN:VEVENT')
    expect(ics).toContain('SUMMARY:🎬 Dune Part Three — AMC Georgetown')
    expect(ics).toContain('LOCATION:AMC Georgetown')
    expect(ics).toContain('DESCRIPTION:With Alex & Sam')
    expect(ics).toContain('TRIGGER:-PT2H')
    expect(ics).toContain('DTSTART:20260718T013000Z')
    expect(ics).toContain('DTEND:20260718T043600Z')
    expect(ics).toContain(`UID:${outing.id}@cinemarchive`)
  })

  it('omits LOCATION and the event DESCRIPTION when venue and receipt fields are blank', () => {
    const outing = makeOuting()
    const ics = buildOutingIcs(outing, 'The Long Reel')
    expect(ics).not.toContain('LOCATION:')
    // The VALARM always carries its own "DESCRIPTION:Reminder" line — only
    // the event-level DESCRIPTION should be omitted.
    expect(ics.match(/DESCRIPTION:/g)).toEqual(['DESCRIPTION:'])
    expect(ics).toContain('DESCRIPTION:Reminder')
    expect(ics).toContain('SUMMARY:🎬 The Long Reel')
  })

  it('escapes commas/semicolons in free text fields', () => {
    const outing = makeOuting({ venue: 'AMC, Georgetown; IMAX', notes: 'Bring snacks, arrive early' })
    const ics = buildOutingIcs(outing, 'The Long Reel')
    expect(ics).toContain('LOCATION:AMC\\, Georgetown\\; IMAX')
    expect(ics).toContain('Bring snacks\\, arrive early')
  })
})

describe('buildOutingIcsFromSharePayload', () => {
  function makePayload(overrides: Partial<OutingSharePayload> = {}): OutingSharePayload {
    return {
      tmdbId: 42,
      type: 'movie',
      title: 'Dune Part Three',
      showtime: '2026-07-17T19:30:00.000-06:00',
      endsAt: '2026-07-17T22:36:00.000-06:00',
      companions: [],
      ...overrides,
    }
  }

  it('builds a VEVENT from the snapshot alone, with a tmdbId+showtime UID', () => {
    const ics = buildOutingIcsFromSharePayload(makePayload({ venue: 'AMC Georgetown', companions: ['Alex', 'Sam'] }))
    expect(ics).toContain('SUMMARY:🎬 Dune Part Three — AMC Georgetown')
    expect(ics).toContain('LOCATION:AMC Georgetown')
    expect(ics).toContain('DESCRIPTION:With Alex & Sam')
    expect(ics).toContain('UID:share-42-2026-07-17T19:30:00.000-06:00@cinemarchive')
  })

  it('omits LOCATION/DESCRIPTION when the snapshot has neither venue nor companions', () => {
    const ics = buildOutingIcsFromSharePayload(makePayload())
    expect(ics).not.toContain('LOCATION:')
    // Only the VALARM's own "DESCRIPTION:Reminder" line should remain.
    expect(ics.match(/DESCRIPTION:/g)).toEqual(['DESCRIPTION:'])
    expect(ics).toContain('DESCRIPTION:Reminder')
  })
})

describe('formatOutingShareSnippet', () => {
  it('reads like a text a friend would send, omitting blank fields', () => {
    const snippet = formatOutingShareSnippet(
      'Dune Part Three',
      '2026-07-17T19:30:00.000-06:00',
      'AMC Georgetown',
      'IMAX',
      'H12'
    )
    expect(snippet).toBe("🎬 Dune Part Three — Fri Jul 17, 7:30 PM · AMC Georgetown · IMAX · I'm in H12 — grab a seat nearby!")
  })

  it('omits venue/format/seat when unset', () => {
    const snippet = formatOutingShareSnippet('The Long Reel', '2026-07-17T19:30:00.000-06:00')
    expect(snippet).toBe('🎬 The Long Reel — Fri Jul 17, 7:30 PM')
  })

  it('omits the Standard format (the default, not worth mentioning)', () => {
    const snippet = formatOutingShareSnippet('The Long Reel', '2026-07-17T19:30:00.000-06:00', 'AMC Georgetown', 'Standard')
    expect(snippet).toBe('🎬 The Long Reel — Fri Jul 17, 7:30 PM · AMC Georgetown')
  })
})

describe('shareOutingSnippet', () => {
  const originalShare = (navigator as any).share
  const originalClipboard = navigator.clipboard

  afterEach(() => {
    Object.defineProperty(navigator, 'share', { value: originalShare, configurable: true })
    Object.defineProperty(navigator, 'clipboard', { value: originalClipboard, configurable: true })
    vi.restoreAllMocks()
  })

  it('uses navigator.share when available', async () => {
    const share = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'share', { value: share, configurable: true })
    await expect(shareOutingSnippet('hello')).resolves.toBe('shared')
    expect(share).toHaveBeenCalledWith({ text: 'hello' })
  })

  it('reports a cancelled share sheet without falling back to the clipboard', async () => {
    const abortError = Object.assign(new Error('cancelled'), { name: 'AbortError' })
    const share = vi.fn().mockRejectedValue(abortError)
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'share', { value: share, configurable: true })
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    await expect(shareOutingSnippet('hello')).resolves.toBe('cancelled')
    expect(writeText).not.toHaveBeenCalled()
  })

  it('falls back to the clipboard when navigator.share is unavailable', async () => {
    Object.defineProperty(navigator, 'share', { value: undefined, configurable: true })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    await expect(shareOutingSnippet('hello')).resolves.toBe('copied')
    expect(writeText).toHaveBeenCalledWith('hello')
  })
})
