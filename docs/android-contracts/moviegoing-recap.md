# Moviegoing recap contract

The yearly "moviegoing wrapped" card (#216): one calendar year of cinema trips summarised as a
shareable card. It is pure presentation over trips the Ledger's "At the Movies" widget already
derives; it adds no tracking, table or sync. Both clients implement it and produce the same
figures for the same year.

## Inputs

A trip is a viewing with a non-null `venue` and a parseable date (the same set as the Ledger's trip
count). Each carries:

| Fact | Source |
|------|--------|
| date | `viewings.date` |
| venue | `viewings.venue` |
| companions | `viewings.companions` |
| format | the outing the viewing came from (`viewings.outing_id` to `cinema_outings.format`), normalised to the format's wire value; absent for a trip logged by hand |
| ticket price | the same outing's `ticket_price`; absent when unpriced |

Format and ticket price live on the owner-private `cinema_outings` row, so the recap's spend and
format lines are owner-only, like the Ledger's total spend. A shared or friend view must not show
them.

## Recap for a year

A trip belongs to the year of its viewing date (1 January to 31 December, no time zone shift: the
date is a plain calendar date). A year with no trips has no recap.

| Field | Rule |
|-------|------|
| `tripCount` | trips in the year |
| `topVenue` / `topFormat` / `topCompanion` | the most frequent label, with its count. A tie goes to the label that sorts first (plain code-point order). Labels are trimmed and blank ones ignored. Absent when no trip has one. |
| `totalSpend` | sum of the year's priced trips, added in whole cents (`round(price * 100)`) so float drift never changes the total. Absent when no trip that year has a price; a price of `0` still counts as known. |

Companions are counted per name occurrence, as in the Ledger's companion tally.

## Which year opens

The recap opens on the current calendar year when it has trips, otherwise the latest year that does.
The picker lists only years with trips, newest first. With no trips there is no recap and the entry
point is hidden.

## Sharing

Both clients render the card to a 1080 x 1350 PNG. Android writes it to the app's cache and hands it to
the system share sheet together with a one-line text summary of the same figures. The web client shares
the PNG through the browser's share sheet where it can share files, and otherwise downloads it. Nothing is
uploaded by either app.

Android pins the rules above in `MoviegoingRecapTest` (core/model) and web in `moviegoingRules.test.ts`
(`apps/web/src/store`), with the same cases.
