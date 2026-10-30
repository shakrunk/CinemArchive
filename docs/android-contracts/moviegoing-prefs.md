# Moviegoing prefs contract

Owner-private moviegoing settings that follow a signed-in account across devices. Both clients
read and write one row of `moviegoing_prefs`
(`supabase/migrations/20261029000000_moviegoing_prefs.sql`). It starts with the spend budget
(#209) and is the home for the subscription plan (#211), recurring outing templates (#204) and
default region (#202).

This is a separate table, not a `user_prefs` column, on purpose: `user_prefs` has friend and
shared-key read policies that expose the whole row, and a spending budget must not be visible to
friends. `moviegoing_prefs` has an owner policy only. Friend and shared-view reads never touch it,
and the Ledger "At the Movies" panel shows the budget only to the owner.

## Payload (`moviegoing_prefs.prefs`)

```json
{
  "budget": { "amount": 40, "period": "month" },
  "plan": { "cap": 4, "period": "week" },
  "templates": [
    {
      "id": "6f1c0a52-3d8e-4b7a-9c21-5e0d7a1b2c33",
      "name": "Tuesday matinee",
      "venue": "AMC Georgetown",
      "companions": ["Sam"],
      "format": "IMAX",
      "weekday": 2,
      "time": "14:00"
    }
  ],
  "region": "GB",
  "updatedAt": "2026-10-29T10:00:00.000Z"
}
```

| Field | Values |
|-------|--------|
| `budget` | optional. Absent or `null` means no budget is set. |
| `budget.amount` | finite number, greater than 0 and at most 100000, in the user's own currency (the app shows `$`, matching `ticket_price`). Typed text is an optional `$`, then plain ASCII decimal digits with an optional point, with commas ignored: `1e3`, `40d`, `0x10`, `NaN` and non-ASCII digits are not amounts. |
| `budget.period` | `month` or `year`, both calendar periods in the device's local time zone |
| `plan` | optional. Absent or `null` means no subscription plan is set. |
| `plan.cap` | whole number from 1 to 100: the outings the plan allows per period. `3` and `3.0` are both accepted in the stored payload; `2.5` is not. Typed text is plain ASCII digits with an optional sign, so `3.0`, `1e1` and non-ASCII digits are not accepted. |
| `plan.period` | `week` or `month` |
| `templates` | optional. Absent or `null` means no templates. An array of at most 12 [outing templates](#outing-templates-204). |
| `region` | optional. Absent or `null` means none saved. Exactly two uppercase ASCII letters (ISO 3166-1 alpha-2), the country whose [now-playing list](#now-playing-202) is shown. |
| `updatedAt` | UTC instant of the user's last change to any key, on the writing device, written `YYYY-MM-DDTHH:mm:ss[.fraction]Z` with up to nine fractional digits and a real calendar date. A date alone, a missing `Z`, a numeric offset such as `+01:00`, a missing seconds field and an hour of `24` are all unparseable, on both clients. |

A payload whose `budget` or `plan` is present but invalid (non-positive, non-finite or fractional
value, out-of-range `cap`, unknown `period`, wrong types), whose `templates` is present but not an
array, whose `region` is present but not two uppercase letters, or whose `updatedAt` is missing or unparseable, is ignored entirely: it is neither applied nor
overwritten. A valid `budget` does not rescue an invalid `plan`, or the reverse. An invalid
individual template is the exception: see below. Unknown top-level keys are preserved when a client writes back,
so a newer client's additions survive an older client's edit.

## Sync rules

- Last write wins on `prefs.updatedAt`, never on the row's `updated_at` (any write bumps that).
- Clients keep the local copy as the source of truth for reads. On sign-in/launch they fetch the
  row; a newer remote is applied locally, a newer local is pushed, a failed fetch changes nothing.
- A user clearing the budget or the plan writes that key as `null` with a fresh `updatedAt`, so
  the clear propagates rather than being undone by an older remote copy. Deleting the last
  template writes an empty `templates` array the same way.
- Changing one of `budget`/`plan`/`templates` leaves the others untouched. All share the one `updatedAt`.
- Last write wins for the whole payload, so two devices editing different templates before they
  sync keep only the newer device's list. Templates are a handful of small, rarely edited items,
  so a per-template merge is not worth a table here.
- The web client (`apps/web/src/lib/moviegoingPrefsSync.ts`) follows the same payload, validation and
  last-write-wins rules, with one deliberate difference: it keeps its copy in memory for the session
  rather than on the device, because browser device storage is shared by whoever signs in next and
  this is one account's private data. It refetches on sign-in and when the browser comes back online,
  and a push that failed is retried by the next reconcile while the tab stays open.

## Period spend

Spend and trip counts are bucketed by the viewing date of a priced outing. The current period is
the calendar month or year containing "today" in local time. The status is:

| `spent / amount` | State |
|------------------|-------|
| below 0.8 | `under` |
| 0.8 up to and including 1.0 | `near` |
| above 1.0 | `over` |

Both clients load the same cases from [`fixtures/moviegoing.json`](fixtures/moviegoing.json): Android in
`MoviegoingBudgetTest`, `SubscriptionPlanTest`, `MoviegoingRecapTest` and `MoviegoingRulesTest` (core/model)
and `MoviegoingPrefsTest` (data), web in `moviegoingRules.test.ts` and `moviegoingPrefsSync.test.ts`
(`apps/web/src`). The fixture also pins the shared edge rules:

- **A trip needs a real date.** A viewing date is read as its first ten characters and must be a real
  calendar date, so `2026-02-30` is no trip date on either client.
- **A trip needs a non-empty venue.** An empty `venue` is the same as none (the web client maps it to
  absent when it reads a viewing); a venue that is only spaces still counts as a trip.
- **Money is `$1,234.50`.** Budget lines and the recap print a dollar sign, comma thousands and two
  decimals, built from whole cents so the device locale never changes the figure.
- **No trips yet is not "no budget".** The panel offers the budget and plan controls before the first
  trip, on both clients, to the owner only.

## Plan usage (#211)

Usage counts every dated cinema trip (a viewing with a venue), priced or not, whose date falls in the
current period. Two trips on the same day both count. The period containing "today" in local time is:

| `plan.period` | Window |
|---------------|--------|
| `week` | Monday to Sunday, always, whatever the device locale's first day of the week |
| `month` | the calendar month |

With `used` trips against `cap`:

| Condition (checked in this order) | State |
|-----------------------------------|-------|
| `used > cap` | `over` |
| `used == cap` | `at_cap` |
| `used > 0` and (`cap - used <= 1` or `used / cap >= 0.8`) | `near` |
| otherwise | `under` |

So a cap of 1 stays `under` until used, a cap of 3 turns `near` at 2, and a cap of 10 at 8. Android
pins these in `SubscriptionPlanTest` (core/model) and web in `moviegoingRules.test.ts`, with the same cases.

## Outing templates (#204)

A template is a recurring habit saved once ("Tuesday matinee with Sam at the same theater") and
applied from the schedule sheet with one tap. Applying fills the form; the title and the actual
showtime stay the user's to confirm. Templates are not shown in a friend or shared view and are
not part of an outing's share snapshot.

| Field | Values |
|-------|--------|
| `id` | required non-blank string, unique within the list (the first of a repeated id wins) |
| `name` | required, trimmed, 1 to 40 characters |
| `venue` | optional, trimmed, up to 100 characters; blank is the same as absent |
| `companions` | optional array of names, each trimmed, at most 40 characters, at most 6; blank and case-insensitive duplicate names are dropped. Names only: a linked friend is not stored. |
| `format` | optional, one of the cinema formats (`Standard`, `IMAX`, `3D`, `Dolby`, `70mm`, `Drive-in`, `Other`), matched case-insensitively and written in that spelling |
| `weekday` | optional whole number 1 to 7, ISO: 1 = Monday, 7 = Sunday |
| `time` | optional `HH:mm`, 24-hour, local |

A template that fails any rule above is skipped on read, so it never blocks the budget, the plan
or the other templates. The client rewrites the list from the templates it accepted, so a skipped
one is dropped by the next edit. At most 12 are kept (extras past the 12th valid one are skipped)
to keep the whole `prefs` row under its 8 KB limit.

"Save as template" captures the form's theater, companions and format, and the weekday and time of
the date and showtime currently entered. Using one sets the date to the first matching showtime
**strictly after now**, in local time:

| Template | Date used |
|----------|-----------|
| has `weekday` | the next day with that weekday; today counts only while its `time` is still ahead |
| no `weekday` | today while its `time` is still ahead, otherwise tomorrow |

A missing `time` is `19:00`. A missing `venue` or `format` leaves the form's value as it was, and
companions are always replaced by the template's. Android pins these cases in `OutingTemplateTest`
(core/model) and `MoviegoingTemplatesPrefsTest` (data); web pins the same in `outingTemplates.test.ts`
and `moviegoingPrefsSync.test.ts`.

## Now playing (#202)

"What's playing in theaters" sits at the top of the "Which movie are your tickets for?" step (the
`I've got tickets` command on Android, the movie picker on web). It lists the movies TMDB has in
theaters for the saved `region`, and picking one starts the usual schedule form.

- **Titles, not showtimes.** TMDB has no per-theater schedule, so the user still enters the time,
  exactly as before. The list only saves hunting for the title.
- **A region, not a location.** The country is chosen by code, saved in the `region` key above and
  shared across devices; there is no GPS and no location permission. Until one is saved the device
  locale's country is used (Android `Locale`, web `navigator.language`), then `US`. Changing the code
  in the picker saves it.
- **Library first.** The schedule form belongs to a library title. A movie already in the library
  opens its form; one that is not opens the Add flow with it preselected, and scheduling follows
  once it is added.
- **Edge function.** `media-proxy?action=now_playing&region=XX[&page=N]` returns TMDB's
  `/movie/now_playing` for that region (movies only, English titles), cached for 24 hours per
  region and page. `region` must be two letters (the function upper-cases it; clients send it
  upper-cased) and `page` an integer 1 to 500, or the call fails with a 400. The function is deployed separately from the apps by
  `.github/workflows/deploy-functions.yml`, so a client that ships first shows "Couldn't load
  what's playing" until it is.

Android pins the region rules in `MoviegoingRegionTest` (core/model) and the payload and request in
`MoviegoingRegionPrefsTest` (data); web pins the same in `moviegoingRegion.test.ts`,
`moviegoingPrefsSync.test.ts` and `mediaBrowse.test.ts`.
