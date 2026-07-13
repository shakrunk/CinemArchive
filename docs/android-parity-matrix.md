# Android parity matrix

This is the versioned contract index for the native Android client. The web app remains the behavioral source of truth. A row may move to **ready** only when its API shape, RLS expectation, Android route, and executable test fixture are linked here.

| Domain | Android release behavior | Web sources | Backend/API dependency | Android surface | Required verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| Authentication | Restore a session, passkey sign-in, invite redemption, sign-out, and actionable errors. | `src/lib/auth.ts`, `InviteRedeemForm.tsx` | Supabase Auth; `redeem-invite`; Credential Manager/App Link association. | Auth graph | Existing-account device proof; invite error matrix; token clearing test. | Discovery |
| Library | Read cached library; poster/list views; filters, sort, and title navigation. | `Library.tsx`, `useAppStore.ts`, `fetchUserLibrary()` | `titles`, seasons/episodes relations; owner RLS; incremental cursor contract. | Library | Contract fixture; Room mapping; compact/expanded screenshot; offline read. | Contract documented ([library.md](./android-contracts/library.md)); Room mapping/screenshot tests pending |
| Title detail | Read film/TV metadata, credits, history, status, notes, and external watch link. | `TitleDetailDrawer.tsx`, `db.ts` | Title/details relation shape; media image allowlist. | Title detail | Detail fixture; read-only RLS test; deep-link test. | Contract documented ([title-detail.md](./android-contracts/title-detail.md)); deep-link test pending |
| Episode tracking | Keep watches, ratings, and reviews independent; present correct rollups. | `episodeUtils.ts`, `logEpisodeToDb()` | `episodes`, watch/rating/review tables; idempotent atomic command. | Title detail | Shared rollup fixtures; offline/retry/process-death tests. | Contract documented ([episode-tracking.md](./android-contracts/episode-tracking.md)); idempotency gap flagged, offline/retry tests pending |
| Discover/add | Search/browse through the protected media proxy; explain duplicates. | `AddTitleWorkflow.tsx`, `media.ts` | `media-proxy` actions; rate limits; TMDB attribution/cache rules. | Discover | Proxy contract fixture; offline/error UI tests. | Discovery |
| Up Next | Show upcoming titles and next unwatched episodes. | `UpNext.tsx`, `store/upNext.ts` | Library bootstrap fields and calculation version. | Up Next | Shared derivation fixtures; empty/error states. | Discovery |
| Ledger | Present all existing calculations and widget preferences with accessible alternatives. | `ledgerDerive.ts`, `ledgerStats.ts`, `ledgerPanels.ts` | Stable library/history inputs; `user_prefs.ledger_prefs`. | Ledger | Shared calculation fixtures; widget persistence; chart semantics. | Discovery |
| Sharing | Create/revoke/expire scoped, read-only share links and open shared libraries. | `Profile.tsx`, `auth.ts`, `fetchSharedLibrary()` | `shared_access_keys`, `share_scopes`, shared-token RPC, RLS. | Profile / shared library | Anonymous/valid/expired/revoked/scope RLS matrix; App Link routes. | Discovery |
| Friends and social | Friend states, friend libraries, comments, reactions, activity, recommendations. | `Friends.tsx`, `db.ts`, social components | Friendship state machine; social tables/triggers; scoped friend RLS. | Friends | Owner/friend/blocked/scoped fixtures; pagination and mutation errors. | Discovery |
| Notifications | In-app inbox and, later, privacy-safe FCM routes. | `NotificationCenter.tsx`, notification store actions | `notifications`; canonical event dispatch; device installation endpoint. | Notification center | Read/dismiss fixture; cold-start route test; lock-screen redaction review. | Discovery |
| Preferences and themes | Persist four themes plus navigation/Ledger preferences. | `theme.ts`, `Profile.tsx`, `saveLedgerLayout()` | `user_prefs`; local DataStore policy. | Profile/settings | Theme token screenshots; account-switch clearing test. | Theme persistence implemented (local DataStore only, no `user_prefs` sync yet); navigation/Ledger preference persistence and account-switch clearing pending |

## Contract completion rules

Every row must document the following before its first Android write is enabled:

1. Request and response fields, IDs, enum values, timestamps, pagination, and error codes.
2. The RLS authorization matrix, including the unauthorized direct-REST case.
3. Whether the action is idempotent, its operation ID format, and retry/conflict behavior.
4. JSON fixture files derived from production-compatible test data and a test that runs them on both web and Android logic where applicable.
5. An Android route and its loading, empty, offline, and recoverable-error states.

## Immediate contract tasks

- [x] Define the authenticated bootstrap and incremental-sync endpoint/cursor strategy; direct full-library reads are not sufficient for offline sync. See [`docs/android-sync-contract.md`](./android-sync-contract.md) §2 — design draft, not yet implemented as a migration.
- [x] Define server-authoritative `updated_at` and tombstone behavior for every synchronizable entity. See [`docs/android-sync-contract.md`](./android-sync-contract.md) §3 — design draft; adds `updated_at` to 10 tables currently missing it and a new `sync_tombstones` table, none applied yet.
- [x] Add idempotency and atomic-command contracts for title, viewing, episode, social, and share mutations before those Android writes are implemented. See [`docs/android-sync-contract.md`](./android-sync-contract.md) §4 for the general contract and [`docs/android-contracts/`](./android-contracts/) for the Library/Title-detail/Episode-tracking specifics; social/share domains still at Discovery (out of scope for this pass).
- [ ] Validate Credential Manager/WebAuthn relying-party and Digital Asset Links on a physical Android device.
- [ ] Add a Supabase test project plus owner/friend/blocked/share-token RLS fixtures.
