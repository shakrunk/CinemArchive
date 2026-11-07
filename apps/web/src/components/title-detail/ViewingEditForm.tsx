import { saveSucceeded } from 'src/lib/localSave'
import { useState } from 'react'
import { StarRating } from 'src/components/ui/star-rating'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { Eyebrow } from 'src/components/ui/typography'
import { useAppStore } from 'src/store/useAppStore'
import { TicketButton } from '../TicketViewer'
import { FileText } from 'lucide-react'
import { cn } from 'src/lib/utils'
import { CINEMA_FORMATS, type Title, type Viewing, type CinemaOuting, type CinemaFormat, type Companion } from 'src/store/mockData'
import { parseSeatsInput } from 'src/lib/seating'
import { VenueCompanionsFields } from './VenueCompanionsFields'

// Per-viewing editor (plan §4.6/§7.4): venue/companions/rating/notes are
// editable on ANY viewing, not just outing-completed ones. When the viewing
// is linked to an outing, a "Ticket details" section also surfaces that
// outing's receipt fields (format/price/seat/booking ref) — those stay
// editable after completion even though the outing's timing fields are
// frozen (rule §5.5); this editor is where that post-completion editing
// happens, not the schedule sheet.
export function ViewingEditForm({
  title,
  viewing,
  outing,
  onClose,
}: {
  title: Title
  viewing: Viewing
  outing: CinemaOuting | undefined
  onClose: () => void
}) {
  const editViewing = useAppStore((s) => s.editViewing)

  const [prePlatform, setPrePlatform] = useState(!viewing.date)
  const [date, setDate] = useState(viewing.date ?? new Date().toISOString().slice(0, 10))
  const [rating, setRating] = useState(viewing.rating ?? 0)
  const [notes, setNotes] = useState(viewing.notes ?? '')
  const [venue, setVenue] = useState(viewing.venue ?? '')
  const [companions, setCompanions] = useState<Companion[]>(viewing.companions ?? [])

  const [format, setFormat] = useState<CinemaFormat | ''>(outing?.format ?? '')
  const [ticketPrice, setTicketPrice] = useState(outing?.ticketPrice != null ? String(outing.ticketPrice) : '')
  const [auditorium, setAuditorium] = useState(outing?.auditorium ?? '')
  const [seatRow, setSeatRow] = useState(outing?.seatRow ?? '')
  const [seats, setSeats] = useState((outing?.seats ?? []).join(', '))
  const [bookingRef, setBookingRef] = useState(outing?.bookingRef ?? '')

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    const saved = await saveSucceeded(editViewing(title.id, viewing.id, {
      date: prePlatform ? undefined : date, rating: rating > 0 ? rating : undefined,
      notes: notes.trim() || undefined, venue: venue.trim() || undefined,
      companions: companions.length > 0 ? companions : undefined,
    }, rating > 0 ? { rating } : {}, outing ? { id: outing.id, patch: {
      format: format || undefined, ticketPrice: ticketPrice.trim() ? Number(ticketPrice) : undefined,
      auditorium: auditorium.trim() || undefined, seatRow: seatRow.trim() || undefined,
      seats: parseSeatsInput(seats), bookingRef: bookingRef.trim() || undefined,
    } } : undefined))
    if (saved) onClose()
  }

  return (
    <form onSubmit={handleSave} className="mt-3 space-y-3 border-t pt-3" style={{ borderColor: 'var(--line)' }}>
      <div>
        {!prePlatform && (
          <Input
            type="date"
            aria-label="Date watched"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            className="bg-secondary/50 border-border font-mono"
          />
        )}
        <label htmlFor="detail-drawer-pre-platform" className="flex items-center gap-2 cursor-pointer mt-2">
          <input
            id="detail-drawer-pre-platform"
            type="checkbox"
            checked={prePlatform}
            onChange={(e) => setPrePlatform(e.target.checked)}
            className="accent-amber w-3.5 h-3.5"
          />
          <span className="font-sans text-xs text-muted-foreground">
            Watched before joining CinemArchive (no date)
          </span>
        </label>
      </div>

      <div>
        <Eyebrow as="p" size="xl" tone="muted" font="sans" className="mb-2">Rating</Eyebrow>
        <StarRating value={rating} onChange={setRating} size="md" />
      </div>

      <VenueCompanionsFields
        venue={venue}
        onVenueChange={setVenue}
        companions={companions}
        onCompanionsChange={setCompanions}
      />

      <div>
        <Eyebrow as="label" htmlFor="viewing-edit-notes" size="xl" tone="muted" font="sans" className="block mb-2 cursor-pointer">
          <FileText className="inline w-3 h-3 mr-1" />
          Notes
        </Eyebrow>
        <textarea
          id="viewing-edit-notes"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={2}
          className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-hidden focus:ring-2 focus:ring-amber/30"
        />
      </div>

      {outing && (
        <div className="space-y-3 rounded-lg border p-3" style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}>
          <Eyebrow as="p" size="md">
            Ticket details
          </Eyebrow>
          <TicketButton outingId={outing.id} />
          <div className="flex flex-wrap gap-2">
            {CINEMA_FORMATS.map((f) => (
              <button
                key={f}
                type="button"
                aria-label={`Cinema format: ${f}`}
                onClick={() => setFormat(format === f ? '' : f)}
                className={cn(
                  'px-2.5 py-1 rounded-md text-xs font-sans border transition-all',
                  format === f
                    ? 'bg-amber/20 border-amber/50 text-amber'
                    : 'bg-secondary/50 border-border text-muted-foreground hover:text-foreground'
                )}
              >
                {f}
              </button>
            ))}
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Eyebrow as="label" htmlFor="viewing-edit-price" size="xl" tone="muted" font="sans" className="block mb-2 cursor-pointer">
                Ticket price
              </Eyebrow>
              <Input
                id="viewing-edit-price"
                type="number"
                min={0}
                step={0.01}
                value={ticketPrice}
                onChange={(e) => setTicketPrice(e.target.value)}
                placeholder="0.00"
                className="bg-secondary/50 border-border font-mono"
              />
            </div>
            <div>
              <Eyebrow as="label" htmlFor="viewing-edit-auditorium" size="xl" tone="muted" font="sans" className="block mb-2 cursor-pointer">
                Auditorium
              </Eyebrow>
              <Input
                id="viewing-edit-auditorium"
                value={auditorium}
                onChange={(e) => setAuditorium(e.target.value)}
                placeholder="7"
                className="bg-secondary/50 border-border"
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Eyebrow as="label" htmlFor="viewing-edit-seat-row" size="xl" tone="muted" font="sans" className="block mb-2 cursor-pointer">
                Row
              </Eyebrow>
              <Input
                id="viewing-edit-seat-row"
                value={seatRow}
                onChange={(e) => setSeatRow(e.target.value)}
                placeholder="F"
                className="bg-secondary/50 border-border"
              />
            </div>
            <div>
              <Eyebrow as="label" htmlFor="viewing-edit-seats" size="xl" tone="muted" font="sans" className="block mb-2 cursor-pointer">
                Seats
              </Eyebrow>
              <Input
                id="viewing-edit-seats"
                value={seats}
                onChange={(e) => setSeats(e.target.value)}
                placeholder="12, 13"
                className="bg-secondary/50 border-border"
              />
            </div>
          </div>
          <div>
            <Eyebrow as="label" htmlFor="viewing-edit-booking-ref" size="xl" tone="muted" font="sans" className="block mb-2 cursor-pointer">
              Booking ref
            </Eyebrow>
            <Input
              id="viewing-edit-booking-ref"
              value={bookingRef}
              onChange={(e) => setBookingRef(e.target.value)}
              placeholder="AMC-4X9KQ2"
              className="bg-secondary/50 border-border font-mono"
            />
          </div>
        </div>
      )}

      <div className="flex gap-2">
        <Button type="submit" className="flex-1 bg-amber hover:bg-amber-muted text-(--on-amber) font-sans font-medium">
          Save changes
        </Button>
        <Button type="button" variant="outline" onClick={onClose}>
          Cancel
        </Button>
      </div>
    </form>
  )
}
