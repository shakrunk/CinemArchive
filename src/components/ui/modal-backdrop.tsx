// Shared by the plain-div "dialog" components that don't go through Radix
// (dialog.tsx/cinema-modal.tsx) — e.g. PersonDetailPanel, ShareScopeEditor,
// SendRecommendationPanel, poster-lightbox. Just the backdrop shell; each
// caller still owns its own inner panel markup and content, and pairs this
// with useModalFocusAndEscape for the close button's ref.

import type { ReactNode } from 'react'

/** Fixed, centered backdrop — click anywhere in it to close. Renders no
 *  inner panel wrapper of its own, since panel markup (rounded corners, max
 *  width/height, stopPropagation) varies enough per caller to stay local. */
export function ModalBackdrop({
  onClose,
  ariaLabel,
  backdropOpacity = 0.82,
  children,
}: {
  onClose: () => void
  ariaLabel: string
  backdropOpacity?: number
  children: ReactNode
}) {
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={ariaLabel}
      className="fixed inset-0 flex items-center justify-center p-4"
      style={{ background: `rgba(0,0,0,${backdropOpacity})`, zIndex: 215 }}
      onClick={onClose}
    >
      {children}
    </div>
  )
}
