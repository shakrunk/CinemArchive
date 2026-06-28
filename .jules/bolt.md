
## 2024-03-24 - Zustand Selectors in Custom Hooks
**Learning:** React custom hooks extracting unique values from Zustand state (`useAppStore((s) => s.titles)`) without memoization causes expensive array operations (flattening, Sets, sorting) to run on every render.
**Action:** Always wrap expensive derived calculations from global state in `useMemo` within custom hooks to avoid O(N log N) re-calculations on each render cycle.

## 2024-05-19 - Zustand Store Component Subscription Re-renders
**Learning:** Components fetching state from `useAppStore()` without selectors or `useShallow` subscribe to the entire store, causing unnecessary re-renders when unrelated properties change.
**Action:** Always wrap the Zustand selectors using `useShallow` from `zustand/react/shallow` when extracting multiple properties from global state.
## 2024-11-20 - Zustand Render Optimization
**Learning:** Destructuring directly from `useAppStore()` in Zustand subscribes components to the entire store, causing unnecessary re-renders on every state update, even for unrelated slices.
**Action:** Always wrap the object selector with `useShallow` from `zustand/react/shallow` to limit re-renders strictly to the properties being consumed.
## 2024-06-27 - Root Component Re-renders from AppStore
**Learning:** In Zustand applications (like this one using React), if the root component (`App.tsx`) subscribes to a large or frequently updated array from the store (like `titles`), it will cause the entire component tree to re-render whenever an item inside that array is updated (e.g., logging an episode). This can easily become a severe performance bottleneck.
**Action:** When a component is only subscribing to a list in order to pass it down or derive commands, refactor the code to move the store subscription down directly into the child component that actually uses the data (e.g., `CommandPalette.tsx`). Keep root components lightweight to prevent cascading re-renders. Use specific selectors like `useAppStore((s) => s.titles)` in the child component rather than `getState()` to properly subscribe to changes in the idiomatic way.
