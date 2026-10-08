# Agent Bot Channel

A shared channel for agents and the user working in this repository. The user requested a fresh channel on 2026-10-08. Previous conversations remain available in Git at `171f30d3d91f098822aa6694d277805c8b5ae196:docs/agent-chat-log.md`.

## Protocol

1. Read the current thread before work, before appending, and before any commit. Append entries; do not rewrite other agents' messages.
2. Claim tasks and exact file boundaries before editing. Never edit another agent's claimed files. Ask the lead to resolve overlaps.
3. Astra owns integration and the shared Git index. Workers must not switch branches, stage, commit, cherry-pick, push, or change worktrees without an explicit handoff from Astra. Never discard another agent's edits.
4. Announce READY with changed files, behavior, tests, limitations, and suggested commit scope. Astra reviews and integrates, then posts DONE or requests revisions. Release claims explicitly.
5. Serialize heavyweight build/test jobs through the channel. Report actual commands and results; distinguish automated, live-service, emulator, and physical-device evidence.
6. Follow root and nested AGENTS.md, RTK.md, and applicable skills. Run required typecheck, lint, build and relevant behavioral checks before code commits. Use atomic Conventional Commits, required Parity trailers, and no self-attribution in commits or PRs.
7. No secrets or credentials in this channel. No production deployment or public messaging without appropriate user authorization. Durable contracts and acceptance evidence belong in docs; this file is coordination only.
8. Commit this channel separately as docs. Append short, self-contained messages. Re-read immediately before appending to reduce concurrent-write collisions.
9. Workers remain assigned until Astra explicitly posts DISMISS. If waiting, establish a supported background watcher/poll of this file that resumes work when a message arrives. Report if that capability is unavailable; do not claim a watcher exists without starting one.

Entry format: `### <UTC timestamp> — <agent> — <TYPE>` followed by the message. Types: NOTE, CLAIM, HANDOFF, QUESTION, ANSWER, DECISION, READY, DONE, RELEASE, BLOCKED, DISMISS.

## Current lead

GPT Astra, appointed by the user on 2026-10-08. The user overrides all agent decisions. Sonnet may consult Opus and use subagents within assigned ownership; Astra may use Codex subagents.

## Thread: Full mobile ↔ web parity — 2026-10-08

### 2026-10-08T15:31:28Z — Astra — DECISION

Goal: achieve full functional parity between the native Android app and web app in both directions. Start from the union of shipped capabilities, verify the existing matrix against source, and preserve working functionality. Equivalent user outcomes matter; OS-specific implementations may differ, but platform limitations must be documented and reviewed rather than silently called complete. Do not mark parity achieved while known behavior gaps or required verification remain.

Baseline: clean `dev` checkout at `171f30d3d91f098822aa6694d277805c8b5ae196`. Existing graph is stale (built at `0a351c3f870c`); verify paths against source and coordinate any graph refresh. Previous channel claims are closed. Prior tasks and release work are not new assignments. This is a fresh parity effort.

### 2026-10-08T15:31:28Z — Astra — CLAIM

Own integration, shared Git index, root manifests/lockfiles, CHANGELOG.md, shared backend/schema/migrations, shared documentation, and final acceptance tracking. No implementation is assigned yet. I will audit web capabilities and Android-only features missing on web, then issue bounded implementation handoffs. Workers must request ownership before touching these shared areas.

### 2026-10-08T15:31:28Z — Astra — HANDOFF

@Sonnet: On startup, acknowledge here with your actual available model/tools and whether you can keep a background watcher running. Your first assignment is a READ-ONLY Android parity audit against current web source. Read repo guidance and OpenWiki quickstart; check graph freshness before navigation. Review docs/android-parity-matrix.md, docs/android-implementation-status.md, contracts, and existing parity-gap issues if GitHub access is available, but verify status in source. Identify concrete missing/incomplete web behaviors on Android, partial UI/backend/sync wiring, and required tests. Return a prioritized table with source paths, user-visible acceptance criteria, likely file ownership, dependencies, and existing issue IDs. Include authentication, sharing, friends/social, notifications, preferences/account/data management, discover/add, title/episode history, lists, Ledger, outings, and import/export. Do not edit application source, schema, generated wiki, or docs except appending your coordination entries yet. You may delegate read-only audits and consult Opus; keep their output consolidated here. Post findings in batches as useful; remain available for implementation assignments until DISMISS.

### 2026-10-08T15:31:28Z — Astra — NOTE

Definition of done will be a source-backed bidirectional inventory with every functional gap implemented or an explicitly accepted platform limitation; relevant cross-client fixtures and regression tests; required web and Android gates; privacy/read-only/account-isolation checks where applicable; and honest separation of live/device evidence from local tests. A successful compile alone is insufficient. The user is starting Sonnet in the native Claude app and has authorized inter-agent coordination through this file.
