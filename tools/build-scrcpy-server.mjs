#!/usr/bin/env node
/** Offline, deterministic build of the pinned scrcpy server plus the negotiated sync-frame patch. */
import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'android/thirdparty/scrcpy-server');
const artifact = join(root, 'android/toolbox-resources/toolbox/scrcpy-server-v4.1.jar');
const buildLockPath = join(source, 'server-build.json');
const args = process.argv.slice(2);
if (args.length !== 1 || !['--check', '--write'].includes(args[0])) throw new Error('Use --check to verify or --write to rebuild the pinned payload.');
const write = args[0] === '--write';
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const upstream = JSON.parse(readFileSync(join(source, 'upstream.json'), 'utf8'));
const archive = join(source, 'upstream-source.tar.gz');
const patch = join(source, 'request-keyframe.patch');
if (sha256(readFileSync(archive)) !== upstream.archiveSha256) throw new Error('scrcpy upstream archive digest mismatch');
const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
const java = process.env.JAVA_HOME;
if (!sdk || !java) throw new Error('ANDROID_HOME and JAVA_HOME must select the installed Android SDK and JDK.');
const suffix = process.platform === 'win32' ? '.exe' : '';
const platform = join(sdk, 'platforms/android-37.2');
const buildTools = join(sdk, 'build-tools/37.0.0');
const androidJar = join(platform, 'android.jar');
const aidlFramework = join(platform, 'framework.aidl');
const d8Jar = join(buildTools, 'lib/d8.jar');
const tools = {
  languageRelease: 8, minApi: 21, androidPlatform: 'android-37.2', buildTools: '37.0.0',
  androidJarSha256: sha256(readFileSync(androidJar)),
  frameworkAidlSha256: sha256(readFileSync(aidlFramework)),
  d8JarSha256: sha256(readFileSync(d8Jar)),
};
const patchSha256 = sha256(readFileSync(patch));
// The identity is written into the JAR (META-INF/ticbox-scrcpy.json). Changing it changes the payload:
// rebuild with --write, then update AgentScreen.SERVER_SHA256 and SCRCPY-NOTICE.txt.
const identity = { schema: 'ticbox-scrcpy-server/v1', upstreamCommit: upstream.commit, extension: 'request-keyframe-v1',
  videoRateControl: 'prefer-cbr-dynamic-bitrate-v2', encoderDiagnostics: 'format-output-input-vendor-v4',
  glInput: 'paced-flush-copies-v3', controlOpcode: 127, bitRateOpcode: 126, scrcpyVersion: '4.1', sourceArchiveSha256: upstream.archiveSha256, patchSha256, tools };
