/**
 * Offline GMS record framing (GMS 23.25.18 and 24.28.35; see docs/spec/transport.md §2).
 * Each record is an i32BE length, then a cleartext MessagePiece or conditional ciphertext.
 * Explicit cleartext parsing only. This module implements no crypto or sockets.
 */
import { createHash } from 'node:crypto';

export const GMS_DATA_UUID = '5e8945b0-9525-11e3-a5e2-0800200c9a66';
const LIMIT = 1024 * 1024;
const utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
const bytes = value => {
  if (!(value instanceof Uint8Array)) throw new TypeError('Expected Buffer or Uint8Array');
  return Buffer.from(value);
};
const digest = value => createHash('sha1').update(value).digest('base64url');

function readVarint(data, offset) {
  let value = 0n;
  for (let index = 0; index < 10; index++) {
    if (offset >= data.length) throw new RangeError('Truncated protobuf varint');
    const byte = data[offset++];
    if (index === 9 && byte > 1) throw new RangeError('Protobuf varint exceeds uint64');
    value |= BigInt(byte & 0x7f) << BigInt(7 * index);
    if (!(byte & 0x80)) return [value, offset];
  }
  throw new RangeError('Protobuf varint exceeds 10 bytes');
}

function varint(value) {
  let number = BigInt(value);
  if (number < 0n) number = BigInt.asUintN(64, number);
  if (number > 0xffffffffffffffffn) throw new RangeError('Value exceeds uint64');
  const output = [];
  do {
    const low = Number(number & 0x7fn);
    number >>= 7n;
    output.push(low | (number ? 0x80 : 0));
  } while (number);
  return Buffer.from(output);
}

/** Preserve unknown fields, their byte offsets and exact encoded bytes. */
export function decodeProtoFields(input, { maxBytes = LIMIT } = {}) {
  const data = bytes(input);
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 1 || data.length > maxBytes) throw new RangeError('Offline protobuf size limit');
  const fields = [];
  let offset = 0;
  while (offset < data.length) {
    const start = offset;
    const [tag, afterTag] = readVarint(data, offset);
    offset = afterTag;
    const field = Number(tag >> 3n);
    const wireType = Number(tag & 7n);
    if (field < 1 || field > 536870911) throw new RangeError('Invalid protobuf field number');
    let value;
    if (wireType === 0) [value, offset] = readVarint(data, offset);
    else if (wireType === 2) {
      const [length, next] = readVarint(data, offset);
      if (length > BigInt(data.length - next)) throw new RangeError('Truncated protobuf bytes');
      offset = next + Number(length);
      value = Buffer.from(data.subarray(next, offset));
    } else if (wireType === 1 || wireType === 5) {
      const size = wireType === 1 ? 8 : 4;
      if (offset + size > data.length) throw new RangeError('Truncated protobuf fixed field');
      value = Buffer.from(data.subarray(offset, offset + size));
      offset += size;
    } else throw new RangeError(`Unsupported protobuf wire type ${wireType}; no guessed decoding`);
    fields.push({ field, wireType, value, offset: start, raw: Buffer.from(data.subarray(start, offset)) });
  }
  return fields;
}

function get(fields, field, wireType, required = false) {
  const matches = fields.filter(item => item.field === field);
  if (!matches.length) {
    if (required) throw new Error(`Missing required field ${field}`);
    return undefined;
  }
  if (matches.length > 1) throw new Error(`Duplicate singular field ${field}; ambiguous offline input`);
  if (matches[0].wireType !== wireType) throw new Error(`Wrong wire type for field ${field}`);
  return matches[0].value;
}

const int32 = value => Number(BigInt.asIntN(32, value));
function number32(value, name) {
  if (!Number.isInteger(value) || value < -2147483648 || value > 2147483647) throw new RangeError(`Invalid int32 ${name}`);
  return value;
}
const byteField = (field, value) => Buffer.concat([varint(field * 8 + 2), varint(value.length), value]);
const intField = (field, value) => Buffer.concat([varint(field * 8), varint(value)]);

