# Release readiness and verification

The [public release tracker (#279)](https://github.com/shakrunk/CinemArchive/issues/279)
owns launch approval. This document records implemented checks and their limits; it does
not declare the web app, Android client, or shared backend ready for public release.

## October 2026 implementation scope

All ten prerequisite issues (#269–#278) were reviewed. The selected engineering slices are:

| Issue | Implementation in this pass | Still required |
| --- | --- | --- |
| [#271](https://github.com/shakrunk/CinemArchive/issues/271) | Web PR/pre-deploy gates; Android build/lint/unit tests and verified signed APK before migration; checksummed release assets; job-level permissions and main-only deployment | Protected-branch required checks/reviews, environment approvals, fully pinned release tooling, signing-identity attestation/SBOM, production smoke and rollback rehearsal |
| [#275](https://github.com/shakrunk/CinemArchive/issues/275) | Production-bundle browser regressions and compatibility checklist; command-palette focus restoration | Live auth/invite, backend authorization, cross-client sync, device and accessibility sign-off |
| [#277](https://github.com/shakrunk/CinemArchive/issues/277) | External synchronous bootstrap scripts; Pages fallback recovery with restricted storage and same-origin redirect validation | CSP/response headers and hosting controls, PWA/social assets, performance/accessibility audits, production smoke tests |
| [#278](https://github.com/shakrunk/CinemArchive/issues/278) | Explicit typecheck/lint-budget commands; media/ICS typing cleanup reducing warnings from 78 to 48; single package-version source; restored RTK entrypoint; corrected CLI graph instructions and rebuilt stale local index | Remaining typing debt, development dependency remediation, Android tooling alignment, generated documentation refresh |

The remaining issues require private security work (#269), backend contract infrastructure
(#270), privacy/owner approval (#272), production Android identity/distribution (#273),
Android scope decisions (#274), and production operations (#276). The rest of public web hardening (#277) also remains required.
They remain release prerequisites, not optional follow-up work. No issue is closed by this pass.

## Web gates and deployment order

From `apps/web`, use Node 22.23.2 and run:

```bash
npm ci
npm run typecheck
npm run lint:ci
npm run test
npm audit --omit=dev --audit-level=moderate
npm run build
npx --no-install playwright install chromium firefox webkit
npm run test:e2e
```

`.github/workflows/web.yml` runs on all PRs targeting `dev`/`main` and pushes to `dev`.
It uses a read-only token, pinned action commits, the lockfile, and no production secrets.
Configure its `Verify web` job as a required status check in repository rules after its
first successful GitHub run. Adding a workflow alone does not enable branch protection.

`deploy.yml` calls the same validation workflow and builds the production web artifact.
It also validates the root package version, then runs Android debug assembly, lint and JVM
tests, builds a signed release APK and checks it with `apksigner verify`. The APK and its
SHA-256 checksum are staged as a seven-day workflow artifact. **Both client builds must
succeed before production database migration**, which precedes Pages deployment.
Only `main` can enter this chain, including manual dispatch. Pages and OIDC write permissions
belong only to the deploy job; Android staging uses a read-only repository token.

After Pages deployment, a new version is tagged and published with the already-built APK
and `SHA256SUMS` in the release creation command. Downloaded checksums are checked again
before tagging. Existing tags skip publication, but still run all validation and signed-build
gates before a web deployment. This avoids Android build failures causing partial releases;
it does not make database, Pages, git and release API updates atomic. API/deployment failures
can still leave partial publication. See [release operations](release-operations.md) for
recovery, artifact verification and the remaining sign-off requirements. Edge Functions
deploy independently. `db-migrate.yml` remains a separate manual recovery/operations path.

## Browser coverage and compatibility sign-off

`npm run test:e2e` builds a production bundle in `dist-e2e/`, starts its own preview server
on `127.0.0.1:4178`, and runs the browser versions bundled with the locked Playwright package.
It explicitly clears the two Supabase build variables, uses isolated browser contexts, and
blocks external page requests. Local `.env.local` credentials do not enable live access.
The test build is separate from `dist/`, which is the only Pages upload directory.

The suite exercises deep-link refresh, local list creation/deletion and browser Back,
theme persistence/system preference changes, keyboard palette focus, and service-worker
offline reload with a lazy view. It uses real UI actions rather than exposing a test-only
store API. Local list tests verify browser persistence, not remote CRUD or authorization.
The actual Pages 404 document is also tested for route restoration, unavailable session
storage, and stale external redirects; see [web startup](web-startup.md).

| Target | Automated coverage | Required manual release evidence |
| --- | --- | --- |
| Chromium desktop | All browser smoke scenarios | Stable Chrome and Edge with production configuration |
| Firefox desktop | UI smoke scenarios; offline worker test skipped | Stable Firefox auth, offline reload, keyboard and private-window storage behavior |
| WebKit desktop | UI smoke scenarios; offline worker test skipped | Actual macOS/iOS Safari including offline reload; emulation does not certify those platforms |
| Chromium mobile (Pixel 7 viewport) | All browser smoke scenarios | Physical Android browser, touch targets and installed PWA |
| Native Android API 31+ | Existing JVM/build/lint gates only | Physical-device auth/deep links, upgrade, process death, sync/outbox and font scaling |

This is a test target matrix, not an approved support promise. For each release candidate,
record the commit, OS/device and browser version, date, tester, result, and evidence link.
Product/release owners must approve the final supported matrix under #275/#274.

- [ ] Live invite, passkey/magic-link authentication and expired-session recovery
- [ ] Owner CRUD, import/export round trip, friends, revoked/expired shares and blocked users
- [ ] Poor networking, concurrent changes, partial failures and cross-device sync
- [ ] Service-worker update prompt, rollback, cache isolation and shared-view clearing
- [ ] PWA installation, private browsing, keyboard/focus, screen reader, contrast and large text
- [ ] Native Android instrumentation and physical-device upgrade/offline coverage

The Web job installs browser engines and Linux prerequisites, runs the suite with no retries,
and retains HTML reports plus failure screenshots/traces for 14 days, including failed runs.
Inspect locally with `npx --no-install playwright show-report`. CI success must still be
recorded after pushing; a local Windows pass does not certify the GitHub Ubuntu runner.
See [Playwright configuration](https://playwright.dev/docs/test-configuration) and
[browser projects](https://playwright.dev/docs/test-projects) for runner configuration.

Offline service-worker navigation is automated only in the two Chromium projects.
[Playwright documents Chromium-only service-worker support](https://playwright.dev/docs/service-workers);
its [WebKit offline navigation issue](https://github.com/microsoft/playwright/issues/42775)
also reproduces with a synthetic response. Local Firefox/WebKit offline-emulation failures
are explicitly skipped, not counted as passes or evidence of production offline behavior.
Their physical-browser offline checks remain release requirements.

## Audit and warning policy

`lint:ci` rejects increases above the checked-in warning budget in `apps/web/package.json`.
Remaining warnings must stay visible and the budget should decrease with each cleanup.
A passing lint gate with warnings is not a zero-debt result.
See [developer tooling](developer-tooling.md) for graph freshness checks and package-version
ownership. The initial baseline was 78 warnings; typing cleanup of `media.ts` and `ics.test.ts` lowered the budget to 48 (see [web tooling debt](web-tooling.md)).

The production dependency audit blocks moderate-or-higher findings. Run full `npm audit`
when changing tooling and at release review as well: development dependency findings remain
tracked in #278 and are not covered by the production-only CI gate. The initial October 7,
2026 check reported nine full-audit findings; the published `braces` release had no fixed
version. Do not force a Tailwind major upgrade solely to silence audit output. Review
upstream fixes and compatibility, keep the lockfile coherent, and record fresh audit evidence
before closing #278.

## Local evidence — October 7, 2026

Verified on Windows with Node 22.23.2 and the committed npm dependency resolutions:

- Clean `npm ci` succeeded; the final metadata-only lockfile refresh changed no dependencies.
- Typecheck, budgeted lint (78 warnings, zero errors), and production build passed.
- Vitest: 240 tests passed across 29 files, including the new focus-restoration regression.
- Playwright: 22 passed, two documented offline-emulation skips across four projects.
- Production dependency audit: zero findings. Full tooling audit is still unresolved under #278.
- Release workflow YAML parses and dependency ordering was checked locally; hosted CI has not run for these local commits.
- Cross-client parity declaration passed for the web-only focus fix.

These are local engineering results, not production, Android-device, or final launch approval.

## Follow-up evidence — October 8, 2026

- Web typecheck, lint (48 warnings, zero errors), production build and all 240 unit tests pass.
- Browser suite: 34 passed, two documented offline-emulation skips, including 12 new
  Pages fallback checks. An earlier run spanned an overnight host pause and timed out;
  the completed rerun is the reported evidence. Unit timeouts during overlapping checks
  also cleared when the unit suite was rerun separately.
- Android `:app:assembleDebug :app:lintDebug testDebugUnitTest` succeeds locally with
  the existing Gradle cache (524 tasks, 523 up-to-date). The SDK XML/tool-version warning
  remains tooling debt; this run does not certify production signing or physical devices.
- Two Node release-version tests cover stable versions, ordering, collisions and overflow.
- Actionlint 1.7.12 validates both changed workflows (ShellCheck unavailable); local DAG
  assertions confirm both client builds precede migration and publication, even for existing tags.
- The production signing/checksum/publication path still needs hosted CI evidence. No
  production migration, Pages deploy, release tag, or GitHub Release was performed locally.

## Documentation maintenance

This file, `CONTRIBUTING.md`, and the client READMEs describe current executable gates.
Generated `openwiki/` pages are refreshed by `.github/workflows/openwiki-update.yml`; do not
hand-edit generated pages to fix operational instructions. Its next regeneration must pick
up these sources and remove the stale claim that web PR checks are absent. Historical audit
results are evidence for their original commit only, not current release sign-off.
