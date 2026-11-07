import type { ComponentProps } from 'react'
import { StarRating } from 'src/components/ui/star-rating'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { CardTitle, MetaBadge, StatLabel } from 'src/components/ui/typography'
import { Clock, Film, Tv } from 'lucide-react'
import { fmtRuntime } from 'src/lib/utils'
import type { Title } from 'src/store/mockData'
import { SpiderNoirModeSelector } from 'src/components/SpiderNoirModeSelector'
import { MatrixPillSelector } from 'src/components/MatrixPillSelector'
import { HeroBackdrop } from 'src/components/ui/hero-backdrop'
import { HeroScores } from 'src/components/ui/media-badges'

interface TitleHeroProps {
  title: Title
  /** Fetched TMDB backdrop, used when the title has none stored. */
  backdropOverride: string | null
  /** Fetched (or Spider-Noir) logo art, shown in place of the title text over a backdrop. */
  logoUrl: string | null
  isSharedView: boolean
  /** Spider-Noir's mode selector, or null for every other title. */
  noirSelector: ComponentProps<typeof SpiderNoirModeSelector> | null
  showMatrixPill: boolean
  onRedPill: () => void
  onPosterClick: () => void
  onRate: (rating: number) => void
}

/** Drawer hero: a cinematic backdrop (stored or fetched) or a blurred-poster fallback,
 *  with the same type line, badges, easter-egg controls, and rating either way. */
export function TitleHero({ title, backdropOverride, logoUrl, onPosterClick, ...details }: TitleHeroProps) {
  if (title.backdropUrl || backdropOverride) {
    return (
      <HeroBackdrop
        title={title}
        backdropOverride={backdropOverride ?? undefined}
        onPosterClick={onPosterClick}
      >
        <HeroDetails
          title={title}
          heading={logoUrl ? (
            <img
              src={logoUrl}
              alt={title.title}
              className="object-contain object-left max-h-20 sm:max-h-28 max-w-[90%] drop-shadow-lg"
            />
          ) : (
            <CardTitle className="text-xl leading-tight">{title.title}</CardTitle>
          )}
          {...details}
        />
      </HeroBackdrop>
    )
  }

  return (
    <div className="relative overflow-hidden shrink-0">
      {(title.backdropUrl ?? title.posterUrl) && (
        <div
          className="absolute inset-0"
          style={{
            backgroundImage: `url(${title.backdropUrl ?? title.posterUrl})`,
            backgroundSize: 'cover',
            backgroundPosition: 'center top',
            filter: 'blur(20px)',
            transform: 'scale(1.3)',
            opacity: 0.18,
          }}
        />
      )}
      <div className="absolute inset-0" style={{ background: 'linear-gradient(to bottom, hsl(var(--card) / 0.55) 0%, hsl(var(--card) / 0.92) 45%, hsl(var(--card)) 65%)' }} />
      <div className="relative z-10 flex gap-5 px-6 pt-10 pb-6">
        <div className="w-28 sm:w-36 shrink-0">
          {title.posterUrl ? (
            <button
              type="button"
              onClick={onPosterClick}
              aria-label={`View full poster for ${title.title}`}
              className="block w-full rounded-lg overflow-hidden transition-opacity hover:opacity-90 focus:outline-hidden focus-visible:ring-2 focus-visible:ring-amber/60"
              title="View full poster"
            >
              <DynamicPoster title={title} hideBadges />
            </button>
          ) : (
            <DynamicPoster title={title} hideBadges />
          )}
        </div>
        <div className="flex-1 min-w-0 space-y-2 pt-6">
          <HeroDetails
            title={title}
            heading={<CardTitle className="text-xl leading-tight">{title.title}</CardTitle>}
            {...details}
          />
        </div>
      </div>
    </div>
  )
}

function HeroDetails({ title, heading, isSharedView, noirSelector, showMatrixPill, onRedPill, onRate }: {
  title: Title
  heading: React.ReactNode
} & Pick<TitleHeroProps, 'isSharedView' | 'noirSelector' | 'showMatrixPill' | 'onRedPill' | 'onRate'>) {
  return (
    <>
      <div className="flex items-center gap-2">
        {title.type === 'movie' ? (
          <Film className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
        ) : (
          <Tv className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
        )}
        <StatLabel>
          {title.type === 'tv' ? 'Series' : 'Film'}
        </StatLabel>
        {title.network && (
          <span className="font-mono text-xs text-muted-foreground">· {title.network}</span>
        )}
      </div>
      {heading}
      <div className="flex flex-wrap items-center gap-2">
        <MetaBadge className="h-7 inline-flex items-center whitespace-nowrap border-amber/20 text-amber/80">{title.year}</MetaBadge>
        {title.director && (
          <MetaBadge className="h-7 inline-flex items-center whitespace-nowrap border-amber/20 text-amber/80">{title.director}</MetaBadge>
        )}
        {title.runtime && title.type === 'movie' && (
          <MetaBadge className="h-7 inline-flex items-center gap-1 whitespace-nowrap border-amber/20 text-amber/80">
            <Clock className="w-3 h-3 shrink-0" />
            {fmtRuntime(title.runtime)}
          </MetaBadge>
        )}
        {title.contentRating && <MetaBadge className="h-7 inline-flex items-center whitespace-nowrap border-amber/20 text-amber/80">{title.contentRating}</MetaBadge>}
        {title.genres.slice(0, 2).map((genre) => (
          <MetaBadge key={genre} className="border-amber/20 text-amber/80">{genre}</MetaBadge>
        ))}
      </div>
      {noirSelector && <SpiderNoirModeSelector {...noirSelector} />}
      {showMatrixPill && <MatrixPillSelector onRedPill={onRedPill} />}
      <div className="flex flex-wrap items-center gap-3">
        <div className="h-8 flex items-center rounded-md px-2.5 bg-black/55 backdrop-blur-xs border border-white/10">
          <StarRating
            value={title.rating ?? 0}
            size="sm"
            tone="onArt"
            onChange={isSharedView ? undefined : onRate}
          />
        </div>
        <HeroScores imdb={title.imdbRating} rt={title.rtScore} meta={title.metacriticScore} />
      </div>
    </>
  )
}
