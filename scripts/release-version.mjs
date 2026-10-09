import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

// Keep the existing Android encoding, rejecting inputs that would collide or overflow.
export function releaseVersion(version) {
  if (typeof version !== 'string' || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)) {
    throw new Error('Release version must be a stable MAJOR.MINOR.PATCH version')
  }
  const [major, minor, patch] = version.split('.').map(Number)
  const versionCode = major * 1_000_000 + minor * 1_000 + patch
  if (minor >= 1000 || patch >= 1000 || !Number.isSafeInteger(versionCode) || versionCode < 1 || versionCode > 2_100_000_000) {
    throw new Error('Release version exceeds the supported Android versionCode encoding')
  }
  return { version, versionCode }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
  const { version, versionCode } = releaseVersion(pkg.version)
  process.stdout.write(`version=${version}\nversion_code=${versionCode}\n`)
}
