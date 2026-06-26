# Title Detail Drawer — Polish Pass

**Date:** 2026-06-26
**Scope:** `src/components/TitleDetailDrawer.tsx` only
**Theme:** Surface the precision that's already in the data; make recording feel responsive.

---

## Problem statement

The TitleDetailDrawer is the most-used screen in CinemArchive. Three clusters of rough edges diminish the experience:

1. **Timestamps discard information.** Ratings and reviews are stored with full ISO datetimes (`ratedAt`, `reviewedAt` — e.g. `"2026-01-10T21:30:00Z"`), but `fmtDateTime` renders them identically to `fmtDate` — just `Jan 10, 2026`. The time-of-day is thrown away. Separately, the movie "Last Seen" stat calls `.getFullYear()` and shows only `2026`, while the timeline directly below it shows `Jan 10, 2026`.

2. **Recording is silent.** The episode log form and the movie viewing form both dismiss without any acknowledgement after save. The user has no confirmation the data stuck. Additionally, the episode log form pre-fills today's date from a module-level constant evaluated at app load time — if the app is open across midnight, the default date is yesterday.

3. **Episode history layout doesn't adapt.** The three-column Watched / Ratings / Reviews grid always renders all three columns. A freshly-watched episode (one watch event, no rating, no review) shows one date and two columns of "—". Separately, an episode watched three times shows the same single Eye icon as one watched once.

---

## Changes

### 1. Timestamp helpers

**Replace the two identical helpers with two actually-different ones.**

`fmtDate(iso: string)` — unchanged; used for watch events where the user picks a calendar date.
```
Jan 10, 2026
```

`fmtDateTime(iso: string)` — now shows date + time on two lines. Time rendered in 12h format using the system locale. Date line same colour as before (`10px`); time line at `9px` in `var(--paper-faint)`, creating a visual hierarchy.
```
Jan 10, 2026
9:30 PM
```

**Movie "Last Seen" stat** — change from `new Date(…).getFullYear()` to `fmtDate(…)`, matching the viewing timeline below it.

### 2. Recording feedback

**Stale default date fix.**
`EMPTY_EP_LOG` stays as a module-level constant for the non-date fields. When `setShowForm(true)` is called (the form opens), patch the date to today:
```js
function openForm() {
  setLog(l => ({ ...l, watchedAt: new Date().toISOString().slice(0, 10) }))
  setShowForm(true)
}
```
This ensures the pre-filled date is always fresh regardless of when the app was loaded.

**Save confirmation — episode log.**
Add `savedFeedback` boolean state to `EpisodePanel`. After `doSave` completes, set it `true` and schedule a `setTimeout` for 1 500 ms to set it `false`. During that window, the trigger button reads `✓ Logged` (Check icon + amber text) instead of the usual `+ Log watch event…`.

**Save confirmation — movie viewing form.**
Same pattern in the main drawer body. After `logViewing` completes, set `savedFeedback` true, delay 1 500 ms, then reset the form and hide it. The "Save Viewing" button reads `✓ Saved` (Check icon + amber) during the window.

### 3. Episode history layout

**Adaptive columns.**
Before rendering the history grid, compute which of the three record types have data:
```js
const hasCols = {
  watch:  episode.watchEvents.length > 0,
  rating: episode.ratings.length > 0,
  review: episode.reviews.length > 0,
}
const colCount = Object.values(hasCols).filter(Boolean).length
```
Use `grid-cols-1 / 2 / 3` accordingly. Only render the columns that have content — skip empty ones entirely.

**Re-watch count on EpisodeRow.**
When `episode.watchEvents.length > 1`, append a `×N` count in `font-mono` amber directly after the Eye icon. When `watchEvents.length === 1`, the icon alone is sufficient.

---

## Out of scope

- Movie viewing timeline behaviour (no editing, intentional — viewings are immutable sentiment records)
- Up Next, Library, or Ledger views
- Any new features (shared key expiry UI, grid virtualisation, passkey WebAuthn)

---

## Files touched

| File | Changes |
|------|---------|
| `src/components/TitleDetailDrawer.tsx` | All changes — helpers, state, JSX |

No schema changes, no store changes, no new dependencies.
