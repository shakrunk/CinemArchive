import { ReelMark } from 'src/components/ui/reel-mark'

interface Props {
  onSignIn: () => void
}

export function LandingScreen({ onSignIn }: Props) {
  return (
    <div className="fixed inset-0 z-50 flex flex-col items-center justify-center px-6">
      <div className="flex flex-col items-center gap-8 text-center">
        {/* Spinning reel mark — large, amber-glowing */}
        <ReelMark className="w-20 h-20 text-amber animate-spin-slow drop-shadow-[0_0_32px_rgba(233,178,102,0.4)]" />

        {/* Title block */}
        <div className="flex flex-col items-center gap-3">
          <div className="flex items-center gap-4 w-full mb-1">
            <div className="flex-1 h-px bg-gradient-to-r from-transparent to-amber/25" />
            <span className="font-mono text-[9px] tracking-[0.42em] uppercase text-amber/45 shrink-0">
              est. mmxxiv
            </span>
            <div className="flex-1 h-px bg-gradient-to-l from-transparent to-amber/25" />
          </div>
          <h1
            className="font-serif text-5xl sm:text-6xl text-paper tracking-tight leading-none"
            style={{ fontVariationSettings: '"opsz" 40' }}
          >
            CinemArchive
          </h1>
          <p className="font-mono text-[10px] tracking-[0.36em] text-amber-deep uppercase mt-1">
            a private film archive
          </p>
        </div>

        {/* Tagline */}
        <p className="font-sans text-[13px] text-paper/55 leading-relaxed max-w-[30ch]">
          A personal record of films and series —<br />
          watched, rated, and remembered.
        </p>

        {/* CTA */}
        <button
          onClick={onSignIn}
          className="mt-1 px-8 py-3 rounded-md bg-amber text-[color:var(--on-amber)] font-sans text-sm font-semibold tracking-wide hover:bg-amber/90 transition-colors"
        >
          Enter the Archive
        </button>
      </div>
    </div>
  )
}
