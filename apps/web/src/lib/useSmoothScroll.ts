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
 * (`react-remove-scroll` marks `<html data-scroll-locked>`), so it never fights
 * a modal's own scroll lock — otherwise Lenis, which drives scroll itself rather
 * than relying on native overflow, would keep scrolling the page underneath.
 */
export function useSmoothScroll(): void {
  useEffect(() => {
    if (prefersReducedMotion()) return

    const lenis = new Lenis({
      duration: 1.1,
      easing: (t: number) => 1 - Math.pow(1 - t, 3),
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
    const html = document.documentElement
    const syncLockState = () => {
      if (html.hasAttribute('data-scroll-locked')) lenis.stop()
      else lenis.start()
    }
    syncLockState()
    const observer = new MutationObserver(syncLockState)
    observer.observe(html, { attributes: true, attributeFilter: ['data-scroll-locked'] })

    return () => {
      observer.disconnect()
      cancelAnimationFrame(frameId)
      lenis.destroy()
    }
  }, [])
}
