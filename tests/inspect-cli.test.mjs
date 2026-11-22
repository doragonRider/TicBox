import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const cli = fileURLToPath(new URL('../tools/inspect-message.mjs', import.meta.url));
const run = args => spawnSync(process.execPath, [cli, ...args], { encoding: 'utf8' });

test('CLI fixed sysproxy and legacy samples report complete offline frames', () => {
  const flow = run(['sysproxy', '--hex', '11 00 00 00 05 00 00']);
  assert.equal(flow.status, 0, flow.stderr);
  assert.equal(JSON.parse(flow.stdout).frames[0].version, 5);
  const legacy = run(['legacy-ble', '--hex', '97918d89020000004142']);
  assert.equal(legacy.status, 0, legacy.stderr);
  assert.deepEqual(JSON.parse(legacy.stdout).payloads, [{ hex: '4142' }]);
});

test('CLI requires external path metadata and prints Java long IDs without rounding', () => {
  const payload = Buffer.from('{"current_step":10,"messageId":9223372036854775807}');
  const result = run(['mobvoi', '--path', '/health/cmd_new_wear_push_to_phone', '--hex', payload.toString('hex')]);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).data.messageId, '9223372036854775807');
  assert.equal(run(['mobvoi', '--hex', '7b7d']).status, 1);
});

test('CLI reports truncated and unknown stream tails and rejects malformed hex/options', () => {
  const partial = run(['sysproxy', '--hex', '110000']);
  assert.equal(partial.status, 2);
  assert.equal(JSON.parse(partial.stdout).status, 'incomplete');
  const unknown = run(['sysproxy', '--hex', 'ff11']);
  assert.equal(unknown.status, 2);
  assert.equal(JSON.parse(unknown.stdout).remainder.hex, 'ff11');
  assert.equal(run(['sysproxy', '--hex', '1']).status, 1);
  assert.equal(run(['sysproxy', '--hex', 'gg']).status, 1);
  assert.equal(run(['sysproxy', '--hex', '', '--hex', '11']).status, 1);
});

test('CLI GMS framing is opaque by default and cleartext protobuf is an explicit layer', () => {
  const wire = run(['gms-record', '--hex', '00000003ff0011']);
  assert.equal(wire.status, 0, wire.stderr);
  assert.deepEqual(JSON.parse(wire.stdout).records, [{ hex: 'ff0011' }]);
  const partial = run(['gms-record', '--hex', '00000003ff00']);
  assert.equal(partial.status, 2);
  assert.equal(JSON.parse(partial.stdout).remainder.hex, '00000003ff00');
  assert.equal(run(['gms-message', '--hex', 'ff0011']).status, 1);
  const connect = run(['gms-message', '--hex', '3a060a016e12014e']);
  assert.equal(connect.status, 0, connect.stderr);
  assert.equal(JSON.parse(connect.stdout).messages[0].nodeId, 'n');
});

test('CLI setup channel distinguishes framing from protobuf and reports incomplete tails', () => {
  const channel = run(['setup-channel', '--hex', '00000008080112040801100100000000']);
  assert.equal(channel.status, 0, channel.stderr);
  const decoded = JSON.parse(channel.stdout);
  assert.equal(decoded.messages[0].command.type.name, 'GET_STATUS');
  assert.equal(decoded.messages[0].command.id, '1');
  assert.equal(decoded.messages[1].type.name, 'TYPE_UNKNOWN');
  const complete = run(['setup-message', '--hex', '080322020803']);
  assert.equal(complete.status, 0, complete.stderr);
  assert.equal(JSON.parse(complete.stdout).setupStatus.state.name, 'COMPLETE');
  const partial = run(['setup-channel', '--hex', '000000080801']);
  assert.equal(partial.status, 2);
  assert.equal(JSON.parse(partial.stdout).remainder.hex, '000000080801');
  assert.equal(run(['setup-message', '--hex', 'ff']).status, 1);
});

test('CLI exposes ChannelRequest and typed DataMap without lossy numeric formatting',()=>{
  const channel=run(['gms-channel','--hex','120b0801110807060504030201']);
  assert.equal(channel.status,0,channel.stderr);
  assert.equal(JSON.parse(channel.stdout).control.channelId,'72623859790382856');
  // DataMap float_value=-0; JSON must not silently change this to +0 or null.
  const map=run(['datamap','--hex','0a0e0a01781209080412052500000080']);
  assert.equal(map.status,0,map.stderr);
  assert.equal(JSON.parse(map.stdout).entries[0].value,'-0');
});
