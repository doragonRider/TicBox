import test from 'node:test';
import assert from 'node:assert/strict';
import {
  decodeProtoFields, decodeMessagePiece, encodeMessagePieces, reassembleMessagePieces,
  decodeWearMessage, encodeGmsRecord, GmsRecordStreamDecoder,
} from '../src/gms-record.mjs';

const hex = value => Buffer.from(value.replaceAll(' ', ''), 'hex');

test('MessagePiece independent SHA1 vector uses unpadded URL-safe Base64 and one-based indices', () => {
  // SHA1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d.
  // '-' replaces '+': the actual Base64url string is qZk-NkcGgWq6PiVxeFDCbJzQ2J0.
  const expected = Buffer.concat([
    hex('0a03616263121b'), Buffer.from('qZk-NkcGgWq6PiVxeFDCbJzQ2J0'), hex('18012001'),
  ]);
  assert.deepEqual(encodeMessagePieces(Buffer.from('abc')), [expected]);
  assert.equal(decodeMessagePiece(expected).queueId, -1);
  assert.equal(reassembleMessagePieces([expected]).toString(), 'abc');
  // A syntactically valid but incorrect whole-message digest must not be accepted.
  const wrongDigest = Buffer.from(expected);
  wrongDigest[7] = 65;
  assert.throws(() => reassembleMessagePieces([wrongDigest]), /digest mismatch/);
});

test('fragmentation reassembles only one complete digest and queue, preserving signed queue IDs', () => {
  const payload = Buffer.from('TicWatch 🕒 protobuf fragments');
  const pieces = encodeMessagePieces(payload, { fragmentBytes: 3, queueId: -2147483648 });
  assert.equal(decodeMessagePiece(pieces[0]).queueId, -2147483648);
  assert.equal(decodeMessagePiece(pieces.at(-1)).pieceIndex, pieces.length);
  assert.deepEqual(reassembleMessagePieces([...pieces].reverse()), payload);
  assert.throws(() => reassembleMessagePieces(pieces.slice(1)), /Incomplete/);
  assert.throws(() => reassembleMessagePieces([pieces[0], pieces[0], ...pieces.slice(2)]), /duplicate|Conflicting/);
  const other = encodeMessagePieces(payload, { fragmentBytes: 3, queueId: 7 });
  assert.throws(() => reassembleMessagePieces([other[0], ...pieces.slice(1)]), /mismatched/);
  const changed = pieces.map(piece => Buffer.from(piece));
  changed[0][2] ^= 1;
  assert.throws(() => reassembleMessagePieces(changed), /digest mismatch/);
});

test('record framing preserves opaque ciphertext at every stream split and with coalesced records', () => {
  // ff and 00 are deliberately invalid protobuf tags: framing must not parse them.
  const wire = hex('00000003ff0011 0000000200ff');
  for (let split = 0; split <= wire.length; split++) {
    const decoder = new GmsRecordStreamDecoder();
    const records = [...decoder.push(wire.subarray(0, split)), ...decoder.push(wire.subarray(split))];
    assert.deepEqual(records, [hex('ff0011'), hex('00ff')]);
    assert.equal(decoder.finish().status, 'complete');
  }
  const decoder = new GmsRecordStreamDecoder();
  assert.deepEqual([...wire].flatMap(byte => decoder.push(Buffer.from([byte]))), [hex('ff0011'), hex('00ff')]);
  assert.equal(encodeGmsRecord(hex('ff0011')).toString('hex'), '00000003ff0011');
  // Aggregate chunks may exceed the per-record limit; segmentation cannot change validity.
  const large = encodeGmsRecord(Buffer.alloc(524289, 0x5a));
  const coalesced = new GmsRecordStreamDecoder().push(Buffer.concat([large, large]));
  assert.deepEqual(coalesced.map(item => item.length), [524289, 524289]);
});

test('partial records retain exact bytes and signed invalid lengths fail closed', () => {
  for (const sample of ['00', '000000', '00000003aabb']) {
    const decoder = new GmsRecordStreamDecoder();
    assert.deepEqual(decoder.push(hex(sample)), []);
    assert.deepEqual(decoder.finish(), { status: 'incomplete', remainder: hex(sample) });
  }
  for (const sample of ['00000000', 'ffffffff', '80000000', '00100001']) {
    assert.throws(() => new GmsRecordStreamDecoder().push(hex(sample)), /length/);
  }
  assert.throws(() => encodeGmsRecord(Buffer.alloc(0)), /length/);
  assert.throws(() => encodeGmsRecord(Buffer.alloc(1048577)), /length/);
});

