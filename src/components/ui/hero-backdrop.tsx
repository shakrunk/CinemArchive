import type { Title } from 'src/store/mockData'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'

interface HeroBackdropProps {
  title: Title
  /** Best-rated backdrop from the images endpoint at original resolution. Falls
   *  back to the stored title.backdropUrl (upgraded to original) when absent. */
  backdropOverride?: string
  onPosterClick: () => void
  /** Anchored to the banner's top-right corner (offset left of the modal's
   *  close button) — e.g. pinned review scores. */
  topRight?: React.ReactNode
  children: React.ReactNode
}

/** Rewrite any stored TMDB size segment to `original` for full-resolution display. */
function hiResBackdrop(url?: string): string | undefined {
  if (!url) return url
  return url.replace(/\/t\/p\/(w\d+|original)\//, '/t/p/original/')
}

export function HeroBackdrop({ title, backdropOverride, onPosterClick, topRight, children }: HeroBackdropProps) {
  const backdropSrc = backdropOverride ?? hiResBackdrop(title.backdropUrl)

  return (
    <div className="relative overflow-hidden shrink-0">
      {/* Backdrop at its natural aspect ratio so the full art — and every
          character in the frame — stays visible, rather than cropping to a band.
          The poster + title overlay its faded lower portion. */}
      <div className="relative w-full overflow-hidden aspect-[16/8]">
        {backdropSrc && (
          <img
            src={backdropSrc}
            alt=""
            aria-hidden="true"
            className="absolute inset-0 block w-full h-full object-cover object-center"
            style={{
              maskImage: 'linear-gradient(to bottom, #000 0%, #000 63%, transparent 100%)',
              WebkitMaskImage: 'linear-gradient(to bottom, #000 0%, #000 63%, transparent 100%)',
            }}
          />
        )}
        {/* Darken the lower band for text legibility, resolving to the card color
            at the bottom so the hero blends seamlessly into the content. */}
        <div
          className="absolute inset-0"
          style={{
            background: 'linear-gradient(to bottom, hsl(var(--card) / 0.05) 0%, hsl(var(--card) / 0.3) 45%, hsl(var(--card) / 0.75) 68%, hsl(var(--card)) 88%)',
          }}
        />

        {topRight && (
          <div className="absolute top-3 sm:top-4 right-12 sm:right-14 z-20 max-w-[calc(100%-3.5rem)]">{topRight}</div>
        )}

        {/* Poster + title — pinned to the bottom of the backdrop so they overlap
            its faded lower portion, scaling with any backdrop height. */}
        <div className="absolute inset-x-0 bottom-0 z-10 flex items-end gap-5 px-6 pb-6">
          <div className="w-28 sm:w-36 shrink-0">
            {title.posterUrl ? (
              <button
                type="button"
                onClick={onPosterClick}
                aria-label={`View full poster for ${title.title}`}
                className="block w-full rounded-lg overflow-hidden shadow-2xl transition-opacity hover:opacity-90 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
              >
                <DynamicPoster title={title} hideBadges />
              </button>
            ) : (
              <DynamicPoster title={title} hideBadges />
            )}
          </div>
          <div className="flex-1 min-w-0 space-y-2 pb-2">
            {children}
          </div>
        </div>
      </div>
    </div>
  )
}
