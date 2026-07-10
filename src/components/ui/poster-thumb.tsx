import { Film, Tv } from 'lucide-react'
import { cn } from 'src/lib/utils'
import type { MediaType } from 'src/store/mockData'

/** Poster thumbnail with an icon fallback for search-result / picker rows. */
export function PosterThumb({
  src,
  alt,
  type,
  size = 'sm',
}: {
  src?: string
  alt: string
  type: MediaType
  size?: 'sm' | 'md'
}) {
  const Icon = type === 'movie' ? Film : Tv
  return (
    <div className={cn('shrink-0', size === 'md' ? 'w-16' : 'w-12')}>
      {src ? (
        <img src={src} alt={alt} className="w-full aspect-[2/3] object-cover rounded" />
      ) : (
        <div className="w-full aspect-[2/3] bg-secondary rounded flex items-center justify-center">
          <Icon className={cn('text-muted-foreground', size === 'md' ? 'w-5 h-5' : 'w-4 h-4')} />
        </div>
      )}
    </div>
  )
}
