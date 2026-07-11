# ADR 0001: Native Android foundation and provisional identifiers

- **Status:** Accepted for foundation spike
- **Date:** 2026-07-11

## Decision

Create a separate `android/` Gradle project with Kotlin, Jetpack Compose, Material 3, Room, a single activity, and a layered module boundary. The app uses `work.kumarfamilynet.cinemarchive` as a provisional namespace and application ID, API 36 as `compileSdk`/`targetSdk`, and Android 12 (API 31) as the initial minimum API.

The build uses AGP 8.9.1 and Gradle 8.11.1 because these are the newest compatible artifacts reachable in the local build environment. Upgrade to the then-current stable AGP only after the repository can resolve it and CI verifies the upgrade.

## Consequences

- The product/Play owner must confirm ownership of the provisional application ID before any distributed build. Changing it after Play publication creates a new app identity.
- UI reads through a Room-backed repository; direct Supabase calls remain out of Compose until the Android contract and RLS test environment are ready.
- The first database schema is versioned at 1 and has exported schema history enabled. Any change requires a migration test before release.
