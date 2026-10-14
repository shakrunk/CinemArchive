---
applyTo: '**'
description: >-
  Use the installed code-review-graph CLI for token-efficient
  codebase exploration and code review.
---

## Code-review-graph

**IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph CLI BEFORE using file/search tools to
explore the codebase.** The graph is faster, cheaper (fewer
tokens), and gives you structural context (callers, dependents,
test coverage) that file scanning cannot.

Start with `rtk code-review-graph status`. MCP registration and hooks are optional,
client-local integrations; neither is guaranteed here. Use the CLI directly:

```bash
rtk code-review-graph search CommandPalette --limit 5
rtk code-review-graph query callers_of CommandPalette
rtk code-review-graph query tests_for CommandPalette
rtk code-review-graph detect-changes --base HEAD
rtk code-review-graph architecture
```

Read `docs/developer-tooling.md` for stale-index recovery and Windows output-encoding
limitations. `detect-changes` never refreshes the index. Fall back to targeted source
inspection when graph coverage is missing or stale; do not infer safety from an empty result.
