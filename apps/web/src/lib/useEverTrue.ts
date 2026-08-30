import { useState } from 'react'

/**
 * Returns `true` once `value` has been `true` at least once, and stays `true`
 * forever after — never flips back even once `value` goes `false` again.
 *
 * Used to gate mounting a lazy-loaded modal/sheet until it's actually opened
 * for the first time (so its chunk isn't fetched on every page load just
 * because the component is unconditionally rendered), while keeping it
 * mounted after that first open so its own close transition can still play
 * out instead of being cut short by an unmount.
 *
 * Verified empirically (Playwright against a production preview build) that
 * this doesn't cost an entrance animation: Radix's `Presence` never renders
 * `Dialog.Content` into the DOM at all while closed (`present: forceMount ||
 * context.open`), so a cold first open already popped in directly at
 * `data-state="open"` with no prior `"closed"` frame to transition from,
 * identically before and after gating the mount this way — there was no
 * entrance transition on first open to lose either way.
 *
 * Adjusts state during render rather than in a `useEffect` — React's
 * documented pattern for "derive state from a prop," avoiding an extra
 * commit for what's otherwise a same-render flip.
 */
export function useEverTrue(value: boolean): boolean {
  const [everTrue, setEverTrue] = useState(value)
  if (value && !everTrue) setEverTrue(true)
  return everTrue
}
