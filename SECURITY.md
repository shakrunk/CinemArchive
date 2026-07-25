# Security Policy

## Supported versions

CinemArchive is a continuously deployed personal project. Only the **latest release** is supported —
see [Releases](https://github.com/shakrunk/CinemArchive/releases). The live web app always tracks
`main`; older Android APKs are not patched.

| Component | Supported |
|---|---|
| Web app (`apps/web`, GitHub Pages) | Latest deploy of `main` only |
| Android APK (`apps/android`) | Latest tagged release only |
| Supabase backend (`supabase/`) | Latest, shared by both clients — there is no versioned backend |

## Safe harbor

Good-faith security research under this policy is authorized. That means: testing against your own
account and data, avoiding privacy violations and service disruption for other users, not exfiltrating
data beyond what's needed to demonstrate a finding, and reporting through the channel below rather than
public disclosure. Reports made in good faith under these terms will not result in legal action from the
maintainer.

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

### Response process

1. **Acknowledgement** — within a few days of the report.
2. **Triage** — the maintainer reproduces the issue and assesses severity/scope against the categories
   below.
3. **Fix** — timeline scales with severity. RLS bypass, share-token escalation, and credential exposure
   are treated as urgent (days, not weeks) since this app holds another user's viewing history and
   social graph. Lower-severity or best-practice findings are scheduled alongside normal work.
4. **Disclosure** — coordinated. Public disclosure (a GitHub Security Advisory) happens once a fix is
   deployed, with credit to the reporter unless they prefer otherwise. If a report goes unfixed and
   unacknowledged for 90 days, the reporter is free to disclose publicly.

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

## Automated security controls

These run continuously against this repository; a finding from any of them is handled through the same
process as an external report, just without the acknowledgement step.

- **Code scanning ([CodeQL](https://github.com/shakrunk/CinemArchive/security/code-scanning))** —
  `.github/workflows/codeql.yml` runs an advanced-setup CodeQL analysis (not GitHub's default setup,
  which can't build the Android app's Gradle project from a non-root directory and would silently skip
  it) across all three source languages this repo ships: `javascript-typescript` (web app),
  `java-kotlin` (Android app), and `actions` (the workflows themselves — this is what catches issues
  like an overly-broad `GITHUB_TOKEN`). It runs on every push/PR to `main`/`dev` and weekly on a
  schedule.
- **Dependabot** — security updates are enabled for both `apps/web` (npm) and `apps/android` (Gradle);
  alerts are triaged as they arrive rather than on a fixed cadence, since severity varies too much for
  one SLA to make sense.
- **Secret scanning**, including push protection, is enabled — a commit containing a recognized secret
  pattern is blocked before it lands.
- **Least-privilege `GITHUB_TOKEN`** — every workflow sets an explicit top-level `permissions: contents:
  read`, escalating only on the specific job that needs more (e.g. the release job in `deploy.yml`
  needs `contents: write` to tag and publish; nothing else does). This is enforced by the CodeQL
  `actions` analysis above, so a future workflow added without a `permissions` block will be flagged
  automatically rather than relying on review to catch it.
- **Convention, not enforcement, on `main`** — GitHub branch protection is not currently enabled; `main`
  is expected to receive code only through a `dev` → `main` PR (see
  [CLAUDE.md § Branching & Release](CLAUDE.md#branching--release)), but this is a process convention
  rather than a server-enforced rule. Tracked as a known gap rather than presented as a control.
