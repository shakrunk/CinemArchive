# Moviegoing prefs contract

Owner-private moviegoing settings that follow a signed-in account across devices. Both clients
read and write one row of `moviegoing_prefs`
(`supabase/migrations/20261029000000_moviegoing_prefs.sql`). It starts with the spend budget
(#209) and is the home for the subscription plan (#211) and default region (#202) as they land.

This is a separate table, not a `user_prefs` column, on purpose: `user_prefs` has friend and
shared-key read policies that expose the whole row, and a spending budget must not be visible to
friends. `moviegoing_prefs` has an owner policy only. Friend and shared-view reads never touch it,
and the Ledger "At the Movies" panel shows the budget only to the owner.

## Payload (`moviegoing_prefs.prefs`)

```json
{ "budget": { "amount": 40, "period": "month" }, "updatedAt": "2026-10-29T10:00:00.000Z" }
```

| Field | Values |
|-------|--------|
| `budget` | optional. Absent or `null` means no budget is set. |
| `budget.amount` | finite number, greater than 0 and at most 100000, in the user's own currency (the app shows `$`, matching `ticket_price`) |
| `budget.period` | `month` or `year`, both calendar periods in the device's local time zone |
| `updatedAt` | ISO-8601 instant of the user's last change to any key, on the writing device |

A payload whose `budget` is present but invalid (non-positive or non-finite amount, unknown
`period`, wrong types), or whose `updatedAt` is missing or unparseable, is ignored entirely: it is
neither applied nor overwritten. Unknown top-level keys are preserved when a client writes back,
so a newer client's additions survive an older client's edit.

## Sync rules

- Last write wins on `prefs.updatedAt`, never on the row's `updated_at` (any write bumps that).
- Clients keep the local copy as the source of truth for reads. On sign-in/launch they fetch the
  row; a newer remote is applied locally, a newer local is pushed, a failed fetch changes nothing.
- A user clearing the budget writes `budget: null` with a fresh `updatedAt`, so the clear
  propagates rather than being undone by an older remote copy.

## Period spend

Spend and trip counts are bucketed by the viewing date of a priced outing. The current period is
the calendar month or year containing "today" in local time. The status is:

| `spent / amount` | State |
|------------------|-------|
| below 0.8 | `under` |
| 0.8 up to and including 1.0 | `near` |
| above 1.0 | `over` |

Android pins these cases in `MoviegoingBudgetTest` (core/model). The web implementation (#389) must
mirror the same cases in its own suite, as the Calendar derivation does.