export function decodeMessagePiece(input) {
  const fields = decodeProtoFields(input);
  const fragment = get(fields, 1, 2, true);
  const hash = utf8.decode(get(fields, 2, 2, true));
  const pieceIndex = int32(get(fields, 3, 0, true));
  const pieceCount = int32(get(fields, 4, 0, true));
  const queue = get(fields, 5, 0);
  if (pieceCount < 1 || pieceIndex < 1 || pieceIndex > pieceCount) throw new RangeError('Invalid one-based MessagePiece index/count');
  if (!/^[A-Za-z0-9_-]{27}$/.test(hash)) throw new Error('Expected SHA1 base64url without padding');
  const queueId = queue === undefined ? -1 : int32(queue);
  if (pieceCount > 1 && queueId === -1) throw new Error('Multi-piece message requires queueId other than -1');
  return { fragment, digest: hash, pieceIndex, pieceCount, queueId, unknownFields: fields.filter(item => item.field > 5) };
}

/** Whole-message digest as computed by GMS: SHA1 + Android Base64 flags 11.
 * fragmentBytes defaults to an offline tool choice, not a negotiated peer limit.
 */
export function encodeMessagePieces(input, { fragmentBytes = 8192, queueId = -1 } = {}) {
  const data = bytes(input);
  if (!data.length || data.length > LIMIT) throw new RangeError('Offline message length must be 1..1 MiB');
  if (!Number.isSafeInteger(fragmentBytes) || fragmentBytes < 1 || fragmentBytes > LIMIT) throw new RangeError('Invalid fragmentBytes');
  number32(queueId, 'queueId');
  const hash = Buffer.from(digest(data));
  const count = Math.ceil(data.length / fragmentBytes);
  if (count > 1 && queueId === -1) throw new Error('Multi-piece message requires explicit queueId other than -1');
  const result = [];
  for (let index = 0; index < count; index++) {
    const fragment = data.subarray(index * fragmentBytes, (index + 1) * fragmentBytes);
    const encoded = [byteField(1, fragment), byteField(2, hash), intField(3, index + 1), intField(4, count)];
    // A single piece omits queueId; fragments carry it.
    if (count > 1 || queueId !== -1) encoded.push(intField(5, queueId));
    const piece = Buffer.concat(encoded);
    if (piece.length > LIMIT) throw new RangeError('Encoded piece exceeds offline record limit; choose smaller fragmentBytes');
    result.push(piece);
  }
  return result;
}

/** Offline all-pieces operation; sorting is a tool feature, not a peer guarantee. */
export function reassembleMessagePieces(inputs) {
  if (!Array.isArray(inputs) || inputs.length < 1 || inputs.length > LIMIT) throw new RangeError('Invalid piece collection');
  const pieces = inputs.map(decodeMessagePiece).sort((a, b) => a.pieceIndex - b.pieceIndex);
  const first = pieces[0];
  if (pieces.length !== first.pieceCount) throw new Error('Incomplete piece collection');
  let size = 0;
  for (let index = 0; index < pieces.length; index++) {
    const piece = pieces[index];
    if (piece.pieceIndex !== index + 1 || piece.pieceCount !== first.pieceCount || piece.digest !== first.digest || piece.queueId !== first.queueId) {
      throw new Error('Conflicting, duplicate or mismatched MessagePiece collection');
    }
    size += piece.fragment.length;
    if (size > LIMIT) throw new RangeError('Offline reassembly size limit');
  }
  const message = Buffer.concat(pieces.map(piece => piece.fragment));
  if (digest(message) !== first.digest) throw new Error('MessagePiece digest mismatch');
  return message;
}

