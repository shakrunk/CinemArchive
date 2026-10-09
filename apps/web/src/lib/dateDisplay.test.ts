import { afterEach, describe, expect, it, vi } from 'vitest'
import { fmtDate } from './utils'

const format = Date.prototype.toLocaleDateString
afterEach(() => vi.restoreAllMocks())

describe.each(['America/Denver', 'UTC', 'Pacific/Kiritimati', 'Pacific/Pago_Pago'])('date display in %s', (timeZone) => {
  function localZone() {
    // Supply the browser's default zone deterministically; explicit calendar
    // date formatting may override it, while actual instants must retain it.
    vi.spyOn(Date.prototype, 'toLocaleDateString').mockImplementation(function (this: Date, locales, options) {
      return format.call(this, locales, { timeZone, ...options })
    })
  }
  it('preserves date-only values across offset, leap-day and DST boundaries', () => {
    localZone()
    expect(fmtDate('2026-10-01')).toBe('Oct 1, 2026')
    expect(fmtDate('2024-02-29')).toBe('Feb 29, 2024')
    expect(fmtDate('2026-03-08')).toBe('Mar 8, 2026')
    expect(fmtDate('2026-11-01')).toBe('Nov 1, 2026')
  })
  it('continues to show full timestamps on the local calendar day', () => {
    localZone()
    const expected = new Intl.DateTimeFormat('en-US', { timeZone, month: 'short', day: 'numeric', year: 'numeric' })
    for (const iso of ['2026-10-01T00:30:00Z', '2026-10-01T23:30:00Z', '2026-10-01T00:30:00+12:00']) {
      expect(fmtDate(iso)).toBe(expected.format(new Date(iso)))
    }
  })
})
