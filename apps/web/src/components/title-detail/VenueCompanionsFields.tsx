import { useState, useEffect, useMemo, useId } from 'react'
import { Input } from 'src/components/ui/input'
import { Eyebrow } from 'src/components/ui/typography'
import { useAppStore } from 'src/store/useAppStore'
import { MapPin, Users } from 'lucide-react'
import { companionSuggestions, venueSuggestions } from 'src/store/outings'
import type { Companion } from 'src/store/mockData'
import { listFriendships, type FriendshipView } from 'src/lib/auth'
import { CompanionInput } from 'src/components/OutingScheduleSheet'

// Venue + companions fields — shared by the log-viewing form and the
// per-viewing editor (plan §4.6/§7.4): the same chip-input/autocomplete
// affordances as OutingScheduleSheet's ticket form, driven by the same
// src/store/outings.ts suggestion helpers, so "your usual theater" and
// "who you usually go with" complete the same way everywhere.
export function VenueCompanionsFields({
  venue,
  onVenueChange,
  companions,
  onCompanionsChange,
}: {
  venue: string
  onVenueChange: (venue: string) => void
  companions: Companion[]
  onCompanionsChange: (companions: Companion[]) => void
}) {
  const outings = useAppStore((s) => s.outings)
  const titles = useAppStore((s) => s.titles)
  const allViewings = useMemo(() => titles.flatMap((t) => t.viewings), [titles])
  const [friends, setFriends] = useState<FriendshipView[]>([])
  const datalistId = useId()

  useEffect(() => {
    // Deferred to satisfy react-hooks/set-state-in-effect (same pattern as
    // OutingScheduleSheet's friend fetch).
    const t = setTimeout(() => {
      listFriendships()
        .then((list) => setFriends(list.filter((f) => f.status === 'accepted')))
        .catch((err) => console.error('Failed to load friends for viewing companions:', err))
    }, 0)
    return () => clearTimeout(t)
  }, [])

  const suggestions = useMemo(
    () => companionSuggestions(outings, allViewings, friends),
    [outings, allViewings, friends]
  )
  const venues = useMemo(() => venueSuggestions(outings, allViewings), [outings, allViewings])

  return (
    <>
      <div>
        <Eyebrow as="label" size="xl" tone="muted" font="sans" className="flex items-center gap-1 mb-2">
          <MapPin className="w-3 h-3" />
          Theater
        </Eyebrow>
        <Input
          list={datalistId}
          value={venue}
          onChange={(e) => onVenueChange(e.target.value)}
          placeholder="e.g. AMC Georgetown"
          className="bg-secondary/50 border-border"
        />
        <datalist id={datalistId}>
          {venues.map((v) => <option key={v} value={v} />)}
        </datalist>
      </div>
      <div>
        <Eyebrow as="p" size="xl" tone="muted" font="sans" className="flex items-center gap-1 mb-2">
          <Users className="w-3 h-3" />
          Companions
        </Eyebrow>
        <CompanionInput companions={companions} onChange={onCompanionsChange} suggestions={suggestions} />
      </div>
    </>
  )
}
