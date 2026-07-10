import * as DialogPrimitive from "@radix-ui/react-dialog"
import { Maximize2, Minimize2 } from "lucide-react"
import { cn } from "src/lib/utils"
import { ModalCloseButton } from "./modal-close-button"
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
  /** Whether the modal is in expanded (full-page) mode. */
  expanded?: boolean
  /** Called when the user toggles the expand/collapse button. */
  onToggleExpand?: () => void
}

export function CinemaModal({
  open,
  onClose,
  children,
  className,
  maxWidth = "sm:max-w-2xl",
  title,
  description,
  expanded = false,
  onToggleExpand,
}: CinemaModalProps) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="cinema-overlay" />
        <DialogPrimitive.Content
          className={cn(
            "cinema-content",
            !expanded && maxWidth,
            expanded && "cinema-content--expanded",
            className,
          )}
        >
          <DialogPrimitive.Title className="sr-only">{title}</DialogPrimitive.Title>
          {description && (
            <DialogPrimitive.Description className="sr-only">
              {description}
            </DialogPrimitive.Description>
          )}
          {onToggleExpand && (
            <button
              onClick={onToggleExpand}
              className="absolute right-14 top-4 z-20 w-8 h-8 rounded-full bg-black/60 backdrop-blur-sm hidden sm:flex items-center justify-center text-white/70 hover:text-white transition-colors focus:outline-none focus:ring-2 focus:ring-amber/50"
              aria-label={expanded ? "Collapse to drawer" : "Expand to full page"}
            >
              {expanded ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}
            </button>
          )}
          <DialogPrimitive.Close asChild>
            <ModalCloseButton ariaLabel="Close" variant="scrim" />
          </DialogPrimitive.Close>
          {children}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
