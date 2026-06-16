import * as DialogPrimitive from "@radix-ui/react-dialog"
import { X } from "lucide-react"
import { cn } from "src/lib/utils"
import { type ReactNode } from "react"

interface CinemaModalProps {
  open: boolean
  onClose: () => void
  children: ReactNode
  className?: string
  maxWidth?: string
  /** Accessible label announced to screen readers (Radix requires a title). */
  title: string
  /** Optional accessible description for assistive tech. */
  description?: string
}

export function CinemaModal({
  open,
  onClose,
  children,
  className,
  maxWidth = "sm:max-w-2xl",
  title,
  description,
}: CinemaModalProps) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="cinema-overlay" />
        <DialogPrimitive.Content className={cn("cinema-content", maxWidth, className)}>
          <DialogPrimitive.Title className="sr-only">{title}</DialogPrimitive.Title>
          {description && (
            <DialogPrimitive.Description className="sr-only">
              {description}
            </DialogPrimitive.Description>
          )}
          <DialogPrimitive.Close
            className="absolute right-4 top-4 z-20 w-8 h-8 rounded-full bg-black/60 backdrop-blur-sm flex items-center justify-center text-white/70 hover:text-white transition-colors focus:outline-none focus:ring-2 focus:ring-amber/50"
            aria-label="Close"
          >
            <X className="w-4 h-4" />
          </DialogPrimitive.Close>
          {children}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
