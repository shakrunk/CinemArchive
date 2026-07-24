# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository. It covers repo-wide conventions that apply to every client. Client-specific
guidance lives in nested `CLAUDE.md` files, auto-discovered by directory:

- [apps/web/CLAUDE.md](apps/web/CLAUDE.md) — the web app (Vite + React + TypeScript)
- [apps/android/CLAUDE.md](apps/android/CLAUDE.md) — the Android app (Kotlin + Jetpack Compose)

## Project Overview

**CinemArchive (The Projection Room v2)** is a personal movie and TV series tracking app with
a cinematic dark-gold aesthetic, backed by a shared Supabase project (Postgres + Auth + Edge
Functions) and TMDB/OMDb for media metadata. It ships two clients today, each a sibling under
`apps/<platform>/` with equal structural standing: a web app (static React, deployed to GitHub
Pages) and a native Android app — see `README.md` for the full feature list and architecture
docs, and [docs/repo-restructure-plan.md](docs/repo-restructure-plan.md) /
[docs/adr/0002-multi-platform-repo-layout.md](docs/adr/0002-multi-platform-repo-layout.md) for
why this layout was adopted and the checklist for onboarding a future platform (iOS, etc.).

## Repository layout

```
apps/web/          # Web app (Vite + React + TypeScript) — see apps/web/CLAUDE.md
apps/android/      # Android app (Kotlin + Jetpack Compose) — see apps/android/CLAUDE.md
supabase/          # Shared backend: migrations/, functions/ (consumed by every client)
schema.sql         # Canonical, human-readable copy of the shared DB schema + RLS policies
docs/              # Repo-wide docs: ADRs, Android↔web contract docs, known problems, etc.
.github/workflows/ # CI: deploy.yml (web + release + Android APK), android.yml, db-migrate.yml, deploy-functions.yml
```

---

## Verification

After every code change, run typecheck, lint, and build before committing; do not consider a task complete until all gates pass.

---

## Git & Commit Conventions

Always create atomic, logically-grouped commits with professional messages, and NEVER add Claude/Co-Authored-By self-attribution to commits or PRs.

**Never add self-attribution to commits or PRs.** Do not include `Co-Authored-By: Claude`, "Generated with Claude Code", or any similar trailer/line in commit messages or PR descriptions in this repo. This is enforced via `attribution.commit` / `attribution.pr` (both set to `""`) in `.claude/settings.json`, but follow it even if editing commit messages by hand.

Before committing, check `git diff --stat` to confirm the diff matches the intended atomic scope; watch for CRLF/LF line-ending normalization silently swallowing unrelated changes.

---

## Branching & Release

When targeting a version-bump or release PR, target `main` (not `dev`) if commits already exist in dev; confirm branch topology before opening the PR.

---

## Versioning

