import { Play, Pause } from 'lucide-react'
import { cn } from 'src/lib/utils'

// Lives beside the section title / View-more link rather than overlaid on the film
// strip (KP-044): always visible, labeled, and distinct from the hover-only chevrons,
// for anyone who finds the perpetual marquee distracting or hard to target.

export function CarouselPauseButton({ paused, onToggle }: { paused: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-pressed={paused}
      aria-label={paused ? 'Resume carousel auto-scroll' : 'Pause carousel auto-scroll'}
      className={cn(
        'flex items-center gap-1.5 font-mono text-[10px] uppercase tracking-wider rounded-full border px-2.5 py-1 transition-colors focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60',
        paused
          ? 'text-amber border-amber/40'
          : 'text-paper-faint hover:text-paper hover:border-amber/40',
      )}
      style={{ background: 'var(--inset)', borderColor: paused ? undefined : 'var(--line)' }}
    >
      {paused ? <Play className="w-3 h-3" /> : <Pause className="w-3 h-3" />}
      {paused ? 'Play' : 'Pause'}
    </button>
  )
}
