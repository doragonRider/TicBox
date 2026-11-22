#!/usr/bin/env node
/** Checks the release pair by default; --debug is an explicit development-only alternative. */
import { mkdir, rm, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { root, inspectApks, sourceDigest } from './android-apks.mjs';

const options = process.argv.slice(2);
const variant = options.includes('--debug') ? 'debug' : 'release';
const gradleOptions = options.filter(value => value !== '--debug');
if (variant === 'release') {
  const missing = ['TICBOX_SIGNING_STORE_FILE', 'TICBOX_SIGNING_STORE_PASSWORD', 'TICBOX_SIGNING_KEY_ALIAS', 'TICBOX_SIGNING_KEY_PASSWORD', 'TICBOX_SIGNING_CERT_SHA256'].filter(name => !process.env[name]);
  if (missing.length) throw new Error(`Release checks require signing configuration: ${missing.join(', ')}. Use --debug only when a debug build is needed.`);
}
const receiptPath = resolve(root, `build/verification/android-${variant}.json`);
await rm(receiptPath, { force: true });
const source = await sourceDigest();
const gradle = process.platform === 'win32' ? 'android\\gradlew.bat' : './android/gradlew';
const args = ['-p', 'android', variant === 'release' ? 'backendCheck' : 'backendDebugCheck', '--console=plain', ...gradleOptions];
const build = spawnSync(gradle, args, { cwd: root, stdio: 'inherit', shell: process.platform === 'win32' });
if (build.status !== 0) process.exit(build.status ?? 1);
const apks = await inspectApks({ variant });
if (source !== await sourceDigest()) throw new Error('Source changed during Android checks; rerun the checks before delivery');
await mkdir(resolve(root, 'build/verification'), { recursive: true });
await writeFile(receiptPath, JSON.stringify({ schema: 'ticbox-android-check/v1', variant, sourceDigest: source, apks }, null, 2) + '\n');
for (const apk of apks) console.log(`${apk.name}: ${apk.path} (version ${apk.versionName}/${apk.versionCode}, ${variant}, certificate ${apk.certificate})`);
