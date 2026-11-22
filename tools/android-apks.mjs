/** Reads and verifies the built phone/watch APK pair. Release is the default delivery variant. */
import { readdir, readFile, mkdtemp, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { PTY_ASSETS, PTY_JNI_CLASS, SCREEN_JNI_CLASS, inspectPtyLibrary, verifyPtyAssetEntries } from './pty-payload.mjs';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export function apkInputs(variant = 'release') {
  if (!['release', 'debug'].includes(variant)) throw new Error(`Unknown APK variant: ${variant}`);
  return [
    { name: 'phone', path: `android/app/build/outputs/apk/${variant}/app-${variant}.apk`, minSdk: 34 },
    { name: 'watch', path: `android/watch/healthbridge/build/outputs/apk/${variant}/healthbridge-${variant}.apk`, minSdk: 30 },
  ];
}

function run(command, args) {
  if (process.platform === 'win32' && command.endsWith('.bat')) {
    return execFileSync('cmd.exe', ['/d', '/c', command, ...args], { cwd: root, encoding: 'utf8' });
  }
  return execFileSync(command, args, { cwd: root, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
}

export async function projectVersion() {
  const text = await readFile(resolve(root, 'android/version.properties'), 'utf8');
  const versionName = text.match(/^versionName=(\S+)$/m)?.[1];
  const versionCode = Number(text.match(/^versionCode=(\d+)$/m)?.[1]);
  if (!versionName || !Number.isSafeInteger(versionCode) || versionCode < 1) throw new Error('Invalid android/version.properties');
  return { versionName, versionCode };
}

async function buildTools() {
  let sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) {
    try {
      const properties = await readFile(resolve(root, 'android/local.properties'), 'utf8');
      sdk = properties.match(/^sdk\.dir=(.+)$/m)?.[1].replaceAll('\\:', ':').replaceAll('\\\\', '\\');
    } catch {}
  }
  if (!sdk) throw new Error('Set ANDROID_HOME to the Android SDK directory');
  const gradle = await readFile(resolve(root, 'android/app/build.gradle.kts'), 'utf8');
  const version = gradle.match(/buildToolsVersion\s*=\s*"([^"]+)"/)?.[1]
    ?? (await readdir(resolve(sdk, 'build-tools'))).sort((a, b) => a.localeCompare(b, 'en', { numeric: true })).at(-1);
  const directory = resolve(sdk, 'build-tools', version);
  const windows = process.platform === 'win32';
  return { aapt2: resolve(directory, windows ? 'aapt2.exe' : 'aapt2'), apksigner: resolve(directory, windows ? 'apksigner.bat' : 'apksigner') };
}

export const normalizeDigest = (value) => value?.replace(/[:\s]/g, '').toLowerCase() || null;
export const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

/** Binds validation to all non-ignored source files, including edits tested before a local commit. */
export async function sourceDigest() {
  const paths = [...new Set(run('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z']).split('\0').filter(Boolean))].sort();
  const hash = createHash('sha256');
  for (const path of paths) {
    let digest;
    try { digest = sha256(await readFile(resolve(root, path))); }
    catch (error) { if (error.code !== 'ENOENT') throw error; digest = 'deleted'; }
    hash.update(JSON.stringify([path, digest]) + '\n');
  }
  return hash.digest('hex');
}

