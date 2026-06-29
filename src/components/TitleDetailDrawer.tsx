import { useState, useEffect, useRef, useMemo } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { StarRating } from 'src/components/ui/star-rating'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { PosterLightbox } from 'src/components/ui/poster-lightbox'
import { SeriesGraph } from 'src/components/ui/series-graph'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { CardTitle, BodyText, MetaBadge, StatNumber, StatLabel } from 'src/components/ui/typography'
import { useAppStore, useSelectedTitle } from 'src/store/useAppStore'
import { PersonDetailPanel, type PersonDetailTarget } from 'src/components/PersonDetailPanel'
import {
  avgSeasonRating,
  avgSeriesRating,
  episodesWatchedInSeason,
  totalEpisodesWatched,
  totalEpisodeCount,
  getUnlockedModes,
  getEarnedModes,
} from 'src/store/episodeUtils'
import { EpisodeCard, EpisodePanel } from 'src/components/ui/episode-card'
import {
  Calendar, Check, Clock, Film, Tv, Plus, FileText, Trash2, Star,
  ChevronLeft, ChevronRight, ChevronDown, RefreshCw, Tag, X,
} from 'lucide-react'
import { cn, fmtDate, fmtReleaseDate, languageName } from 'src/lib/utils'
import type { Title, Viewing, WatchStatus, Season, Episode, CastMember, CrewMember, EpisodeCrew } from 'src/store/mockData'
import { fetchSeasonDetails, fetchTitleVideos, fetchTitleImages, type TitleVideo } from 'src/lib/media'
import { upsertEpisodeMetadataInDb, upsertSeasonCastInDb, upsertEpisodeCrewInDb } from 'src/lib/db'
import SpiderWebOverlay from 'src/components/SpiderWebOverlay'
import { SpiderNoirModeSelector } from 'src/components/SpiderNoirModeSelector'
import { transitionSpiderNoir } from 'src/lib/theme'
import { HeroBackdrop } from 'src/components/ui/hero-backdrop'
import { TrailerRow } from 'src/components/ui/trailer-row'

const TMDB_STILL_BASE = 'https://image.tmdb.org/t/p/w300'
const SPIDER_NOIR_TMDB_ID = 220102
type SelectorMode = 'normal' | 'bw' | 'color'
const EASTER_EGG_KEY = 'spider_noir_color'

function getSpiderNoirActiveMode(title: Title): 'bw' | 'color' | null {
  let lastMode: 'bw' | 'color' | null = null
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) {
      for (const we of ep.watchEvents) {
        if (we.colorMode) lastMode = we.colorMode
      }
      for (const rv of ep.reviews) {
        if (rv.colorMode) lastMode = rv.colorMode
      }
    }
  }
  return lastMode
}

// ─── Shared status options ────────────────────────────────────────────────────

const STATUS_OPTIONS: { value: WatchStatus; label: string }[] = [
  { value: 'watched', label: 'Watched' },
  { value: 'watchlist', label: 'Watchlist' },
  { value: 'watching', label: 'Watching' },
  { value: 'dropped', label: 'Dropped' },
]

// ─── Movie-only: viewing timeline ────────────────────────────────────────────

function ViewingTimeline({
  viewings,
  onDeleteViewing,
  onLogViewing,
  isSharedView,
}: {
  viewings: Viewing[]
  onDeleteViewing?: (viewingId: string) => void
  onLogViewing?: () => void
  isSharedView?: boolean
}) {
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null)
  if (viewings.length === 0) {
    return (
      <div className="text-center py-6 text-muted-foreground text-sm font-sans flex flex-col items-center gap-3">
        <div>No viewings logged yet</div>
        {!isSharedView && onLogViewing && (
          <button
            onClick={onLogViewing}
            className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber"
          >
            <Plus className="w-3.5 h-3.5" />
            Log first viewing
          </button>
        )}
      </div>
    )
  }

  return (
    <div className="relative pl-6">
      <div className="absolute left-2 top-0 bottom-0 w-px bg-border" />
      <div className="space-y-4">
        {viewings
          .slice()
          .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())
          .map((v) => (
            <div key={v.id} className="relative">
              <div className="absolute -left-[18px] top-1 w-3 h-3 rounded-full bg-amber/70 border-2 border-void" />
              <div className="bg-secondary/50 rounded-lg p-3">
                {pendingDeleteId === v.id ? (
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
                      Remove this viewing?
                    </span>
                    <div className="flex items-center gap-3">
                      <button
                        onClick={() => {
                          onDeleteViewing?.(v.id)
                          setPendingDeleteId(null)
                        }}
                        className="font-mono text-xs transition-opacity hover:opacity-80"
                        style={{ color: 'var(--ember)' }}
                      >
                        Delete forever
                      </button>
                      <button
                        onClick={() => setPendingDeleteId(null)}
                        className="font-mono text-xs transition-opacity hover:opacity-80"
                        style={{ color: 'var(--paper-faint)' }}
                      >
                        Cancel
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="flex items-center justify-between mb-1">
                      <span className="font-mono text-xs text-amber">
                        {new Date(v.date).toLocaleDateString('en-US', {
                          month: 'short', day: 'numeric', year: 'numeric',
                        })}
                      </span>
                      <div className="flex items-center gap-2">
                        {v.rating && (
                          <span className="font-mono text-xs text-amber">★ {v.rating}</span>
                        )}
                        {!isSharedView && onDeleteViewing && (
                          <button
                            onClick={() => setPendingDeleteId(v.id)}
                            style={{ color: 'var(--paper-faint)', opacity: 0.45 }}
                            onMouseEnter={(e) => (e.currentTarget.style.opacity = '1')}
                            onMouseLeave={(e) => (e.currentTarget.style.opacity = '0.45')}
                            aria-label="Delete viewing"
                          >
                            <Trash2 className="w-3 h-3" />
                          </button>
                        )}
                      </div>
                    </div>
                    {v.notes && (
                      <p className="text-xs text-muted-foreground font-sans italic leading-relaxed">
                        "{v.notes}"
                      </p>
                    )}
                  </>
                )}
              </div>
            </div>
          ))}
      </div>
    </div>
  )
}

function ReviewBadges({ imdb, rt, meta }: { imdb?: number; rt?: number; meta?: number }) {
  return (
    <div className="flex flex-wrap gap-2">
      {imdb && (
        <div className="flex items-center gap-1.5 bg-secondary/60 rounded px-2.5 py-1.5">
          <span className="text-[#F5C518] font-mono font-bold text-xs">IMDb</span>
          <span className="font-mono text-sm text-foreground">{imdb}/10</span>
        </div>
      )}
      {rt && (
        <div className="flex items-center gap-1.5 bg-secondary/60 rounded px-2.5 py-1.5">
          <span className="text-[#FA320A] font-mono font-bold text-xs">RT</span>
          <span className="font-mono text-sm text-foreground">{rt}%</span>
        </div>
      )}
      {meta && (
        <div className="flex items-center gap-1.5 bg-secondary/60 rounded px-2.5 py-1.5">
          <span className="text-[#6ebc24] font-mono font-bold text-xs">MC</span>
          <span className="font-mono text-sm text-foreground">{meta}/100</span>
        </div>
      )}
    </div>
  )
}

// ─── Details sidebar ──────────────────────────────────────────────────────────

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="font-mono text-xs uppercase tracking-wider text-muted-foreground shrink-0">{label}</dt>
      <dd className="font-sans text-sm text-foreground text-right min-w-0 break-words">{value}</dd>
    </div>
  )
}

// ─── External links ───────────────────────────────────────────────────────────

type LinkBrand = 'tmdb' | 'imdb' | 'rt' | 'metacritic'

// TMDB resolves exactly from id+type. IMDb uses the stored imdb_id when present,
// otherwise a name search. Rotten Tomatoes and Metacritic have no stable id-based
// URL, so they always use a name search.
function buildExternalLinks(title: Title): Array<{ brand: LinkBrand; name: string; href: string }> {
  const links: Array<{ brand: LinkBrand; name: string; href: string }> = []
  const q = encodeURIComponent(title.title)

  if (title.tmdbId > 0) {
    links.push({ brand: 'tmdb', name: 'TMDB', href: `https://www.themoviedb.org/${title.type}/${title.tmdbId}` })
  }
  links.push({
    brand: 'imdb',
    name: 'IMDb',
    href: title.imdbId
      ? `https://www.imdb.com/title/${title.imdbId}/`
      : `https://www.imdb.com/find/?q=${q}&s=tt`,
  })
  links.push({ brand: 'rt', name: 'Rotten Tomatoes', href: `https://www.rottentomatoes.com/search?search=${q}` })
  links.push({ brand: 'metacritic', name: 'Metacritic', href: `https://www.metacritic.com/search/${q}/` })

  return links
}

