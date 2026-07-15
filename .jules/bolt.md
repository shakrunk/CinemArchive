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

## 2024-11-20 - Global State Subscriptions at Root Level
**Learning:** Subscribing to frequently updated arrays (like `titles`) directly in the root `<App />` component causes the entire component tree to re-render whenever any item in that array changes.
**Action:** Isolate non-critical logic that depends on large lists into dedicated child components (e.g. `AppCommandPalette.tsx`) so that updates to the list only trigger re-renders in the specific component that needs it.
## 2026-07-01 - Supabase Bulk Upserts
**Learning:** Resolving N+1 query bottlenecks in Supabase by passing an array of objects to `.upsert()` reduces database calls significantly.
**Action:** Replace iterative upsert loops with bulk `.upsert()` calls wherever an array of data is processed (e.g., seasons in `src/lib/db.ts`).

## 2024-06-25 - Resolve N+1 Query in Viewing Upserts
**Learning:** Resolving N+1 database queries with Supabase is extremely effective using bulk `.upsert()` with an array of objects rather than running an upsert in a loop. A mock benchmark verified ~49x improvement on 50 records.
**Action:** Always favor bulk database queries with Supabase over loops.
## 2025-02-17 - Resolve N+1 Query in Insertion Loops
**Learning:** Adding a new TV show triggers an N+1 query problem by running separate `supabase.from('episode_crew').insert()` calls for *every single episode* in a loop.
**Action:** Replace the nested loops with `flatMap` to generate arrays of all items across the entire structure, and then perform bulk inserts.

## 2025-03-02 - Resolve N+1 Query in Metadata Refreshes
**Learning:** Calling iterative database upserts (`upsertSeasonCastInDb` and `upsertEpisodeCrewInDb`) in `for...of` loops when refreshing TV show metadata or backfilling details creates severe N+1 query bottlenecks.
**Action:** Replace `for...of` iteration over Supabase inserts/upserts with bulk functions that map all incoming data to a single flat array and call `.upsert()` exactly once.
## 2026-07-10 - Zustand Multiple Subscriptions
**Learning:** Components subscribing to multiple store properties individually via separate `useAppStore` hooks create multiple independent store subscriptions. This leads to higher memory usage, subscription execution overhead, and potential re-render cascades.
**Action:** Always batch state extractions into a single object selector using `useShallow` from `zustand/react/shallow` to reduce subscriptions to exactly one per component.
## 2026-07-26 - Zustand Multiple Subscriptions
**Learning:** Components subscribing to multiple store properties individually via separate `useAppStore` hooks create multiple independent store subscriptions. This leads to higher memory usage, subscription execution overhead, and potential re-render cascades. However, when injecting `useShallow` to fix this, it is easy to forget the import statement.
**Action:** Always batch state extractions into a single object selector using `useShallow` from `zustand/react/shallow` to reduce subscriptions to exactly one per component, and ALWAYS ensure the `useShallow` import is added.
## 2026-07-13 - Debouncing Search and Re-render pitfalls
**Learning:** In Zustand, multiple atomic selectors (e.g. `useAppStore(s => s.a)`) are already highly optimized and do not cause extra re-renders. Converting them to a batched `useShallow` is an unnecessary micro-optimization that introduces object allocation overhead. However, synchronously updating a derived store property via string filtering on every keystroke in a large list is a genuine bottleneck.
**Action:** Focus on debouncing frequent state updates at the local component level (e.g., search inputs) rather than micro-optimizing atomic store subscriptions.
