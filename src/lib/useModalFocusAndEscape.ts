import { useEffect, useRef } from 'react'

/** Focuses `ref` on mount, restores the previously-focused element on
 *  unmount, and calls `onClose` on Escape. Returns the ref to attach to the
 *  element that should receive focus (typically a modal's close button). */
export function useModalFocusAndEscape<T extends HTMLElement>(onClose: () => void) {
  const focusRef = useRef<T>(null)

  const onCloseRef = useRef(onClose)
  useEffect(() => { onCloseRef.current = onClose })

  useEffect(() => {
    const returnEl = document.activeElement as HTMLElement
    focusRef.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onCloseRef.current() }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      returnEl?.focus()
    }
  }, [])

  return focusRef
}
