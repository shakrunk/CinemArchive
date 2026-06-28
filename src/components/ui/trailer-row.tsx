import { useState, useEffect } from 'react'
import { Play, X } from 'lucide-react'
import type { TitleVideo } from 'src/lib/media'

export function TrailerRow({ videos }: { videos: TitleVideo[] }) {
  const [activeKey, setActiveKey] = useState<string | null>(null)

  useEffect(() => {
    if (!activeKey) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setActiveKey(null)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [activeKey])

  if (videos.length === 0) return null

  const activeVideo = activeKey ? videos.find((v) => v.key === activeKey) : null

  return (
    <>
      <div>
        <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">
          Trailers
        </h4>
        <div className="flex gap-3 overflow-x-auto scrollbar-none -mx-1 px-1 pb-1">
          {videos.map((v) => (
            <button
              key={v.key}
              type="button"
              onClick={() => setActiveKey(v.key)}
              aria-label={`Watch ${v.name}`}
              className="group shrink-0 w-[160px] text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 rounded-lg"
            >
              <div className="relative w-full rounded-lg overflow-hidden" style={{ paddingBottom: '56.25%' }}>
                <img
                  src={`https://img.youtube.com/vi/${v.key}/hqdefault.jpg`}
                  alt={v.name}
                  className="absolute inset-0 w-full h-full object-cover transition-opacity group-hover:opacity-80"
                />
                <div className="absolute inset-0 flex items-center justify-center">
                  <div
                    className="rounded-full p-2 transition-transform group-hover:scale-110"
                    style={{ background: 'rgba(0,0,0,0.55)' }}
                  >
                    <Play className="w-4 h-4 text-white" fill="white" />
                  </div>
                </div>
              </div>
              <div className="mt-1.5 px-0.5">
                <div
                  className="font-sans line-clamp-1"
                  style={{ fontSize: '11px', color: 'var(--paper)' }}
                >
                  {v.name}
                </div>
                <div
                  className="font-mono uppercase"
                  style={{ fontSize: '9px', color: 'var(--paper-faint)', letterSpacing: '0.1em' }}
                >
                  {v.type}
                </div>
              </div>
            </button>
          ))}
        </div>
      </div>

      {activeKey && (
        <div
          className="fixed inset-0 z-[70] flex items-center justify-center p-4 sm:p-10"
          style={{ background: 'rgba(7,5,4,0.92)' }}
          onClick={() => setActiveKey(null)}
        >
          <div
            className="relative w-full max-w-3xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="relative w-full rounded-xl overflow-hidden shadow-2xl" style={{ paddingBottom: '56.25%' }}>
              <iframe
                src={`https://www.youtube.com/embed/${activeKey}?autoplay=1&rel=0`}
                title={activeVideo?.name ?? 'Trailer'}
                allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                allowFullScreen
                className="absolute inset-0 w-full h-full"
                style={{ border: 'none' }}
              />
            </div>
            {activeVideo && (
              <div className="mt-3 px-1 flex items-center justify-between">
                <div>
                  <div className="font-sans text-sm" style={{ color: 'var(--paper)' }}>
                    {activeVideo.name}
                  </div>
                  <div
                    className="font-mono uppercase mt-0.5"
                    style={{ fontSize: '10px', color: 'var(--paper-faint)', letterSpacing: '0.1em' }}
                  >
                    {activeVideo.type}
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setActiveKey(null)}
                  aria-label="Close trailer"
                  className="rounded-full p-2 transition-colors hover:bg-white/10 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                  style={{ color: 'var(--paper-faint)' }}
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  )
}