const BRAND_CONFIG: Record<LinkBrand, { bg: string; fg: string }> = {
  tmdb:       { bg: 'bg-[#0d253f]',  fg: 'text-white' },
  imdb:       { bg: 'bg-yellow-500', fg: 'text-black' },
  rt:         { bg: 'bg-white',       fg: 'text-black' },
  metacritic: { bg: 'bg-[#1c1c1c]',  fg: 'text-[#6ebc24]' },
}

function BrandLogo({ brand }: { brand: LinkBrand }) {
  switch (brand) {
    case 'tmdb':
      return (
        <svg width="1.5em" height="1.5em" fill="currentColor" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 190.24 81.52" aria-hidden="true">
          <defs>
            <linearGradient id="tmdbGrad2" y1="40.76" x2="190.24" y2="40.76" gradientUnits="userSpaceOnUse">
              <stop offset="0" stopColor="#90cea1" />
              <stop offset="0.56" stopColor="#3cbec9" />
              <stop offset="1" stopColor="#00b3e5" />
            </linearGradient>
          </defs>
          <path fill="url(#tmdbGrad2)" d="M105.67,36.06h66.9A17.67,17.67,0,0,0,190.24,18.4h0A17.67,17.67,0,0,0,172.57.73h-66.9A17.67,17.67,0,0,0,88,18.4h0A17.67,17.67,0,0,0,105.67,36.06Zm-88,45h76.9A17.67,17.67,0,0,0,112.24,63.4h0A17.67,17.67,0,0,0,94.57,45.73H17.67A17.67,17.67,0,0,0,0,63.4H0A17.67,17.67,0,0,0,17.67,81.06ZM10.41,35.42h7.8V6.92h10.1V0H.31v6.9h10.1Zm28.1,0h7.8V8.25h.1l9,27.15h6l9.3-27.15h.1V35.4h7.8V0H66.76l-8.2,23.1h-.1L50.31,0H38.51ZM152.43,55.67a15.07,15.07,0,0,0-4.52-5.52,18.57,18.57,0,0,0-6.68-3.08,33.54,33.54,0,0,0-8.07-1h-11.7v35.4h12.75a24.58,24.58,0,0,0,7.55-1.15A19.34,19.34,0,0,0,148.11,77a16.27,16.27,0,0,0,4.37-5.5,16.91,16.91,0,0,0,1.63-7.58A18.5,18.5,0,0,0,152.43,55.67ZM145,68.6A8.8,8.8,0,0,1,142.36,72a10.7,10.7,0,0,1-4,1.82,21.57,21.57,0,0,1-5,.55h-4.05v-21h4.6a17,17,0,0,1,4.67.63,11.66,11.66,0,0,1,3.88,1.87A9.14,9.14,0,0,1,145,59a9.87,9.87,0,0,1,1,4.52A11.89,11.89,0,0,1,145,68.6Zm44.63-.13a8,8,0,0,0-1.58-2.62A8.38,8.38,0,0,0,185.63,64a10.31,10.31,0,0,0-3.17-1v-.1a9.22,9.22,0,0,0,4.42-2.82,7.43,7.43,0,0,0,1.68-5,8.42,8.42,0,0,0-1.15-4.65,8.09,8.09,0,0,0-3-2.72,12.56,12.56,0,0,0-4.18-1.3,32.84,32.84,0,0,0-4.62-.33h-13.2v35.4h14.5a22.41,22.41,0,0,0,4.72-.5,13.53,13.53,0,0,0,4.28-1.65,9.42,9.42,0,0,0,3.1-3,8.52,8.52,0,0,0,1.2-4.68A9.39,9.39,0,0,0,189.66,68.47ZM170.21,52.72h5.3a10,10,0,0,1,1.85.18,6.18,6.18,0,0,1,1.7.57,3.39,3.39,0,0,1,1.22,1.13,3.22,3.22,0,0,1,.48,1.82,3.63,3.63,0,0,1-.43,1.8,3.4,3.4,0,0,1-1.12,1.2,4.92,4.92,0,0,1-1.58.65,7.51,7.51,0,0,1-1.77.2h-5.65Zm11.72,20a3.9,3.9,0,0,1-1.22,1.3,4.64,4.64,0,0,1-1.68.7,8.18,8.18,0,0,1-1.82.2h-7v-8h5.9a15.35,15.35,0,0,1,2,.15,8.47,8.47,0,0,1,2.05.55,4,4,0,0,1,1.57,1.18,3.11,3.11,0,0,1,.63,2A3.71,3.71,0,0,1,181.93,72.72Z" />
        </svg>
      )
    case 'imdb':
      return (
        <svg width="2em" height="2em" fill="currentColor" viewBox="0 0 32 32" aria-hidden="true">
          <g>
            <path d="M8.4,21.1H5.9V9.9h3.8l0.7,4.7h0.1L11,9.9h3.8v11.2h-2.5v-6.7h-0.1l-0.9,6.7H9.4l-1-6.7h0L8.4,21.1L8.4,21.1z" />
            <path d="M15.8,9.8c0.4,0,3.2-0.1,4.7,0.1c1.2,0.1,1.8,1.1,1.9,2.3c0.1,2.2,0.1,4.4,0.1,6.6c0,0.2,0,0.5-0.1,0.8 c-0.2,0.9-0.7,1.4-1.9,1.5c-1.5,0.1-3,0.1-4.4,0.1c0,0-0.1,0-0.2,0V9.8z M18.8,11.9v7.2c0.5,0,0.8-0.2,0.8-0.7c0-1.9,0-3.9,0-5.9 C19.6,12,19.4,11.8,18.8,11.9z" />
            <path d="M2,21.1V9.9h2.9v11.2H2z" />
            <path d="M29.9,14.1c-0.1-0.8-0.6-1.2-1.4-1.4c-0.8-0.1-1.6,0-2.3,0.7V9.9h-2.8v11.2H26c0.1-0.2,0.1-0.4,0.2-0.5c0,0,0,0,0.1,0 c0.1,0.1,0.2,0.2,0.3,0.3c0.7,0.5,1.5,0.6,2.3,0.3c0.7-0.3,1-0.9,1-1.6c0-0.8,0.1-1.7,0.1-2.6C30,16,30,15,29.9,14.1L29.9,14.1z M27.1,19.1c0,0.2-0.2,0.4-0.4,0.4s-0.4-0.2-0.4-0.4v-4.3c0-0.2,0.2-0.4,0.4-0.4s0.4,0.2,0.4,0.4V19.1z" />
          </g>
        </svg>
      )
    case 'rt':
      return (
        <svg width="1.4em" height="1.4em" viewBox="0 0 80 82" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
          <defs>
            <mask id="rt-tomato-mask" fill="white">
              <polygon points="0.000109100102 0.246970954 77.0827837 0.246970954 77.0827837 63.7145228 0.000109100102 63.7145228" />
            </mask>
          </defs>
          <g transform="translate(1.33, 0)">
            <g transform="translate(0, 16.27)">
              <path d="M77.0137759,27.0426556 C76.2423237,14.6741909 69.9521992,5.42041494 60.4876349,0.246970954 C60.5414108,0.548381743 60.273195,0.925145228 59.9678008,0.791701245 C53.7772614,-1.91634855 43.2753527,6.84780083 35.9365975,2.25825726 C35.9917012,3.90539419 35.6700415,11.940249 24.3515353,12.4063071 C24.0843154,12.4172614 23.9372614,12.1443983 24.1062241,11.9512033 C25.619917,10.2247303 27.1482158,5.85360996 24.9507054,3.5233195 C20.2446473,7.74041494 17.5117012,9.32746888 8.48829876,7.23319502 C2.71103734,13.2740249 -0.562655602,21.5419087 0.08,31.8413278 C1.39120332,52.86639 21.0848133,64.8846473 40.9165145,63.6471369 C60.746888,62.4106224 78.3253112,48.0677178 77.0137759,27.0426556" fill="#FA320A" mask="url(#rt-tomato-mask)" />
            </g>
            <path d="M40.8717012,11.4648963 C44.946722,10.49361 56.6678838,11.3702905 60.4232365,16.3518672 C60.6486307,16.6506224 60.3312863,17.2159336 59.9678008,17.0572614 C53.7772614,14.3492116 43.2753527,23.113361 35.9365975,18.5238174 C35.9917012,20.1709544 35.6700415,28.2058091 24.3515353,28.6718672 C24.0843154,28.6828216 23.9372614,28.4099585 24.1062241,28.2167635 C25.619917,26.4902905 27.1478838,22.1191701 24.9507054,19.7888797 C19.8243983,24.3827386 17.0453112,25.8589212 5.91900415,22.8514523 C5.55485477,22.753195 5.67900415,22.1679668 6.06639004,22.020249 C8.16929461,21.2165975 12.933444,17.6965975 17.4406639,16.1450622 C18.2987552,15.8499585 19.1541909,15.6209129 19.9890456,15.4878008 C15.02639,15.0443154 12.7893776,14.3541909 9.63286307,14.8302075 C9.28697095,14.8823237 9.05195021,14.479668 9.26639004,14.2034855 C13.5193361,8.7253112 21.3540249,7.07087137 26.1878838,9.98107884 C23.2082988,6.28912863 20.8743568,3.34473029 20.8743568,3.34473029 L26.4046473,0.203485477 C26.4046473,0.203485477 28.6894606,5.30821577 30.3518672,9.02340249 C34.4657261,2.94506224 42.119834,2.38406639 45.3536929,6.69676349 C45.5455602,6.95302905 45.3450622,7.31751037 45.0247303,7.30987552 C42.3926971,7.24580913 40.9434025,9.63983402 40.833527,11.4605809 L40.8717012,11.4648963" fill="#00912D" />
          </g>
        </svg>
      )
    case 'metacritic':
      return (
        <svg width="1.6em" height="0.9em" viewBox="0 0 36 18" aria-hidden="true">
          <text x="18" y="14" textAnchor="middle" fontFamily="Arial Black, Arial, sans-serif" fontWeight="900" fontSize="16" fill="currentColor">MC</text>
        </svg>
      )
  }
}

