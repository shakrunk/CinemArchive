## 2023-10-27 - Icon-Only Action Buttons in Navigation
**Learning:** The circular 'Add' button in the BottomNav is visually distinct but lacks an accessible name, making it opaque to screen readers. This pattern of placing primary actions as visually prominent but textless icons in navigation bars is common but requires explicit ARIA labels.
**Action:** Always verify that central or primary action buttons in navigation components have an explicit `aria-label` when they don't contain visible text.