test('protobuf walker preserves unknown wire fields and uint64 without rounding', () => {
  const data = hex('08ffffffffffffffffff01 91010102030405060708 9a0103616263 a501aabbccdd');
  const fields = decodeProtoFields(data);
  assert.equal(fields[0].value, 18446744073709551615n);
  assert.deepEqual(fields.map(item => [item.field, item.wireType, item.offset]), [[1, 0, 0], [18, 1, 11], [19, 2, 21], [20, 5, 27]]);
  assert.deepEqual(Buffer.concat(fields.map(item => item.raw)), data);
  for (const sample of ['00', '80', '0880808080808080808080', '08ffffffffffffffffff02', '0a03ff', '090000', '0d0000', '0b']) {
    assert.throws(() => decodeProtoFields(hex(sample)));
  }
});

test('MessagePiece reports missing, duplicate, wrong-type and impossible count fields', () => {
  const valid = encodeMessagePieces(hex('3a060a0161120162'))[0];
  assert.throws(() => decodeMessagePiece(Buffer.alloc(0)), /required field 1/);
  assert.throws(() => decodeMessagePiece(Buffer.concat([valid, hex('1801')])), /Duplicate/);
  assert.throws(() => decodeMessagePiece(Buffer.concat([valid, hex('2800')]).subarray(2)), /wire type|protobuf|required/);
  const zero = Buffer.from(valid);
  zero[zero.length - 1] = 0;
  assert.throws(() => decodeMessagePiece(zero), /index\/count/);
  assert.throws(() => encodeMessagePieces(hex('01'), { queueId: 2147483648 }), /int32/);
  assert.throws(() => encodeMessagePieces(hex('01'), { fragmentBytes: 0 }), /fragmentBytes/);
  assert.throws(() => encodeMessagePieces(Buffer.alloc(8193)), /explicit queueId/);
  assert.throws(() => encodeMessagePieces(Buffer.alloc(1048576), { fragmentBytes: 1048576 }), /choose smaller fragmentBytes/);
  const multipart = encodeMessagePieces(Buffer.from('ab'), { fragmentBytes: 1, queueId: 2 });
  // Remove the final tag5/value pair, leaving the protobuf default -1.
  assert.throws(() => decodeMessagePiece(multipart[0].subarray(0, -2)), /queueId/);
});

test('WearMessage RPC is field 10, SET_DATA_ITEM is field 9, and unknowns remain raw', () => {
  // requestId=7, pkg="p", cert="c", target="t", unknown_zero=0, path="/x", data=ff, source="s".
  const rpc = hex('08071201701a0163220174280032022f783a01ff420173');
  const payload = Buffer.concat([hex('52'), Buffer.from([rpc.length]), rpc, hex('f80101')]);
  const decoded = decodeWearMessage(payload);
  assert.equal(decoded.messages[0].kind, 'RPC_REQUEST');
  assert.deepEqual(
    [decoded.messages[0].packageName, decoded.messages[0].packageDigest, decoded.messages[0].targetNodeId, decoded.messages[0].path, decoded.messages[0].sourceNodeId],
    ['p', 'c', 't', '/x', 's'],
  );
  assert.deepEqual(decoded.messages[0].payload, hex('ff'));
  assert.equal(decoded.fields.at(-1).field, 31);
  assert.equal(decodeWearMessage(hex('4a020801')).messages[0].kind, 'SET_DATA_ITEM');
});

test('CONNECT retains signed int64 android_id and distinct connection, wire and sender SDK versions', () => {
  const connect = hex('0a016e12014e18ffffffffffffffffff012003280130003a036e65744203706b675821');
  const decoded = decodeWearMessage(Buffer.concat([hex('3a'), Buffer.from([connect.length]), connect])).messages[0];
  assert.equal(decoded.kind, 'CONNECT');
  assert.equal(decoded.androidId, -1n);
  assert.equal(decoded.senderSdkVersion, 33);
  assert.deepEqual([decoded.nodeId, decoded.nodeName, decoded.networkId, decoded.companionPackage], ['n', 'N', 'net', 'pkg']);
  assert.deepEqual(decoded.fields.filter(field => [4, 5, 6].includes(field.field)).map(field => field.value), [3n, 1n, 0n]);
});

test('protobuf strings preserve literal U+FEFF and reject invalid UTF8 instead of replacing bytes', () => {
  assert.equal(decodeWearMessage(hex('3a090a04efbbbf6e12014e')).messages[0].nodeId, '\ufeffn');
  assert.throws(() => decodeWearMessage(hex('3a060a01ff12014e')), /encoded data/);
});
