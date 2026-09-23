/** Browser persistence is a cache, not the success boundary for an in-memory
 * update or its independent remote save. Storage may throw after state changes
 * (quota exhaustion, private browsing, disabled site storage).
 */
export function createBrowserCacheStorage(
  getStorage: () => Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>,
  onUnavailable: (error: unknown) => void,
) {
  let unavailable = false
  function report(error: unknown) {
    if (unavailable) return
    unavailable = true // Set before notifying: notifications also persist state.
    onUnavailable(error)
  }
  return {
    getItem(name: string): string | null {
      try {
        return getStorage().getItem(name)
      } catch (error) {
        report(error)
        return null
      }
    },
    setItem(name: string, value: string): void {
      try {
        getStorage().setItem(name, value)
        unavailable = false
      } catch (error) {
        report(error)
      }
    },
    removeItem(name: string): void {
      try {
        getStorage().removeItem(name)
      } catch (error) {
        report(error)
      }
    },
  }
}
