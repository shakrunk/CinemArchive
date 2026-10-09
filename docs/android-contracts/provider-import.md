# Provider imports on Android

## New titles

Import & sync uses the existing Simkl, Plex, Emby and Letterboxd entry points.
After catalog matching, a new title, its fetched catalog children, supplied movie
viewing dates, and its `external_title_links` identity are admitted in one Room
transaction with one immutable library command. The screen reports success only
after that transaction commits. A storage failure is reported as **not saved**,
separately from a title that could not be matched.

The command reuses the installed JSON restore graph protocol, with an optional
`providerLinks` array containing literal `provider` and `externalId` values.
Older archive commands omit this property and retain their original request bytes.
Each provider link becomes an `external_title_links` insert in the same
`apply_library_command` transaction as the graph. Provider credentials never
enter the graph or queue. Existing natural identities cannot be retargeted.

Movie imports retain distinct supplied viewing dates, with the provider rating on
the latest supplied date. They do not invent an undated viewing when no date is
known. TV imports retain fetched seasons, episodes and credits without creating
movie-style viewings or guessed episode watch history.

Account and project identity are captured by the runtime. Account changes fence
metadata results, queue admission, progress, picker callbacks and connection
operations. A transaction failure or account switch during admission rolls back
both the graph and command.

## Delivery and recovery

Retries use the saved operation ID and the exact saved operations, including the
provider identity. A successful receipt must confirm the owner, natural link key
and target title. A fresh current-owner snapshot is then used for acknowledgement;
an old receipt cannot resurrect a subsequently deleted title. Failure to validate
or finish confirmation retains the original command.

New-title provider imports appear under **Import & sync → Restore JSON → Saved
imports**. This uses the same explicit retry, original-graph export and confirmed
rejection recovery as installed JSON restore. Unknown outcomes cannot be removed.
Removing a definitively rejected new title requires reviewing its never-dispatched
dependent changes; original provider data and any archive files are untouched.

## Remaining existing-title work

This checkpoint changes new-title imports only. Existing-title provider merges
still use the older separate title-rating/status and viewing mutations and do not
yet atomically journal their provider link. Matching this part of web requires a
separate guarded, optimistic merge command and recovery path; provider-import
parity is not complete until that path is installed and verified.

## Verification boundaries

Room tests cover complete graph/link admission, no invented TV/movie history,
storage and account rollback, duplicate and provider-identity checks, old archive
compatibility, and exact queue survival after database reopen. Transport tests
exercise exact unknown-outcome retry and malformed provider receipt retention.
Installed-app tests use the real CSV document picker, matching repository and Room
journal with HTTP intercepted locally; they do not contact live providers or write
to a production service.
