import assert from 'node:assert/strict';
import test from 'node:test';
import { LegacyBleDecoder, encodeLegacyBleFrame, packetizeLegacyBle } from '../src/legacy-ble.mjs';

test('known ASCII vector uses the observed magic and little-endian payload length', () => {
  assert.equal(encodeLegacyBleFrame(Buffer.from('abc')).toString('hex'), '97918d8903000000616263');
  const decoder = new LegacyBleDecoder();
  assert.deepEqual(decoder.feed(Buffer.from('97918d8903000000616263', 'hex')), [Buffer.from('abc')]);
  decoder.finish();
});

test('33-byte payload matches fixed characteristic-value fragments at 20 bytes', () => {
  const packets = packetizeLegacyBle(Buffer.from(Array.from({ length: 33 }, (_, i) => i)));
  assert.deepEqual(packets.map(p => p.toString('hex')), [
    '97918d8921000000000102030405060708090a0b',
    '0c0d0e0f101112131415161718191a1b1c1d1e1f',
    '20',
  ]);
});

test('every split of a literal frame, including inside magic and length, reassembles', () => {
  const frame = Buffer.from('97918d8903000000616263', 'hex');
  for (let split = 0; split <= frame.length; split++) {
    const decoder = new LegacyBleDecoder();
    const messages = [...decoder.feed(frame.subarray(0, split)), ...decoder.feed(frame.subarray(split))];
    assert.deepEqual(messages, [Buffer.from('abc')], `split ${split}`);
    decoder.finish();
  }
});

test('one-byte reads handle concatenated frames and magic bytes inside a payload', () => {
  const stream = Buffer.from('97918d890300000061626397918d890400000097918d89', 'hex');
  const decoder = new LegacyBleDecoder();
  const messages = Array.from(stream, value => decoder.feed(Buffer.from([value]))).flat();
  assert.deepEqual(messages.map(p => p.toString('hex')), ['616263', '97918d89']);
  decoder.finish();
});

test('resynchronization preserves overlapping magic prefixes and reports discarded bytes', () => {
  const decoder = new LegacyBleDecoder();
  assert.deepEqual(decoder.feed(Buffer.from('00979197918d8903000000616263', 'hex')), [Buffer.from('abc')]);
  assert.equal(decoder.discardedBytes, 3);
  decoder.finish();
});

test('invalid signed lengths and configured resource bounds fail without unbounded waiting', () => {
  for (const suffix of ['00000000', 'ffffffff', '00000080', '05000000']) {
    const decoder = new LegacyBleDecoder({ maxPayloadBytes: 4 });
    assert.throws(() => decoder.feed(Buffer.from(`97918d89${suffix}`, 'hex')), RangeError);
    assert.equal(decoder.bufferedBytes, 0);
  }
  assert.throws(() => encodeLegacyBleFrame(Buffer.alloc(0)), RangeError);
  assert.throws(() => packetizeLegacyBle(Buffer.from('x'), { valueBytes: 7 }), RangeError);
});

test('truncation is visible, and reset drops partial state for the next stream', () => {
  const decoder = new LegacyBleDecoder();
  assert.deepEqual(decoder.feed(Buffer.from('97918d890300000061', 'hex')), []);
  assert.throws(() => decoder.finish(), /Incomplete/);
  decoder.reset();
  assert.deepEqual(decoder.feed(Buffer.from('97918d8903000000616263', 'hex')), [Buffer.from('abc')]);
  decoder.finish();
});