const MESSAGE_KINDS = new Map([[4, 'SET_ASSET'], [5, 'ACK_ASSET'], [6, 'FETCH_ASSET'], [7, 'CONNECT'], [8, 'SYNC_START'], [9, 'SET_DATA_ITEM'], [10, 'RPC_REQUEST'], [11, 'HEARTBEAT'], [12, 'FILE_PIECE'], [16, 'CHANNEL_RPC_REQUEST'], [17, 'CRYPTO'], [18, 'RPC_SERVICE_REQUEST'], [20, 'CONTROL']]);

/** Names only source-confirmed fields; all other bytes remain available. */
export function decodeWearMessage(input) {
  const fields = decodeProtoFields(input);
  const messages = [];
  for (const item of fields) {
    const kind = MESSAGE_KINDS.get(item.field);
    if (!kind || item.wireType !== 2) continue;
    const nested = decodeProtoFields(item.value);
    const entry = { field: item.field, kind, fields: nested, raw: item.value };
    if (kind === 'CONNECT') {
      const text = (field, required = false) => {
        const value = get(nested, field, 2, required);
        return value === undefined ? undefined : utf8.decode(value);
      };
      entry.nodeId = text(1, true);
      entry.nodeName = text(2, true);
      const androidId = get(nested, 3, 0);
      if (androidId !== undefined) entry.androidId = BigInt.asIntN(64, androidId);
      for (const [field, name] of [[4, 'connectionVersion'], [5, 'wireVersion'], [6, 'minWireVersion'], [11, 'senderSdkVersion']]) {
        const value = get(nested, field, 0);
        if (value !== undefined) entry[name] = int32(value);
      }
      entry.networkId = text(7);
      entry.companionPackage = text(8);
      const migrating = get(nested, 9, 0);
      if (migrating !== undefined) entry.isMigrating = migrating !== 0n;
      entry.previousNodeId = text(10);
    } else if (['RPC_REQUEST', 'CHANNEL_RPC_REQUEST', 'RPC_SERVICE_REQUEST'].includes(kind)) {
      for (const [field, name] of [[2, 'packageName'], [3, 'packageDigest'], [4, 'targetNodeId'], [6, 'path'], [8, 'sourceNodeId']]) {
        const value = get(nested, field, 2);
        if (value !== undefined) entry[name] = utf8.decode(value);
      }
      entry.payload = get(nested, 7, 2);
    }
    messages.push(entry);
  }
  return { messages, fields };
}

export function encodeGmsRecord(input) {
  const data = bytes(input);
  if (!data.length || data.length > LIMIT) throw new RangeError('Offline record length must be 1..1 MiB');
  const header = Buffer.alloc(4);
  header.writeInt32BE(data.length);
  return Buffer.concat([header, data]);
}

/** Length framing only: the payload may be encrypted. No automatic protobuf parse. */
export class GmsRecordStreamDecoder {
  #pending = Buffer.alloc(0);
  push(input) {
    const chunk = bytes(input);
    const records = [];
    let offset = 0;
    while (offset < chunk.length) {
      if (this.#pending.length < 4) {
        const take = Math.min(4 - this.#pending.length, chunk.length - offset);
        this.#pending = Buffer.concat([this.#pending, chunk.subarray(offset, offset + take)]);
        offset += take;
        if (this.#pending.length < 4) break;
      }
      const size = this.#pending.readInt32BE(0);
      if (size < 1 || size > LIMIT) throw new RangeError('Invalid or excessive GMS record length');
      const take = Math.min(size + 4 - this.#pending.length, chunk.length - offset);
      this.#pending = Buffer.concat([this.#pending, chunk.subarray(offset, offset + take)]);
      offset += take;
      if (this.#pending.length === size + 4) {
        records.push(Buffer.from(this.#pending.subarray(4)));
        this.#pending = Buffer.alloc(0);
      }
    }
    return records;
  }
  finish() { return { status: this.#pending.length ? 'incomplete' : 'complete', remainder: Buffer.from(this.#pending) }; }
}
