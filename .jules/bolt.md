
## 2024-03-24 - Zustand Selectors in Custom Hooks
**Learning:** React custom hooks extracting unique values from Zustand state (`useAppStore((s) => s.titles)`) without memoization causes expensive array operations (flattening, Sets, sorting) to run on every render.
**Action:** Always wrap expensive derived calculations from global state in `useMemo` within custom hooks to avoid O(N log N) re-calculations on each render cycle.

## 2024-05-19 - Zustand Store Component Subscription Re-renders
**Learning:** Components fetching state from `useAppStore()` without selectors or `useShallow` subscribe to the entire store, causing unnecessary re-renders when unrelated properties change.
**Action:** Always wrap the Zustand selectors using `useShallow` from `zustand/react/shallow` when extracting multiple properties from global state.
