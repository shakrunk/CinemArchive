

export function DiscoverSkeleton() {
  return (
    <div className="-mx-4 sm:-mx-8 px-4 sm:px-8" aria-hidden>
      <div className="film-strip">
        <div className="reel-strip reel-strip--top" />
        <div className="reel-strip reel-strip--bottom" />
        <div className="discover-grid flex">
          {Array.from({ length: 10 }, (_, i) => (
            <div key={i} className="film-frame shrink-0 w-[38vw] sm:w-[170px] md:w-[185px]">
              <div className="film-frame__window aspect-2/3">
                <div className="w-full h-full rounded-[1px] animate-pulse" style={{ background: 'var(--inset)' }} />
              </div>
              <div className="film-frame__caption px-1.5 py-1.5">
                <div className="h-2.5 w-2/3 rounded animate-pulse" style={{ background: 'var(--inset)' }} />
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
