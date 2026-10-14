# RTK command entrypoint

Prefix repository shell commands with `rtk`. The authoritative command catalogue lives
in [AGENTS.md](AGENTS.md#rtk-rust-token-killer---token-optimized-commands); this file restores
the `@RTK.md` reference used by local agent instructions without duplicating that catalogue.

Use `rtk proxy <command>` when full output is needed or a wrapper does not support an
option. For example:

```bash
rtk git status --short
rtk git diff --stat
rtk proxy git diff
rtk npm run typecheck --prefix apps/web
rtk npm run lint:ci --prefix apps/web
rtk npm run test --prefix apps/web
rtk npm run build --prefix apps/web
```

Graph discovery uses the installed CLI; an MCP registration is not required. See
[developer tooling](docs/developer-tooling.md) for commands and stale-index recovery.
