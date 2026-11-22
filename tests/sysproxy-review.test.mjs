// Independent vectors for the Flow15 frame boundaries in docs/spec/flow15-legacy.md.
// Synthetic bytes: these tests do not establish device interoperability.
import test from 'node:test';
import assert from 'node:assert/strict';
import { decodeFrame, Flow15StreamDecoder, encodeActiveNetworkState } from '../src/sysproxy.mjs';

const hex = value => Buffer.from(value.replaceAll(' ', ''), 'hex');

test('PING consumes exactly 5 bytes and leaves the following ACK aligned', () => {
  // PING is opcode + 32-bit echo; a 5-byte ACK is consumed as spurious.
  const parser = new Flow15StreamDecoder();
  const frames = parser.push(hex('05 01234567 06 ffffffff'));
  assert.deepEqual(frames.map(p => p.typeName), ['PING', 'ACK']);
  assert.equal(frames[0].token, 0x01234567);
  assert.equal(frames[1].id, -1);
  assert.equal(parser.finish().status, 'complete');
});

test('UDP_V2_FAILURE omits the UDP_V2_DATAGRAM extra u16, including at zero length', () => {
  // Layout: opcode/src4/srcPort2/dst4/dstPort2/length2/payload.
  const failure = hex('0f c0000201 0001 c6336401 0002 0000');
  assert.equal(failure.length, 15);
  assert.deepEqual(
    [decodeFrame(failure).sourcePort, decodeFrame(failure).destinationPort, decodeFrame(failure).payload.length],
    [1, 2, 0],
  );
  const parser = new Flow15StreamDecoder();
  const frames = parser.push(Buffer.concat([failure, hex('05 01020304')]));
  assert.deepEqual(frames.map(p => p.typeName), ['UDP_V2_FAILURE', 'PING']);
  assert.equal(parser.finish().status, 'complete');
});

test('TCP_OPEN preserves unsigned final u16 without inferring its semantics', () => {
  // Two u16 values follow the IPv4 host and are read as unsigned.
  const open = decodeFrame(hex('01 ffffffff cb007109 ffff ffff'));
  assert.deepEqual([open.id, open.host, open.port, open.opaqueU16], [-1, '203.0.113.9', 65535, 65535]);
});

test('UDP v2 payload length has unsigned high-bit semantics and differs from its opaque u16', () => {
  // Length is at offset 15, after the opaque u16.
  const raw = Buffer.concat([hex('0e c0000201 1234 c6336401 0035 5678 8000'), Buffer.alloc(32768, 0xa5)]);
  const frame = decodeFrame(raw);
  assert.equal(frame.opaqueU16, 0x5678);
  assert.equal(frame.payload.length, 32768);
  assert.equal(frame.payload[32767], 0xa5);
});

test('handshake field values are retained without claiming version negotiation succeeded', () => {
  // The official client logs this int but does not compare it to the ACK's 5.
  for (const type of ['10', '12']) {
    const frame = decodeFrame(hex(`${type} ffffffff 0001 aa`));
    assert.equal(frame.version, -1);
    assert.equal(frame.extensions.toString('hex'), 'aa');
  }
});

test('ACTIVE_NETWORK_STATE bool means isMetered, independently of network availability', () => {
  // Connected status carries networkType and isMetered; disconnected still serializes isMetered=false.
  assert.equal(encodeActiveNetworkState(1, false).toString('hex'), '0b0000000100');
  const metered = decodeFrame(hex('0b 00000000 01'));
  assert.equal(metered.networkType, 0);
  assert.equal(metered.isMetered, true);
  const disconnected = decodeFrame(hex('0b ffffffff 00'));
  assert.equal(disconnected.networkType, -1);
  assert.equal(disconnected.isMetered, false);
  // Preserve noncanonical capture bytes without inventing a JNI interpretation.
  const noncanonical = decodeFrame(hex('0b 00000001 02'));
  assert.equal(noncanonical.networkStateByte, 2);
  assert.equal(noncanonical.isMetered, null);
});
