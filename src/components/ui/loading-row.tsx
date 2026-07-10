import { cn } from 'src/lib/utils'

export function LoadingRow({ label, className }: { label: string; className?: string }) {
  return <div className={cn('text-center py-4 text-xs font-mono text-muted-foreground', className)}>{label}</div>
}

export function EmptyRow({ label, className }: { label: string; className?: string }) {
  return <div className={cn('text-center py-4 text-xs font-sans text-muted-foreground italic', className)}>{label}</div>
}
