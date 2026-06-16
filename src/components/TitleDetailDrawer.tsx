import { useState } from 'react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { StarRating } from 'src/components/ui/star-rating'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { CardTitle, BodyText, MetaBadge, StatNumber, StatLabel } from 'src/components/ui/typography'
import { useAppStore, useSelectedTitle } from 'src/store/useAppStore'
import { Calendar, Clock, Film, Tv, Plus, FileText, Trash2 } from 'lucide-react'
import { cn } from 'src/lib/utils'
import type { Viewing, WatchStatus } from 'src/store/mockData'

const STATUS_OPTIONS: { value: WatchStatus; label: string }[] = [
  { value: 'watched', label: 'Watched' },
  { value: 'watchlist', label: 'Watchlist' },
  { value: 'watching', label: 'Watching' },
  { value: 'dropped', label: 'Dropped' },
]

function ViewingTimeline({ viewings }: { viewings: Viewing[] }) {
  if (viewings.length === 0) {
    return (
      <div className="text-center py-6 text-muted-foreground text-sm font-sans">
        No viewings logged yet
      </div>
    )
  }

  return (
    <div className="relative pl-6">
      <div className="absolute left-2 top-0 bottom-0 w-px bg-border" />
      <div className="space-y-4">
        {viewings
          .slice()
          .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())
          .map((v) => (
            <div key={v.id} className="relative">
              <div className="absolute -left-[18px] top-1 w-3 h-3 rounded-full bg-amber/70 border-2 border-void" />
              <div className="bg-secondary/50 rounded-lg p-3">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-mono text-xs text-amber">
                    {new Date(v.date).toLocaleDateString('en-US', {
                      month: 'short',
                      day: 'numeric',
                      year: 'numeric',
                    })}
                  </span>
                  {v.rating && (
                    <span className="font-mono text-xs text-amber">★ {v.rating}</span>
                  )}
                </div>
                {v.notes && (
                  <p className="text-xs text-muted-foreground font-sans italic leading-relaxed">
                    "{v.notes}"
                  </p>
                )}
              </div>
            </div>
          ))}
      </div>
    </div>
  )
}

function SeasonProgress({ seasons }: { seasons: NonNullable<ReturnType<typeof useSelectedTitle>>['seasons'] }) {
  if (!seasons || seasons.length === 0) return null

  return (
    <div className="space-y-2">
      {seasons.map((s) => {
        const pct = s.episodeCount > 0 ? (s.episodesWatched / s.episodeCount) * 100 : 0
        return (
          <div key={s.id} className="flex items-center gap-3">
            <span className="font-mono text-xs text-muted-foreground w-8 shrink-0">S{s.seasonNumber}</span>
            <div className="flex-1 h-1.5 bg-secondary rounded-full overflow-hidden">
              <div
                className="h-full bg-amber rounded-full transition-all"
                style={{ width: `${pct}%` }}
              />
            </div>
            <span className="font-mono text-xs text-muted-foreground w-12 text-right shrink-0">
              {s.episodesWatched}/{s.episodeCount}
            </span>
          </div>
        )
      })}
    </div>
  )
}

function ReviewBadges({ imdb, rt, meta }: { imdb?: number; rt?: number; meta?: number }) {
  return (
    <div className="flex flex-wrap gap-2">
      {imdb && (
        <div className="flex items-center gap-1.5 bg-secondary/60 rounded px-2.5 py-1.5">
          <span className="text-[#F5C518] font-mono font-bold text-xs">IMDb</span>
          <span className="font-mono text-sm text-foreground">{imdb}/10</span>
        </div>
      )}
      {rt && (
        <div className="flex items-center gap-1.5 bg-secondary/60 rounded px-2.5 py-1.5">
          <span className="text-[#FA320A] font-mono font-bold text-xs">RT</span>
          <span className="font-mono text-sm text-foreground">{rt}%</span>
        </div>
      )}
      {meta && (
        <div className="flex items-center gap-1.5 bg-secondary/60 rounded px-2.5 py-1.5">
          <span className="text-[#6ebc24] font-mono font-bold text-xs">MC</span>
          <span className="font-mono text-sm text-foreground">{meta}/100</span>
        </div>
      )}
    </div>
  )
}

