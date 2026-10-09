import assert from 'node:assert/strict'
import { test } from 'node:test'
import { releaseVersion } from './release-version.mjs'

test('preserves existing Android version codes and numeric release ordering', () => {
  assert.deepEqual(releaseVersion('1.33.1'), { version: '1.33.1', versionCode: 1_033_001 })
  assert.ok(releaseVersion('1.999.999').versionCode < releaseVersion('2.0.0').versionCode)
  assert.equal(releaseVersion('2100.0.0').versionCode, 2_100_000_000)
})

test('rejects ambiguous tags, shell content, collisions and Android overflow', () => {
  for (const version of [null, 123, '', '1.2', 'v1.2.3', '01.2.3', '1.02.3', '1.2.03',
    '1.2.3-beta', '1.2.3+build', '1.2.3\n', '1.2.3;echo bad', '0.0.0', '1.1000.0',
    '1.0.1000', '2100.0.1', '9999999999999999999999.0.0']) {
    assert.throws(() => releaseVersion(version), Error, String(version))
  }
})
