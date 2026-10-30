# Showtime reminders (Android only)

An optional notification "N minutes before showtime" for a scheduled cinema outing (#213). It is a
local, per-device feature: nothing here is synced, there is no schema change, and the web app has
no equivalent (a browser tab cannot wake for an alarm, the same reason completion alarms are
Android-only).

## Setting

Moviegoing settings → Showtime reminders: **Off** (default), **30 min**, **1 hour** or **2 hours**.
The lead lives in the device preferences (`outing_reminder_lead_minutes`), not in
`moviegoing_prefs`, because a second phone may want a different lead or none. A stored value that is
not one of the offered leads reads as off.

## One alarm, two jobs

The app arms a single exact alarm per account (`AndroidOutingAlarmScheduler`). Its target is now the
**earlier of**:

- the soonest `endsAt` of a scheduled outing (the completion engine, unchanged), and
- the soonest unsent reminder time, `showtime - lead`, ahead of now (or now itself when one is
  already due).

When it fires, `OutingCompletionReceiver` completes due outings and then claims due reminders.
Every path that re-arms after a schedule, edit, cancel, completion, restore or boot also re-arms for
reminders; changing the lead in Settings re-arms immediately. The `canScheduleExactAlarms()`
fallback is unchanged: with exact alarms denied nothing is armed, and a reminder may not arrive.

## When a reminder is due

A scheduled outing's reminder is due from `showtime - lead` until **10 minutes** after that, and
never once the show has started. The grace window means a phone that was off when the alarm fired
still tells the user shortly after it comes back, while:

- a reminder missed by more than 10 minutes is dropped rather than announced late, and
- an outing scheduled inside the lead window (for example 20 minutes out with a 60 minute lead) gets
  no reminder, since its reminder time has already passed.

## Once only

A claimed reminder is remembered on the device as `outingId@showtime` (`outing_reminders_sent`), so
it is posted once and survives a restart. The showtime is part of the key: moving an outing to a
new time earns a fresh reminder. Keys of outings that were moved, completed, cancelled or deleted
are dropped each time the alarm is re-armed. Reminders that are claimed while the notification
permission is denied are still marked sent; nothing is queued for later.

## Notification

Posted on its own channel, **Showtime reminders**, so it can be muted without silencing "how was
it?". Title `<Title> starts at <time>` (device locale, local time zone), text `At <venue>` or
`Time to get ready.` when there is no venue, with the poster as the large icon. Tapping opens the
title. It is owner-scoped like the completion notification: it is never posted for an account that
is not the current sign-in.

## Not included

A "leave by" time that accounts for travel needs a venue location, which does not exist yet
(`venue` is free text), plus a directions service. That is the later step of #213, and #215 (the
drive-in weather nudge) builds on the same alarm path.

Android pins the rules in `OutingReminderRulesTest` (core/model) and the claim, re-arm and pruning
behavior in `OutingReminderClaimTest` (data).
