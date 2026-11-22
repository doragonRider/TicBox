import test from 'node:test';
import assert from 'node:assert/strict';
import { decodeSetupMessage, encodeSetupQuery, encodeSetupFrame, SetupChannelStreamDecoder } from '../src/setup-channel.mjs';

const hex = value => Buffer.from(value.replaceAll(' ', ''), 'hex');

test('setup queries match independently written protobuf and BE length vectors', () => {
  assert.deepEqual(encodeSetupFrame(encodeSetupQuery('GET_STATUS', { commandId: 1n })), hex('00000008 0801 1204 0801 1001'));
  assert.deepEqual(encodeSetupFrame(encodeSetupQuery('GET_SYSTEM_INFO')), hex('00000006 0801 1202 0802'));
  assert.deepEqual(encodeSetupFrame(encodeSetupQuery('GET_CONNECTION_INFO', { commandId: 300n })), hex('00000009 0801 1205 0805 10ac02'));
  assert.equal(decodeSetupMessage(hex('0801 1204 0801 1001')).command.id, 1n);
  assert.throws(() => encodeSetupQuery('STOP'), /Expected/);
  assert.throws(() => encodeSetupQuery('GET_STATUS', { commandId: 9007199254740992 }), /safe integer/);
  assert.throws(() => encodeSetupQuery('GET_STATUS', { commandId: 9223372036854775808n }), /int64/);
});

test('setup channel accepts empty protobufs, concatenation and every possible split', () => {
  const wire = hex('00000008 0801120408011001 00000000 00000006 080322020803');
  const expected = [hex('0801120408011001'), Buffer.alloc(0), hex('080322020803')];
  for (let split = 0; split <= wire.length; split++) {
    const decoder = new SetupChannelStreamDecoder();
    assert.deepEqual([...decoder.push(wire.subarray(0, split)), ...decoder.push(wire.subarray(split))], expected);
    assert.equal(decoder.finish().status, 'complete');
  }
  const decoder = new SetupChannelStreamDecoder();
  assert.deepEqual([...wire].flatMap(byte => decoder.push(Buffer.from([byte]))), expected);
  assert.equal(decodeSetupMessage(expected[1]).type.name, 'TYPE_UNKNOWN');
  assert.deepEqual(encodeSetupFrame(Buffer.alloc(0)), hex('00000000'));
});

test('setup status, time and command IDs preserve defaults, unknowns and signed longs', () => {
  assert.equal(decodeSetupMessage(hex('0803 2202 0803')).setupStatus.state.name, 'COMPLETE');
  const event = decodeSetupMessage(hex('0802 1a0d 0804 18ffffffffffffffffff01')).event;
  assert.equal(event.type.name, 'UNKNOWN_COMMAND');
  assert.equal(event.commandId, -1n);
  const time = decodeSetupMessage(hex('0805 3210 10ffffffffffffffffff01 2a03555443')).companionTime;
  assert.equal(time.millis, -1n);
  assert.equal(time.timeZoneId, 'UTC');
  const query = encodeSetupQuery('GET_STATUS', { commandId: -9223372036854775808n });
  assert.equal(decodeSetupMessage(query).command.id, -9223372036854775808n);
  const message = decodeSetupMessage(hex('0863 2a00 3a01ff 5001'));
  assert.equal(message.type.value, 99);
  assert.equal(message.type.name, null);
  assert.deepEqual(message.rawMessages[0].payload, Buffer.alloc(0));
  assert.deepEqual(message.unknownFields.map(field => field.raw), [hex('3a01ff'), hex('5001')]);
  const defaults = decodeSetupMessage(hex('1200 2200'));
  assert.equal(defaults.command.idExplicit, false);
  assert.equal(defaults.setupStatus.state.explicit, false);
});

test('setup system capabilities, cellular info, enrollment and opt-in are decoded with watch enum boundaries',()=>{
  const info=decodeSetupMessage(hex('08042a1308041001100618012021280032030205063801')).systemInfo;
  assert.equal(info.version.name,'KYANITE');
  assert.equal(info.platformVersion,33);
  assert.deepEqual(info.capabilities.map(item=>item.name),['WIFI','SETUP_PROTOCOMM_CHANNEL']);
  assert.deepEqual(info.setupCapabilities.map(item=>item.name),['GMS_CHECKIN','TETHER_CONFIG','SIGNALS_OOBE_DONE']);
  assert.equal(info.setupCapabilityVersion.name,'V1');
  const connection=decodeSetupMessage(hex('0807420e0a0c08840210b6021a0474657374')).connectionInfo;
  assert.deepEqual([connection.cellInfo.mnc,connection.cellInfo.mcc,connection.cellInfo.carrierName],[260,310,'test']);
  assert.equal(decodeSetupMessage(hex('08084a050a03616263')).enrollmentInfo.enrollmentIntent,'abc');
  const optin=decodeSetupMessage(hex('08021a0b080312070805100118ac02')).event.optinStatus;
  assert.equal(optin.type.name,'NOTIFICATIONS');
  assert.equal(optin.state.name,'ACCEPTED');
  assert.equal(optin.time,300n);
  const newer=decodeSetupMessage(hex('2a0332010c')).systemInfo.setupCapabilities[0];
  assert.equal(newer.value,12);
  assert.equal(newer.name,null);
  assert.throws(()=>decodeSetupMessage(hex('2a02ff00')),/wire type/);
});

test('setup truncated and malformed frames retain or reject exact input', () => {
  for (const data of ['00', '000000', '000000080801']) {
    const decoder = new SetupChannelStreamDecoder();
    assert.deepEqual(decoder.push(hex(data)), []);
    assert.deepEqual(decoder.finish(), { status: 'incomplete', remainder: hex(data) });
  }
  for (const data of ['ffffffff', '80000000', '00100001']) {
    assert.throws(() => new SetupChannelStreamDecoder().push(hex(data)), /length/);
  }
  assert.throws(() => encodeSetupFrame(Buffer.alloc(1048577)), /length/);
  assert.throws(() => decodeSetupMessage(hex('08010802')), /Duplicate/);
  assert.throws(() => decodeSetupMessage(hex('0a0100')), /wire type/);
  assert.throws(() => decodeSetupMessage(hex('120108')), /Truncated/);
});
