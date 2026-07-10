import { useModalFocusAndEscape } from 'src/lib/useModalFocusAndEscape'
import { ModalBackdrop } from './modal-backdrop'
import { ModalCloseButton } from './modal-close-button'

interface PosterLightboxProps {
  src: string
  alt: string
  onClose: () => void
}

export function PosterLightbox({ src, alt, onClose }: PosterLightboxProps) {
  const largeSrc = src.replace('/w500/', '/w780/')
  const closeButtonRef = useModalFocusAndEscape<HTMLButtonElement>(onClose)

  return (
    <ModalBackdrop onClose={onClose} ariaLabel={`${alt} poster`} backdropOpacity={0.92}>
      <ModalCloseButton ref={closeButtonRef} onClick={onClose} ariaLabel="Close poster view" variant="scrim" />
      <img
        src={largeSrc}
        alt={alt}
        className="rounded-lg shadow-2xl object-contain"
        style={{ maxHeight: '85vh', maxWidth: '90vw' }}
        onClick={(e) => e.stopPropagation()}
      />
    </ModalBackdrop>
  )
}
