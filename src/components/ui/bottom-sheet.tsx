import { Sheet, SheetContent, SheetTitle, SheetDescription } from 'src/components/ui/sheet'
import { cn } from 'src/lib/utils'
import { type ReactNode } from 'react'

interface BottomSheetProps {
  open: boolean
  onClose: () => void
  title?: string
  description?: string
  children: ReactNode
  className?: string
  side?: 'bottom' | 'right'
}

export function BottomSheet({
  open,
  onClose,
  title,
  description,
  children,
  className,
  side = 'bottom',
}: BottomSheetProps) {
  return (
    <Sheet open={open} onOpenChange={(o) => !o && onClose()}>
      <SheetContent
        side={side}
        className={cn(
          'bg-card border-border text-foreground p-0 flex flex-col',
          side === 'bottom' && 'max-h-[92vh] rounded-t-2xl',
          side === 'right' && 'w-full sm:max-w-lg',
          className
        )}
      >
        {/* SheetTitle is always sr-only so callers own the visual header.
            It still satisfies Radix Dialog's accessible-name requirement. */}
        {title && <SheetTitle className="sr-only">{title}</SheetTitle>}
        {description && <SheetDescription className="sr-only">{description}</SheetDescription>}
        {/* Scrolling lives here, not on SheetContent, so the close button
            (absolutely positioned inside SheetContent) stays pinned instead
            of scrolling out of reach on long panels. */}
        <div className="overflow-y-auto scrollbar-thin p-6 flex-1 min-h-0">{children}</div>
      </SheetContent>
    </Sheet>
  )
}
