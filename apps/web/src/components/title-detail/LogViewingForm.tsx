import { StarRating } from 'src/components/ui/star-rating'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { Eyebrow } from 'src/components/ui/typography'
import { Calendar, Check, FileText, Star } from 'lucide-react'
import { VenueCompanionsFields } from './VenueCompanionsFields'
import type { LogViewingFormState } from './useLogViewingForm'

interface LogViewingFormProps {
  form: LogViewingFormState
  titleId: string
  onSave: () => void
}

/** The inline "Log a viewing" form in the drawer's Viewing History card. */
export function LogViewingForm({ form, titleId, onSave }: LogViewingFormProps) {
  const {
    logDate, setLogDate, logPrePlatform, setLogPrePlatform, logRating, setLogRating,
    logNotes, setLogNotes, logVenue, setLogVenue, logCompanions, setLogCompanions,
    showMovieSaved, setShowLogForm,
  } = form

  return (
    <div className="border-t pt-3 mb-4 space-y-3" style={{ borderColor: 'var(--line)' }}>
      <div>
        <Eyebrow as="label" htmlFor="viewing-date" size="xl" tone="muted" font="sans" className="block mb-2 cursor-pointer">
          <Calendar className="inline w-3 h-3 mr-1" />
          Date Watched
        </Eyebrow>
        {!logPrePlatform && (
          <Input
            id="viewing-date"
            type="date"
            value={logDate}
            onChange={(e) => setLogDate(e.target.value)}
            className="bg-secondary/50 border-border font-mono"
          />
        )}
        <label htmlFor={`drawer-pre-platform-${titleId}`} className="flex items-center gap-2 cursor-pointer mt-2">
          <input
            id={`drawer-pre-platform-${titleId}`}
            type="checkbox"
            checked={logPrePlatform}
            onChange={(e) => setLogPrePlatform(e.target.checked)}
            className="accent-amber w-3.5 h-3.5"
          />
          <span className="font-sans text-xs text-muted-foreground">
            Watched before joining CinemArchive (no date)
          </span>
        </label>
      </div>
      <div>
        <Eyebrow as="p" size="xl" tone="muted" font="sans" className="block mb-2">
          Rating
        </Eyebrow>
        <StarRating value={logRating} onChange={setLogRating} size="md" />
      </div>
      <VenueCompanionsFields
        venue={logVenue}
        onVenueChange={setLogVenue}
        companions={logCompanions}
        onCompanionsChange={setLogCompanions}
      />
      <div>
        <Eyebrow as="label" htmlFor="viewing-notes" size="xl" tone="muted" font="sans" className="block mb-2 cursor-pointer">
          <FileText className="inline w-3 h-3 mr-1" />
          Notes
        </Eyebrow>
        <textarea
          id="viewing-notes"
          value={logNotes}
          onChange={(e) => setLogNotes(e.target.value)}
          placeholder="Your thoughts…"
          rows={2}
          className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-hidden focus:ring-2 focus:ring-amber/30"
        />
      </div>
      <div className="flex gap-2">
        <Button
          className="flex-1 bg-amber hover:bg-amber-muted text-(--on-amber) font-sans font-medium"
          onClick={onSave}
          disabled={showMovieSaved}
        >
          {showMovieSaved ? (
            <>
              <Check className="w-4 h-4 mr-2" />
              Saved
            </>
          ) : (
            <>
              <Star className="w-4 h-4 mr-2" />
              Save Viewing
            </>
          )}
        </Button>
        <Button variant="outline" onClick={() => setShowLogForm(false)}>
          Cancel
        </Button>
      </div>
    </div>
  )
}
