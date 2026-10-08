# Developer tooling and version ownership

Related release work: [#278](https://github.com/shakrunk/CinemArchive/issues/278).

## Package versions

The repository-root `package.json` is the only application release version. Vite reads it
for `__APP_VERSION__`; the release workflow uses it for tags and Android version fields.
The private, unpublished `apps/web` package does not need an independent version field:
[npm makes version optional for unpublished packages](https://docs.npmjs.com/cli/v10/configuring-npm/package-json/#version).
Its lockfile records dependencies, not a second release version. Do not update the root
version during ordinary work; follow `AGENTS.md` at the `dev` → `main` release boundary.

## Code graph

Use the installed `code-review-graph` CLI before text search for structural questions.
MCP registration and auto-update hooks are client-local capabilities, not guaranteed by
this repository. Check freshness explicitly:

```bash
rtk code-review-graph status
rtk code-review-graph search CommandPalette --limit 5
rtk code-review-graph query tests_for CommandPalette
rtk code-review-graph detect-changes --base HEAD
```

If a short target is ambiguous, copy the exact `qualified_name` from `search` into `query`.

`detect-changes` reads the existing graph; it does not refresh it. If the index is current
apart from a known change range, use `rtk code-review-graph update --base <commit>`.
After a layout move or a stale/unknown baseline, rebuild with
`rtk code-review-graph build`, then confirm `status` and search results refer to `apps/web/`
and `apps/android/`, not obsolete root `src/` paths. The local `.code-review-graph/` cache
is ignored; do not commit generated database files. Do not enable cloud embeddings just
to refresh structural data.

The October 7, 2026 rebuild replaced the July index that still referenced root `src/`
paths. A search now resolves the web command palette and its regression test under
`apps/web/src/components/`. Rebuilding this cache does not install or prove any client hook.

On Windows, `--brief` may fail when a legacy output encoding cannot render the Unicode
summary panel. Omit it for JSON output or configure a UTF-8 terminal. This is an output
encoding issue, not proof the index is healthy. A graph that omits new files is not evidence
of zero impact; fall back to targeted source/test inspection.

The separate [GitHub agent instruction](../.github/code-review-graph.instruction.md) points
to these CLI commands. Optional local hooks should be tested in their actual client; do not
assume they run in Codex just because another client has them installed.

## Verification

Use the explicit web scripts and browser commands in [release readiness](release-readiness.md).
`tsconfig.node.json` includes Vite, Vitest, Playwright and browser test sources so configuration
errors fail typecheck before the runner starts. Generated browser output is ignored by Git
and ESLint. The lint warning budget is enforced separately from ordinary diagnostic lint.

Generated OpenWiki output is refreshed by its existing scheduled workflow from source and
normative docs. Fix these inputs first; hand edits to generated prose would be overwritten.