function ExternalLinks({ title }: { title: Title }) {
  const links = buildExternalLinks(title)
  if (links.length === 0) return null
  return (
    <div>
      <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">Links</h4>
      <div className="flex flex-wrap items-center gap-2">
        {links.map((l, i) => {
          const { bg, fg } = BRAND_CONFIG[l.brand]
          return (
            <a
              key={l.brand}
              href={l.href}
              target="_blank"
              rel="noopener noreferrer"
              aria-label={`Open ${title.title} on ${l.name}`}
              title={`View on ${l.name}`}
              className={`w-8 h-8 rounded-md ${bg} ${fg} flex items-center justify-center transition-transform hover:scale-110 animate-[scaleIn_0.6s_ease-out_forwards]`}
              style={{ animationDelay: `${i * 60}ms`, transform: 'scale(0)', opacity: 0 }}
            >
              <BrandLogo brand={l.brand} />
            </a>
          )
        })}
      </div>
    </div>
  )
}

// ─── Cast & Crew section ──────────────────────────────────────────────────────

const CREW_DISPLAY: Array<{ jobs: string[]; label: string }> = [
  { jobs: ['Creator'],                                     label: 'Created by' },
  { jobs: ['Director'],                                    label: 'Dir.' },
  { jobs: ['Screenplay', 'Writer', 'Teleplay', 'Story'],   label: 'Written by' },
  { jobs: ['Producer'],                                    label: 'Prod.' },
  { jobs: ['Director of Photography'],                     label: 'D.O.P.' },
  { jobs: ['Original Music Composer'],                     label: 'Composer' },
]

interface CastCrewSectionProps {
  cast?: CastMember[]
  crew?: CrewMember[]
  studios?: string[]
  onPersonClick: (person: PersonDetailTarget) => void
  onStudioClick: (studio: string) => void
}

