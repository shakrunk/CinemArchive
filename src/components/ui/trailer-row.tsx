import { Play } from 'lucide-react'
import type { TitleVideo } from 'src/lib/media'

export function TrailerRow({ videos }: { videos: TitleVideo[] }) {
  if (videos.length === 0) return null

  return (
    <div>
      <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">
        Trailers
      </h4>
      <div className="flex gap-3 overflow-x-auto scrollbar-none -mx-1 px-1 pb-1">
        {videos.map((v) => (
          <a
            key={v.key}
            href={`https://www.youtube.com/watch?v=${v.key}`}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={`Watch ${v.name} on YouTube`}
            className="group shrink-0 w-[160px] focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 rounded-lg"
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
          </a>
        ))}
      </div>
    </div>
  )
}
