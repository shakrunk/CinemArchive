import { forwardRef, type ComponentPropsWithoutRef } from 'react'
import { X } from 'lucide-react'
import { cn } from 'src/lib/utils'

interface ModalCloseButtonProps extends ComponentPropsWithoutRef<'button'> {
  ariaLabel: string
  /** 'scrim' — floating over media (poster art, video) that needs its own
   *  dark backdrop for contrast. 'plain' — already sitting on a solid panel
   *  background, so it can use the bare icon-btn treatment. */
  variant?: 'scrim' | 'plain'
}

/** Shared close ("×") button for every modal/panel/lightbox in the app —
 *  consistent size, background, hover, and focus-ring regardless of variant.
 *  Radix dialogs should render it via `<DialogPrimitive.Close asChild>`. */
export const ModalCloseButton = forwardRef<HTMLButtonElement, ModalCloseButtonProps>(
  function ModalCloseButton({ ariaLabel, variant = 'plain', className, ...props }, ref) {
    return (
      <button
        ref={ref}
        type="button"
        aria-label={ariaLabel}
        title={ariaLabel}
        className={cn(
          'icon-btn absolute right-4 top-4 z-20 flex items-center justify-center w-8 h-8 rounded-full',
          variant === 'scrim' && 'bg-black/60 backdrop-blur-sm text-white/70 hover:bg-black/60 hover:text-white',
          className,
        )}
        {...props}
      >
        <X className="w-4 h-4" />
      </button>
    )
  },
)
