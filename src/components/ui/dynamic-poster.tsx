import { useMemo } from 'react'
import { cn } from 'src/lib/utils'
import type { Title } from 'src/store/mockData'

interface DynamicPosterProps {
  title: Title
  className?: string
  onClick?: () => void
}

const PALETTE = [
  ['#1a0a2e', '#e9b266'],
  ['#0a1a2e', '#66b2e9'],
  ['#1a2e0a', '#a8e966'],
  ['#2e0a0a', '#e96666'],
  ['#0a2e1a', '#66e9b2'],
  ['#2e1a0a', '#e9a066'],
  ['#1a0a1a', '#cc66e9'],
  ['#0a0a2e', '#6680e9'],
]

function hashString(str: string): number {
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i)
    hash = (hash << 5) - hash + char
    hash = hash & hash
  }
  return Math.abs(hash)
}

export function DynamicPoster({ title, className, onClick }: DynamicPosterProps) {
  const hasImage = Boolean(title.posterUrl)

  const [bg, accent] = useMemo(() => {
    const idx = hashString(title.title) % PALETTE.length
    return PALETTE[idx]
  }, [title.title])

  const initials = title.title
    .split(' ')
    .filter((w) => w.length > 2)
    .slice(0, 2)
    .map((w) => w[0].toUpperCase())
    .join('')

  if (hasImage) {
    return (
      <div
        className={cn('poster-container cursor-pointer group', className)}
        onClick={onClick}
        role={onClick ? 'button' : undefined}
      >
        <img
          src={title.posterUrl}
          alt={title.title}
          className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
          loading="lazy"
        />
        <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-transparent to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-200" />
      </div>
    )
  }

  return (
    <div
      className={cn('poster-container cursor-pointer group select-none', className)}
      style={{ background: `linear-gradient(135deg, ${bg} 0%, ${bg}ee 100%)` }}
      onClick={onClick}
      role={onClick ? 'button' : undefined}
    >
      {/* Decorative lines */}
      <div
        className="absolute inset-0 opacity-10"
        style={{
          backgroundImage: `repeating-linear-gradient(45deg, ${accent} 0px, ${accent} 1px, transparent 1px, transparent 20px)`,
        }}
      />
      {/* Initials monogram */}
      <div className="absolute inset-0 flex flex-col items-center justify-center p-3">
        <span
          className="font-serif text-4xl font-light mb-2 transition-transform group-hover:scale-110"
          style={{ color: accent }}
        >
          {initials}
        </span>
        <span
          className="font-sans text-xs text-center leading-tight px-2 line-clamp-3"
          style={{ color: `${accent}cc` }}
        >
          {title.title}
        </span>
        <span
          className="font-mono text-xs mt-2 opacity-60"
          style={{ color: accent }}
        >
          {title.year}
        </span>
      </div>
      <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent opacity-0 group-hover:opacity-100 transition-opacity" />
    </div>
  )
}
