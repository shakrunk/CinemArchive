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

## 2024-11-21 - Zustand Atomic Selectors Optimization
**Learning:** Batching multiple atomic selectors into a single `useShallow` call (e.g., `useAppStore(useShallow(s => ({ a: s.a, b: s.b })))`) introduces unnecessary object allocation and shallow diffing overhead on every render, without providing any performance benefit over individual atomic selector hooks (e.g., `useAppStore(s => s.a); useAppStore(s => s.b)`).
**Action:** When extracting multiple primitive or atomic values from a Zustand store, use multiple individual `useAppStore` hooks rather than batching them inside a `useShallow` object.

## 2024-11-21 - Zustand Atomic Selectors Optimization (Addendum)
**Learning:** Batching multiple atomic selectors into a single `useShallow` call introduces object allocation and shallow diffing overhead on every render, overriding the rapid referential equality check built natively into Zustand atomic selectors.
**Action:** When extracting multiple primitive or atomic values from a Zustand store, strictly map them to multiple individual `useAppStore` hooks rather than batching them inside a `useShallow` object.
## 2024-07-25 - Prevent O(N*M) Hover Lag in PersonDetailPanel
**Learning:** Extracting data from large global state stores via filtering arrays inside render loops causes extreme lag during UI interactions (like hover states or focus shifts) that trigger localized re-renders. In `PersonDetailPanel`, calculating `personTitles` without memoization iterated the entire library and nested arrays on every render.
**Action:** Always wrap expensive O(N) array filtering/mapping operations over large datasets (like `titles`) in `useMemo`, ensuring they depend only on the explicit source array and specific identifier required for the filter to avoid needless recalculation during unrelated re-renders.

## 2024-03-09 - Performance Optimizations Must Not Alter Functional Behavior
**Learning:** When moving filtering logic (e.g., hiding zero-count emojis) out of inline render operations and into a memoized `Map` (`reactionStats`), introducing a new conditional return like `if (count === 0 && !mine) return null` actively suppresses elements that the user expects to see based on the previous implementation, causing an unintended visual regression.
**Action:** When implementing performance optimizations (such as wrapping calculations in `useMemo`), strictly preserve the original render logic and return conditions. Do not introduce new conditional returns unless explicitly requested, as this causes visual regressions and fails the functional equivalence check.

## 2024-03-09 - Clean Up Temporary Scripts Before Review
**Learning:** Requesting a code review with a temporary patch script (`patch_comments.cjs`) still in the working directory pollutes the commit and fails the review validation step.
**Action:** Ensure all temporary workspace files (e.g., patching scripts like `.cjs` files) are explicitly deleted before requesting a code review to prevent them from being accidentally included in the final validation and commit.

## 2024-03-24 - O(N*M) lookups in render loops
**Learning:** Checking for title ownership via `titles.some(...)` inside a `recommendations.map(...)` array iteration during render creates an O(N*M) calculation that severely impacts performance for users with large libraries.
**Action:** When optimizing React render loops, replace nested array lookups (e.g., `.some()` or `.find()` inside a `.map()`) with O(1) `Set` or `Map` lookups, precomputing the hash map in a `useMemo` hook to avoid O(N*M) complexity.
