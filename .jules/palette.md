## 2023-10-27 - Icon-Only Action Buttons in Navigation
**Learning:** The circular 'Add' button in the BottomNav is visually distinct but lacks an accessible name, making it opaque to screen readers. This pattern of placing primary actions as visually prominent but textless icons in navigation bars is common but requires explicit ARIA labels.
**Action:** Always verify that central or primary action buttons in navigation components have an explicit `aria-label` when they don't contain visible text.

## 2024-05-18 - Hidden Responsive Text Unintentionally Stripping Button Accessible Names
**Learning:** Responsive utility classes that hide inline text content (like `hidden sm:inline`) on small viewports will also remove that text from the accessibility tree, inadvertently turning standard text buttons into inaccessible icon-only buttons for mobile screen reader users.
**Action:** When using responsive utility classes to hide text alongside an icon within an interactive element, ALWAYS provide a dynamic fallback `aria-label` on the parent interactive element to ensure an accessible name is present across all breakpoints.
## 2026-06-25 - Unlinked Labels and Missing ARIA Labels in Custom Forms
**Learning:** Custom forms in this app frequently use <label> tags without `htmlFor` attributes to connect them to their respective <input> or <textarea> elements. Additionally, some inputs lack explicit labels altogether and are missing `aria-label` attributes for screen readers.
**Action:** When adding or modifying form fields, always ensure that labels are correctly linked using `htmlFor` and `id`, or add descriptive `aria-label` attributes to inputs that lack visual labels.

## 2024-06-26 - Actionable Empty States
**Learning:** Empty states caused by active filters can be frustrating. Telling users to "reset the filters" is good, but providing a direct call-to-action button to do so is much better for UX, saving them time and interaction steps.
**Action:** Always include a "Clear filters" or similar direct action button in empty states where the condition is reversible by the user directly from that context.
