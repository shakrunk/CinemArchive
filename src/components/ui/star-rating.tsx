import { useState } from 'react'
import { cn } from 'src/lib/utils'

interface StarRatingProps {
  value?: number
  max?: number
  onChange?: (rating: number) => void
  readonly?: boolean
  size?: 'sm' | 'md' | 'lg'
  className?: string
}

const sizeMap = { sm: 'text-sm', md: 'text-base', lg: 'text-xl' }

export function StarRating({
  value = 0,
  max = 5,
  onChange,
  readonly = false,
  size = 'md',
  className,
}: StarRatingProps) {
  const [hovered, setHovered] = useState<number | null>(null)

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
            {type === 'full' && <span>★</span>}
            {type === 'half' && (
              <span className="relative">
                <span className="text-muted-foreground">★</span>
                <span className="absolute inset-0 overflow-hidden w-1/2">★</span>
              </span>
            )}
            {type === 'empty' && <span className="text-muted-foreground/40">★</span>}
          </button>
        )
      })}
      {value > 0 && (
        <span className="ml-1 text-muted-foreground text-xs font-mono">
          {value % 1 === 0 ? value.toFixed(0) : value.toFixed(1)}
        </span>
      )}
    </div>
  )
}

// Display-only compact version for poster overlays
export function StarBadge({ rating, className }: { rating: number; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-0.5 font-mono text-xs bg-black/70 text-amber px-1.5 py-0.5 rounded',
        className
      )}
    >
      <span>★</span>
      <span>{rating % 1 === 0 ? rating.toFixed(0) : rating.toFixed(1)}</span>
    </span>
  )
}