export function TitleDetailDrawer() {
  const { isDetailDrawerOpen, closeDetailDrawer, updateTitle, removeTitle, isSharedView } = useAppStore()
  const title = useSelectedTitle()

  const [showLogForm, setShowLogForm] = useState(false)
  const [logDate, setLogDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [logRating, setLogRating] = useState(0)
  const [logNotes, setLogNotes] = useState('')

  if (!title) return null

  function logViewing() {
    if (!title || !logDate) return
    const viewing: Viewing = {
      id: `v-${title.id}-${Date.now()}`,
      titleId: title.id,
      date: logDate,
      rating: logRating > 0 ? logRating : undefined,
      notes: logNotes || undefined,
    }
    updateTitle(title.id, {
      viewings: [...title.viewings, viewing],
      status: 'watched',
      rating: logRating > 0 ? logRating : title.rating,
    })
    setShowLogForm(false)
    setLogDate(new Date().toISOString().slice(0, 10))
    setLogRating(0)
    setLogNotes('')
  }

  function handleDelete() {
    if (!title) return
    const ok = window.confirm(`Remove "${title.title}" from your library? This can't be undone.`)
    if (!ok) return
    closeDetailDrawer()
    removeTitle(title.id)
  }

  return (
    <CinemaModal
      open={isDetailDrawerOpen}
      onClose={closeDetailDrawer}
      maxWidth="sm:max-w-2xl"
      title={title.title}
      description={title.synopsis ?? `Details and viewing history for ${title.title}.`}
    >
      <div className="overflow-y-auto flex-1 scrollbar-thin">
        {/* Hero: blurred poster background + title info */}
        <div className="relative overflow-hidden shrink-0">
          {title.posterUrl && (
            <div
              className="absolute inset-0"
              style={{
                backgroundImage: `url(${title.posterUrl})`,
                backgroundSize: 'cover',
                backgroundPosition: 'center top',
                filter: 'blur(20px)',
                transform: 'scale(1.3)',
                opacity: 0.18,
              }}
            />
          )}
          <div className="absolute inset-0 bg-gradient-to-b from-secondary/30 via-card/70 to-card" />
          <div className="relative z-10 flex gap-5 px-6 pt-10 pb-6">
            <div className="w-28 sm:w-36 shrink-0">
              <DynamicPoster title={title} />
            </div>
            <div className="flex-1 min-w-0 space-y-2 pt-6">
              <div className="flex items-center gap-2">
                {title.type === 'movie' ? (
                  <Film className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                ) : (
                  <Tv className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                )}
                <span className="font-mono text-xs text-muted-foreground uppercase tracking-wider">
                  {title.type}
                </span>
              </div>
              <CardTitle className="text-xl leading-tight">{title.title}</CardTitle>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="font-mono text-sm text-amber">{title.year}</span>
                {title.director && (
                  <span className="text-xs text-muted-foreground font-sans">
                    dir. {title.director}
                  </span>
                )}
                {title.runtime && (
                  <div className="flex items-center gap-1 text-xs text-muted-foreground">
                    <Clock className="w-3 h-3" />
                    <span className="font-mono">{title.runtime}m</span>
                  </div>
                )}
              </div>
              <StarRating
                value={title.rating ?? 0}
                size="sm"
                onChange={isSharedView ? undefined : (rating) => updateTitle(title.id, { rating })}
              />
            </div>
          </div>
        </div>

        {/* Scrollable body */}
        <div className="px-6 pb-6 space-y-5">
          {/* Status */}
          <div>
            <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">Status</h4>
            <div className="flex flex-wrap gap-2">
              {STATUS_OPTIONS.map((opt) => (
                <button
                  key={opt.value}
                  onClick={isSharedView ? undefined : () => updateTitle(title.id, { status: opt.value })}
                  className={cn(
                    'px-3 py-1.5 rounded-md text-xs font-sans border transition-all',
                    title.status === opt.value
                      ? 'bg-amber/20 border-amber/50 text-amber'
                      : 'bg-secondary/50 border-border text-muted-foreground hover:text-foreground',
                    isSharedView && 'opacity-60 cursor-default pointer-events-none'
                  )}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          {/* Synopsis */}
          {title.synopsis && (
            <div>
              <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">Synopsis</h4>
              <BodyText className="text-sm leading-relaxed">{title.synopsis}</BodyText>
            </div>
          )}

          {/* Genres + Tags */}
          {(title.genres.length > 0 || title.tags.length > 0) && (
            <div className="flex flex-wrap gap-1.5">
              {title.genres.map((g) => (
                <MetaBadge key={g} className="border-amber/20 text-amber/70">{g}</MetaBadge>
              ))}
              {title.tags.map((t) => (
                <MetaBadge key={t}>{t}</MetaBadge>
              ))}
            </div>
          )}

          {/* Critical Reception */}
          {(title.imdbRating || title.rtScore || title.metacriticScore) && (
            <div>
              <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                Critical Reception
              </h4>
              <ReviewBadges imdb={title.imdbRating} rt={title.rtScore} meta={title.metacriticScore} />
            </div>
          )}

          {/* TV Season Progress */}
          {title.type === 'tv' && title.seasons && title.seasons.length > 0 && (
            <div>
              <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">
                Season Progress
              </h4>
              <SeasonProgress seasons={title.seasons} />
            </div>
          )}

          {/* Viewing Stats */}
          <div className="grid grid-cols-2 gap-4">
            <div className="bg-secondary/40 rounded-lg p-3 text-center">
              <StatNumber className="text-xl">{title.viewings.length}</StatNumber>
              <div className="mt-0.5">
                <StatLabel>Viewings</StatLabel>
              </div>
            </div>
            <div className="bg-secondary/40 rounded-lg p-3 text-center">
              <div className="flex items-center justify-center gap-1 mb-0.5">
                <Calendar className="w-3.5 h-3.5 text-muted-foreground" />
                <StatNumber className="text-xl">
                  {title.viewings.length > 0
                    ? new Date(
                        title.viewings
                          .slice()
                          .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())[0]
                          .date
                      ).getFullYear()
                    : '—'}
                </StatNumber>
              </div>
              <StatLabel>Last Seen</StatLabel>
            </div>
          </div>

          {/* Viewing History */}
          <div>
            <div className="flex items-center justify-between mb-3">
              <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground">
                Viewing History
              </h4>
              {!showLogForm && !isSharedView && (
                <button
                  onClick={() => setShowLogForm(true)}
                  className="flex items-center gap-1 text-xs font-mono text-amber/70 hover:text-amber transition-colors"
                >
                  <Plus className="w-3.5 h-3.5" />
                  Log a viewing
                </button>
              )}
            </div>

            {showLogForm && (
              <div className="bg-secondary/40 rounded-lg p-3 mb-4 space-y-3">
                <div>
                  <label className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                    <Calendar className="inline w-3 h-3 mr-1" />
                    Date Watched
                  </label>
                  <Input
                    type="date"
                    value={logDate}
                    onChange={(e) => setLogDate(e.target.value)}
                    className="bg-secondary/50 border-border font-mono"
                  />
                </div>
                <div>
                  <label className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                    Rating
                  </label>
                  <StarRating value={logRating} onChange={setLogRating} size="md" />
                </div>
                <div>
                  <label className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                    <FileText className="inline w-3 h-3 mr-1" />
                    Notes
                  </label>
                  <textarea
                    value={logNotes}
                    onChange={(e) => setLogNotes(e.target.value)}
                    placeholder="Your thoughts…"
                    rows={2}
                    className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-2 focus:ring-amber/30"
                  />
                </div>
                <div className="flex gap-2">
                  <Button
                    className="flex-1 bg-amber hover:bg-amber-muted text-void font-sans font-medium"
                    onClick={logViewing}
                  >
                    Save Viewing
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => setShowLogForm(false)}
                  >
                    Cancel
                  </Button>
                </div>
              </div>
            )}

            <ViewingTimeline viewings={title.viewings} />
          </div>

          {/* Remove from library */}
          {!isSharedView && (
            <div className="pt-2 border-t" style={{ borderColor: 'var(--line)' }}>
              <button
                onClick={handleDelete}
                className="flex items-center gap-2 text-xs font-mono text-muted-foreground hover:text-amber transition-colors"
              >
                <Trash2 className="w-3.5 h-3.5" />
                Remove from library
              </button>
            </div>
          )}
        </div>
      </div>
    </CinemaModal>
  )
}
