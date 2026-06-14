import { BottomSheet } from 'src/components/ui/bottom-sheet'
import { StarRating } from 'src/components/ui/star-rating'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { CardTitle, BodyText, MetaBadge, StatNumber, StatLabel } from 'src/components/ui/typography'
import { useAppStore, useSelectedTitle } from 'src/store/useAppStore'
import { Calendar, Clock, Film, Tv } from 'lucide-react'

function ViewingTimeline({ viewings }: { viewings: { date: string; rating?: number; notes?: string }[] }) {
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
          .map((v, i) => (
            <div key={i} className="relative">
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

function ReviewBadges({
  imdb,
  rt,
  meta,
}: {
  imdb?: number
  rt?: number
  meta?: number
}) {
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
  const { isDetailDrawerOpen, closeDetailDrawer } = useAppStore()
  const title = useSelectedTitle()

  if (!title) return null

  return (
    <BottomSheet open={isDetailDrawerOpen} onClose={closeDetailDrawer} side="right">
      <div className="space-y-5">
        {/* Hero header with blurred poster background */}
        <div className="relative rounded-xl overflow-hidden -mx-6 -mt-6 mb-1">
          {title.posterUrl && (
            <div
              className="absolute inset-0"
              style={{
                backgroundImage: `url(${title.posterUrl})`,
                backgroundSize: 'cover',
                backgroundPosition: 'center top',
                filter: 'blur(20px)',
                transform: 'scale(1.3)',
                opacity: 0.14,
              }}
            />
          )}
          <div className="absolute inset-0 bg-gradient-to-b from-secondary/30 via-card/70 to-card" />
          <div className="relative z-10 flex gap-4 px-6 pt-8 pb-5">
            <div className="w-28 shrink-0">
              <DynamicPoster title={title} />
            </div>
            <div className="flex-1 min-w-0 space-y-2">
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
              <CardTitle className="text-lg leading-tight">{title.title}</CardTitle>
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
              {title.rating && (
                <StarRating value={title.rating} readonly size="sm" />
              )}
            </div>
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
        <div className="flex flex-wrap gap-1.5">
          {title.genres.map((g) => (
            <MetaBadge key={g} className="border-amber/20 text-amber/70">{g}</MetaBadge>
          ))}
          {title.tags.map((t) => (
            <MetaBadge key={t}>{t}</MetaBadge>
          ))}
        </div>

        {/* Review Badges */}
        {(title.imdbRating || title.rtScore || title.metacriticScore) && (
          <div>
            <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
              Critical Reception
            </h4>
            <ReviewBadges imdb={title.imdbRating} rt={title.rtScore} meta={title.metacriticScore} />
          </div>
        )}

        {/* TV Seasons */}
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
                {title.viewings[0]
                  ? new Date(
                      title.viewings.slice().sort(
                        (a, b) => new Date(b.date).getTime() - new Date(a.date).getTime()
                      )[0].date
                    ).getFullYear()
                  : '—'}
              </StatNumber>
            </div>
            <StatLabel>Last Seen</StatLabel>
          </div>
        </div>

        {/* Viewing Timeline */}
        <div>
          <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">
            Viewing History
          </h4>
          <ViewingTimeline viewings={title.viewings} />
        </div>
      </div>
    </BottomSheet>
  )
}