const lock = existsSync(buildLockPath) ? JSON.parse(readFileSync(buildLockPath, 'utf8')) : null;
if (!write && (!lock || JSON.stringify(lock.identity) !== JSON.stringify(identity))) throw new Error('scrcpy build input/tool identity changed; review and explicitly rebuild the pinned payload.');
const scratch = mkdtempSync(join(tmpdir(), 'ticbox-scrcpy-'));
const run = (command, argv, options = {}) => execFileSync(command, argv, { cwd: scratch, stdio: 'pipe', maxBuffer: 8 * 1024 * 1024, timeout: 120_000, ...options });
function files(path, prefix = '') {
  return readdirSync(path, { withFileTypes: true }).flatMap(entry => {
    if (entry.isSymbolicLink()) throw new Error('Unexpected symlink in scrcpy sources');
    return entry.isDirectory() ? files(join(path, entry.name), `${prefix}${entry.name}/`) : [`${prefix}${entry.name}`];
  }).sort();
}
function crc32(bytes) {
  let value = 0xffffffff;
  for (const byte of bytes) { value ^= byte; for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0); }
  return (value ^ 0xffffffff) >>> 0;
}
/** Stored ZIP entries with fixed DOS timestamps avoid host ZIP/zlib/version metadata differences. */
function zip(entries) {
  const locals = [], central = []; let offset = 0;
  for (const [name, contents] of entries.toSorted(([a], [b]) => a.localeCompare(b))) {
    const filename = Buffer.from(name), crc = crc32(contents);
    const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x21, 12); local.writeUInt32LE(crc, 14); local.writeUInt32LE(contents.length, 18);
    local.writeUInt32LE(contents.length, 22); local.writeUInt16LE(filename.length, 26);
    const header = Buffer.alloc(46); header.writeUInt32LE(0x02014b50); header.writeUInt16LE(20, 4); header.writeUInt16LE(20, 6);
    header.writeUInt16LE(0x21, 14); header.writeUInt32LE(crc, 16); header.writeUInt32LE(contents.length, 20);
    header.writeUInt32LE(contents.length, 24); header.writeUInt16LE(filename.length, 28); header.writeUInt32LE(offset, 42);
    locals.push(local, filename, contents); central.push(header, filename); offset += local.length + filename.length + contents.length;
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}
try {
  run('tar', ['-xzf', archive, '-C', scratch]);
  const inventory = files(scratch).map(path => ({ path, sha256: sha256(readFileSync(join(scratch, path))) })).sort((a, b) => a.path.localeCompare(b.path));
  if (JSON.stringify(inventory) !== JSON.stringify(upstream.files)) throw new Error('scrcpy extracted source inventory mismatch');
  run('patch', ['--batch', '--fuzz=0', '-p1', '-i', patch]);
  const main = join(scratch, 'server/src/main');
  const generated = join(scratch, 'generated'); const classes = join(scratch, 'classes'); const dex = join(scratch, 'dex');
  [join(generated, 'com/genymobile/scrcpy'), classes, dex].forEach(path => mkdirSync(path, { recursive: true }));
  writeFileSync(join(generated, 'com/genymobile/scrcpy/BuildConfig.java'), 'package com.genymobile.scrcpy;\npublic final class BuildConfig { public static final boolean DEBUG = false; public static final String VERSION_NAME = "4.1"; }\n');
  for (const relative of files(join(main, 'aidl')).filter(name => name.endsWith('.aidl'))) {
    run(join(buildTools, `aidl${suffix}`), ['--lang=java', `-I${join(main, 'aidl')}`, `-p${aidlFramework}`, `-o${generated}`, join(main, 'aidl', relative)]);
  }
  const javaSources = [ ...files(join(main, 'java')).map(path => join(main, 'java', path)), ...files(generated).map(path => join(generated, path)) ].filter(path => path.endsWith('.java')).sort();
  run(join(java, 'bin', `javac${suffix}`), ['--release', '8', '-Xlint:-options', '-encoding', 'UTF-8', '-g:source,lines', '-classpath', androidJar, '-d', classes, ...javaSources]);
  const classesJar = join(scratch, 'classes.jar');
  writeFileSync(classesJar, zip(files(classes).map(path => [path, readFileSync(join(classes, path))])));
  run(join(java, 'bin', `java${suffix}`), ['-cp', d8Jar, 'com.android.tools.r8.D8', '--release', '--min-api', '21', '--lib', androidJar, '--output', dex, classesJar]);
  if (files(dex).join(',') !== 'classes.dex') throw new Error('Unexpected scrcpy dex output');
  const dexDump = run(join(buildTools, `dexdump${suffix}`), ['-f', join(dex, 'classes.dex')], { encoding: 'utf8' });
  const dexClasses = new Set([...dexDump.matchAll(/Class descriptor\s*:\s*'([^']+)'/g)].map(match => match[1]));
  for (const name of files(classes).filter(path => path.endsWith('.class'))) {
    if (!dexClasses.has(`L${name.slice(0, -6)};`)) throw new Error(`Compiled scrcpy class missing from DEX: ${name}`);
  }
  // Tests compile against and exercise the actual patched production classes. Android doubles are
  // first on this host-only class path and are never included in classes.jar or the shipped DEX.
  const host = join(scratch, 'host-test'); mkdirSync(host);
  const testSources = files(join(source, 'tests')).filter(path => path.endsWith('.java')).map(path => join(source, 'tests', path));
  run(join(java, 'bin', `javac${suffix}`), ['--release', '8', '-Xlint:-options', '-classpath', classesJar, '-d', host, ...testSources]);
  const output = run(join(java, 'bin', `java${suffix}`), ['-cp', `${host}${process.platform === 'win32' ? ';' : ':'}${classesJar}`, 'RequestKeyframeTest'], { encoding: 'utf8' });
  process.stdout.write(output);
  process.stdout.write(run(join(java, 'bin', `java${suffix}`), ['-cp', `${host}${process.platform === 'win32' ? ';' : ':'}${classesJar}`, 'VideoRateControlTest'], { encoding: 'utf8' }));
  process.stdout.write(run(join(java, 'bin', `java${suffix}`), ['-cp', `${host}${process.platform === 'win32' ? ';' : ':'}${classesJar}`, 'VideoBitRateTest'], { encoding: 'utf8' }));
  process.stdout.write(run(join(java, 'bin', `java${suffix}`), ['-cp', `${host}${process.platform === 'win32' ? ';' : ':'}${classesJar}`, 'VideoEncoderOutputDiagnosticsTest'], { encoding: 'utf8' }));
  process.stdout.write(run(join(java, 'bin', `java${suffix}`), ['-cp', `${host}${process.platform === 'win32' ? ';' : ':'}${classesJar}`, 'PacedInputTest'], { encoding: 'utf8' }));
  const diagnosticTest = spawnSync(join(java, 'bin', `java${suffix}`), ['-cp', `${host}${process.platform === 'win32' ? ';' : ':'}${classesJar}`, 'VideoEncoderDiagnosticsTest'], {
    cwd: scratch, encoding: 'utf8', stdio: 'pipe', maxBuffer: 8 * 1024 * 1024, timeout: 120_000,
  });
  if (diagnosticTest.error || diagnosticTest.status !== 0) throw new Error(diagnosticTest.error?.message ?? diagnosticTest.stderr);
  const encoderLine = diagnosticTest.stderr.trim().split(/\r?\n/);
  if (encoderLine.length !== 1 || !encoderLine[0].startsWith('TICWATCH_ENCODER_V1 ')) throw new Error('Encoder metadata lost after System streams were disabled');
  const encoderRecord = JSON.parse(encoderLine[0].slice('TICWATCH_ENCODER_V1 '.length));
  if (encoderRecord.kind !== 'screen_encoder' || encoderRecord.configuredBitrate !== 20000000 || diagnosticTest.stderr.includes('private')) throw new Error('Invalid encoder metadata projection');
  process.stdout.write(diagnosticTest.stdout);
  const payload = zip([
    ['classes.dex', readFileSync(join(dex, 'classes.dex'))],
    ['META-INF/LICENSE', readFileSync(join(source, 'LICENSE'))],
    ['META-INF/ticbox-scrcpy.json', Buffer.from(JSON.stringify(identity) + '\n')],
  ]);
  const result = { identity, bytes: payload.length, sha256: sha256(payload) };
  if (write) {
    writeFileSync(artifact, payload);
    writeFileSync(buildLockPath, JSON.stringify(result, null, 2) + '\n');
  } else if (result.sha256 !== lock.sha256 || !payload.equals(readFileSync(artifact))) {
    throw new Error('Pinned scrcpy payload does not reproduce from its reviewed source and patch');
  }
  console.log(JSON.stringify({ operation: write ? 'rebuilt' : 'verified', bytes: result.bytes, sha256: result.sha256,
    javac: run(join(java, 'bin', `javac${suffix}`), ['-version'], { encoding: 'utf8' }).trim() }));
} catch (error) {
  if (error.stderr) process.stderr.write(error.stderr);
  throw error;
} finally { rmSync(scratch, { recursive: true, force: true }); }
