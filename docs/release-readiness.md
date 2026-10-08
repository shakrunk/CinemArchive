# Release readiness and verification

The [public release tracker (#279)](https://github.com/shakrunk/CinemArchive/issues/279)
owns launch approval. This document records implemented checks and their limits; it does
not declare the web app, Android client, or shared backend ready for public release.

## October 2026 implementation scope

All ten prerequisite issues (#269–#278) were reviewed. The selected engineering slices are:

| Issue | Implementation in this pass | Still required |
| --- | --- | --- |
| [#271](https://github.com/shakrunk/CinemArchive/issues/271) | Web PR validation, reusable pre-deploy validation, web build before migration, job-level Pages permissions, main-only deployment | Protected-branch required checks/reviews, environment approvals, fully pinned release tooling, Android validation before publication, signed-artifact provenance, production smoke and rollback rehearsal |
| [#275](https://github.com/shakrunk/CinemArchive/issues/275) | Next slice: browser regression foundation and compatibility checklist | Live auth/invite, backend authorization, cross-client sync, device and accessibility sign-off |
| [#278](https://github.com/shakrunk/CinemArchive/issues/278) | Explicit typecheck and lint-budget commands; targeted typing cleanup assigned separately | Remaining typing debt, development dependency remediation, Android tooling alignment, generated documentation refresh |

The remaining issues require private security work (#269), backend contract infrastructure
(#270), privacy/owner approval (#272), production Android identity/distribution (#273),
Android scope decisions (#274), production operations (#276), and public web hardening (#277).
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
```

`.github/workflows/web.yml` runs on all PRs targeting `dev`/`main` and pushes to `dev`.
It uses a read-only token, pinned action commits, the lockfile, and no production secrets.
Configure its `Verify web` job as a required status check in repository rules after its
first successful GitHub run. Adding a workflow alone does not enable branch protection.

`deploy.yml` calls the same validation workflow, builds the production web artifact, then
applies pending database migrations before deploying that artifact. Only `main` can enter
this chain, including manual dispatch. Pages and OIDC write permissions belong only to the
deploy job. A validation/build failure prevents production migration and publication.

Release tagging still follows Pages deployment, and the signed Android APK is built after
tagging. This remains a partial-release risk under #271; these web gates do not establish
an atomic cross-client release. Edge Functions deploy independently. `db-migrate.yml` is a
separate manual recovery/operations path, not the only way migrations reach production.

## Audit and warning policy

`lint:ci` rejects increases above the checked-in warning budget in `apps/web/package.json`.
Remaining warnings must stay visible and the budget should decrease with each cleanup.
A passing lint gate with warnings is not a zero-debt result.

The production dependency audit blocks moderate-or-higher findings. Run full `npm audit`
when changing tooling and at release review as well: development dependency findings remain
tracked in #278 and are not covered by the production-only CI gate. The initial October 7,
2026 check reported nine full-audit findings; the published `braces` release had no fixed
version. Do not force a Tailwind major upgrade solely to silence audit output. Review
upstream fixes and compatibility, keep the lockfile coherent, and record fresh audit evidence
before closing #278.

## Documentation maintenance

This file, `CONTRIBUTING.md`, and the client READMEs describe current executable gates.
Generated `openwiki/` pages are refreshed by `.github/workflows/openwiki-update.yml`; do not
hand-edit generated pages to fix operational instructions. Its next regeneration must pick
up these sources and remove the stale claim that web PR checks are absent. Historical audit
results are evidence for their original commit only, not current release sign-off.
