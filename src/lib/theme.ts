import { useAppStore, type Theme } from 'src/store/useAppStore'

// Keep in sync with the inline FOUC script in index.html and the
// <meta name="theme-color"> defaults.
const THEME_COLORS: Record<Theme, string> = {
  dark: '#0b0907',
  light: '#f4ede0',
  noir: '#0d0d0f',
  matrix: '#020403',
}

/** Apply a theme to the document: sets [data-theme] and the address-bar color.
 *  Pure DOM — does not touch the store (callers decide when to persist). */
export function applyTheme(theme: Theme): void {
  const root = document.documentElement
  root.setAttribute('data-theme', theme)
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', THEME_COLORS[theme])
}

/** Build a 16-point starburst polygon centred at (cx, cy) with the given
 *  outer radius. Odd-indexed points sit at innerRatio * r, creating the
 *  "silk thread between spokes" concavity of a web. All points are well
 *  outside the viewport at full radius, so the shape covers the entire screen. */
function webPolygon(cx: number, cy: number, r: number, innerRatio = 0.72): string {
  const N = 16
  const points: string[] = []
  for (let i = 0; i < N; i++) {
    const angle = (i * Math.PI * 2) / N - Math.PI / 2
    const rad = i % 2 === 0 ? r : r * innerRatio
    points.push(`${cx + rad * Math.cos(angle)}px ${cy + rad * Math.sin(angle)}px`)
  }
  return `polygon(${points.join(', ')})`
}

/** Apply the Spider-Noir body-class change wrapped in a View Transition so the
 *  mode change irises in/out as an expanding spider-web starburst from screen
 *  centre. Falls back to an instant change when the API is unavailable or
 *  motion is reduced (CSS filter transition on #root provides a graceful
 *  degradation in those cases).
 *
 *  Important: we suppress #root's CSS `transition: filter` before calling
 *  startViewTransition, because the API captures the new-state screenshot
 *  immediately after commit() — at t=0 of a CSS transition the rendered value
 *  is still the OLD value, which would make the starburst reveal an unfiltered
 *  page and then pop to noir once the VT ends. */
export function transitionSpiderNoir(commit: () => void): void {
  const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  type VT = { ready: Promise<void>; finished: Promise<void> }
  const startViewTransition = (
    document as Document & { startViewTransition?: (cb: () => void) => VT }
  ).startViewTransition

  if (!startViewTransition || prefersReduced) {
    commit()
    return
  }

  const cx = window.innerWidth / 2
  const cy = window.innerHeight / 2
  const endRadius = Math.hypot(
    Math.max(cx, window.innerWidth - cx),
    Math.max(cy, window.innerHeight - cy),
  )

  const collapsed = webPolygon(cx, cy, 0)
  const expanded  = webPolygon(cx, cy, endRadius)

  // Suppress the CSS filter transition on #root so the VT new-state snapshot
  // captures the final filter value, not the start of a CSS transition.
  const root = document.getElementById('root')
  if (root) root.style.transition = 'none'

  const vt = startViewTransition.call(document, commit)

  vt.ready
    .then(() => {
      document.documentElement.animate(
        { clipPath: [collapsed, expanded] },
        {
          duration: 700,
          easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
          pseudoElement: '::view-transition-new(root)',
        },
      )
    })
    .catch(() => { /* transition skipped or interrupted */ })

  // Restore the CSS transition after the VT finishes so future non-VT changes
  // (e.g. reduced-motion fallback) still get a smooth filter ease.
  vt.finished.then(() => {
    if (root) root.style.transition = ''
  }).catch(() => {
    if (root) root.style.transition = ''
  })
}

/** Flip the theme. When the View Transitions API is available (and motion is
 *  allowed), the new theme irises in from the click point as an expanding
 *  circle — the projector "aperture" opening. Otherwise it applies instantly.
 *  Spider-noir's #root filter is captured in the transition snapshot, so the
 *  easter egg composes with the reveal automatically. */
export function toggleTheme(origin?: { clientX: number; clientY: number }): void {
  const next: Theme = useAppStore.getState().theme === 'dark' ? 'light' : 'dark'

  const commit = () => {
    applyTheme(next)
    useAppStore.getState().setTheme(next)
  }

  const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const startViewTransition = (
    document as Document & {
      startViewTransition?: (cb: () => void) => { ready: Promise<void> }
    }
  ).startViewTransition

  if (!startViewTransition || prefersReduced || !origin) {
    commit()
    return
  }

  const x = origin.clientX
  const y = origin.clientY
  const endRadius = Math.hypot(
    Math.max(x, window.innerWidth - x),
    Math.max(y, window.innerHeight - y)
  )

  const transition = startViewTransition.call(document, commit)
  transition.ready
    .then(() => {
      document.documentElement.animate(
        {
          clipPath: [
            `circle(0px at ${x}px ${y}px)`,
            `circle(${endRadius}px at ${x}px ${y}px)`,
          ],
        },
        {
          duration: 520,
          easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
          pseudoElement: '::view-transition-new(root)',
        }
      )
    })
    .catch(() => {
      /* transition was skipped/interrupted — theme already committed */
    })
}
