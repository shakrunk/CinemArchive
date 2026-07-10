import { useEffect, useLayoutEffect, useRef, type RefObject } from 'react'

/**
 * Closes a floating panel (dropdown, popover) on a pointer-down outside `ref`,
 * and on Escape when `escape` is set. No-ops while `active` is false so the
 * listener isn't attached for the lifetime of an always-mounted panel.
 */
export function useClickOutside(
  ref: RefObject<HTMLElement | null>,
  onOutside: () => void,
  active: boolean,
  options?: { escape?: boolean }
): void {
  const callbackRef = useRef(onOutside)
  useLayoutEffect(() => {
    callbackRef.current = onOutside
  })

  const escape = options?.escape ?? false

  useEffect(() => {
    if (!active) return
    function onPointerDown(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) callbackRef.current()
    }
    document.addEventListener('mousedown', onPointerDown)
    if (!escape) return () => document.removeEventListener('mousedown', onPointerDown)

    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') callbackRef.current()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [active, ref, escape])
}
