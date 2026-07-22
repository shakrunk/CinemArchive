The plan is to add focus-visible styles to the interactive buttons inside `src/components/NotificationCenter.tsx` to improve keyboard navigation accessibility.
Specifically:
1. The main toggle button (`handleToggle`)
2. The "Mark all read" button
3. The individual notification rows (which are buttons)
4. The "Dismiss notification" button (X)
5. The "I've got tickets too" button
6. The "Add to calendar" button

This aligns with the instruction:
"To support keyboard navigation accessibility, ensure interactive elements like buttons have explicit focus states, typically using Tailwind classes like focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60."

The changes will be minimal, strictly adding the class to `className` properties without changing layout or logic, which satisfies the boundaries (< 50 lines, no custom CSS, just accessibility focus).
