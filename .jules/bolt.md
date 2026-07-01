
## 2024-03-24 - Zustand Selectors in Custom Hooks
**Learning:** React custom hooks extracting unique values from Zustand state (`useAppStore((s) => s.titles)`) without memoization causes expensive array operations (flattening, Sets, sorting) to run on every render.
**Action:** Always wrap expensive derived calculations from global state in `useMemo` within custom hooks to avoid O(N log N) re-calculations on each render cycle.

## 2024-05-19 - Zustand Store Component Subscription Re-renders
**Learning:** Components fetching state from `useAppStore()` without selectors or `useShallow` subscribe to the entire store, causing unnecessary re-renders when unrelated properties change.
**Action:** Always wrap the Zustand selectors using `useShallow` from `zustand/react/shallow` when extracting multiple properties from global state.
## 2024-11-20 - Zustand Render Optimization
**Learning:** Destructuring directly from `useAppStore()` in Zustand subscribes components to the entire store, causing unnecessary re-renders on every state update, even for unrelated slices.
**Action:** Always wrap the object selector with `useShallow` from `zustand/react/shallow` to limit re-renders strictly to the properties being consumed.

## 2024-11-20 - Global State Subscriptions at Root Level
**Learning:** Subscribing to frequently updated arrays (like `titles`) directly in the root `<App />` component causes the entire component tree to re-render whenever any item in that array changes.
**Action:** Isolate non-critical logic that depends on large lists into dedicated child components (e.g. `AppCommandPalette.tsx`) so that updates to the list only trigger re-renders in the specific component that needs it.
## 2026-07-01 - Supabase Bulk Upserts
**Learning:** Resolving N+1 query bottlenecks in Supabase by passing an array of objects to `.upsert()` reduces database calls significantly.
**Action:** Replace iterative upsert loops with bulk `.upsert()` calls wherever an array of data is processed (e.g., seasons in `src/lib/db.ts`).
