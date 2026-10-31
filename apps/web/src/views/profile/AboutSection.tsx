import { Info, ExternalLink } from 'lucide-react'
import { Section } from 'src/components/ui/section'
import { Eyebrow } from 'src/components/ui/typography'

const ABOUT_LINKS: { label: string; href: string }[] = [
  { label: 'Source on GitHub', href: 'https://github.com/shakrunk/CinemArchive' },
  { label: 'Release notes', href: 'https://github.com/shakrunk/CinemArchive/releases' },
]

export function AboutSection() {
  return (
    <Section
      id="about"
      title="About"
      Icon={Info}
      description="What this app is, which release you're running, and where its data comes from."
    >
      <div className="flex items-baseline gap-2.5 flex-wrap">
        <span className="font-serif text-lg font-semibold text-paper">CinemArchive</span>
        <span className="font-mono text-xs text-amber">v{__APP_VERSION__}</span>
        <Eyebrow as="span" size="md">
          The Projection Room
        </Eyebrow>
      </div>
      <p className="font-sans text-xs text-muted-foreground leading-relaxed max-w-[60ch]">
        A private film archive — track the movies and series you watch, rate and
        revisit them, chart your habits in the Ledger, and share a read-only
        window with friends.
      </p>
      <p className="font-sans text-[11px] text-muted-foreground leading-relaxed max-w-[60ch]">
        Metadata and artwork from{' '}
        <a href="https://www.themoviedb.org/" target="_blank" rel="noopener noreferrer" className="text-amber/80 hover:text-amber underline underline-offset-2">TMDB</a>
        , critic scores via{' '}
        <a href="https://www.omdbapi.com/" target="_blank" rel="noopener noreferrer" className="text-amber/80 hover:text-amber underline underline-offset-2">OMDb</a>
        , awards and Bechdel data from{' '}
        <a href="https://www.wikidata.org/" target="_blank" rel="noopener noreferrer" className="text-amber/80 hover:text-amber underline underline-offset-2">Wikidata</a>
        . This product uses the TMDB API but is not endorsed or certified by TMDB.
      </p>
      <div className="flex items-center gap-3 flex-wrap">
        {ABOUT_LINKS.map((l) => (
          <a
            key={l.href}
            href={l.href}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 font-sans text-xs text-paper-dim hover:text-amber transition-colors"
          >
            <ExternalLink className="w-3.5 h-3.5 text-amber/70" />
            {l.label}
          </a>
        ))}
      </div>
    </Section>
  )
}
