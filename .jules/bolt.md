
## 2024-03-24 - Zustand Selectors in Custom Hooks
**Learning:** React custom hooks extracting unique values from Zustand state (`useAppStore((s) => s.titles)`) without memoization causes expensive array operations (flattening, Sets, sorting) to run on every render.
**Action:** Always wrap expensive derived calculations from global state in `useMemo` within custom hooks to avoid O(N log N) re-calculations on each render cycle.

## 2024-05-19 - Zustand Store Component Subscription Re-renders
**Learning:** Components fetching state from `useAppStore()` without selectors or `useShallow` subscribe to the entire store, causing unnecessary re-renders when unrelated properties change.
**Action:** Always wrap the Zustand selectors using `useShallow` from `zustand/react/shallow` when extracting multiple properties from global state.
## 2024-11-20 - Zustand Render Optimization
**Learning:** Destructuring directly from `useAppStore()` in Zustand subscribes components to the entire store, causing unnecessary re-renders on every state update, even for unrelated slices.
**Action:** Always wrap the object selector with `useShallow` from `zustand/react/shallow` to limit re-renders strictly to the properties being consumed.

## 2024-05-19 - Zustand Store Component Subscription Re-renders at the Root Level
**Learning:** Subscribing to large, frequently updated global state properties (like `titles`) directly in the root `App.tsx` component causes the entire application tree to re-render whenever that property updates, even if it's only used to generate options for a conditionally rendered child component (like `CommandPalette`).
**Action:** Isolate the state subscription into a dedicated wrapper component (`AppCommandPalette`) so that only the specific component re-renders when the state updates, preventing expensive, broad UI re-renders.
