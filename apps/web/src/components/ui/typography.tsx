import { cn } from 'src/lib/utils'
import { type HTMLAttributes } from 'react'

interface TypographyProps extends HTMLAttributes<HTMLElement> {
  className?: string
}

export function DisplayTitle({ className, children, ...props }: TypographyProps) {
  return (
    <h1
      className={cn('font-serif text-4xl md:text-6xl font-light tracking-tight text-gold', className)}
      {...props}
    >
      {children}
    </h1>
  )
}

export function SectionHeading({ className, children, ...props }: TypographyProps) {
  return (
    <h2
      className={cn('font-serif text-2xl md:text-3xl font-light text-foreground', className)}
      {...props}
    >
      {children}
    </h2>
  )
}

export function CardTitle({ className, children, ...props }: TypographyProps) {
  return (
    <h3 className={cn('font-sans text-base font-medium text-foreground', className)} {...props}>
      {children}
    </h3>
  )
}

export function BodyText({ className, children, ...props }: TypographyProps) {
  return (
    <p className={cn('font-sans text-sm text-muted-foreground leading-relaxed', className)} {...props}>
      {children}
    </p>
  )
}

export function StatNumber({ className, children, ...props }: TypographyProps) {
  return (
    <span className={cn('font-mono text-2xl md:text-3xl font-medium text-amber', className)} {...props}>
      {children}
    </span>
  )
}

export function StatLabel({ className, children, ...props }: TypographyProps) {
  return (
    <span className={cn('font-mono text-xs uppercase tracking-widest text-muted-foreground', className)} {...props}>
      {children}
    </span>
  )
}

/* ── Eyebrow / kicker labels ──────────────────────────────────────────────────
   The small uppercase labels that head a field, stat or subsection (Cast,
   Genres, Status, …). These were hand-rolled at dozens of call sites, so size,
   tracking, font and colour each drifted independently (KP-058). Pick a size
   and a tone here instead of restating the classes; tracking is bound to the
   size on purpose, since the two only ever move together. */

export type EyebrowSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl'
export type EyebrowTone = 'faint' | 'dim' | 'muted' | 'accent' | 'inherit'

const EYEBROW_SIZE: Record<EyebrowSize, string> = {
  xs: 'text-[8px] tracking-[0.15em]',
  sm: 'text-[9px] tracking-[0.15em]',
  md: 'text-[10px] tracking-[0.16em]',
  lg: 'text-[11px] tracking-[0.18em]',
  xl: 'text-xs tracking-widest',
}

const EYEBROW_TONE: Record<EyebrowTone, string> = {
  faint: 'text-paper-faint',
  dim: 'text-paper-dim',
  muted: 'text-muted-foreground',
  accent: 'text-amber-deep',
  // For labels inside an already-coloured container (a chip, an amber banner)
  // that must not fight the surrounding colour.
  inherit: '',
}

/** Eyebrows head all sorts of things — a form field, a definition term, a
 *  subsection — so the element is the caller's choice. */
type EyebrowElement = 'span' | 'p' | 'div' | 'label' | 'dt' | 'h2' | 'h3' | 'h4'

interface EyebrowProps extends HTMLAttributes<HTMLElement> {
  as?: EyebrowElement
  size?: EyebrowSize
  tone?: EyebrowTone
  /** Eyebrows are mono by default; the sans face matches SubsectionLabel. */
  font?: 'mono' | 'sans'
  /** Only meaningful when `as` is 'label'. */
  htmlFor?: string
}

export function Eyebrow({
  as: Tag = 'span',
  size = 'sm',
  tone = 'faint',
  font = 'mono',
  className,
  children,
  ...props
}: EyebrowProps) {
  return (
    <Tag
      className={cn(
        font === 'mono' ? 'font-mono' : 'font-sans',
        'uppercase',
        EYEBROW_SIZE[size],
        EYEBROW_TONE[tone],
        className
      )}
      {...props}
    >
      {children}
    </Tag>
  )
}

export function SubsectionLabel({ className, children, ...props }: TypographyProps) {
  return (
    <h4
      className={cn('font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim mb-4', className)}
      {...props}
    >
      {children}
    </h4>
  )
}

export function MetaBadge({ className, children, ...props }: TypographyProps) {
  return (
    <span
      className={cn('font-mono text-xs text-muted-foreground border border-border rounded px-1.5 py-0.5', className)}
      {...props}
    >
      {children}
    </span>
  )
}
