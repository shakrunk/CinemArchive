import { useEffect, useRef, type RefObject } from 'react'

/** One-shot reduced-motion check for call sites that don't need to react to changes. */
export function prefersReducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * Reduced-motion state kept in a ref rather than React state, so consumers
 * (e.g. an animation loop) can read the live value each frame without
 * triggering a re-render on every media-query change.
 */
export function usePrefersReducedMotionRef(): RefObject<boolean> {
  const ref = useRef(prefersReducedMotion())
  useEffect(() => {
    const mql = window.matchMedia('(prefers-reduced-motion: reduce)')
    ref.current = mql.matches
    const handleChange = () => { ref.current = mql.matches }
    mql.addEventListener('change', handleChange)
    return () => mql.removeEventListener('change', handleChange)
  }, [])
  return ref
}