The app follows [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`). The repo-root `package.json` → `version` is the single canonical source of truth across every client (it governs the web app's `__APP_VERSION__` and the Android APK's `versionCode`/`versionName` — see `.github/workflows/deploy.yml`). `CHANGELOG.md` (Keep a Changelog format) records what shipped in each version.

- **Commit messages already follow [Conventional Commits](https://www.conventionalcommits.org/)** (`feat(scope): ...`, `fix(scope): ...`, `refactor: ...`, etc. — see recent `git log`). The bump type is derived from the commit types being shipped:
  - Any commit with a `!` after the type/scope (e.g. `feat!:`) or a `BREAKING CHANGE:` footer → **MAJOR**
  - Any `feat` commit (with no breaking change) → **MINOR**
  - Any `fix` or `perf` commit only (no `feat`, no breaking change) → **PATCH**
  - Only `docs`/`chore`/`refactor`/`test`/`style`/`ci`/`build` commits → **no bump** — these ship without touching `version`/`CHANGELOG.md`
- **The bump happens once per release**, when opening the `dev` → `main` PR — not per-commit. The `ship` skill (`.claude/skills/ship/SKILL.md`) checks the commits being shipped and performs the bump as part of that PR when one is warranted; see that skill for the mechanics.
- **`CHANGELOG.md`** keeps an `[Unreleased]` section at the top that entries accumulate under during normal work. At release time, `[Unreleased]` is retitled to the new `[X.Y.Z] - YYYY-MM-DD` and a fresh empty `[Unreleased]` is added above it.
- **Git tags:** after a version-bumping PR merges to `main`, `.github/workflows/deploy.yml`'s `release` job tags the commit `vX.Y.Z` (read from the root `package.json`) and publishes a GitHub Release using the matching `CHANGELOG.md` section — this is automatic, not a manual step.
- Any agent making changes should add a line under `CHANGELOG.md`'s `[Unreleased]` section when the change is user-facing (matches `feat`/`fix`/`perf`/breaking); purely internal changes (`refactor`/`docs`/`test`/`chore`) don't need an entry.

---

## Dependencies

Do not revert dependency migrations as suspected supply-chain issues without verifying against the official package registry first.

---

<!-- rtk-instructions v2 -->
# RTK (Rust Token Killer) - Token-Optimized Commands

## Golden Rule

**Always prefix commands with `rtk`**. If RTK has a dedicated filter, it uses it. If not, it passes through unchanged. This means RTK is always safe to use.

**Important**: Even in command chains with `&&`, use `rtk`:
```bash
# ❌ Wrong
git add . && git commit -m "msg" && git push

# ✅ Correct
rtk git add . && rtk git commit -m "msg" && rtk git push
```

## RTK Commands by Workflow

### Build & Compile (80-90% savings)
```bash
rtk cargo build         # Cargo build output
rtk cargo check         # Cargo check output
rtk cargo clippy        # Clippy warnings grouped by file (80%)
rtk tsc                 # TypeScript errors grouped by file/code (83%)
rtk lint                # ESLint/Biome violations grouped (84%)
rtk prettier --check    # Files needing format only (70%)
rtk next build          # Next.js build with route metrics (87%)
```

### Test (60-99% savings)
```bash
rtk cargo test          # Cargo test failures only (90%)
rtk go test             # Go test failures only (90%)
rtk jest                # Jest failures only (99.5%)
rtk vitest              # Vitest failures only (99.5%)
rtk playwright test     # Playwright failures only (94%)
rtk pytest              # Python test failures only (90%)
rtk rake test           # Ruby test failures only (90%)
rtk rspec               # RSpec test failures only (60%)
rtk test <cmd>          # Generic test wrapper - failures only
```

### Git (59-80% savings)
```bash
rtk git status          # Compact status
rtk git log             # Compact log (works with all git flags)
rtk git diff            # Compact diff (80%)
rtk git show            # Compact show (80%)
rtk git add             # Ultra-compact confirmations (59%)
rtk git commit          # Ultra-compact confirmations (59%)
rtk git push            # Ultra-compact confirmations
rtk git pull            # Ultra-compact confirmations
rtk git branch          # Compact branch list
rtk git fetch           # Compact fetch
rtk git stash           # Compact stash
rtk git worktree        # Compact worktree
```

Note: Git passthrough works for ALL subcommands, even those not explicitly listed.

### GitHub (26-87% savings)
```bash
rtk gh pr view <num>    # Compact PR view (87%)
rtk gh pr checks        # Compact PR checks (79%)
rtk gh run list         # Compact workflow runs (82%)
rtk gh issue list       # Compact issue list (80%)
rtk gh api              # Compact API responses (26%)
```

### JavaScript/TypeScript Tooling (70-90% savings)
```bash
rtk pnpm list           # Compact dependency tree (70%)
rtk pnpm outdated       # Compact outdated packages (80%)
rtk pnpm install        # Compact install output (90%)
rtk npm run <script>    # Compact npm script output
rtk npx <cmd>           # Compact npx command output
rtk prisma              # Prisma without ASCII art (88%)
```

### Files & Search (60-75% savings)
```bash
rtk ls <path>           # Tree format, compact (65%)
rtk read <file>         # Code reading with filtering (60%)
rtk grep <pattern>      # Search grouped by file (75%). Format flags (-c, -l, -L, -o, -Z) run raw.
rtk find <pattern>      # Find grouped by directory (70%)
```

### Analysis & Debug (70-90% savings)
```bash
rtk err <cmd>           # Filter errors only from any command
rtk log <file>          # Deduplicated logs with counts
rtk json <file>         # JSON structure without values
rtk deps                # Dependency overview
rtk env                 # Environment variables compact
rtk summary <cmd>       # Smart summary of command output
rtk diff                # Ultra-compact diffs
```

### Infrastructure (85% savings)
```bash
rtk docker ps           # Compact container list
rtk docker images       # Compact image list
rtk docker logs <c>     # Deduplicated logs
rtk kubectl get         # Compact resource list
rtk kubectl logs        # Deduplicated pod logs
```

### Network (65-70% savings)
```bash
rtk curl <url>          # Compact HTTP responses (70%)
rtk wget <url>          # Compact download output (65%)
```

### Meta Commands
```bash
rtk gain                # View token savings statistics
rtk gain --history      # View command history with savings
rtk discover            # Analyze Claude Code sessions for missed RTK usage
rtk proxy <cmd>         # Run command without filtering (for debugging)
rtk init                # Add RTK instructions to CLAUDE.md
rtk init --global       # Add RTK to ~/.claude/CLAUDE.md
```

## Token Savings Overview

| Category | Commands | Typical Savings |
|----------|----------|-----------------|
| Tests | vitest, playwright, cargo test | 90-99% |
| Build | next, tsc, lint, prettier | 70-87% |
| Git | status, log, diff, add, commit | 59-80% |
| GitHub | gh pr, gh run, gh issue | 26-87% |
| Package Managers | pnpm, npm, npx | 70-90% |
| Files | ls, read, grep, find | 60-75% |
| Infrastructure | docker, kubectl | 85% |
| Network | curl, wget | 65-70% |

Overall average: **60-90% token reduction** on common development operations.
<!-- /rtk-instructions -->

<!-- code-review-graph MCP tools -->
## MCP Tools: code-review-graph

**IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph MCP tools BEFORE using Grep/Glob/Read to explore
the codebase.** The graph is faster, cheaper (fewer tokens), and gives
you structural context (callers, dependents, test coverage) that file
scanning cannot.

### When to use graph tools FIRST

- **Exploring code**: `semantic_search_nodes` or `query_graph` instead of Grep
- **Understanding impact**: `get_impact_radius` instead of manually tracing imports
- **Code review**: `detect_changes` + `get_review_context` instead of reading entire files
- **Finding relationships**: `query_graph` with callers_of/callees_of/imports_of/tests_for
- **Architecture questions**: `get_architecture_overview` + `list_communities`

Fall back to Grep/Glob/Read **only** when the graph doesn't cover what you need.

### Key Tools

| Tool | Use when |
| ------ | ---------- |
| `detect_changes` | Reviewing code changes — gives risk-scored analysis |
| `get_review_context` | Need source snippets for review — token-efficient |
| `get_impact_radius` | Understanding blast radius of a change |
| `get_affected_flows` | Finding which execution paths are impacted |
| `query_graph` | Tracing callers, callees, imports, tests, dependencies |
| `semantic_search_nodes` | Finding functions/classes by name or keyword |
| `get_architecture_overview` | Understanding high-level codebase structure |
| `refactor_tool` | Planning renames, finding dead code |

### Workflow

1. The graph auto-updates on file changes (via hooks).
2. Use `detect_changes` for code review.
3. Use `get_affected_flows` to understand impact.
4. Use `query_graph` pattern="tests_for" to check coverage.
