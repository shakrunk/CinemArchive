import { Film } from 'lucide-react'

interface Props {
  onSignIn: () => void
}

export function LandingScreen({ onSignIn }: Props) {
  return (
    <div className="fixed inset-0 z-50 flex flex-col items-center justify-center px-6">
      <div className="flex flex-col items-center gap-6 text-center max-w-sm">
        <div className="flex items-center justify-center w-16 h-16 rounded-full border border-amber/30 bg-amber/5">
          <Film className="w-7 h-7 text-amber" strokeWidth={1.5} />
        </div>

        <div className="space-y-2">
          <h1 className="font-serif text-4xl text-paper tracking-tight">
            CinemArchive
          </h1>
          <p className="font-mono text-xs tracking-widest text-paper/40 uppercase">
            Your private film archive
          </p>
        </div>

        <p className="font-sans text-sm text-paper/60 leading-relaxed">
          A personal library for the films and series that matter to you — watched, rated, and remembered.
        </p>

        <button
          onClick={onSignIn}
          className="mt-2 px-8 py-3 rounded-md bg-amber text-[color:var(--on-amber)] font-sans text-sm font-medium tracking-wide hover:bg-amber/90 transition-colors"
        >
          Sign In
        </button>
      </div>
    </div>
  )
}
