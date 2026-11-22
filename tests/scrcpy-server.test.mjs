import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const base = new URL('../android/thirdparty/scrcpy-server/', import.meta.url);

test('pinned scrcpy source, patch, payload and Agent authorization digest agree', async () => {
  const [manifest, upstream, archive, patch, payload, agent, notice] = await Promise.all([
    readFile(new URL('server-build.json', base), 'utf8').then(JSON.parse),
    readFile(new URL('upstream.json', base), 'utf8').then(JSON.parse),
    readFile(new URL('upstream-source.tar.gz', base)),
    readFile(new URL('request-keyframe.patch', base)),
    readFile(new URL('../android/toolbox-resources/toolbox/scrcpy-server-v4.1.jar', import.meta.url)),
    readFile(new URL('../android/watch/toolboxagent/src/main/kotlin/dev/ticbox/companion/toolboxagent/AgentScreen.kt', import.meta.url), 'utf8'),
    readFile(new URL('../android/toolbox-resources/toolbox/SCRCPY-NOTICE.txt', import.meta.url), 'utf8'),
  ]);
  assert.equal(digest(archive), upstream.archiveSha256);
  assert.equal(digest(archive), manifest.identity.sourceArchiveSha256);
  assert.equal(digest(patch), manifest.identity.patchSha256);
  assert.equal(digest(payload), manifest.sha256);
  assert.equal(payload.length, manifest.bytes);
  assert.equal(manifest.identity.upstreamCommit, upstream.commit);
  assert.equal(manifest.identity.controlOpcode, 127);
  assert.equal(manifest.identity.bitRateOpcode, 126);
  assert.equal(manifest.identity.scrcpyVersion, '4.1');
  assert.equal(agent.match(/SERVER_SHA256 = "([0-9a-f]{64})"/)?.[1], manifest.sha256);
  assert.ok(notice.includes(manifest.sha256));
  assert.ok(notice.includes(upstream.commit));
  assert.ok(notice.includes('Apache License 2.0'));
  assert.ok(upstream.files.some(file => file.path.endsWith('/CaptureControl.java')));
  assert.ok(upstream.files.every(file => /^[a-zA-Z0-9_./]+$/.test(file.path) && !file.path.split('/').includes('..')));
});
