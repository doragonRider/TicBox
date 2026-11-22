#!/usr/bin/env node
/** Packages the validated release pair by default, with source and APK digest bindings. */
import { appendFile, copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { root, inspectApks, sourceDigest, sha256 } from './android-apks.mjs';

const channel = process.argv[2] ?? 'release';
if (process.argv.length > 3 || !['release', 'signed', 'debug', 'ci-debug'].includes(channel)) throw new Error('Use npm run android:package -- [release|debug]');
const variant = ['debug', 'ci-debug'].includes(channel) ? 'debug' : 'release';
const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
if (git('status', '--porcelain')) throw new Error('Commit source changes before packaging');
const commit = git('rev-parse', 'HEAD');
const checked = await inspectApks({ variant });
const version = checked[0].versionName;
if (variant === 'release' && process.env.GITHUB_REF_TYPE === 'tag' && process.env.GITHUB_REF_NAME !== `v${version}`) {
  throw new Error(`Release tag ${process.env.GITHUB_REF_NAME} does not match APK version v${version}`);
}
let receipt;
try { receipt = JSON.parse(await readFile(resolve(root, `build/verification/android-${variant}.json`), 'utf8')); }
catch { throw new Error(`Run npm run android:check${variant === 'debug' ? ' -- --debug' : ''} before packaging`); }
if (receipt.schema !== 'ticbox-android-check/v1' || receipt.variant !== variant || receipt.sourceDigest !== await sourceDigest()) throw new Error('Source does not match the successful Android checks; rerun android:check');
for (const apk of checked) {
  if (!receipt.apks?.some(item => item.name === apk.name && item.path === apk.path && item.sha256 === apk.sha256)) throw new Error(`${apk.name}: APK changed after the successful checks`);
}
const output = resolve(root, 'build/distributions', `ticbox-${version}-${variant}-${commit.slice(0, 12)}`);
await mkdir(output, { recursive: true });
const sums = [];
const delivered = [];
for (const apk of checked) {
  const filename = `ticbox-${apk.name === 'phone' ? 'phone' : 'healthbridge'}-${version}-${variant}-${commit.slice(0, 12)}.apk`;
  await copyFile(resolve(root, apk.path), resolve(output, filename));
  const digest = sha256(await readFile(resolve(output, filename)));
  if (digest !== apk.sha256) throw new Error(`${apk.name}: APK changed while packaging`);
  sums.push(`${digest}  ${filename}`);
  delivered.push({ ...apk, path: filename });
}
await writeFile(resolve(output, 'SHA256SUMS.txt'), sums.join('\n') + '\n');
await writeFile(resolve(output, 'manifest.json'), JSON.stringify({ schema: 'ticbox-apk-delivery/v1', commit, version, variant, sourceDigest: receipt.sourceDigest, apks: delivered }, null, 2) + '\n');
await writeFile(resolve(output, 'README.txt'), [
  `TicBox ${version}, variant ${variant}, commit ${commit}.`,
  'Install the phone APK on Android 14 (API 34) or later and the healthbridge APK on the watch (API 30+).',
  `Both APKs are signed with certificate SHA-256 ${checked[0].certificate}.`,
  'For an in-place update, retain the same application ID and signing certificate; do not uninstall or clear app data.',
  variant === 'release' ? 'Release APKs are not debuggable. Health JSON exports are saved to Download/TicBox on the watch.' : 'Explicit debug build. A different signing key cannot update an existing installation.',
  'The APK digests and current source digest match a successful Android check receipt.',
  '',
].join('\n'));
if (process.env.GITHUB_OUTPUT) await appendFile(process.env.GITHUB_OUTPUT, `directory=${output}\nversion=${version}\nvariant=${variant}\n`);
console.log(`Packaged ${checked.length} ${variant} APKs in ${output}`);
