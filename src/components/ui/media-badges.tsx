import type { MediaType } from 'src/store/mockData'

// ─── Review Badges ─────────────────────────────────────────────────────────────

export function ReviewBadges({ imdb, rt, meta }: { imdb?: number; rt?: number; meta?: number }) {
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

// ─── External Links ─────────────────────────────────────────────────────────────

export interface MediaRef {
  title: string
  tmdbId: number
  type: MediaType
  imdbId?: string
}

type LinkBrand = 'tmdb' | 'imdb' | 'rt' | 'metacritic'

export function buildExternalLinks(m: MediaRef): Array<{ brand: LinkBrand; name: string; href: string }> {
  const links: Array<{ brand: LinkBrand; name: string; href: string }> = []
  const q = encodeURIComponent(m.title)

  if (m.tmdbId > 0) {
    links.push({ brand: 'tmdb', name: 'TMDB', href: `https://www.themoviedb.org/${m.type}/${m.tmdbId}` })
  }
  links.push({
    brand: 'imdb',
    name: 'IMDb',
    href: m.imdbId
      ? `https://www.imdb.com/title/${m.imdbId}/`
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

export function ExternalLinks({ media }: { media: MediaRef }) {
  const links = buildExternalLinks(media)
  if (links.length === 0) return null
  return (
    <div>
      <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim mb-4">Links</h4>
      <div className="flex flex-wrap items-center gap-2">
        {links.map((l, i) => {
          const { bg, fg } = BRAND_CONFIG[l.brand]
          return (
            <a
              key={l.brand}
              href={l.href}
              target="_blank"
              rel="noopener noreferrer"
              aria-label={`Open ${media.title} on ${l.name}`}
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
