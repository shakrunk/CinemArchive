import { X } from 'lucide-react'

interface PosterLightboxProps {
  src: string
  alt: string
  onClose: () => void
}

export function PosterLightbox({ src, alt, onClose }: PosterLightboxProps) {
  const largeSrc = src.replace('/w500/', '/w780/')

  return (
    <div
      className="fixed inset-0 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.92)', zIndex: 60 }}
      onClick={onClose}
    >
      <button
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
