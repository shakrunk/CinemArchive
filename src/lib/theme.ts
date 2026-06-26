import { useAppStore, type Theme } from 'src/store/useAppStore'

// Keep in sync with the inline FOUC script in index.html and the
// <meta name="theme-color"> defaults.
const THEME_COLORS: Record<Theme, string> = {
  dark: '#0b0907',
  light: '#f4ede0',
}

/** Apply a theme to the document: sets [data-theme] and the address-bar color.
 *  Pure DOM — does not touch the store (callers decide when to persist). */
export function applyTheme(theme: Theme): void {
  const root = document.documentElement
  root.setAttribute('data-theme', theme)
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', THEME_COLORS[theme])
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
