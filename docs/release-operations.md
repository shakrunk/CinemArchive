# Release artifacts and partial-publication recovery

The `main` deployment workflow validates web and Android before applying production
migrations. Android runs debug assembly, lint and JVM tests, then signed release assembly.
Missing/empty signing material fails the job. `apksigner verify --verbose --print-certs`
checks the resulting APK; the runner records its SHA-256 digest and stages the APK plus
`SHA256SUMS` as `android-release` for seven days. Signing material is removed in an
`always()` cleanup step. The staging job does not have repository write permission.

The root `package.json` version is read by `scripts/release-version.mjs`. Only stable
`MAJOR.MINOR.PATCH` values are accepted. Android retains the existing encoding
`major * 1,000,000 + minor * 1,000 + patch`; minor/patch must be below 1,000, and the
result must be between 1 and Android's 2,100,000,000 maximum. Invalid versions fail before
migration or publication. Run `node --test scripts/release-version.test.mjs` at the repo
root; the web gate runs these checks too. This validates encoding, not release bump policy
or a device's upgrade history.

Pages publishes only after both builds and migration succeed. For a new tag, the release
job downloads the staged APK, verifies `SHA256SUMS`, and creates the GitHub Release with
both files. It never rebuilds the APK after deployment. When the tag already exists,
publication is skipped; Android validation and signed staging still run for that commit.

## Verify a downloaded release

Download the APK and `SHA256SUMS` from the **same** release into an empty directory.
On Linux/macOS with the corresponding checksum tool, run `sha256sum --check SHA256SUMS`
(macOS may use `shasum -a 256 --check SHA256SUMS`). On Windows, compare
`Get-FileHash -Algorithm SHA256 <apk>` against the digest recorded for that exact filename.
With Android SDK build-tools installed, also run `apksigner verify --verbose --print-certs <apk>`.

A checksum confirms file integrity; a valid signature alone does not establish the expected
publisher identity. Compare its certificate fingerprint with an independently approved
release-signing record before distribution. Signing ownership, certificate attestation,
SBOM/provenance and store identity remain under #271/#273; this pipeline does not invent
that trust record. See [Android's apksigner reference](https://developer.android.com/tools/apksigner).

## Failure and recovery

- **Validation/build/signing failed:** migration and publication have not started. Fix
  the cause, rerun checks, and rerun the workflow for the intended `main` commit.
- **Migration failed:** Pages/tag publication are blocked, but inspect migration state
  before retrying; a database change is not automatically rolled back. Use the reviewed
  database recovery procedure and retain evidence of applied migration versions.
- **Pages failed after migration:** the database may already be newer than the deployed
  client. Confirm compatibility, then rerun the failed deployment. Do not reset migration
  history to make the workflow appear successful.
- **Tag/release API or asset upload failed:** inspect the tag's commit, Pages deployment,
  GitHub Release and assets first. An existing tag causes the automatic publication step
  to skip on rerun. Recover the verified `android-release` artifact from that exact run,
  check its digest and signature, and complete the missing release/assets using the
  approved release procedure. Do not move a published tag, overwrite an existing APK,
  or substitute an APK from another commit. If the seven-day artifact expired, prepare
  a reviewed recovery release; do not assume a rebuild is byte-for-byte identical.

GitHub/database/Pages publication is still not transactional. Rollback rehearsal, named
operators, production approval rules, smoke tests and backup/restore evidence remain
release prerequisites in #271/#276. No deployment or signing rehearsal is implied by local
workflow linting. See [release readiness](release-readiness.md) for current evidence.
