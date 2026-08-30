import { useEffect } from 'react'

/**
 * Toggles `<body class="is-tab-hidden">` in step with page visibility, so the
 * atmosphere layers' infinite CSS animations (`.grain`/`.projector-beam`/
 * `.dust` in index.css) can pause while the tab is backgrounded — they run
 * for the whole session otherwise, and `.grain`'s `mix-blend-mode` keeps the
 * browser recompositing every frame regardless of whether anyone can see it.
 */
export function useTabVisibility(): void {
  useEffect(() => {
    const sync = () => document.body.classList.toggle('is-tab-hidden', document.hidden)
    sync()
    document.addEventListener('visibilitychange', sync)
    return () => document.removeEventListener('visibilitychange', sync)
  }, [])
}
