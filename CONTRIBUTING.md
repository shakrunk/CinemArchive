# Contributing to CinemArchive

Thanks for looking. CinemArchive is a personal project, but the conventions here are strict — both
humans and AI agents work in this repo, and undocumented drift is expensive.

**[CLAUDE.md](CLAUDE.md) is authoritative for every convention below.** This file is the short
version; the wiki's [Contributing](https://github.com/shakrunk/CinemArchive/wiki/Contributing) page is
the walkthrough with reasoning and a pre-PR checklist.

Please also read the [Code of Conduct](CODE_OF_CONDUCT.md).

---

## Before you start

- **Setup:** [Getting Started](https://github.com/shakrunk/CinemArchive/wiki/Getting-Started) covers
  both clients, including the `.env.local` / `local.properties` values you'll need.
- **Check the backlogs first.** Most small items are already tracked:
  [`docs/known-problems.md`](docs/known-problems.md) (`KP-###` rows) and
  [`docs/android-parity-matrix.md`](docs/android-parity-matrix.md) (Android↔web gaps — many apparent
  bugs are documented gaps).

## Verification gates

**Typecheck, lint and build must all pass before a change is complete.** There is no CI job running
these for the web app on a pull request, so this is on you.

```bash
# web — from apps/web/
npm run lint && npm run build && npm run test
```

`npm run build` runs `tsc -b` first, so it covers the typecheck gate.

```bash
# android — from apps/android/
./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest
```

That Gradle line is exactly what `.github/workflows/android.yml` runs.

## Branching

| Branch | Role |
|--------|------|
| `main` | Release branch — every push deploys |
| `dev` | Integration branch — day-to-day work merges here |
| `feat/…`, `fix/…`, `chore/…` | Topic branches |

Topic branch → `dev` → (release PR) → `main`. **Version-bump and release PRs target `main`, not
`dev`;** confirm branch topology before opening one.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/) — the commit type determines the release
version bump (see [CLAUDE.md § Versioning](CLAUDE.md#versioning)).

```
feat(ledger): add Revival House widget
fix(web): keep drawer open when back button fires mid-animation
chore(deps): bump vite to 8.0.16
```

Keep commits atomic and logically grouped. Check `git diff --stat` before committing to confirm the
diff matches the intended scope — watch for CRLF/LF normalization silently swallowing unrelated
changes.

**No self-attribution.** Do not add `Co-Authored-By: Claude`, "Generated with…", or any similar
trailer to commit messages or PR descriptions. This is enforced via `attribution.commit` /
`attribution.pr` in `.claude/settings.json`, and applies equally when writing a message by hand.

## Changelog

Add a line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) whenever a change is **user-facing**
(anything matching `feat` / `fix` / `perf` / breaking). Purely internal changes (`refactor`, `docs`,
`test`, `chore`) don't need one. Format is [Keep a Changelog](https://keepachangelog.com/).

## Schema changes

Never edit the database directly. Add a timestamped migration under `supabase/migrations/`, keep
[`schema.sql`](schema.sql) in sync, and note in your PR whether it still needs manual application —
full procedure in
[Database Migrations](https://github.com/shakrunk/CinemArchive/wiki/Database-Migrations).

If the change touches RLS, state in the PR how you checked all four principals: owner, friend,
share-token holder (in and out of scope), and an anonymous caller hitting REST directly.

## Cross-client changes

The **web app is the behavioural source of truth.** Changing shared behaviour means landing it on web,
updating the affected [`docs/android-contracts/`](docs/android-contracts/) document, and updating the
[parity matrix](docs/android-parity-matrix.md) row so Android's gap is visible rather than discovered
later. Documented gaps are fine; undocumented divergence is not.

### Cross-client parity

Features and fixes drift between clients when nobody decides, at ship time, what the other client
should do. So every `feat` or `fix` commit (or breaking change) that touches shipped source in only
one client — `apps/web/src/` or an Android `src/main/` — must carry a `Parity:` trailer:

```text
feat(web): bring rich title previews to Discover

Parity: #312
```

- `Parity: #<issue>` — the other client needs this; the gap is tracked in a
  [Parity gap](.github/ISSUE_TEMPLATE/parity_gap.yml) issue (label `parity-gap`). This works in both
  directions: Android-first features get a web gap issue the same way.
- `Parity: n/a: <reason>` — no counterpart is needed (a web-only layout bug, an Android-only system
  integration, ...). The reason is required.

Commits that change both clients, and commits that touch neither (backend, docs, CI), need nothing.

The [Parity workflow](.github/workflows/parity.yml) enforces this on every pull request into `dev`
or `main`, including the `dev` → `main` release PR. A commit already pushed without a trailer can be
declared from the PR body instead: `Parity-Override: <sha> #<issue>` or
`Parity-Override: <sha> n/a: <reason>`. Run `npm run check:parity` from the repo root (defaults to
`origin/main..HEAD`) to see what it will flag before pushing.

Open `parity-gap` issues are the working backlog for closing the gap; when a domain's overall status
changes, update its [parity matrix](docs/android-parity-matrix.md) row too.

## Dependencies

Do not revert a dependency migration as a suspected supply-chain issue without verifying against the
official package registry first. An unfamiliar name or a package rename is not evidence of compromise.

## Pull requests

Describe what changed and why, say how you verified it, and flag any migration, RLS change, or parity
row that moved. The [PR template](.github/pull_request_template.md) prompts for each of these. Issues use [templates](.github/ISSUE_TEMPLATE/) — blank issues are disabled.

Security problems: **do not open a public issue.** See [SECURITY.md](SECURITY.md).
