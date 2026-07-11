---
name: ship
description: Run typecheck, lint, and build; if green, commit atomically, push, and open a dev->main PR. Use when the user asks to "ship", "ship it", or wants the current changes verified, committed, and turned into a PR in one go.
---

# Ship

Verify the working tree, then commit and open a PR — but only if verification passes.

## Steps

1. **Verify.** Run, in order, stopping immediately if any fails:
   - `npm run build -- --mode=typecheck` is not a real script — instead run typecheck via `npx tsc --noEmit` (or `npm run typecheck` if that script exists in `package.json`)
   - `npm run lint`
   - `npm run build`

   If any gate fails, stop, report the failure, and fix it (or ask the user) before continuing. Do not commit or push on a red gate.

2. **Review the diff.** Run `git status` and `git diff --stat` to see what changed. Group changes into atomic, logically-coherent commits (don't lump unrelated changes into one commit). Check for CRLF/LF normalization noise that might be swallowing unrelated changes.

3. **Commit.** For each logical group, stage only the relevant files (avoid `git add -A`/`git add .` when the changes aren't all related) and commit with a professional message describing *why*, not just *what*. Never add `Co-Authored-By: Codex`, "Generated with Codex", or any self-attribution trailer — this repo enforces `attribution.commit`/`attribution.pr` as empty, but follow it even when editing messages by hand.

4. **Check whether this release needs a version bump.** This only applies when the target of the PR is `main` (a release), not to intermediate commits landing on `dev`. See `AGENTS.md` → **Versioning** for the full policy; the check itself:
   - List the commits that will ship in this PR: `git log --oneline origin/main..HEAD` (or `origin/main...dev` if pushing from `dev`).
   - Classify by [Conventional Commits](https://www.conventionalcommits.org/) type prefix:
     - Any `!` after type/scope, or a `BREAKING CHANGE:` footer, anywhere in the range → **MAJOR**
     - Else any `feat` commit → **MINOR**
     - Else any `fix`/`perf` commit → **PATCH**
     - Else (only `docs`/`chore`/`refactor`/`test`/`style`/`ci`/`build`) → **no bump** — skip the rest of this step
   - If a bump is warranted: bump `version` in `package.json` per semver, retitle `CHANGELOG.md`'s `[Unreleased]` heading to `[X.Y.Z] - <today's date>` (backfilling entries from the shipped `feat`/`fix`/`perf` commit subjects if `[Unreleased]` is empty), and add a fresh empty `[Unreleased]` section above it. Commit this as its own atomic commit, e.g. `chore(release): bump version to X.Y.Z`.
   - Do not create git tags or GitHub Releases yourself — `.github/workflows/deploy.yml`'s `release` job does that automatically once this lands on `main`.

5. **Push.** Push the current branch to its remote (create the upstream if it doesn't exist yet).

6. **Open a PR from the current branch into `main`.** Confirm branch topology first — if the current branch is `dev` and commits already exist there, target `main`. Use `gh pr create` with a concise title (<70 chars) and a body with a `## Summary` and `## Test plan` section, again with no self-attribution. If a version bump was made, mention the new version in the PR body.

7. Report the PR URL back to the user.

## Notes

- Never skip verification gates to save time — a red gate blocks commit/push/PR entirely.
- Never use `--no-verify`, `--no-gpg-sign`, or force-push unless the user explicitly asks.
- Confirm before pushing/opening a PR is generally expected in this workflow — this skill is the explicit go-ahead to do so when invoked, but still surface what you're about to push/open.
- The version-bump check (step 4) only fires on a release PR (targeting `main`). Shipping intermediate work to `dev` should not touch `version`/`CHANGELOG.md`.
