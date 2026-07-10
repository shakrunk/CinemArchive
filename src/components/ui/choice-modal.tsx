// Shared by the two-option "choice" modals — SpiderNoirModeModal (rewatch
// color-mode picker) and MatrixPillModal (Easter-egg pill choice). Both are
// createPortal-based rather than Radix Dialog.Root (see cinema-modal.tsx),
// since neither fits Radix's shape here, and both converged on identical
// backdrop/positioning/hover-card mechanics independently.

import { useEffect, type CSSProperties, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

export function ChoiceModal({
  open,
  onDismiss,
  ariaLabel,
  backdropColor,
  children,
}: {
  open: boolean
  onDismiss: () => void
  ariaLabel: string
  backdropColor: string
  children: ReactNode
}) {
  useEffect(() => {
    if (!open) return
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onDismiss()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, onDismiss])

  if (!open) return null

  return createPortal(
    <>
      <div
        aria-hidden="true"
        onClick={onDismiss}
        style={{
          position: 'fixed',
          inset: 0,
          zIndex: 10000,
          background: backdropColor,
          backdropFilter: 'blur(8px)',
          animation: 'spider-noir-fade-in 300ms ease',
        }}
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        style={{
          position: 'fixed',
          inset: 0,
          zIndex: 10001,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          padding: '24px',
          pointerEvents: 'none',
        }}
      >
        <div style={{ display: 'contents', pointerEvents: 'auto' }}>{children}</div>
      </div>
    </>,
    document.body,
  )
}

export function ChoiceCard({
  onClick,
  ariaLabel,
  borderColor,
  hoverBorderColor,
  hoverShadowColor,
  background,
  filter,
  gap = '12px',
  padding = '24px 16px',
  children,
}: {
  onClick: () => void
  ariaLabel: string
  borderColor: string
  hoverBorderColor: string
  /** Box-shadow color to apply on hover; omit for no hover shadow. */
  hoverShadowColor?: string
  background: string
  filter?: string
  gap?: string
  padding?: string
  children: ReactNode
}) {
  return (
    <button
      aria-label={ariaLabel}
      onClick={onClick}
      style={{
        flex: 1,
        border: `1px solid ${borderColor}`,
        borderRadius: '12px',
        padding,
        cursor: 'pointer',
        transition: hoverShadowColor
          ? 'transform 0.18s, border-color 0.18s, box-shadow 0.18s'
          : 'transform 0.18s, border-color 0.18s',
        background,
        filter,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap,
      } as CSSProperties}
      onMouseEnter={(e) => {
        e.currentTarget.style.transform = 'translateY(-3px)'
        e.currentTarget.style.borderColor = hoverBorderColor
        if (hoverShadowColor) e.currentTarget.style.boxShadow = `0 8px 32px -8px ${hoverShadowColor}`
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.transform = ''
        e.currentTarget.style.borderColor = borderColor
        if (hoverShadowColor) e.currentTarget.style.boxShadow = ''
      }}
    >
      {children}
    </button>
  )
}
