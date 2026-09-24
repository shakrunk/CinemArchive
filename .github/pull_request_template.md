## What & why

<!-- What changed and why. Link issues with "Closes #123". -->

## Verification

<!-- How you verified it: web `npm run lint && npm run build && npm run test`, Android Gradle line, on-device checks. -->

## Cross-client parity

<!--
Every single-client `feat`/`fix` commit needs a `Parity:` trailer (`Parity: #<issue>` or
`Parity: n/a: <reason>`); the Parity workflow fails the PR otherwise. For commits already pushed
without one, declare them here instead, one line each:

Parity-Override: <sha> #<issue>
Parity-Override: <sha> n/a: <reason>

Run `npm run check:parity` from the repo root to see what the check will flag.
-->

- [ ] Changes both clients, or no user-facing client change
- [ ] Other client's gap is tracked in a `parity-gap` issue (and the parity matrix row, if a domain's status moved)
- [ ] Client-specific — no counterpart needed (reason in the trailer)

## Also flag

<!-- Any migration, RLS change, or Edge Function deploy this needs. Delete if none. -->