function CastCrewSection({ cast, crew, studios, onPersonClick, onStudioClick }: CastCrewSectionProps) {
  const hasCast = cast && cast.length > 0
  const hasCrew = crew && crew.length > 0
  const hasStudios = studios && studios.length > 0
  if (!hasCast && !hasCrew && !hasStudios) return null

  return (
    <div className="space-y-4">
      {hasCast && (
        <div>
          <div
            className="font-mono mb-2"
            style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
          >
            Main Cast
          </div>
          <div className="flex gap-2.5 overflow-x-auto scrollbar-none pb-1 -mx-1 px-1">
            {cast.map((member) => (
              <button
                key={member.tmdbPersonId}
                type="button"
                onClick={() => onPersonClick({ tmdbPersonId: member.tmdbPersonId, name: member.name, profileUrl: member.profileUrl, character: member.character })}
                aria-label={`View details for ${member.name}`}
                className="group shrink-0 w-[110px] overflow-hidden rounded-lg text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 transition-all"
                style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}
              >
                <div className="aspect-[2/3] overflow-hidden">
                  {member.profileUrl ? (
                    <img
                      src={member.profileUrl}
                      alt={member.name}
                      className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
                    />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center" style={{ background: 'var(--card)' }}>
                      <span className="font-mono text-3xl" style={{ color: 'var(--paper-faint)' }}>
                        {member.name.charAt(0).toUpperCase()}
                      </span>
                    </div>
                  )}
                </div>
                <div className="p-2">
                  <div
                    className="font-sans font-semibold line-clamp-1 transition-colors group-hover:text-amber"
                    style={{ fontSize: '12px', color: 'var(--paper)', lineHeight: 1.3 }}
                    title={member.name}
                  >
                    {member.name}
                  </div>
                  {member.character && (
                    <div
                      className="font-mono line-clamp-1 mt-0.5"
                      style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3 }}
                      title={member.character}
                    >
                      {member.character}
                    </div>
                  )}
                  {member.episodeCount != null && (
                    <div
                      className="font-mono mt-0.5"
                      style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: 0.7 }}
                    >
                      {member.episodeCount} ep{member.episodeCount !== 1 ? 's' : ''}
                    </div>
                  )}
                </div>
              </button>
            ))}
          </div>
        </div>
      )}

      {(hasCrew || hasStudios) && (
        <div className="space-y-1.5">
          {hasCrew && CREW_DISPLAY.map(({ jobs, label }) => {
            const members = crew!.filter((c) => jobs.includes(c.job))
            if (members.length === 0) return null
            return (
              <div key={label} className="flex gap-3" style={{ fontSize: '12px' }}>
                <span
                  className="font-mono shrink-0 text-right"
                  style={{ width: '80px', color: 'var(--paper-faint)', fontSize: '10px', paddingTop: '1px' }}
                >
                  {label}
                </span>
                <span className="font-sans" style={{ color: 'var(--paper)' }}>
                  {members.map((m, i) => (
                    <span key={m.tmdbPersonId}>
                      {i > 0 && ' · '}
                      <button
                        type="button"
                        onClick={() => onPersonClick({ tmdbPersonId: m.tmdbPersonId, name: m.name, profileUrl: m.profileUrl, job: m.job })}
                        aria-label={`View details for ${m.name}`}
                        className="text-paper transition-colors hover:text-amber focus-visible:text-amber focus:outline-none"
                      >
                        {m.name}
                      </button>
                    </span>
                  ))}
                </span>
              </div>
            )
          })}
          {hasStudios && (
            <div className="flex gap-3" style={{ fontSize: '12px' }}>
              <span
                className="font-mono shrink-0 text-right"
                style={{ width: '80px', color: 'var(--paper-faint)', fontSize: '10px', paddingTop: '1px' }}
              >
                Studio
              </span>
              <span className="font-sans" style={{ color: 'var(--paper)' }}>
                {studios!.map((s, i) => (
                  <span key={s}>
                    {i > 0 && ', '}
                    <button
                      type="button"
                      onClick={() => onStudioClick(s)}
                      aria-label={`Browse titles from ${s}`}
                      className="text-paper transition-colors hover:text-amber focus-visible:text-amber focus:outline-none"
                    >
                      {s}
                    </button>
                  </span>
                ))}
              </span>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ─── TV: Season selector + episode carousel ───────────────────────────────────

interface TVSeriesSectionProps {
  titleId: string
  seasons: Season[]
  isSharedView: boolean
  isSpiderNoir: boolean
  onPersonClick: (person: PersonDetailTarget) => void
  onColorModeSelected?: (mode: 'bw' | 'color') => void
}

function TVSeriesSection({ titleId, seasons, isSharedView, isSpiderNoir, onPersonClick, onColorModeSelected }: TVSeriesSectionProps) {
  const [selectedSeason, setSelectedSeason] = useState(seasons[0]?.seasonNumber ?? 1)
  const [selectedEpId, setSelectedEpId] = useState<string | null>(null)
  const [canScrollLeft, setCanScrollLeft] = useState(false)
  const [canScrollRight, setCanScrollRight] = useState(false)
  const carouselRef = useRef<HTMLDivElement>(null)
  const graphClickEpIdRef = useRef<string | null>(null)

  const season = seasons.find((s) => s.seasonNumber === selectedSeason)
  const hasEpisodes = (s: Season) => (s.episodes?.length ?? 0) > 0
  const selectedEp = season?.episodes?.find((e) => e.id === selectedEpId) ?? null

  const totalWatched = totalEpisodesWatched(seasons)
  const totalCount = totalEpisodeCount(seasons)
  const seriesAvg = avgSeriesRating(seasons)

  const CARD_WIDTH = 252 // 240px card + 12px gap

  function handleCarouselScroll() {
    const el = carouselRef.current
    if (!el) return
    setCanScrollLeft(el.scrollLeft > 4)
    setCanScrollRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 4)
  }

  function scrollCarousel(dir: 'left' | 'right') {
    carouselRef.current?.scrollBy({ left: dir === 'right' ? CARD_WIDTH : -CARD_WIDTH, behavior: 'smooth' })
  }

  // Reset carousel + selection on season change (honours graph-click ref)
  useEffect(() => {
    const pendingEpId = graphClickEpIdRef.current
    graphClickEpIdRef.current = null
    const el = carouselRef.current
    if (el) el.scrollLeft = 0
    setTimeout(() => {
      setSelectedEpId(pendingEpId)
      setCanScrollLeft(false)
    }, 0)
    const t = setTimeout(() => {
      const el2 = carouselRef.current
      if (el2) setCanScrollRight(el2.scrollWidth > el2.clientWidth + 4)
    }, 50)
    return () => clearTimeout(t)
  }, [selectedSeason])

  function handleSeasonChange(seasonNumber: number) {
    setSelectedSeason(seasonNumber)
  }

  return (
    <div className="space-y-5">
      {/* Series-level stats */}
      <div className="grid grid-cols-3 gap-3">
        <div className="rounded-lg p-3 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--paper)', fontVariationSettings: '"opsz" 30' }}>
            {totalWatched}<span className="text-sm font-mono ml-0.5" style={{ color: 'var(--paper-faint)' }}>/{totalCount}</span>
          </div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Episodes</div>
        </div>
        <div className="rounded-lg p-3 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--amber)', fontVariationSettings: '"opsz" 30' }}>
            {seriesAvg !== null ? `★ ${seriesAvg.toFixed(1)}` : '—'}
          </div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Avg Rating</div>
        </div>
        <div className="rounded-lg p-3 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--paper)', fontVariationSettings: '"opsz" 30' }}>{seasons.length}</div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Seasons</div>
        </div>
      </div>

      {/* Series Graph heatmap */}
      {seasons.some(hasEpisodes) && (
        <div>
          <h4 className="font-mono mb-3" style={{ fontSize: '10px', letterSpacing: '0.18em', textTransform: 'uppercase', color: 'var(--paper-faint)' }}>
            Series Graph
          </h4>
          <SeriesGraph
            seasons={seasons}
            onCellClick={(seasonNumber, episodeNumber) => {
              const targetSeason = seasons.find((s) => s.seasonNumber === seasonNumber)
              const ep = targetSeason?.episodes?.find((e) => e.episodeNumber === episodeNumber)
              if (ep) graphClickEpIdRef.current = ep.id
              handleSeasonChange(seasonNumber)
            }}
          />
        </div>
      )}

      {/* Smart season selector */}
      {seasons.length <= 3 ? (
        <div className="flex gap-2 overflow-x-auto scrollbar-none">
          {seasons.map((s) => {
            const watched = episodesWatchedInSeason(s)
            const pct = s.episodeCount > 0 ? Math.round((watched / s.episodeCount) * 100) : 0
            const seasonAvg = avgSeasonRating(s)
            return (
              <button
                key={s.seasonNumber}
                onClick={() => handleSeasonChange(s.seasonNumber)}
                aria-label={`Season ${s.seasonNumber}`}
                aria-current={selectedSeason === s.seasonNumber ? 'true' : undefined}
                className={cn(
                  'shrink-0 px-3 py-2 rounded-lg text-left transition-all border',
                  selectedSeason === s.seasonNumber
                    ? 'border-amber/40 bg-amber/10'
                    : 'border-transparent hover:border-[var(--line)] hover:bg-[var(--wash)]'
                )}
              >
                <div className="font-mono" style={{ fontSize: '11px', color: selectedSeason === s.seasonNumber ? 'var(--amber)' : 'var(--paper-dim)' }}>
                  S{s.seasonNumber}
                </div>
                <div className="font-mono" style={{ fontSize: '9px', color: 'var(--paper-faint)' }}>
                  {pct}%{seasonAvg !== null ? ` · ★${seasonAvg.toFixed(1)}` : ''}
                </div>
              </button>
            )
          })}
        </div>
      ) : (
        <select
          value={selectedSeason}
          onChange={(e) => handleSeasonChange(parseInt(e.target.value, 10))}
          aria-label="Select season"
          className="font-mono text-sm rounded-lg px-3 py-2 bg-secondary border border-amber/30 focus:outline-none focus:border-amber/60"
          style={{ color: 'var(--amber)' }}
        >
          {seasons.map((s) => {
            const watched = episodesWatchedInSeason(s)
            const pct = s.episodeCount > 0 ? Math.round((watched / s.episodeCount) * 100) : 0
            const seasonAvg = avgSeasonRating(s)
            return (
              <option key={s.seasonNumber} value={s.seasonNumber}>
                {`Season ${s.seasonNumber} · ${pct}%${seasonAvg !== null ? ` · ★${seasonAvg.toFixed(1)}` : ''}`}
              </option>
            )
          })}
        </select>
      )}

      {/* Season cast */}
      {season?.cast && season.cast.length > 0 && (
        <div className="pl-3 border-l-2" style={{ borderColor: 'var(--line)' }}>
          <div className="font-mono mb-2" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>
            Season {season.seasonNumber} Cast
          </div>
          <div className="flex gap-2.5 overflow-x-auto scrollbar-none pb-1 -mx-1 px-1">
            {season.cast.map((member) => (
              <button
                key={member.tmdbPersonId}
                type="button"
                onClick={() => onPersonClick({ tmdbPersonId: member.tmdbPersonId, name: member.name, profileUrl: member.profileUrl, character: member.character })}
                aria-label={`View details for ${member.name}`}
                className="group shrink-0 w-[110px] overflow-hidden rounded-lg text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 transition-all"
                style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}
              >
                <div className="aspect-[2/3] overflow-hidden">
                  {member.profileUrl ? (
                    <img src={member.profileUrl} alt={member.name} className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105" />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center" style={{ background: 'var(--card)' }}>
                      <span className="font-mono text-3xl" style={{ color: 'var(--paper-faint)' }}>{member.name.charAt(0).toUpperCase()}</span>
                    </div>
                  )}
                </div>
                <div className="p-2">
                  <div className="font-sans font-semibold line-clamp-1 transition-colors group-hover:text-amber" style={{ fontSize: '12px', color: 'var(--paper)', lineHeight: 1.3 }} title={member.name}>{member.name}</div>
                  {member.character && (
                    <div className="font-mono line-clamp-1 mt-0.5" style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3 }} title={member.character}>{member.character}</div>
                  )}
                  {member.episodeCount != null && (
                    <div className="font-mono mt-0.5" style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: 0.7 }}>
                      {member.episodeCount} ep{member.episodeCount !== 1 ? 's' : ''}
                    </div>
                  )}
                </div>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Episode carousel */}
      {season && hasEpisodes(season) && (
        <div>
          <div className="relative">
            {/* Left arrow */}
            {canScrollLeft && (
              <button
                type="button"
                onClick={() => scrollCarousel('left')}
                aria-label="Scroll episodes left"
                className="absolute left-0 top-1/2 -translate-y-1/2 z-10 -translate-x-2 rounded-full p-1.5 transition-opacity hover:opacity-100 opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                style={{ background: 'var(--card)', border: '1px solid var(--line)' }}
              >
                <ChevronLeft className="w-4 h-4" style={{ color: 'var(--paper)' }} />
              </button>
            )}

            {/* Carousel */}
            <div
              ref={carouselRef}
              onScroll={handleCarouselScroll}
              className="flex gap-3 overflow-x-auto scrollbar-none pb-1"
            >
              {season.episodes!.map((ep) => (
                <EpisodeCard
                  key={ep.id}
                  episode={ep}
                  season={season}
                  titleId={titleId}
                  isSelected={selectedEpId === ep.id}
                  onSelect={() => setSelectedEpId(selectedEpId === ep.id ? null : ep.id)}
                  isSharedView={isSharedView}
                  isSpiderNoir={isSpiderNoir}
                />
              ))}
            </div>

            {/* Right arrow */}
            {canScrollRight && (
              <button
                type="button"
                onClick={() => scrollCarousel('right')}
                aria-label="Scroll episodes right"
                className="absolute right-0 top-1/2 -translate-y-1/2 z-10 translate-x-2 rounded-full p-1.5 transition-opacity hover:opacity-100 opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                style={{ background: 'var(--card)', border: '1px solid var(--line)' }}
              >
                <ChevronRight className="w-4 h-4" style={{ color: 'var(--paper)' }} />
              </button>
            )}
          </div>

          {/* Inline logging panel — renders below the carousel when a card is selected */}
          {selectedEp && (
            <div className="mt-3">
              <EpisodePanel
                episode={selectedEp}
                season={season}
                titleId={titleId}
                isSharedView={isSharedView}
                isSpiderNoir={isSpiderNoir}
                onColorModeSelected={onColorModeSelected}
              />
            </div>
          )}
        </div>
      )}

      {/* Fallback: coarse progress bar when season has no episode-level data */}
      {season && !hasEpisodes(season) && (() => {
        const pct = season.episodeCount > 0 ? (season.episodesWatched / season.episodeCount) * 100 : 0
        return (
          <div className="flex items-center gap-3 px-2 py-3">
            <span className="font-mono text-xs text-muted-foreground w-8 shrink-0">S{season.seasonNumber}</span>
            <div className="flex-1 h-1.5 bg-secondary rounded-full overflow-hidden">
              <div className="h-full bg-amber rounded-full transition-all" style={{ width: `${pct}%` }} />
            </div>
            <span className="font-mono text-xs text-muted-foreground w-12 text-right shrink-0">
              {season.episodesWatched}/{season.episodeCount}
            </span>
          </div>
        )
      })()}
    </div>
  )
}

// ─── Inline tag editor ───────────────────────────────────────────────────────

function DrawerTagEditor({
  tags,
  isSharedView,
  onChange,
}: {
  tags: string[]
  isSharedView: boolean
  onChange: (tags: string[]) => void
}) {
  const [input, setInput] = useState('')
  const [editing, setEditing] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  function commit() {
    const trimmed = input.trim().replace(/,+$/, '')
    if (trimmed && !tags.includes(trimmed)) {
      onChange([...tags, trimmed])
    }
    setInput('')
  }

  function handleKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      commit()
    } else if (e.key === 'Backspace' && input === '' && tags.length > 0) {
      onChange(tags.slice(0, -1))
    } else if (e.key === 'Escape') {
      commit()
      setEditing(false)
    }
  }

  function removeTag(tag: string) {
    onChange(tags.filter((t) => t !== tag))
  }

  if (isSharedView) {
    if (tags.length === 0) return null
    return (
      <div className="flex flex-wrap gap-1.5">
        {tags.map((t) => (
          <MetaBadge key={t} className="border-amber/30 text-amber/80">
            <Tag className="w-2.5 h-2.5 mr-1 inline" />{t}
          </MetaBadge>
        ))}
      </div>
    )
  }

  return (
    <div>
      <div className="flex items-center gap-2 mb-2">
        <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground">Tags</h4>
        {!editing && (
          <button
            onClick={() => { setEditing(true); setTimeout(() => inputRef.current?.focus(), 0) }}
            className="text-xs font-mono text-amber/50 hover:text-amber transition-colors"
          >
            + add
          </button>
        )}
      </div>
      <div className="flex flex-wrap gap-1.5">
        {tags.map((t) => (
          <span
            key={t}
            className="flex items-center gap-1 px-2 py-0.5 rounded-full bg-amber/10 border border-amber/20 font-mono text-xs text-amber"
          >
            {t}
            <button
              type="button"
              onClick={() => removeTag(t)}
              className="hover:text-amber-bright transition-colors"
              aria-label={`Remove tag ${t}`}
            >
              <X className="w-2.5 h-2.5" />
            </button>
          </span>
        ))}
        {editing && (
          <input
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKey}
            onBlur={() => { commit(); setEditing(false) }}
            placeholder="tag name…"
            aria-label="New tag name"
            className="px-2 py-0.5 rounded-full bg-secondary border border-amber/30 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-amber/60 w-28"
          />
        )}
        {!editing && tags.length === 0 && (
          <button
            onClick={() => { setEditing(true); setTimeout(() => inputRef.current?.focus(), 0) }}
            className="flex items-center gap-1 px-2 py-0.5 rounded-full border border-dashed border-amber/20 font-mono text-xs text-muted-foreground hover:border-amber/40 hover:text-amber/70 transition-colors"
          >
            <Tag className="w-2.5 h-2.5" /> add tag
          </button>
        )}
      </div>
    </div>
  )
}

