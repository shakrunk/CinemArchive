# Security Policy

## Supported versions

CinemArchive is a continuously deployed personal project. Only the **latest release** is supported —
see [Releases](https://github.com/shakrunk/CinemArchive/releases). The live web app always tracks
`main`; older Android APKs are not patched.

## Reporting a vulnerability

**Please do not open a public issue for a suspected vulnerability**, in particular anything that could
expose another user's library, viewing history, social graph, or share tokens.

Instead, use GitHub's private vulnerability reporting:
**[Report a vulnerability](https://github.com/shakrunk/CinemArchive/security/advisories/new)**

If that form is unavailable, contact the maintainer directly via
[@shakrunk](https://github.com/shakrunk).

Please include:

- what you did — the request, the endpoint, and the account/token state you were in
- what came back, and what you expected instead
- whether it required an authenticated session, a share token, or nothing at all

Expect an acknowledgement within a few days. This is a personal project with no security team and no
bounty programme, but reports are taken seriously and credited if you'd like.

## Scope

The security model is documented in full at
[Security Model](https://github.com/shakrunk/CinemArchive/wiki/Security-Model). Authorization is
enforced by Postgres Row Level Security rather than by client code, so the highest-value reports are:

- **RLS bypass** — reading or writing rows belonging to another user, especially via a direct REST
  call with no client involved
- **Share-token escalation** — a token seeing outside its `share_scopes`, surviving revocation or
  expiry, or gaining write access
- **Invite bypass** — creating an account without consuming a valid invite code
- **Credential or key exposure** — a TMDB/OMDb key, service key, or signing material reachable from a
  client bundle or APK
- **Authentication flaws** in the passkey/WebAuthn flow

### Out of scope

- The Supabase URL and publishable/anon key are **public by design** — they appear in every client
  bundle. RLS is what protects data; possessing them is not a vulnerability.
- Absence of rate limiting beyond the invite-attempt log and the metadata cache. This is a known
  personal-scale trade-off, as are the lack of an audit log and per-field encryption.
- Findings against third-party services (Supabase, GitHub Pages, TMDB, OMDb) — report those upstream.
- Automated-scanner output with no demonstrated impact.

### Known open item

A Supabase test project with owner / friend / blocked / share-token RLS fixtures does not exist yet,
so the unauthorized direct-REST case is not covered by an automated test. It is tracked in
[`docs/android-parity-matrix.md`](docs/android-parity-matrix.md). Reports in that area are especially
welcome.
