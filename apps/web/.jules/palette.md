
## 2024-05-18 - Missing focus indicators on contextual plain-text actions
**Learning:** In transient context actions like multiple "Delete forever" or "Cancel" buttons that are purely textual rather than standard buttons (e.g., using `font-mono text-xs`), focus styling is often overlooked, creating severe keyboard navigation traps.
**Action:** When implementing plain-text contextual buttons (like 'Cancel' or 'Delete forever' actions hidden behind initial intent), always explicitly add `focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm` to maintain keyboard focus discoverability.
