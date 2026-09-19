import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import { defaultRangeExtractor, type Range } from '@tanstack/react-virtual'

/** Document coordinates also account for changing filters and franchise headings
 * above the window. offsetTop is relative to the nearest positioned ancestor. */
export function useLibraryWindow() {
  const ref = useRef<HTMLDivElement>(null)
  const [layout, setLayout] = useState({ width: 0, viewportWidth: 0, offset: 0 })
  useLayoutEffect(() => {
    const element = ref.current
    if (!element) return
    const measure = () => {
      const rect = element.getBoundingClientRect()
      const next = { width: rect.width, viewportWidth: window.innerWidth, offset: rect.top + window.scrollY }
      setLayout(previous => previous.width === next.width && previous.viewportWidth === next.viewportWidth && Math.abs(previous.offset - next.offset) < 0.5 ? previous : next)
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    observer.observe(document.body)
    window.addEventListener('resize', measure)
    document.body.addEventListener('animationend', measure)
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', measure)
      document.body.removeEventListener('animationend', measure)
    }
  }, [])
  return { ref, ...layout }
}

/** Keep the focused item and its neighbours mounted so Tab/Shift+Tab can
 * traverse a window boundary without losing focus or skipping titles. */
export function useLibraryFocus() {
  const [focusedIndex, setFocusedIndex] = useState<number | null>(null)
  const rangeExtractor = useCallback((range: Range) => {
    const indices = new Set(defaultRangeExtractor(range))
    if (focusedIndex !== null) {
      for (let index = Math.max(0, focusedIndex - 3); index <= Math.min(range.count - 1, focusedIndex + 3); index++) indices.add(index)
    }
    return [...indices].sort((a, b) => a - b)
  }, [focusedIndex])
  return { rangeExtractor, setFocusedIndex }
}
