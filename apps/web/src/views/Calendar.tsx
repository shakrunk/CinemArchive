import { useEffect, useMemo, useState } from 'react'
import { CalendarDays, ChevronLeft, ChevronRight, Film, Sparkles, Ticket, Tv } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { localTodayYmd, seasonShortLabel } from 'src/store/episodeUtils'
import {
  computeCalendarEvents,
  formatEpisodeRange,
  groupEventsByDay,
  monthGrid,
  type CalendarEvent,
} from 'src/store/calendar'
import { PosterThumb } from 'src/components/ui/poster-thumb'
import { EmptyState } from 'src/components/ui/empty-state'
import { Eyebrow } from 'src/components/ui/typography'
import { cn } from 'src/lib/utils'

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

const KIND_META: Record<CalendarEvent['kind'], { Icon: typeof Film; dot: string }> = {
  outing: { Icon: Ticket, dot: 'var(--ember)' },
  premiere: { Icon: Sparkles, dot: 'var(--amber)' },
  release: { Icon: Film, dot: 'var(--amber)' },
  episodes: { Icon: Tv, dot: 'var(--paper-dim)' },
}

/** Local Date for a YYYY-MM-DD string — never `new Date(ymd)` (UTC parse). */
function localDate(ymd: string): Date {
  const [y, m, d] = ymd.split('-').map(Number)
  return new Date(y, m - 1, d)
}

function dayHeading(date: string, today: string): string {
  const d = localDate(date)
  const tomorrow = localTodayYmd(new Date(localDate(today).getTime() + 36 * 3_600_000))
  const label = d.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' })
  if (date === today) return `Today · ${label}`
  if (date === tomorrow) return `Tomorrow · ${label}`
  return label
}

function eventLabel(event: CalendarEvent): string {
  switch (event.kind) {
    case 'release':
      return 'Release day'
    case 'premiere':
      return 'Series premiere'
    case 'episodes': {
      const code = `${seasonShortLabel(event.seasonNumber)} · ${formatEpisodeRange(event.episodeNumbers)}`
      if (event.episodeName) return `${code} — ${event.episodeName}`
      return event.episodeNumbers.length > 1 ? `${code} (${event.episodeNumbers.length} episodes)` : code
    }
    case 'outing': {
      const time = new Date(event.outing.showtime).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })
      return [`Tickets · ${time}`, event.outing.venue].filter(Boolean).join(' · ')
    }
  }
}

function useLocalToday(): string {
  const [today, setToday] = useState(() => localTodayYmd())
  useEffect(() => {
    // Re-derive across midnight; setState bails out while the day is unchanged.
    const id = window.setInterval(() => setToday(localTodayYmd()), 60_000)
    return () => window.clearInterval(id)
  }, [])
  return today
}

function EventRow({ event }: { event: CalendarEvent }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const { Icon } = KIND_META[event.kind]
  return (
    <li>
      <button
        type="button"
        onClick={() => openDetailDrawer(event.title.id)}
        className="w-full flex items-center gap-3 rounded-lg p-2 text-left hover:bg-(--wash) transition-colors focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-amber/60"
      >
        <PosterThumb src={event.title.posterUrl} alt="" type={event.title.type} />
        <span className="flex-1 min-w-0">
          <span
            className="block font-serif text-base sm:text-lg font-medium text-paper truncate"
            style={{ fontVariationSettings: '"opsz" 30' }}
          >
            {event.title.title}
          </span>
          <span className="mt-0.5 flex items-center gap-1.5 font-mono text-xs text-amber">
            <Icon className="w-3.5 h-3.5 shrink-0" aria-hidden="true" />
            <span className="truncate">{eventLabel(event)}</span>
          </span>
        </span>
      </button>
    </li>
  )
}

