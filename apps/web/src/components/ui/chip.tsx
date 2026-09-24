import { cn } from 'src/lib/utils'

/** Small pill toggle used across filter/mode selectors — see `.chip` in index.css. */
export function Chip({
  active,
  onClick,
  children,
  className,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
  className?: string
}) {
  return (
    <button
      onClick={onClick}
      className={cn('chip', active && 'is-active', 'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60', className)}
    >
      {children}
    </button>
  )
}
