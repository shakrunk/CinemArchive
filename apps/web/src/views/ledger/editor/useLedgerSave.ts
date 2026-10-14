import { useRef, useState } from 'react'

/** Keep the editor open on local persistence failure. Network delivery can
 * remain pending after this promise resolves: the device already has the edit. */
export function useLedgerSave() {
  const busy = useRef(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  async function save(work: () => Promise<unknown>) {
    if (busy.current) return false
    busy.current = true
    setPending(true)
    setError(null)
    try {
      await work()
      return true
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Could not save this change. Try again.')
      return false
    } finally {
      busy.current = false
      setPending(false)
    }
  }
  return { save, pending, error }
}
