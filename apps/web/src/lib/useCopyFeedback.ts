import { useRef, useState } from 'react'

/** Copies text to the clipboard and flashes a 2s "copied" state keyed by an id. */
export function useCopyFeedback() {
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  function copy(text: string, id: string) {
    navigator.clipboard.writeText(text)
    setCopiedId(id)
    clearTimeout(timeoutRef.current)
    timeoutRef.current = setTimeout(() => setCopiedId(null), 2000)
  }

  return { copiedId, copy }
}
