# Offline Android license notices

The Credits pane's **View license texts & notices** screen reads
`open_source_licenses.json` from APK assets. Each variant generates this file with
`GenerateLicenseNotices` in `buildSrc`, wired through Android's generated-assets API.
No network request is made by the viewer or generator; Gradle resolves dependencies
normally before generation. Test dependencies and build plugins are excluded.

The generator uses the actual runtime artifact/POM graph, groups Apache-licensed
libraries with the complete license below, extracts LICENSE/NOTICE/COPYING files
(including nested JARs), and decodes Google's embedded third-party notice records.
Unknown license declarations, missing POMs, and malformed notice ranges fail the
build instead of silently dropping disclosures. Proprietary Google SDK terms stay
linked in Credits; their embedded open-source notices are still bundled.

When updating dependencies, run both variants' generators and inspect the output:

```sh
rtk sh ./gradlew :app:generateDebugLicenseNotices :app:generateReleaseLicenseNotices :buildSrc:test
```

If a new license is reported, obtain its full text from the relevant upstream
release, retain its attribution/patent notices, and add an explicit mapping in
`GenerateLicenseNotices`. Do not broadly exempt a dependency group. The four version-pinned
Apache overrides cover POMs without direct license declarations: `javax.inject`,
Guava `listenablefuture`, Auto Value annotations, and ZXing Core. Review them when upgrading.

## Vendored text sources

Retrieved 2026-10-16. Text files are retained verbatim; refresh them deliberately
when fonts or the corresponding upstream licenses change.

- `Apache-2.0.txt`: https://www.apache.org/licenses/LICENSE-2.0.txt
- `Fraunces-OFL.txt`: https://raw.githubusercontent.com/google/fonts/main/ofl/fraunces/OFL.txt
- `Hanken-Grotesk-OFL.txt`: https://raw.githubusercontent.com/google/fonts/main/ofl/hankengrotesk/OFL.txt
- `DM-Mono-OFL.txt`: https://raw.githubusercontent.com/google/fonts/main/ofl/dmmono/OFL.txt
- `Lexend-OFL.txt`: https://raw.githubusercontent.com/google/fonts/main/ofl/lexend/OFL.txt
- `libyuv-LICENSE.txt`: https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/LICENSE
- `libyuv-PATENTS.txt`: https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/PATENTS

The font licenses include each font's copyright statement. CameraX declares
libyuv separately from its own Apache license. Embedded notices are taken from
the exact resolved dependency binaries rather than these upstream URLs.

Parity: Android-specific dependency disclosures; the web dependency set is separate.
