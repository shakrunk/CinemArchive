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

## 2026-06-26 - Input Fields Lacking Accessible Labels
**Learning:** Several custom input elements (e.g., tag inputs and search inputs) in components like CommandPalette, AddTitleWorkflow, and TitleDetailDrawer lacked explicit `<label>` elements or `aria-label` attributes, making them inaccessible to screen readers.
**Action:** Always ensure that every input field, especially those acting as search bars or standalone tag inputs, has a clear `aria-label` or an associated `<label>` using `htmlFor` and `id`.

## 2024-05-18 - Missing ARIA Labels on Core Inputs and Actions
**Learning:** Found multiple instances where core inputs (like Search and Email) and icon-only actions (like Copy/Revoke) missed crucial `aria-label`s, despite having `htmlFor` bindings or text placeholders.
**Action:** When working on modals (`ProfileModal.tsx`, `RefreshMetadataModal.tsx`), specifically check the `Input` and `Button` components for screen-reader friendly descriptive attributes, rather than assuming standard label behaviors are enough.

## 2024-06-27 - Actionable Empty States
**Learning:** Empty states caused by active filters or missing data (like no search results or no viewings logged) are often passive and rely purely on informative text. Telling users to try a different search or log a view is okay, but providing a direct call-to-action button to do so is much better for UX, saving them time and interaction steps.
**Action:** Always include a "Clear search", "Log viewing", or similar direct action button in empty states where the condition is reversible or actionable by the user directly from that context. Ensure that buttons are clearly labeled and accessible.

## 2024-06-29 - Icon-Only Tooltips and Keyboard Focus
**Learning:** Icon-only buttons and inline interactive elements (like tag remove buttons) often miss `title` attributes for tooltips and `focus-visible` styling for keyboard navigation, making them harder to discover and use.
**Action:** Always include `focus-visible` ring styling and `title` attributes (in addition to `aria-label`) on all interactive buttons, especially icon-only ones, to ensure they are discoverable via mouse hover and keyboard tabbing.

## 2024-06-30 - Contextual ARIA Labels for Destructive Actions
**Learning:** Confirmation and cancellation buttons for destructive actions (like "Delete forever" or "Cancel") often share the same text across different contexts on the same page, which can be ambiguous for screen reader users navigating by interactive elements.
**Action:** Always add explicit `aria-label` attributes to contextual confirmation buttons to clarify the exact action being performed (e.g., `aria-label="Confirm delete viewing"` instead of just "Delete forever").

## 2024-06-30 - WCAG 2.5.3 (Label in Name) Compliance
**Learning:** Adding an `aria-label` to a button that completely overwrites its visible text violates WCAG 2.5.3, which requires the accessible name to contain the exact visible text. This breaks voice control software where users might command "Click [Visible Text]".
**Action:** When adding `aria-label` to give extra context to a text button (e.g. "Delete forever"), ensure the label INCLUDES the exact visible words (e.g. `aria-label="Delete viewing forever"`).
## 2026-07-02 - Actionable Empty States in Dashboards
**Learning:** Empty states in dashboard components (like those in Ledger.tsx) often just display static text (e.g., 'No titles yet'), which is a dead end. Providing a clear CTA like 'Browse Library' helps the user take the next step.
**Action:** When working on dashboard panels or data visualization components, ensure any empty states include an actionable CTA button that guides the user on how to populate the data.