export function Calendar({ onBrowseDiscover }: { onBrowseDiscover: () => void }) {
  const titles = useAppStore((s) => s.titles)
  const outings = useAppStore((s) => s.outings)
  // Cinema outings are owner-only and never rendered in shared/friend views.
  const isSharedView = useAppStore((s) => s.isSharedView || s.viewerContext.kind !== 'owner')
  const today = useLocalToday()

  const events = useMemo(
    () => computeCalendarEvents(titles, isSharedView ? [] : outings, today),
    [titles, outings, isSharedView, today]
  )
  const eventsByDate = useMemo(() => {
    const map = new Map<string, CalendarEvent[]>()
    for (const day of groupEventsByDay(events)) map.set(day.date, day.events)
    return map
  }, [events])

  const todayDate = localDate(today)
  const [cursor, setCursor] = useState(() => ({ year: todayDate.getFullYear(), month: todayDate.getMonth() }))
  const [selected, setSelected] = useState<string | null>(null)

  const cells = useMemo(() => monthGrid(cursor.year, cursor.month), [cursor])
  const monthPrefix = `${cursor.year}-${String(cursor.month + 1).padStart(2, '0')}-`
  const isCurrentMonth = cursor.year === todayDate.getFullYear() && cursor.month === todayDate.getMonth()
  const monthLabel = new Date(cursor.year, cursor.month, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' })

  const agenda = useMemo(() => {
    if (selected) return [{ date: selected, events: eventsByDate.get(selected) ?? [] }]
    return groupEventsByDay(events.filter((e) => e.date.startsWith(monthPrefix)))
  }, [selected, events, eventsByDate, monthPrefix])

  function shiftMonth(delta: number) {
    setSelected(null)
    setCursor(({ year, month }) => {
      const d = new Date(year, month + delta, 1)
      return { year: d.getFullYear(), month: d.getMonth() }
    })
  }

  const nextEvent = events[0]

  return (
    <div className="max-w-[1100px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <header className="mb-6">
        <p className="kicker">
          <span className="dot" /> coming soon
        </p>
        <h1 className="display-title text-[clamp(32px,6vw,56px)] mt-3">Calendar</h1>
      </header>

      {events.length === 0 ? (
        <EmptyState
          Icon={CalendarDays}
          title="Nothing on the horizon."
          subtext="Upcoming episodes of your series and release dates of watchlist movies will appear here."
          ctaLabel="Find something upcoming"
          onCta={onBrowseDiscover}
          ctaClassName="mt-6"
        />
      ) : (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,420px)_minmax(0,1fr)] lg:items-start">
          <section
            aria-label="Month"
            className="rounded-xl p-3 sm:p-4"
            style={{ border: '1px solid var(--line)', background: 'linear-gradient(180deg, var(--ink-1), rgba(17,13,11,0.4))' }}
          >
            <div className="flex items-center justify-between mb-3">
              <button
                type="button"
                onClick={() => shiftMonth(-1)}
                disabled={isCurrentMonth}
                aria-label="Previous month"
                className="w-8 h-8 rounded-full flex items-center justify-center text-paper-dim hover:text-amber disabled:opacity-30 disabled:hover:text-paper-dim focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <h2 className="font-serif text-lg text-paper" aria-live="polite">{monthLabel}</h2>
              <button
                type="button"
                onClick={() => shiftMonth(1)}
                aria-label="Next month"
                className="w-8 h-8 rounded-full flex items-center justify-center text-paper-dim hover:text-amber focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
              >
                <ChevronRight className="w-4 h-4" />
              </button>
            </div>
            <div className="grid grid-cols-7 gap-1">
              {WEEKDAYS.map((w) => (
                <div key={w} aria-hidden="true" className="text-center font-mono text-[10px] uppercase text-paper-faint pb-1">
                  {w}
                </div>
              ))}
              {cells.map((date, i) => {
                if (!date) return <div key={`pad-${i}`} aria-hidden="true" />
                const dayEvents = eventsByDate.get(date) ?? []
                const isPast = date < today
                const isSelected = date === selected
                const dayNum = Number(date.slice(8))
                return (
                  <button
                    key={date}
                    type="button"
                    disabled={dayEvents.length === 0}
                    aria-pressed={isSelected}
                    aria-label={`${localDate(date).toLocaleDateString('en-US', { month: 'long', day: 'numeric' })}${dayEvents.length ? `, ${dayEvents.length} event${dayEvents.length === 1 ? '' : 's'}` : ''}`}
                    onClick={() => setSelected(isSelected ? null : date)}
                    className={cn(
                      'aspect-square rounded-lg flex flex-col items-center justify-center gap-1 font-mono text-xs transition-colors',
                      'focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60',
                      isPast ? 'text-paper-faint opacity-50' : 'text-paper-dim',
                      dayEvents.length > 0 && 'text-paper hover:bg-(--wash)',
                      date === today && 'ring-1 ring-amber/50',
                      isSelected && 'bg-amber/15 text-amber'
                    )}
                  >
                    <span>{dayNum}</span>
                    <span className="flex gap-0.5 h-1.5" aria-hidden="true">
                      {dayEvents.slice(0, 3).map((e) => (
                        <span key={e.id} className="w-1.5 h-1.5 rounded-full" style={{ background: KIND_META[e.kind].dot }} />
                      ))}
                    </span>
                  </button>
                )
              })}
            </div>
            {nextEvent && (
              <p className="mt-3 font-sans text-xs text-paper-faint">
                Next up: <span className="text-paper-dim">{nextEvent.title.title}</span> · {dayHeading(nextEvent.date, today)}
              </p>
            )}
          </section>

          <section aria-label={selected ? 'Selected day' : `Upcoming in ${monthLabel}`}>
            {selected && (
              <button
                type="button"
                onClick={() => setSelected(null)}
                className="mb-2 font-mono text-xs text-amber/80 hover:text-amber focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm"
              >
                ← Show all of {monthLabel}
              </button>
            )}
            {agenda.length === 0 ? (
              <p className="font-sans text-sm text-paper-faint py-8 text-center">Nothing scheduled in {monthLabel}.</p>
            ) : (
              agenda.map((day) => (
                <div key={day.date} className="mb-5">
                  <Eyebrow as="h3" size="md" tone={day.date === today ? 'accent' : 'faint'} className="pb-1">
                    {dayHeading(day.date, today)}
                  </Eyebrow>
                  <ul>
                    {day.events.map((event) => (
                      <EventRow key={event.id} event={event} />
                    ))}
                  </ul>
                </div>
              ))
            )}
          </section>
        </div>
      )}
    </div>
  )
}
