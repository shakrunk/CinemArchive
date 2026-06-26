import { useEffect, useRef } from 'react'
import { X } from 'lucide-react'

interface PosterLightboxProps {
  src: string
  alt: string
  onClose: () => void
}

export function PosterLightbox({ src, alt, onClose }: PosterLightboxProps) {
  const largeSrc = src.replace('/w500/', '/w780/')
  const closeButtonRef = useRef<HTMLButtonElement>(null)

  // Keep the latest onClose without resetting the focus effect each render
  // (same pattern as CaughtUpCard in UpNext.tsx).
  const onCloseRef = useRef(onClose)
  useEffect(() => { onCloseRef.current = onClose })

  useEffect(() => {
    const returnEl = document.activeElement as HTMLElement
    closeButtonRef.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onCloseRef.current() }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      returnEl?.focus()
    }
  }, [])

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`${alt} poster`}
      className="fixed inset-0 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.92)', zIndex: 60 }}
      onClick={onClose}
    >
      <button
        ref={closeButtonRef}
        onClick={onClose}
        className="absolute top-4 right-4 flex items-center justify-center w-8 h-8 rounded-full transition-colors"
        style={{ background: 'rgba(0,0,0,0.6)', color: 'rgba(255,255,255,0.7)' }}
        aria-label="Close poster view"
        onMouseEnter={(e) => (e.currentTarget.style.color = 'rgba(255,255,255,1)')}
        onMouseLeave={(e) => (e.currentTarget.style.color = 'rgba(255,255,255,0.7)')}
      >
        <X className="w-4 h-4" />
      </button>
      <img
        src={largeSrc}
        alt={alt}
        className="rounded-lg shadow-2xl object-contain"
        style={{ maxHeight: '85vh', maxWidth: '90vw' }}
        onClick={(e) => e.stopPropagation()}
      />
    </div>
  )
}