/** Verifies variant, version, package, API levels, matching signatures and watch permissions. */
export async function inspectApks({ variant = 'release', expectedCertificate = normalizeDigest(process.env.TICBOX_SIGNING_CERT_SHA256) } = {}) {
  if (expectedCertificate && !/^[a-f0-9]{64}$/.test(expectedCertificate)) throw new Error('Invalid TICBOX_SIGNING_CERT_SHA256');
  if (variant === 'release' && !expectedCertificate) throw new Error('Release delivery requires TICBOX_SIGNING_CERT_SHA256');
  const inputs = apkInputs(variant);
  const tools = await buildTools();
  const expected = await projectVersion();
  const results = [];
  for (const { name, path, minSdk } of inputs) {
    const file = resolve(root, path);
    const badging = run(tools.aapt2, ['dump', 'badging', file]);
    const signature = run(tools.apksigner, ['verify', '--print-certs', file]);
    const manifest = run(tools.aapt2, ['dump', 'xmltree', '--file', 'AndroidManifest.xml', file]);
    if (!badging.includes("package: name='dev.ticbox.companion'")) throw new Error(`${name}: unexpected package`);
    if (!badging.includes(`minSdkVersion:'${minSdk}'`)) throw new Error(`${name}: minSdk is not ${minSdk}`);
    const versionName = badging.match(/versionName='([^']*)'/)?.[1];
    const versionCode = Number(badging.match(/versionCode='(\d+)'/)?.[1]);
    const targetSdk = Number(badging.match(/targetSdkVersion:'(\d+)'/)?.[1]);
    const debuggable = /^application-debuggable\s*$/m.test(badging);
    if (debuggable !== (variant === 'debug')) throw new Error(`${name}: APK debuggable=${debuggable} does not match ${variant}`);
    if (versionName !== expected.versionName || versionCode !== expected.versionCode) {
      throw new Error(`${name}: APK version ${versionName}/${versionCode}, expected ${expected.versionName}/${expected.versionCode}`);
    }
    if (targetSdk !== 37) throw new Error(`${name}: unexpected targetSdk ${targetSdk}`);
    if (variant === 'release' && manifest.includes('androidx.activity.ComponentActivity')) throw new Error(`${name}: test host leaked into release Manifest`);
    if (name === 'watch') {
      if (badging.includes("name='android.permission.INTERNET'")) throw new Error('watch: the health bridge must not request INTERNET');
      if (variant === 'release' && manifest.includes('HealthSampleActivity')) throw new Error('watch: debug probe leaked into release Manifest');
    }
    const certificate = normalizeDigest(signature.match(/certificate SHA-256 digest: (\w+)/)?.[1]);
    if (!certificate) throw new Error(`${name}: signature did not verify`);
    const bytes = await readFile(file);
    results.push({ name, path, variant, versionName, versionCode, targetSdk, debuggable, certificate, bytes: bytes.length, sha256: sha256(bytes) });
  }
  if (results[0].certificate !== results[1].certificate) throw new Error('Phone and watch APKs use different certificates');
  if (expectedCertificate && results[0].certificate !== expectedCertificate) throw new Error(`APKs are signed with ${results[0].certificate}, expected ${expectedCertificate}`);
  // Verify actual packaged bytes, not just the Gradle copy task's inputs. The same signed
  // watch APK must serve installation and ADB execution; no old helper may remain merged.
  const jar = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'jar.exe' : 'jar') : 'jar';
  const phone = resolve(root, inputs[0].path);
  const entries = run(jar, ['--list', '--file', phone]).split(/\r?\n/);
  const asset = 'assets/watch/local-bridge.apk';
  const moduleAsset = 'assets/watch/health-module.zip';
  const moduleCatalog = 'assets/watch/health-module.json';
  for (const entry of [moduleAsset, moduleCatalog]) {
    if (entries.filter(name => name === entry).length !== 1) throw new Error(`phone: expected exactly one ${entry}`);
  }
  if (entries.filter(entry => entry === asset).length !== 1 || entries.includes('assets/toolbox/toolbox-agent.apk') || entries.includes('assets/toolbox/scrcpy-server-v4.1.jar')) {
    throw new Error('phone: expected one Local Bridge APK without a duplicate helper or scrcpy server');
  }
  if (entries.some(entry => entry.startsWith('assets/toolbox/pty/') || /^lib\/[^/]+\/libticbox_pty\.so$/.test(entry))) {
    throw new Error('phone: PTY payload must only be carried inside the Local Bridge APK');
  }
  const directory = await mkdtemp(resolve(tmpdir(), 'ticbox-watch-payload-'));
  try {
    run(jar, ['--extract', '--file', phone, '--dir', directory, asset, moduleAsset, moduleCatalog]);
    const moduleBytes = await readFile(resolve(directory, moduleAsset));
    const catalog = JSON.parse(await readFile(resolve(directory, moduleCatalog), 'utf8'));
    const builtModule = await readFile(resolve(root, `android/watch/healthmodule/build/distributions/ticbox-healthmodule-${expected.versionName}.zip`));
    if (catalog.schema !== 1 || catalog.id !== 'ticwatch_health_settings' || catalog.versionName !== expected.versionName || catalog.versionCode !== expected.versionCode
      || catalog.bytes !== moduleBytes.length || catalog.sha256 !== sha256(moduleBytes) || catalog.sha256 !== sha256(builtModule)
      || !/^[a-f0-9]{64}$/.test(catalog.buildId)) throw new Error('phone: bundled Magisk module identity mismatch');
    const moduleFiles = run(jar, ['--list', '--file', resolve(directory, moduleAsset)]).split(/\r?\n/).filter(name => name && !name.endsWith('/'));
    if (new Set(moduleFiles).size !== moduleFiles.length || moduleFiles.some(name => !/^[a-zA-Z0-9._/-]+$/.test(name) || name.startsWith('/') || name.split('/').includes('..'))
      || moduleFiles.length !== Object.keys(catalog.entries).length) throw new Error('phone: invalid module catalog');
    const moduleDirectory = resolve(directory, 'module-contents');
    run(jar, ['--extract', '--file', resolve(directory, moduleAsset), '--dir', moduleDirectory]);
    for (const name of moduleFiles) {
      if (sha256(await readFile(resolve(moduleDirectory, name))) !== catalog.entries[name]) throw new Error('phone: module entry digest mismatch');
    }
    const buildMetadata = await readFile(resolve(moduleDirectory, 'companion-build.properties'), 'utf8');
    if (!buildMetadata.includes(`buildId=${catalog.buildId}\n`)) throw new Error('phone: module build ID mismatch');
    if (!(await readFile(resolve(moduleDirectory, 'module.dex'))).includes(Buffer.from(catalog.buildId))) throw new Error('phone: module runtime build ID was removed');
    results[0].embeddedModule = { bytes: moduleBytes.length, sha256: catalog.sha256, buildId: catalog.buildId };
    if (sha256(await readFile(resolve(directory, asset))) !== results[1].sha256) throw new Error('phone: embedded watch APK does not match the verified watch build');
    const watch = resolve(root, inputs[1].path);
    const watchEntries = run(jar, ['--list', '--file', watch]).split(/\r?\n/);
    verifyPtyAssetEntries(watchEntries);
    const dex = watchEntries.filter(entry => /^classes\d*\.dex$/.test(entry));
    if (!dex.length) throw new Error('watch: no agent bytecode');
    const server = 'assets/toolbox/scrcpy-server-v4.1.jar';
    run(jar, ['--extract', '--file', watch, '--dir', directory, ...dex, server, ...PTY_ASSETS]);
    const bodies = await Promise.all(dex.map(entry => readFile(resolve(directory, entry))));
    if (!bodies.some(body => body.includes(Buffer.from('Ldev/ticbox/companion/toolboxagent/ToolboxAgentMain;')))) throw new Error('watch: app_process agent entry was removed');
    if (!bodies.some(body => body.includes(Buffer.from(PTY_JNI_CLASS)))) throw new Error('watch: PTY JNI entry was removed');
    if (!bodies.some(body => body.includes(Buffer.from(SCREEN_JNI_CLASS)))) throw new Error('watch: screen JNI entry was removed');
    results[1].ptyLibraries = await Promise.all(PTY_ASSETS.map(async asset => ({
      path: asset, ...inspectPtyLibrary(await readFile(resolve(directory, asset)), asset.split('/').at(-2)),
    })));
    if (sha256(await readFile(resolve(directory, server))) !== sha256(await readFile(resolve(root, 'android/toolbox-resources/toolbox/scrcpy-server-v4.1.jar')))) throw new Error('watch: embedded scrcpy server does not match the source payload');
    results[0].embeddedWatchSha256 = results[1].sha256;
    results[0].embeddedWatchBytes = results[1].bytes;
  } finally { await rm(directory, { recursive: true, force: true }); }
  return results;
}
