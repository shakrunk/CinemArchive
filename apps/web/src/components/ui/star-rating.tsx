import { useState } from 'react'
import { cn } from 'src/lib/utils'

/** Where the stars sit, which decides whether their colors flip with the theme.
 *  `default` — on a themed surface: amber deepens on light so it stays legible.
 *  `onArt`   — on a dark scrim over poster/backdrop art, which stays dark in
 *              both themes, so the marks pin to the constant art palette. */
type StarTone = 'default' | 'onArt'

interface StarRatingProps {
  value?: number
  max?: number
  onChange?: (rating: number) => void
  readonly?: boolean
  size?: 'sm' | 'md' | 'lg'
  tone?: StarTone
  className?: string
}

const sizeMap = { sm: 'text-sm', md: 'text-base', lg: 'text-xl' }

const toneMap: Record<StarTone, { filled: string; empty: string; caption: string }> = {
  default: {
    filled: 'var(--amber)',
    empty: 'rgb(var(--paper-faint-rgb) / 0.45)',
    caption: 'var(--paper-dim)',
  },
  onArt: {
    filled: 'rgb(var(--amber-on-art))',
    empty: 'rgb(var(--ivory) / 0.32)',
    caption: 'rgb(var(--ivory) / 0.75)',
  },
}

// Trailing ".0" reads as noise on a star rating — drop it for whole numbers.
function fmtRating(value: number): string {
  return value % 1 === 0 ? value.toFixed(0) : value.toFixed(1)
}

export function StarRating({
  value = 0,
  max = 5,
  onChange,
  readonly = false,
  size = 'md',
  tone = 'default',
  className,
}: StarRatingProps) {
  const [hovered, setHovered] = useState<number | null>(null)
  const colors = toneMap[tone]

  const display = hovered ?? value

  function getStarType(starIndex: number): 'full' | 'half' | 'empty' {
    const filled = display * 2
    const starFilled = starIndex * 2
    if (filled >= starFilled) return 'full'
    if (filled >= starFilled - 1) return 'half'
    return 'empty'
  }

  function handleMouseMove(e: React.MouseEvent<HTMLButtonElement>, star: number) {
    const rect = e.currentTarget.getBoundingClientRect()
    const half = e.clientX - rect.left < rect.width / 2
    setHovered(half ? star - 0.5 : star)
  }

  function handleClick(e: React.MouseEvent<HTMLButtonElement>, star: number) {
    if (readonly || !onChange) return
    const rect = e.currentTarget.getBoundingClientRect()
    const half = e.clientX - rect.left < rect.width / 2
    onChange(half ? star - 0.5 : star)
  }

  return (
    <div
      className={cn('star-rating', sizeMap[size], className)}
      aria-label={`Rating: ${value} out of ${max}`}
    >
      {Array.from({ length: max }, (_, i) => {
        const star = i + 1
        const type = getStarType(star)
        return (
          <button
            key={star}
            type="button"
            disabled={readonly}
            onMouseMove={(e) => !readonly && handleMouseMove(e, star)}
            onMouseLeave={() => !readonly && setHovered(null)}
            onClick={(e) => handleClick(e, star)}
            className={cn(
              'relative focus:outline-none',
              readonly ? 'cursor-default' : 'cursor-pointer hover:scale-110 transition-transform'
            )}
            aria-label={`${star} stars`}
          >
            {type === 'full' && <span style={{ color: colors.filled }}>★</span>}
            {type === 'half' && (
              <span className="relative">
                <span style={{ color: colors.empty }}>★</span>
                <span
                  className="absolute inset-0 overflow-hidden w-1/2"
                  style={{ color: colors.filled }}
                >
                  ★
                </span>
              </span>
            )}
            {type === 'empty' && <span style={{ color: colors.empty }}>★</span>}
          </button>
        )
      })}
      {value > 0 && (
        <span className="ml-1 text-xs font-mono" style={{ color: colors.caption }}>
          {fmtRating(value)}
        </span>
      )}
    </div>
  )
}

// Display-only compact version for poster overlays. Always sits on artwork, so
// it pins the constant amber rather than the theme-aware one.
export function StarBadge({ rating, className }: { rating: number; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-0.5 font-mono text-xs bg-black/70 px-1.5 py-0.5 rounded',
        className
      )}
      style={{ color: 'rgb(var(--amber-on-art))' }}
    >
      <span>★</span>
      <span>{fmtRating(rating)}</span>
    </span>
  )
}

