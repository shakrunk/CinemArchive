import type { Title } from 'src/store/mockData'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'

interface HeroBackdropProps {
  title: Title
  onPosterClick: () => void
  children: React.ReactNode
}

export function HeroBackdrop({ title, onPosterClick, children }: HeroBackdropProps) {
  return (
    <div className="relative overflow-hidden shrink-0">
      {title.backdropUrl && (
        <img
          src={title.backdropUrl}
          alt=""
          aria-hidden="true"
          className="absolute inset-0 w-full h-full object-cover"
          style={{ objectPosition: 'center top' }}
        />
      )}
      <div
        className="absolute inset-0"
        style={{
          background: 'linear-gradient(to bottom, rgba(0,0,0,0.15) 0%, rgba(0,0,0,0.55) 50%, var(--card) 100%)',
        }}
      />

      {/* Spacer that establishes the backdrop height */}
      <div className="relative z-10 h-28" />

      {/* Poster + title info row — sits at the bottom of the backdrop */}
      <div className="relative z-10 flex gap-5 px-6 pb-6">
        <div className="w-28 sm:w-36 shrink-0 -mt-10">
          {title.posterUrl ? (
            <button
              type="button"
              onClick={onPosterClick}
              aria-label={`View full poster for ${title.title}`}
              className="block w-full rounded-lg overflow-hidden shadow-2xl transition-opacity hover:opacity-90 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
            >
              <DynamicPoster title={title} />
            </button>
          ) : (
            <DynamicPoster title={title} />
          )}
        </div>
        <div className="flex-1 min-w-0 space-y-2 pt-4">
          {children}
        </div>
      </div>
    </div>
  )
}
