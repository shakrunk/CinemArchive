# Theme sync contract

Opt-in sync of the appearance a signed-in user picked, across their browsers and Android devices.
Both clients implement it against `user_prefs.theme_prefs`
(`supabase/migrations/20261024000000_user_prefs_theme_prefs.sql`). The mapping cases are pinned in
[fixtures/theme-sync.json](fixtures/theme-sync.json). The web suite (`apps/web/src/lib/themeSync.test.ts`)
and the Android suite (`ThemeSyncParityTest`) both read that file, so the two mappings can't drift.

## Payload

```json
{ "palette": "catppuccin-mocha", "mode": "system", "pureBlack": true, "updatedAt": "2026-10-24T10:00:00.000Z" }
```

| Field | Values |
|-------|--------|
| `palette` | `brand`, `material-you`, `noir`, `matrix`, `catppuccin-latte`, `catppuccin-frappe`, `catppuccin-macchiato`, `catppuccin-mocha` |
| `mode` | `system`, `light`, `dark`. `system` is sent as itself, never as the theme it currently resolves to. |
| `pureBlack` | boolean (the AMOLED toggle) |
| `updatedAt` | ISO-8601 instant of the user's last appearance change on the writing device |

A payload with a missing field, an unknown `palette` or `mode` (e.g. from a newer client), a
non-boolean `pureBlack` or an unparseable `updatedAt` is ignored entirely: it is neither applied
nor overwritten.

## Encoding (local → payload)

- **Web:** themes `dark`/`light` → `brand`; every other theme id is the palette as-is. `mode` is
  the store's `themeMode`.
- **Android:** `ArchivePalette` → kebab-case (`MATERIAL_YOU` → `material-you`,
  `CATPPUCCIN_MOCHA` → `catppuccin-mocha`). `mode` is `ArchiveThemeMode` lowercased.

## Applying (payload → local)

`pureBlack` always applies. The palette applies like this:

| Remote palette | Web | Android |
|----------------|-----|---------|
| `brand` | `themeMode = mode`. The theme is the OS scheme for `system`, otherwise `mode`. | `BRAND`, and the mode applies |
| `material-you` | Keeps its current theme and mode (no web equivalent) | `MATERIAL_YOU`, and the mode applies |
| `noir`, `matrix` | Applies only if unlocked on this device, else keeps the current theme. `themeMode` → `dark`. | Applies only if unlocked, else keeps the current palette. The mode is kept. |
| `catppuccin-*` | Theme = flavor; `themeMode` → `light` for Latte, `dark` otherwise | The flavor applies. The mode is kept, since fixed palettes ignore it. |

## Sync rules

- **Opt-in, per device.** Off by default; stored locally, never synced itself.
- **Owner only.** Sync runs for the signed-in auth user (a friend view still syncs the viewer's own
  appearance). It never runs for anonymous share links or the web's dev mock user, and never reads
  another user's row.
- **Local stamp.** Each device keeps the `updatedAt` of its current appearance. Only a user-made
  change sets it to now and pushes. The web changes are a theme pick, the light/dark/system toggle,
  the `T` key and the Pure black switch; on Android they are `setThemeMode`, `setPalette` and
  `setPureBlack`. Rehydrating, following an OS scheme change, or applying a remote payload never
  pushes. Applying a remote payload sets the local stamp to the remote one.
- **Reconcile** (on enabling sync, on sign-in and on each app launch):
  - remote newer than the local stamp: apply it;
  - local newer, or no remote yet: push local;
  - equal stamps: nothing.
  - A failed fetch, a missing `theme_prefs` column (before the migration is deployed) or an
    invalid payload leaves both sides untouched.
- **Writes** upsert `{ user_id, theme_prefs }` only (PostgREST `merge-duplicates`), so
  `ledger_layout` is never touched. A failed push is dropped; the next reconcile retries it.
- **Privacy.** The friend read policy exposes the whole `user_prefs` row. `theme_prefs` holds
  nothing sensitive, and friend/shared reads select `ledger_layout` only.
