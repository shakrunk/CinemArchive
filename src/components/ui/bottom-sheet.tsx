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
          'bg-card border-border text-foreground',
          side === 'bottom' && 'max-h-[92vh] rounded-t-2xl overflow-y-auto scrollbar-thin',
          side === 'right' && 'w-full sm:max-w-lg overflow-y-auto scrollbar-thin',
          className
        )}
      >
        {/* SheetTitle is always sr-only so callers own the visual header.
            It still satisfies Radix Dialog's accessible-name requirement. */}
        {title && <SheetTitle className="sr-only">{title}</SheetTitle>}
        {description && <SheetDescription className="sr-only">{description}</SheetDescription>}
        {children}
      </SheetContent>
    </Sheet>
  )
}
