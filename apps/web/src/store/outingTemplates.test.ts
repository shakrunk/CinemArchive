import { describe, expect, it } from 'vitest'
import {
  createOutingTemplate, nextTemplateShowtime, templateFormat, templateSummary,
  MAX_TEMPLATE_COMPANIONS, MAX_TEMPLATE_COMPANION_NAME, MAX_TEMPLATE_NAME, MAX_TEMPLATE_VENUE,
  type OutingTemplate,
} from './outingTemplates'

// Same cases as Android's OutingTemplateTest. 2026-10-27 is a Tuesday.

const template = (weekday?: number, time?: string): OutingTemplate =>
  createOutingTemplate({ id: 't1', name: 'Tuesday matinee', venue: 'AMC', companions: ['Sam'], format: 'IMAX', weekday, time })!

describe('createOutingTemplate', () => {
  it('trims text and drops blank and duplicate companions', () => {
    const made = createOutingTemplate({
      id: 't1', name: '  Date night ', venue: '  ', companions: [' Sam ', '', 'sam', 'Alex'], time: '19:30',
    })!
    expect(made).toEqual({ id: 't1', name: 'Date night', companions: ['Sam', 'Alex'], time: '19:30' })
  })

  it('rejects a blank or over-long name and over-long fields', () => {
    const make = (over: Partial<Parameters<typeof createOutingTemplate>[0]>) =>
      createOutingTemplate({ id: 't', name: 'ok', ...over })
    expect(make({ name: '   ' })).toBeNull()
    expect(make({ name: 'x'.repeat(MAX_TEMPLATE_NAME + 1) })).toBeNull()
    expect(make({ name: 'x'.repeat(MAX_TEMPLATE_NAME) })).not.toBeNull()
    expect(make({ venue: 'v'.repeat(MAX_TEMPLATE_VENUE + 1) })).toBeNull()
    expect(make({ companions: Array.from({ length: MAX_TEMPLATE_COMPANIONS + 1 }, (_, i) => `c${i}`) })).toBeNull()
    expect(make({ companions: ['c'.repeat(MAX_TEMPLATE_COMPANION_NAME + 1)] })).toBeNull()
    expect(make({ id: ' ' })).toBeNull()
  })

  it('rejects an invalid weekday or time', () => {
    const make = (over: Partial<Parameters<typeof createOutingTemplate>[0]>) =>
      createOutingTemplate({ id: 't', name: 'ok', ...over })
    for (const weekday of [0, 8, 2.5]) expect(make({ weekday })).toBeNull()
    for (const time of ['25:00', '7pm', '19:60', '9:00']) expect(make({ time })).toBeNull()
    expect(make({ weekday: 7, time: '00:00' })).not.toBeNull()
  })
})

describe('templateFormat', () => {
  it('matches stored spellings case-insensitively and rejects the rest', () => {
    expect(templateFormat('IMAX')).toBe('IMAX')
    expect(templateFormat('imax')).toBe('IMAX')
    expect(templateFormat('Betamax')).toBeNull()
    expect(templateFormat(3)).toBeNull()
  })
})

describe('nextTemplateShowtime', () => {
  it('lands a weekday template on the next matching day', () => {
    const monday = new Date(2026, 9, 26, 12, 0)
    expect(nextTemplateShowtime(template(2, '14:00'), monday)).toEqual({ date: '2026-10-27', time: '14:00' })
  })

  it('counts today while the time is still ahead', () => {
    const tuesdayMorning = new Date(2026, 9, 27, 9, 0)
    expect(nextTemplateShowtime(template(2, '14:00'), tuesdayMorning)).toEqual({ date: '2026-10-27', time: '14:00' })
  })

  it('moves to next week once the time has passed, and exactly now is not ahead', () => {
    expect(nextTemplateShowtime(template(2, '14:00'), new Date(2026, 9, 27, 20, 0))).toEqual({ date: '2026-11-03', time: '14:00' })
    expect(nextTemplateShowtime(template(2, '14:00'), new Date(2026, 9, 27, 14, 0))).toEqual({ date: '2026-11-03', time: '14:00' })
  })

  it('means today or tomorrow with no weekday', () => {
    expect(nextTemplateShowtime(template(), new Date(2026, 9, 27, 12, 0))).toEqual({ date: '2026-10-27', time: '19:00' })
    expect(nextTemplateShowtime(template(), new Date(2026, 9, 27, 23, 0))).toEqual({ date: '2026-10-28', time: '19:00' })
  })

  it('uses the default showtime for a weekday with no time', () => {
    expect(nextTemplateShowtime(template(6), new Date(2026, 9, 26, 12, 0))).toEqual({ date: '2026-10-31', time: '19:00' })
  })

  it('treats Sunday as ISO weekday 7', () => {
    expect(nextTemplateShowtime(template(7, '10:00'), new Date(2026, 9, 26, 12, 0))).toEqual({ date: '2026-11-01', time: '10:00' })
  })
})

describe('templateSummary', () => {
  it('lists only what is set', () => {
    expect(templateSummary(template(2, '14:00'))).toBe('Tue 14:00 · AMC · IMAX · with Sam')
    expect(templateSummary(template())).toBe('AMC · IMAX · with Sam')
    expect(templateSummary(createOutingTemplate({ id: 't', name: 'Bare' })!)).toBe('')
  })
})