// ─── Main drawer ─────────────────────────────────────────────────────────────

export function TitleDetailDrawer() {
  // ⚡ Bolt: Prevent unnecessary re-renders by using useShallow
  const { isDetailDrawerOpen, closeDetailDrawer, updateTitle, removeTitle, removeViewing, openRefreshMetadata, isSharedView } = useAppStore(
    useShallow((s) => ({
      isDetailDrawerOpen: s.isDetailDrawerOpen,
      closeDetailDrawer: s.closeDetailDrawer,
      updateTitle: s.updateTitle,
      removeTitle: s.removeTitle,
      removeViewing: s.removeViewing,
      openRefreshMetadata: s.openRefreshMetadata,
      isSharedView: s.isSharedView,
    }))
  )
  const browseByStudio = useAppStore((s) => s.browseByStudio)
  const title = useSelectedTitle()
  const user = useAppStore((s) => s.user)

  const isSpiderNoir = title?.tmdbId === SPIDER_NOIR_TMDB_ID

  const pinnedModes = useAppStore((s) => s.pinnedModes)
  const setPinnedMode = useAppStore((s) => s.setPinnedMode)

  const unlockedModes = useMemo(
    () => (isSpiderNoir && title ? getUnlockedModes(title) : new Set<'bw' | 'color'>()),
    [isSpiderNoir, title]
  )
  const earnedModes = useMemo(
    () => (isSpiderNoir && title ? getEarnedModes(title) : new Set<'bw' | 'color'>()),
    [isSpiderNoir, title]
  )

  const prevNoirModeRef = useRef<'bw' | 'color' | null | undefined>(undefined)
  const [noirAnim, setNoirAnim] = useState<'bw' | 'color' | null>(null)
  const noirAnimTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [manualMode, setManualMode] = useState<SelectorMode>('normal')

  // closeDetailDrawer only sets isDetailDrawerOpen=false, not selectedTitleId=null,
  // so title and isSpiderNoir remain valid here even when the drawer is closed.
  const pinnedModeRaw = (title ? (pinnedModes[`${title.id}:${EASTER_EGG_KEY}`] ?? null) : null) as 'bw' | 'color' | null

  // Seed manualMode from pinned → last watch event → normal when the drawer opens.
  // setTimeout(0) defers the setState out of the effect body to satisfy react-hooks/set-state-in-effect.
  useEffect(() => {
    if (!isSpiderNoir || !title || !isDetailDrawerOpen) return
    const derived = getSpiderNoirActiveMode(title)
    const seeded = pinnedModeRaw ?? derived ?? 'normal'
    const t = setTimeout(() => setManualMode(seeded as SelectorMode), 0)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title?.id, isDetailDrawerOpen])

  // While drawer is open: use manual selection. When closed: use pinned (if any).
  const effectiveNoirMode: 'bw' | 'color' | null = isSpiderNoir
    ? (isDetailDrawerOpen ? (manualMode !== 'normal' ? (manualMode as 'bw' | 'color') : null) : pinnedModeRaw)
    : null

  useEffect(() => {
    const ALL = ['spider-noir-bw', 'spider-noir-color', 'spider-noir-bw-enter', 'spider-noir-color-enter'] as const
    const prevMode = prevNoirModeRef.current

    // Skip the transition on the very first render (prev === undefined) —
    // the drawer isn't open yet so there's nothing to animate between.
    // Also skip if the mode hasn't actually changed.
    const isVisualChange = prevMode !== undefined && prevMode !== effectiveNoirMode

    function applyClasses() {
      document.body.classList.remove(...ALL)
      if (effectiveNoirMode) {
        document.body.classList.add(effectiveNoirMode === 'bw' ? 'spider-noir-bw' : 'spider-noir-color')
        document.body.classList.add(effectiveNoirMode === 'bw' ? 'spider-noir-bw-enter' : 'spider-noir-color-enter')
      }
    }

    if (isVisualChange) {
      // The View Transition must capture the current (pre-change) DOM state
      // before applyClasses runs. We pass applyClasses as the commit so the
      // API snapshots before/after correctly — this works for both the enter
      // (normal → noir) and exit (noir → normal) directions.
      transitionSpiderNoir(applyClasses)
      if (noirAnimTimerRef.current) clearTimeout(noirAnimTimerRef.current)
      if (effectiveNoirMode) {
        // Entering or switching: cast the web overlay on top of the VT reveal.
        // Deferred so it runs as an async callback (not synchronously in the
        // effect body) — avoids the react-hooks/set-state-in-effect lint rule.
        noirAnimTimerRef.current = setTimeout(() => {
          setNoirAnim(effectiveNoirMode)
          noirAnimTimerRef.current = setTimeout(() => {
            setNoirAnim(null)
            document.body.classList.remove('spider-noir-bw-enter', 'spider-noir-color-enter')
          }, 2100)
        }, 0)
      } else {
        // Exiting: dismiss any lingering web overlay so it doesn't hang over
        // the returned normal view.
        noirAnimTimerRef.current = setTimeout(() => setNoirAnim(null), 0)
      }
    } else {
      applyClasses()
    }

    prevNoirModeRef.current = effectiveNoirMode

    // Do NOT remove body classes in the cleanup — the cleanup fires before the
    // next effect run, which would cause the exit View Transition to capture
    // no-noir → no-noir (invisible). Class removal is handled by applyClasses
    // in the next run. The timer must still be cleared on unmount.
    return () => {
      if (noirAnimTimerRef.current) clearTimeout(noirAnimTimerRef.current)
    }
  }, [effectiveNoirMode])

  const [showLogForm, setShowLogForm] = useState(false)
  const [logDate, setLogDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [logRating, setLogRating] = useState(0)
  const [logNotes, setLogNotes] = useState('')
  const [showMovieSaved, setShowMovieSaved] = useState(false)
  const [pendingDeleteTitle, setPendingDeleteTitle] = useState(false)
  const [posterLightboxOpen, setPosterLightboxOpen] = useState(false)
  const [activePerson, setActivePerson] = useState<PersonDetailTarget | null>(null)
  const [videos, setVideos] = useState<TitleVideo[]>([])
  const [logoUrl, setLogoUrl] = useState<string | null>(null)
  const [heroBackdropUrl, setHeroBackdropUrl] = useState<string | null>(null)

  useEffect(() => {
    if (!isDetailDrawerOpen || !title?.tmdbId) {
      // Deferred to satisfy react-hooks/set-state-in-effect (same pattern as manualMode seeding above).
      const t = setTimeout(() => setVideos([]), 0)
      return () => clearTimeout(t)
    }
    let cancelled = false
    fetchTitleVideos(title.tmdbId, title.type).then((v) => {
      if (!cancelled) setVideos(v)
    })
    return () => { cancelled = true }
  }, [isDetailDrawerOpen, title?.tmdbId, title?.type])

  // Images: best logo + best backdrop at original resolution, fetched together.
  // No backdropUrl guard — we fetch for any title with a tmdbId so titles
  // without a stored backdrop can still get a cinematic hero + logo.
  useEffect(() => {
    if (!isDetailDrawerOpen || !title?.tmdbId) {
      const t = setTimeout(() => { setLogoUrl(null); setHeroBackdropUrl(null) }, 0)
      return () => clearTimeout(t)
    }
    let cancelled = false
    fetchTitleImages(title.tmdbId, title.type).then(({ logoUrl: logo, backdropUrl: backdrop }) => {
      if (!cancelled) {
        setLogoUrl(logo)
        setHeroBackdropUrl(backdrop)
      }
    })
    return () => { cancelled = true }
  }, [isDetailDrawerOpen, title?.tmdbId, title?.type])

  function onClose() {
    setPendingDeleteTitle(false)
    setPosterLightboxOpen(false)
    setActivePerson(null)
    closeDetailDrawer()
  }

  // Track which title IDs have already been backfilled this session to avoid repeat calls.
  const backfilledRef = useRef<Set<string>>(new Set())

  // When a TV show is opened and episode metadata is missing, fetch season details
  // from TMDB and hydrate them in-place, then persist to DB. Handles two cases:
  // (a) season rows exist but no episode rows were ever inserted, and
  // (b) episode rows exist but none have names yet.
  useEffect(() => {
    if (!title || title.type !== 'tv' || !title.seasons || title.tmdbId <= 0 || isSharedView) return

    const seasonsNeedingBackfill = title.seasons.filter((s) =>
      // Case (a): season exists but no episodes were persisted to DB yet
      (s.episodeCount > 0 && (!s.episodes || s.episodes.length === 0)) ||
      // Case (b): episode rows exist but none have a name (pre-feature data)
      (s.episodes && s.episodes.length > 0 && s.episodes.every((ep) => !ep.episodeName))
      // Season cast is captured as a bonus of episode backfills above;
      // shows with complete episode data get cast via "Refresh metadata" instead.
    )
    if (seasonsNeedingBackfill.length === 0) return

    const cacheKey = `${title.id}:${title.tmdbId}`
    if (backfilledRef.current.has(cacheKey)) return
    backfilledRef.current.add(cacheKey)

    const snapshotTitle = title
    const snapshotUser = user

    const EP_CREW_JOBS_BF = new Set(['Director', 'Writer', 'Teleplay', 'Story'])

    async function backfill() {
      const settled = await Promise.allSettled(
        seasonsNeedingBackfill.map(async (season) => {
          const { episodes: tmdbEps, cast: seasonCast } = await fetchSeasonDetails(snapshotTitle.tmdbId, season.seasonNumber)
          return { season, tmdbEps, seasonCast }
        })
      )

      let updatedSeasons = [...snapshotTitle.seasons!]
      const allUpdatedEpisodes: Parameters<typeof upsertEpisodeMetadataInDb>[2] = []
      const allEpisodeCrew: Array<{ episodeId: string; crew: EpisodeCrew[] }> = []
      const allSeasonCast: Array<{ seasonId: string; cast: CastMember[] }> = []

      for (const result of settled) {
        if (result.status !== 'fulfilled' || result.value.tmdbEps.length === 0) continue
        const { season, tmdbEps, seasonCast } = result.value

        const existingEpisodes = season.episodes || []
        let updatedEpisodes: Episode[]

        if (existingEpisodes.length === 0) {
          updatedEpisodes = tmdbEps.map((tmdbEp) => {
            const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
              .filter((c) => EP_CREW_JOBS_BF.has(c.job))
              .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
            return {
              id: crypto.randomUUID(),
              episodeNumber: tmdbEp.episode_number,
              episodeName: tmdbEp.name || undefined,
              airDate: tmdbEp.air_date || undefined,
              runtime: tmdbEp.runtime || undefined,
              synopsis: tmdbEp.overview || undefined,
              stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : undefined,
              director: epCrew.find((c) => c.job === 'Director')?.name,
              writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
              crew: epCrew.length > 0 ? epCrew : undefined,
              watchEvents: [],
              ratings: [],
              reviews: [],
            }
          })
          updatedSeasons = updatedSeasons.map((s) =>
            s.seasonNumber === season.seasonNumber
              ? { ...s, episodes: updatedEpisodes, episodeCount: updatedEpisodes.length, cast: seasonCast.length > 0 ? seasonCast : s.cast }
              : s
          )
        } else {
          updatedEpisodes = existingEpisodes.map((ep) => {
            const tmdbEp = tmdbEps.find((e) => e.episode_number === ep.episodeNumber)
            if (!tmdbEp) return ep
            const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
              .filter((c) => EP_CREW_JOBS_BF.has(c.job))
              .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
            return {
              ...ep,
              episodeName: tmdbEp.name || ep.episodeName,
              airDate: tmdbEp.air_date || ep.airDate,
              runtime: tmdbEp.runtime || ep.runtime,
              synopsis: tmdbEp.overview || ep.synopsis,
              stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : ep.stillUrl,
              director: epCrew.find((c) => c.job === 'Director')?.name ?? ep.director,
              writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
              crew: epCrew.length > 0 ? epCrew : ep.crew,
            }
          })
          updatedSeasons = updatedSeasons.map((s) =>
            s.seasonNumber === season.seasonNumber
              ? { ...s, episodes: updatedEpisodes, cast: seasonCast.length > 0 ? seasonCast : s.cast }
              : s
          )
        }

        for (const ep of updatedEpisodes) {
          allUpdatedEpisodes.push({
            id: ep.id,
            seasonNumber: season.seasonNumber,
            episodeNumber: ep.episodeNumber,
            episodeName: ep.episodeName,
            airDate: ep.airDate,
            runtime: ep.runtime,
            synopsis: ep.synopsis,
            stillUrl: ep.stillUrl,
          })
          if (ep.crew && ep.crew.length > 0) {
            allEpisodeCrew.push({ episodeId: ep.id, crew: ep.crew })
          }
        }

        if (seasonCast.length > 0) {
          allSeasonCast.push({ seasonId: season.id, cast: seasonCast })
        }
      }

      if (allUpdatedEpisodes.length > 0 || allSeasonCast.length > 0) {
        updateTitle(snapshotTitle.id, { seasons: updatedSeasons })
        if (snapshotUser) {
          if (allUpdatedEpisodes.length > 0) {
            upsertEpisodeMetadataInDb(snapshotUser.id, snapshotTitle.id, allUpdatedEpisodes).catch((e) =>
              console.error('Episode metadata backfill DB write failed:', e)
            )
          }
          for (const { seasonId, cast } of allSeasonCast) {
            upsertSeasonCastInDb(snapshotUser.id, snapshotTitle.id, seasonId, cast).catch((e) =>
              console.error('Season cast backfill DB write failed:', e)
            )
          }
          for (const { episodeId, crew } of allEpisodeCrew) {
            upsertEpisodeCrewInDb(snapshotUser.id, snapshotTitle.id, episodeId, crew).catch((e) =>
              console.error('Episode crew backfill DB write failed:', e)
            )
          }
        }
      }
    }

    backfill().catch((e) => console.error('Episode metadata backfill failed:', e))
  }, [title?.id, title?.tmdbId]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!title) return null

  function logViewing() {
    if (!title || !logDate) return
    const viewing: Viewing = {
      id: crypto.randomUUID(),
      titleId: title.id,
      date: logDate,
      rating: logRating > 0 ? logRating : undefined,
      notes: logNotes || undefined,
    }
    updateTitle(title.id, {
      viewings: [...title.viewings, viewing],
      status: 'watched',
      rating: logRating > 0 ? logRating : title.rating,
    })
    setShowMovieSaved(true)
    setTimeout(() => {
      setShowMovieSaved(false)
      setShowLogForm(false)
      setLogDate(new Date().toISOString().slice(0, 10))
      setLogRating(0)
      setLogNotes('')
    }, 1500)
  }

  function handleDelete() {
    if (!title) return
    closeDetailDrawer()
    removeTitle(title.id)
  }

  function handleModeSelect(mode: SelectorMode) {
    setManualMode(mode)
  }

  function handleTogglePin(mode: 'bw' | 'color') {
    if (!title) return
    const newVariant = pinnedModeRaw === mode ? null : mode
    setPinnedMode(title.id, EASTER_EGG_KEY, newVariant)
  }

  return (
    <>
    {noirAnim && <SpiderWebOverlay mode={noirAnim} />}
    <CinemaModal
      open={isDetailDrawerOpen}
      onClose={onClose}
      maxWidth="sm:max-w-2xl"
      title={title.title}
      description={title.synopsis ?? `Details and viewing history for ${title.title}.`}
      expanded
    >
      {/* Poster lightbox — rendered inside portal, above dialog via z-[60] */}
      {posterLightboxOpen && title.posterUrl && (
        <PosterLightbox
          src={title.posterUrl}
          alt={title.title}
          onClose={() => setPosterLightboxOpen(false)}
        />
      )}

      {/* Person detail panel */}
      {activePerson && (
        <PersonDetailPanel
          person={activePerson}
          onClose={() => setActivePerson(null)}
        />
      )}

      <div className="overflow-y-auto flex-1 scrollbar-thin pb-16 sm:pb-0">
        {/* Hero: cinematic backdrop (stored or fetched) or blurred-poster fallback */}
        {(title.backdropUrl || heroBackdropUrl) ? (
          <HeroBackdrop title={title} backdropOverride={heroBackdropUrl ?? undefined} onPosterClick={() => setPosterLightboxOpen(true)}>
            <div className="flex items-center gap-2">
              {title.type === 'movie' ? (
                <Film className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
              ) : (
                <Tv className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
              )}
              <span className="font-mono text-xs text-muted-foreground uppercase tracking-wider">
                {title.type === 'tv' ? 'Series' : 'Film'}
              </span>
              {title.network && (
                <span className="font-mono text-xs text-muted-foreground">· {title.network}</span>
              )}
            </div>
            {logoUrl ? (
              <img
                src={logoUrl}
                alt={title.title}
                className="object-contain object-left max-h-20 sm:max-h-28 max-w-[90%] drop-shadow-lg"
              />
            ) : (
              <CardTitle className="text-xl leading-tight">{title.title}</CardTitle>
            )}
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span className="font-mono text-sm text-amber">{title.year}</span>
              {title.director && title.type === 'movie' && (
                <span className="text-xs text-muted-foreground font-sans">dir. {title.director}</span>
              )}
              {title.runtime && title.type === 'movie' && (
                <div className="flex items-center gap-1 text-xs text-muted-foreground">
                  <Clock className="w-3 h-3" />
                  <span className="font-mono">{title.runtime}m</span>
                </div>
              )}
            </div>
            {isSpiderNoir && (
              <SpiderNoirModeSelector
                unlockedModes={unlockedModes}
                earnedModes={earnedModes}
                selected={manualMode}
                pinned={pinnedModeRaw}
                onSelect={handleModeSelect}
                onTogglePin={handleTogglePin}
              />
            )}
            <StarRating
              value={title.rating ?? 0}
              size="sm"
              onChange={isSharedView ? undefined : (rating) => updateTitle(title.id, { rating })}
            />
          </HeroBackdrop>
        ) : (
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
            <div className="absolute inset-0 bg-gradient-to-b from-secondary/30 via-card/70 to-card" />
            <div className="relative z-10 flex gap-5 px-6 pt-10 pb-6">
              <div className="w-28 sm:w-36 shrink-0">
                {title.posterUrl ? (
                  <button
                    type="button"
                    onClick={() => setPosterLightboxOpen(true)}
                    aria-label={`View full poster for ${title.title}`}
                    className="block w-full rounded-lg overflow-hidden transition-opacity hover:opacity-90 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                    title="View full poster"
                  >
                    <DynamicPoster title={title} />
                  </button>
                ) : (
                  <DynamicPoster title={title} />
                )}
              </div>
              <div className="flex-1 min-w-0 space-y-2 pt-6">
                <div className="flex items-center gap-2">
                  {title.type === 'movie' ? (
                    <Film className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                  ) : (
                    <Tv className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                  )}
                  <span className="font-mono text-xs text-muted-foreground uppercase tracking-wider">
                    {title.type === 'tv' ? 'Series' : 'Film'}
                  </span>
                  {title.network && (
                    <span className="font-mono text-xs text-muted-foreground">· {title.network}</span>
                  )}
                </div>
                <CardTitle className="text-xl leading-tight">{title.title}</CardTitle>
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <span className="font-mono text-sm text-amber">{title.year}</span>
                  {title.director && (
                    <span className="text-xs text-muted-foreground font-sans">dir. {title.director}</span>
                  )}
                  {title.runtime && title.type === 'movie' && (
                    <div className="flex items-center gap-1 text-xs text-muted-foreground">
                      <Clock className="w-3 h-3" />
                      <span className="font-mono">{title.runtime}m</span>
                    </div>
                  )}
                </div>
                {isSpiderNoir && (
                  <SpiderNoirModeSelector
                    unlockedModes={unlockedModes}
                    earnedModes={earnedModes}
                    selected={manualMode}
                    pinned={pinnedModeRaw}
                    onSelect={handleModeSelect}
                    onTogglePin={handleTogglePin}
                  />
                )}
                <StarRating
                  value={title.rating ?? 0}
                  size="sm"
                  onChange={isSharedView ? undefined : (rating) => updateTitle(title.id, { rating })}
                />
              </div>
            </div>
          </div>
        )}

        {/* Scrollable body */}
        <div className="px-6 pb-6 space-y-5">
          {/* Upper info — two columns on desktop so the right side is used */}
          <div className="lg:grid lg:grid-cols-[1fr_300px] lg:gap-8 lg:items-start space-y-5 lg:space-y-0">
            {/* Left column — status, synopsis, genres */}
            <div className="space-y-5 min-w-0">
              {/* Status */}
              <div>
                <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">Status</h4>
                <div className="relative inline-block">
                  <select
                    value={title.status}
                    onChange={isSharedView ? undefined : (e) => updateTitle(title.id, { status: e.target.value as WatchStatus })}
                    disabled={isSharedView}
                    aria-label="Title status"
                    className={cn(
                      'appearance-none font-sans text-sm rounded-lg pl-3 pr-9 py-2 bg-secondary border border-amber/30 focus:outline-none focus:border-amber/60',
                      isSharedView && 'opacity-60 cursor-default'
                    )}
                    style={{ color: 'var(--amber)' }}
                  >
                    {STATUS_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                  <ChevronDown
                    className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4"
                    style={{ color: 'var(--amber)' }}
                  />
                </div>
              </div>

              {/* Synopsis */}
              {title.synopsis && (
                <div>
                  <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">Synopsis</h4>
                  <BodyText className="text-sm leading-relaxed max-w-2xl">{title.synopsis}</BodyText>
                </div>
              )}

              {/* Genres */}
              {title.genres.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  {title.genres.map((g) => (
                    <MetaBadge key={g} className="border-amber/20 text-amber/70">{g}</MetaBadge>
                  ))}
                </div>
              )}
            </div>

            {/* Right column — details + critical reception */}
            <div className="space-y-5">
              <div>
                <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">Details</h4>
                <dl className="space-y-2 rounded-lg bg-secondary/30 p-3">
                  {title.network && <DetailRow label="Network" value={title.network} />}
                  {title.type === 'movie' && title.director && <DetailRow label="Director" value={title.director} />}
                  {title.runtime ? <DetailRow label="Runtime" value={`${title.runtime} min`} /> : null}
                  {title.contentRating && <DetailRow label="Rated" value={title.contentRating} />}
                  {title.originalLanguage && <DetailRow label="Language" value={languageName(title.originalLanguage)} />}
                  {title.releaseDate && (
                    <DetailRow
                      label={title.releaseDate > new Date().toISOString().slice(0, 10) ? 'Releases' : 'Released'}
                      value={fmtReleaseDate(title.releaseDate)}
                    />
                  )}
                  {title.studios && title.studios.length > 0 && (
                    <DetailRow label="Studio" value={title.studios.join(', ')} />
                  )}
                  <DetailRow label="Added" value={fmtDate(title.addedAt)} />
                </dl>
              </div>

              {/* Critical Reception */}
              {(title.imdbRating || title.rtScore || title.metacriticScore) && (
                <div>
                  <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                    Critical Reception
                  </h4>
                  <ReviewBadges imdb={title.imdbRating} rt={title.rtScore} meta={title.metacriticScore} />
                </div>
              )}

              {/* External links */}
              <ExternalLinks title={title} />
            </div>
          </div>

          {/* Tags — editable when not in shared view */}
          {(!isSharedView || title.tags.length > 0) && (
            <DrawerTagEditor
              tags={title.tags}
              isSharedView={isSharedView}
              onChange={(tags) => updateTitle(title.id, { tags })}
            />
          )}

          {/* Cast & Crew */}
          {(title.cast?.length || title.crew?.length || title.studios?.length) ? (
            <div>
              <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">
                Cast &amp; Crew
              </h4>
              <CastCrewSection
                cast={title.cast}
                crew={title.crew}
                studios={title.studios}
                onPersonClick={setActivePerson}
                onStudioClick={browseByStudio}
              />
            </div>
          ) : null}

          {/* ── TV Series section ───────────────────────────────────── */}
          {title.type === 'tv' && title.seasons && title.seasons.length > 0 && (
            <div>
              <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-4">
                Season &amp; Episodes
              </h4>
              <TVSeriesSection
                titleId={title.id}
                seasons={title.seasons}
                isSharedView={isSharedView}
                isSpiderNoir={title.tmdbId === SPIDER_NOIR_TMDB_ID}
                onPersonClick={setActivePerson}
                onColorModeSelected={handleModeSelect}
              />
            </div>
          )}

          {/* ── Movie section (and TV without seasons) ─────────────── */}
          {title.type === 'movie' && (
            <>
              {/* Viewing Stats */}
              <div className="grid grid-cols-2 gap-4">
                <div className="bg-secondary/40 rounded-lg p-3 text-center">
                  <StatNumber className="text-xl">{title.viewings.length}</StatNumber>
                  <div className="mt-0.5">
                    <StatLabel>Viewings</StatLabel>
                  </div>
                </div>
                <div className="bg-secondary/40 rounded-lg p-3 text-center">
                  <div className="flex items-center justify-center gap-1 mb-0.5">
                    <Calendar className="w-3.5 h-3.5 text-muted-foreground" />
                    <StatNumber className="text-base leading-tight">
                      {title.viewings.length > 0
                        ? fmtDate(
                            title.viewings
                              .slice()
                              .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())[0]
                              .date
                          )
                        : '—'}
                    </StatNumber>
                  </div>
                  <StatLabel>Last Seen</StatLabel>
                </div>
              </div>

              {/* Viewing History */}
              <div>
                <div className="flex items-center justify-between mb-3">
                  <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground">
                    Viewing History
                  </h4>
                  {!showLogForm && !isSharedView && (
                    <button
                      onClick={() => setShowLogForm(true)}
                      className="flex items-center gap-1 text-xs font-mono text-amber/70 hover:text-amber transition-colors"
                    >
                      <Plus className="w-3.5 h-3.5" />
                      Log a viewing
                    </button>
                  )}
                </div>

                {showLogForm && (
                  <div className="border-t pt-3 mb-4 space-y-3" style={{ borderColor: 'var(--line)' }}>
                    <div>
                      <label htmlFor="viewing-date" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
                        <Calendar className="inline w-3 h-3 mr-1" />
                        Date Watched
                      </label>
                      <Input
                        id="viewing-date"
                        type="date"
                        value={logDate}
                        onChange={(e) => setLogDate(e.target.value)}
                        className="bg-secondary/50 border-border font-mono"
                      />
                    </div>
                    <div>
                      <p className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                        Rating
                      </p>
                      <StarRating value={logRating} onChange={setLogRating} size="md" />
                    </div>
                    <div>
                      <label htmlFor="viewing-notes" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
                        <FileText className="inline w-3 h-3 mr-1" />
                        Notes
                      </label>
                      <textarea
                        id="viewing-notes"
                        value={logNotes}
                        onChange={(e) => setLogNotes(e.target.value)}
                        placeholder="Your thoughts…"
                        rows={2}
                        className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-2 focus:ring-amber/30"
                      />
                    </div>
                    <div className="flex gap-2">
                      <Button
                        className="flex-1 bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
                        onClick={logViewing}
                        disabled={showMovieSaved}
                      >
                        {showMovieSaved ? (
                          <>
                            <Check className="w-4 h-4 mr-2" />
                            Saved
                          </>
                        ) : (
                          <>
                            <Star className="w-4 h-4 mr-2" />
                            Save Viewing
                          </>
                        )}
                      </Button>
                      <Button variant="outline" onClick={() => setShowLogForm(false)}>
                        Cancel
                      </Button>
                    </div>
                  </div>
                )}

                <ViewingTimeline
                  viewings={title.viewings}
                  isSharedView={isSharedView}
                  onDeleteViewing={(viewingId) => removeViewing(title.id, viewingId)}
                  onLogViewing={() => setShowLogForm(true)}
                />
              </div>
            </>
          )}

          {/* Trailers */}
          <TrailerRow videos={videos} />

          {/* Maintenance actions */}
          {!isSharedView && (
            <div
              className="pt-2 border-t"
              style={{ borderColor: 'var(--line)' }}
            >
              {pendingDeleteTitle ? (
                <div className="flex items-center justify-between">
                  <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
                    Remove from library forever?
                  </span>
                  <div className="flex items-center gap-3">
                    <button
                      onClick={handleDelete}
                      className="font-mono text-xs transition-opacity hover:opacity-80"
                      style={{ color: 'var(--ember)' }}
                    >
                      Delete forever
                    </button>
                    <button
                      onClick={() => setPendingDeleteTitle(false)}
                      className="font-mono text-xs transition-opacity hover:opacity-80"
                      style={{ color: 'var(--paper-faint)' }}
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              ) : (
                <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
                  <button
                    onClick={openRefreshMetadata}
                    className="flex items-center gap-2 text-xs font-mono text-muted-foreground hover:text-amber transition-colors"
                  >
                    <RefreshCw className="w-3.5 h-3.5" />
                    Refresh poster &amp; metadata
                  </button>
                  <button
                    onClick={() => setPendingDeleteTitle(true)}
                    className="flex items-center gap-2 text-xs font-mono text-muted-foreground hover:text-ember transition-colors"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                    Remove from library
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </CinemaModal>
    </>
  )
}
