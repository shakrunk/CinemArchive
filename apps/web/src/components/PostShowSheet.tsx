import { useState } from 'react'
import { Send } from 'lucide-react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { StarRating } from 'src/components/ui/star-rating'
import { PosterThumb } from 'src/components/ui/poster-thumb'
import { Button } from 'src/components/ui/button'
import { useAppStore } from 'src/store/useAppStore'
import { formatCompanions } from 'src/store/outings'
import { SendRecommendationPanel } from 'src/components/SendRecommendationPanel'
import type { CinemaOuting, Title, Viewing } from 'src/store/mockData'

// Post-show follow-up — "How was it?" (plan §4.4). One screen, no navigation:
// rate, jot a note, deep-link to a recommendation, or back out via "Didn't
// make it". Mounted only while the sheet is open (see RefreshMetadataModal
// for the same "fresh mount resets local state" idiom), so rating/note always
// reseed from whichever outing/viewing it's opened against.
function PostShowBody({
  outing,
  title,
  viewing,
  onClose,
}: {
  outing: CinemaOuting
  title: Title
  viewing: Viewing | undefined
  onClose: () => void
}) {
  const updateTitle = useAppStore((s) => s.updateTitle)
  const dismissOutingFollowUp = useAppStore((s) => s.dismissOutingFollowUp)
  const revertOutingCompletion = useAppStore((s) => s.revertOutingCompletion)
  const openOutingSchedule = useAppStore((s) => s.openOutingSchedule)

  const [rating, setRating] = useState(viewing?.rating ?? 0)
  const [note, setNote] = useState(viewing?.notes ?? '')
  const [showRecommend, setShowRecommend] = useState(false)

  // Writes straight onto the auto-logged viewing — same control and semantics
  // as logViewing (TitleDetailDrawer): rating updates both the viewing and
  // title.rating, notes land on the viewing alone.
  function commitViewing(patch: Partial<Pick<Viewing, 'rating' | 'notes'>>) {
    const viewings = title.viewings.map((v) => (v.id === viewing?.id ? { ...v, ...patch } : v))
    updateTitle(title.id, {
      viewings,
      ...(patch.rating !== undefined ? { rating: patch.rating } : {}),
    })
  }

  function handleRate(value: number) {
    setRating(value)
    if (!viewing) return
    commitViewing({ rating: value })
    // A rating asserts you saw it — the follow-up card/notification have done
    // their job (rule §5.6; dismissOutingFollowUp is called both here and by
    // the ✕, per its own doc comment).
    dismissOutingFollowUp(outing.id)
  }

  function handleNoteBlur() {
    if (viewing && note !== (viewing.notes ?? '')) commitViewing({ notes: note || undefined })
  }

  function handleRecommend() {
    handleNoteBlur()
    setShowRecommend(true)
  }

  function handleDidntMakeIt() {
    revertOutingCompletion(outing.id)
    onClose()
    openOutingSchedule(title.id, outing.id)
  }

  const companionFriendIds = new Set(
    outing.companions.map((c) => c.friendUserId).filter((id): id is string => Boolean(id))
  )

  const showtime = new Date(outing.showtime)
  const dateLabel = showtime.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
  const companionLabel = formatCompanions(outing.companions)
  const tripSummary = [dateLabel, outing.venue, companionLabel && `with ${companionLabel}`].filter(Boolean).join(' · ')

  // Rule §5.6: hidden once the viewing has a rating (rating asserts you saw
  // it); un-rating would re-expose the escape hatch.
  const showDidntMakeIt = rating === 0

  return (
    <div className="px-6 py-6 space-y-5">
      <div className="flex gap-3">
        <PosterThumb src={title.posterUrl} alt={title.title} type={title.type} size="md" />
        <div className="min-w-0 pt-1">
          <p className="font-serif text-lg text-foreground truncate">{title.title}</p>
          {tripSummary && <p className="font-mono text-xs text-muted-foreground mt-0.5">{tripSummary}</p>}
        </div>
      </div>

      <div>
        <p className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">How was it?</p>
        <StarRating value={rating} onChange={handleRate} size="lg" />
      </div>

      <div>
        <label htmlFor="postshow-note" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
          Quick note
        </label>
        <textarea
          id="postshow-note"
          value={note}
          onChange={(e) => setNote(e.target.value)}
          onBlur={handleNoteBlur}
          rows={2}
          placeholder="IMAX sandworms…"
          className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-2 focus:ring-amber/30"
        />
      </div>

      <Button
        onClick={handleRecommend}
        className="w-full bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
      >
        <Send className="w-4 h-4 mr-2" />
        Recommend to friends
      </Button>

      {showDidntMakeIt && (
        <button
          onClick={handleDidntMakeIt}
          className="w-full text-center font-mono text-xs text-muted-foreground hover:text-ember transition-colors"
        >
          Didn't make it after all?
        </button>
      )}

      {showRecommend && (
        <SendRecommendationPanel
          title={title}
          onClose={() => setShowRecommend(false)}
          companionFriendIds={companionFriendIds}
        />
      )}
    </div>
  )
}

export function PostShowSheet() {
  const isOpen = useAppStore((s) => s.isPostShowSheetOpen)
  const close = useAppStore((s) => s.closePostShowSheet)
  const outing = useAppStore((s) => (s.postShowOutingId ? s.outings.find((o) => o.id === s.postShowOutingId) ?? null : null))
  const title = useAppStore((s) => (outing ? s.titles.find((t) => t.id === outing.titleId) ?? null : null))
  const viewing = title?.viewings.find((v) => v.id === outing?.completedViewingId)

  return (
    <CinemaModal
      open={isOpen}
      onClose={close}
      maxWidth="sm:max-w-lg"
      title="How was it?"
      description="Rate the movie, jot a quick note, or recommend it to friends."
    >
      {isOpen && outing && title ? (
        <PostShowBody outing={outing} title={title} viewing={viewing} onClose={close} />
      ) : null}
    </CinemaModal>
  )
}
