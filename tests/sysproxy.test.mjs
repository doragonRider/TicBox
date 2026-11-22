import test from 'node:test';
import assert from 'node:assert/strict';
import {
  decodeFrame, encodeHandshake, encodeTcpWrite, encodePullData,
  encodeTcpClose, encodeActiveNetworkState, encodePing, Flow15StreamDecoder,
} from '../src/sysproxy.mjs';

const hex = value => Buffer.from(value.replaceAll(' ', ''), 'hex');

test('fixed Java ByteBuffer vectors: protocol-v5 ACK and phone serializers', () => {
  // Values computed from the APK ByteBuffer serializers, not captured traffic.
  assert.equal(encodeHandshake().toString('hex'), '11000000050000');
  assert.equal(encodeTcpWrite(0x01020304, hex('deadbeef')).toString('hex'), '03010203040004deadbeef');
  assert.equal(encodePullData(-1, hex('000102')).toString('hex'), '09ffffffff0003000102');
  assert.equal(encodeTcpClose(-2147483648).toString('hex'), '0280000000');
  assert.equal(encodeActiveNetworkState(1, true).toString('hex'), '0b0000000101');
  assert.equal(encodePing(0xffffffff).toString('hex'), '05ffffffff');
});

test('known framing fixtures preserve big-endian fields and opaque bytes', () => {
  const open = decodeFrame(hex('01 01020304 c0000201 01bb 1234'));
  assert.deepEqual([open.id, open.host, open.port, open.opaqueU16], [0x01020304, '192.0.2.1', 443, 0x1234]);
  const udp = decodeFrame(hex('04 c0000201 0035 0003 616263'));
  assert.deepEqual([udp.host, udp.port, udp.payload.toString()], ['192.0.2.1', 53, 'abc']);
  const udp2 = decodeFrame(hex('0e c0000202 1234 c6336401 0035 5678 0001 ff'));
  assert.deepEqual([udp2.sourceHost, udp2.destinationHost, udp2.opaqueU16, udp2.payload[0]], ['192.0.2.2', '198.51.100.1', 0x5678, 255]);
  const failure = decodeFrame(hex('0f c0000202 1234 c6336401 0035 0001 ff'));
  assert.equal(failure.typeName, 'UDP_V2_FAILURE');
  assert.equal(failure.opaqueU16, undefined);
  assert.equal(failure.payload.toString('hex'), 'ff');
  const pkg = decodeFrame(hex('0a aabb 0003 fffefd'));
  assert.equal(pkg.opaquePrefix.toString('hex'), 'aabb');
  assert.equal(pkg.payload.toString('hex'), 'fffefd');
  const start = decodeFrame(hex('10 00000005 0002 aabb'));
  assert.equal(start.extensions.toString('hex'), 'aabb');
});

test('every split point and one-byte chunks yield the same three frames', () => {
  const wire = hex('10 00000005 0000 03 01020304 0004 deadbeef 05 ffffffff');
  for (let split = 0; split <= wire.length; split++) {
    const parser = new Flow15StreamDecoder();
    const frames = [...parser.push(wire.subarray(0, split)), ...parser.push(wire.subarray(split))];
    assert.deepEqual(frames.map(frame => frame.typeName), ['HANDSHAKE_START', 'TCP_WRITE', 'PING']);
    assert.equal(frames[1].payload.toString('hex'), 'deadbeef');
    assert.equal(parser.finish().status, 'complete');
  }
  const parser = new Flow15StreamDecoder();
  const frames = [...wire].flatMap(byte => parser.push(Buffer.from([byte])));
  assert.equal(frames.length, 3);
  assert.equal(parser.finish().status, 'complete');
});

test('truncated length and trailing data are rejected or retained explicitly', () => {
  assert.throws(() => decodeFrame(hex('03 00000001 0002 ff')), /length mismatch/);
  assert.throws(() => decodeFrame(hex('05 00000001 ff')), /length mismatch/);
  assert.throws(() => decodeFrame(Buffer.alloc(0)), /empty frame/);
  const parser = new Flow15StreamDecoder();
  assert.equal(parser.push(hex('03 00000001 0002 ff')).length, 0);
  assert.equal(parser.finish().status, 'incomplete');
  assert.equal(parser.pending.toString('hex'), '03000000010002ff');
  assert.equal(parser.push(hex('00'))[0].payload.toString('hex'), 'ff00');
});

test('unknown opcode stops at the boundary without scanning for a plausible next header', () => {
  const parser = new Flow15StreamDecoder();
  assert.equal(parser.push(hex('05 00000001 7f aa 05 00000002')).length, 1);
  assert.equal(parser.finish().status, 'unknown');
  assert.equal(parser.pending.toString('hex'), '7faa0500000002');
  assert.equal(parser.push(hex('ff')).length, 0);
  assert.equal(parser.pending.toString('hex'), '7faa0500000002ff');
  assert.equal(decodeFrame(hex('7f aabb')).boundaryKnown, false);
});

test('unsigned payload-length boundary, signed IDs and bounded memory', () => {
  const encoded = encodeTcpWrite(2147483647, Buffer.alloc(65535, 0x5a));
  const decoded = decodeFrame(encoded);
  assert.equal(decoded.id, 2147483647);
  assert.equal(decoded.payload.length, 65535);
  assert.equal(decoded.payload.at(-1), 0x5a);
  assert.throws(() => encodeTcpWrite(1, Buffer.alloc(65536)), /16-bit/);
  assert.throws(() => encodeTcpClose(2147483648), /id/);
  assert.throws(() => encodeHandshake('TCP_OPEN'), /kind/);
  assert.throws(() => encodeActiveNetworkState(1, 1), /boolean/);
  assert.throws(() => encodePing(-1), /token/);
  const parser = new Flow15StreamDecoder({ maxBufferedBytes: 8 });
  parser.push(hex('03 00000001 0002 ff'));
  assert.throws(() => parser.push(hex('0000')), /buffer limit/);
  // A rejected input leaves previously retained bytes available.
  assert.equal(parser.pending.length, 8);
});
