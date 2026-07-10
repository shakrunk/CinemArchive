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
