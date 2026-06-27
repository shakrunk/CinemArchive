import { useEffect, useLayoutEffect, useRef } from 'react'

type ShortcutMap = Record<string, () => void>

function isEditableTarget(target: EventTarget | null): boolean {
  if (!target || !(target instanceof HTMLElement)) return false
  const tag = target.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  if (target.isContentEditable) return true
  return false
}

/**
 * Registers single-key global shortcuts. Listeners are suppressed when focus
 * is inside a text field or when `active` is false (i.e. a modal is open).
 * Modified keys (⌘, Ctrl, Alt) are always ignored — they have their own handlers.
 */
export function useKeyboardShortcuts(shortcuts: ShortcutMap, active: boolean): void {
  const ref = useRef(shortcuts)

  // Sync after each render, before paint, so the stable event listener always
  // sees the latest handlers without re-registering. useLayoutEffect is an
  // effect scope — allowed by react-hooks/refs (only render-phase writes are banned).
  useLayoutEffect(() => {
    ref.current = shortcuts
  })

  useEffect(() => {
    if (!active) return
    function onKeyDown(e: KeyboardEvent) {
      if (e.metaKey || e.ctrlKey || e.altKey) return
      if (isEditableTarget(e.target)) return
      const handler = ref.current[e.key]
      if (handler) {
        e.preventDefault()
        handler()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [active])
}
