import { useEffect } from 'react'
import Lenis from 'lenis'
import 'lenis/dist/lenis.css'
import { prefersReducedMotion } from './motion'

/**
 * Buttery, inertia-based page scroll (à la stopkillinggames.com) — wheel input eases
 * into motion and decelerates smoothly into the top/bottom bounds instead of the
 * browser's default instant-stop scroll. Touch scrolling is left native (already
 * has its own momentum on mobile).
 *
 * Suspended while a Radix `Dialog`/`Sheet` has the page scroll-locked
 * (`react-remove-scroll-bar` marks `<body data-scroll-locked>`, not `<html>`),
 * so it never fights a modal's own scroll lock — otherwise Lenis, which drives
 * scroll itself rather than relying on native overflow, would keep scrolling
 * the page underneath.
 */
export function useSmoothScroll(): void {
  useEffect(() => {
    if (prefersReducedMotion()) return

    const lenis = new Lenis({
      lerp: 0.12,
      syncTouch: false,
    })

    let frameId: number
    const raf = (time: number) => {
      lenis.raf(time)
      frameId = requestAnimationFrame(raf)
    }
    frameId = requestAnimationFrame(raf)

    // Pause/resume around Radix's scroll lock rather than only checking once —
    // dialogs open and close throughout the session.
    const body = document.body
    const syncLockState = () => {
      if (body.hasAttribute('data-scroll-locked')) lenis.stop()
      else lenis.start()
    }
    syncLockState()
    const observer = new MutationObserver(syncLockState)
    observer.observe(body, { attributes: true, attributeFilter: ['data-scroll-locked'] })

    return () => {
      observer.disconnect()
      cancelAnimationFrame(frameId)
      lenis.destroy()
    }
  }, [])
}

/**
 * Same buttery inertia scroll as {@link useSmoothScroll}, but scoped to a single
 * scrollable element (e.g. a modal/drawer body) instead of the page. The page-level
 * instance already suspends itself for the whole `<html>`/`<body>` while a Radix
 * `Dialog`/`Sheet` holds the scroll lock, so there's no double-smoothing to guard
 * against here — this element just needs its own instance since it isn't `window`.
 *
 * Takes the DOM node itself, not a `RefObject` — pass state from a callback ref
 * (`useState<HTMLElement | null>`), not `someRef.current`. A plain object ref's
 * identity never changes, so an effect keyed on `[ref, enabled]` can fire while
 * `ref.current` is still null (e.g. the node hasn't mounted into a Radix Dialog's
 * portal yet) and then never re-fire once it does, silently never creating an
 * instance. Keying on the node itself re-runs the effect exactly when it mounts.
 *
 * `enabled` additionally gates creation (e.g. only while the drawer is open).
 *
 * Because `wrapper` and `content` are the same node here, Lenis's own `ResizeObserver`
 * never fires when the content grows: the scroll container's border box is fixed
 * (`flex-1`), only its `scrollHeight` changes. Lenis would then keep the scroll limit it
 * measured on mount, so expanding a section (e.g. "show full cast") left the extra height
 * unreachable. We therefore observe the container's children ourselves and re-measure.
 */
export function useScopedSmoothScroll(el: HTMLElement | null, enabled: boolean): void {
  useEffect(() => {
    if (!enabled || !el || prefersReducedMotion()) return

    const lenis = new Lenis({
      wrapper: el,
      content: el,
      lerp: 0.12,
      syncTouch: false,
    })

    let frameId: number
    const raf = (time: number) => {
      lenis.raf(time)
      frameId = requestAnimationFrame(raf)
    }
    frameId = requestAnimationFrame(raf)

    // Re-measure whenever the content's height changes. `ResizeObserver` only reports the
    // boxes of the nodes it observes, so watch every element child; a `MutationObserver`
    // keeps that set in sync as children mount/unmount.
    const resizeObserver = new ResizeObserver(() => lenis.resize())
    const observeChildren = () => {
      resizeObserver.disconnect()
      for (const child of Array.from(el.children)) resizeObserver.observe(child)
      lenis.resize()
    }
    observeChildren()
    const mutationObserver = new MutationObserver(observeChildren)
    mutationObserver.observe(el, { childList: true })

    return () => {
      mutationObserver.disconnect()
      resizeObserver.disconnect()
      cancelAnimationFrame(frameId)
      lenis.destroy()
    }
  }, [el, enabled])
}
