/**
 * Offline codec for the Wear companion Flow/Flow15 sysproxy byte stream.
 * Specification: docs/spec/flow15-legacy.md
 * This module opens no sockets and performs no pairing, proxying or device I/O.
 */

export const UUIDS = Object.freeze({
  flow15: 'c3cfde9a-7300-11ec-90d6-0242ac120003',
  flow: 'fafbdd20-83f0-4389-addf-917ac9dae5b2',
  psmService: '3675c0ff-21a6-4f96-9984-09d4f10c64f9',
  psmCharacteristic: 'c8625a0b-28c8-4460-874d-0cf9a7538b46',
});

export const FRAME_TYPES = Object.freeze({
  TCP_OPEN: 1,
  TCP_CLOSE: 2,
  TCP_WRITE: 3,
  UDP_DATAGRAM: 4,
  PING: 5,
  ACK: 6,
  UDP_DATAGRAM_FAILURE: 7,
  PULL_REQUEST: 8,
  PULL_DATA: 9,
  PACKAGE_NAME: 10,
  ACTIVE_NETWORK_STATE: 11,
  PUSH_REQUEST: 12,
  PUSH_DATA: 13,
  UDP_V2_DATAGRAM: 14,
  UDP_V2_FAILURE: 15,
  HANDSHAKE_START: 16,
  HANDSHAKE_ACK: 17,
  HANDSHAKE_DONE: 18,
});

const NAMES = new Map(Object.entries(FRAME_TYPES).map(([name, code]) => [code, name]));
const FIXED_LENGTHS = new Map([[1, 13], [2, 5], [5, 5], [6, 5], [8, 9], [11, 6], [12, 9]]);
// [fixed header length, offset of unsigned big-endian payload length].
const VARIABLE_LENGTHS = new Map([
  [3, [7, 5]], [4, [9, 7]], [7, [9, 7]], [9, [7, 5]],
  [10, [5, 3]], [13, [7, 5]], [14, [17, 15]], [15, [15, 13]],
  [16, [7, 5]], [17, [7, 5]], [18, [7, 5]],
]);

function bytes(value, name = 'bytes') {
  if (!(value instanceof Uint8Array)) throw new TypeError(`${name} must be a Uint8Array or Buffer`);
  return Buffer.from(value);
}

function int(value, min, max, name) {
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new RangeError(`${name} must be an integer in ${min}..${max}`);
  }
  return value;
}

function id(value) { return int(value, -0x80000000, 0x7fffffff, 'id'); }
function u32(value, name) { return int(value, 0, 0xffffffff, name); }
function payloadBytes(value) {
  const payload = bytes(value, 'payload');
  if (payload.length > 0xffff) throw new RangeError('payload exceeds unsigned 16-bit length');
  return payload;
}

function lengthAtStart(input) {
  if (!input.length) return { status: 'incomplete', needed: 1 };
  const type = input[0];
  if (FIXED_LENGTHS.has(type)) {
    const length = FIXED_LENGTHS.get(type);
    return { status: input.length < length ? 'incomplete' : 'complete', needed: length, length };
  }
  if (VARIABLE_LENGTHS.has(type)) {
    const [headerLength, lengthOffset] = VARIABLE_LENGTHS.get(type);
    if (input.length < headerLength) return { status: 'incomplete', needed: headerLength };
    const length = headerLength + input.readUInt16BE(lengthOffset);
    return { status: input.length < length ? 'incomplete' : 'complete', needed: length, length };
  }
  return { status: 'unknown', reason: `unknown frame boundary for opcode 0x${type.toString(16).padStart(2, '0')}` };
}

/** Decode exactly one caller-delimited frame. Unknown opcodes retain all raw bytes. */
export function decodeFrame(value) {
  const raw = bytes(value);
  if (!raw.length) throw new RangeError('empty frame');
  const shape = lengthAtStart(raw);
  const type = raw[0];
  const result = { type, typeName: NAMES.get(type) ?? 'UNKNOWN', raw };
  if (shape.status === 'unknown') return { ...result, boundaryKnown: false, reason: shape.reason };
  if (shape.status !== 'complete' || shape.length !== raw.length) {
    throw new RangeError(`frame length mismatch: expected ${shape.needed}, received ${raw.length}`);
  }
  result.boundaryKnown = true;
  switch (type) {
    case 1:
      result.id = raw.readInt32BE(1);
      result.host = [...raw.subarray(5, 9)].join('.');
      result.port = raw.readUInt16BE(9);
      result.opaqueU16 = raw.readUInt16BE(11);
      break;
    case 2: case 6:
      result.id = raw.readInt32BE(1);
      break;
    case 3: case 9: case 13:
      result.id = raw.readInt32BE(1);
      result.payload = Buffer.from(raw.subarray(7));
      break;
    case 4: case 7:
      result.host = [...raw.subarray(1, 5)].join('.');
      result.port = raw.readUInt16BE(5);
      result.payload = Buffer.from(raw.subarray(9));
      break;
    case 5:
      result.token = raw.readUInt32BE(1);
      break;
    case 8: case 12:
      result.id = raw.readInt32BE(1);
      result.requestedBytes = raw.readInt32BE(5);
      break;
    case 10:
      // The SDK consumes this frame without interpreting its fields.
      result.opaquePrefix = Buffer.from(raw.subarray(1, 3));
      result.payload = Buffer.from(raw.subarray(5));
      break;
    case 11:
      result.networkType = raw.readInt32BE(1);
      result.networkStateByte = raw[5];
      // The official serializer writes isMetered in this byte.
      // Only 0 and 1 are observed writer values; preserve other bytes as unknown.
      result.isMetered = raw[5] === 0 ? false : raw[5] === 1 ? true : null;
      break;
    case 14: case 15:
      result.sourceHost = [...raw.subarray(1, 5)].join('.');
      result.sourcePort = raw.readUInt16BE(5);
      result.destinationHost = [...raw.subarray(7, 11)].join('.');
      result.destinationPort = raw.readUInt16BE(11);
      if (type === 14) result.opaqueU16 = raw.readUInt16BE(13);
      result.payload = Buffer.from(raw.subarray(type === 14 ? 17 : 15));
      break;
    case 16: case 17: case 18:
      result.version = raw.readInt32BE(1);
      result.extensions = Buffer.from(raw.subarray(7));
      break;
  }
  return result;
}

/**
 * Streaming offline parser. One Bluetooth read may contain partial or multiple
 * frames. Unknown opcodes stop parsing; pending bytes are never resynchronized
 * by guessing. Call finish() to detect a truncated capture.
 *
 * Both directions are understood: HANDSHAKE_ACK and UDP_V2_FAILURE have phone
 * serializers in the official client even though its watch-to-phone dispatch does not
 * accept them. Ordinary ACK (opcode 6) is consumed as a spurious incoming ACK.
 */
export class Flow15StreamDecoder {
  #pending = Buffer.alloc(0);
  #stoppedReason = null;
  #maxBufferedBytes;

  constructor({ maxBufferedBytes = 1024 * 1024 } = {}) {
    this.#maxBufferedBytes = int(maxBufferedBytes, 1, 0x7fffffff, 'maxBufferedBytes');
  }

  get pending() { return Buffer.from(this.#pending); }
  get stoppedReason() { return this.#stoppedReason; }

  push(value) {
    const chunk = bytes(value, 'chunk');
    if (this.#pending.length + chunk.length > this.#maxBufferedBytes) {
      throw new RangeError('offline decoder buffer limit exceeded; feed smaller chunks or raise maxBufferedBytes');
    }
    this.#pending = Buffer.concat([this.#pending, chunk]);
    const frames = [];
    while (!this.#stoppedReason && this.#pending.length) {
      const shape = lengthAtStart(this.#pending);
      if (shape.status === 'unknown') {
        this.#stoppedReason = shape.reason;
        break;
      }
      if (shape.status === 'incomplete') break;
      frames.push(decodeFrame(this.#pending.subarray(0, shape.length)));
      this.#pending = this.#pending.subarray(shape.length);
    }
    // Copy so a small tail cannot retain the entire previous trace chunk.
    this.#pending = Buffer.from(this.#pending);
    return frames;
  }

  finish() {
    return {
      status: this.#stoppedReason ? 'unknown' : this.#pending.length ? 'incomplete' : 'complete',
      reason: this.#stoppedReason,
      remainder: this.pending,
    };
  }
}

/**
 * Offline reconstruction of the 7-byte handshake header plus opaque extensions.
 * Only the default ACK (version 5, empty extensions) is observed phone output.
 * Other arguments construct synthetic fixtures from the parser's layout; they
 * do not claim that the APK sends them or that any watch accepts them.
 */
export function encodeHandshake(kind = 'HANDSHAKE_ACK', version = 5, extensions = Buffer.alloc(0)) {
  const type = typeof kind === 'string' ? FRAME_TYPES[kind] : kind;
  if (![16, 17, 18].includes(type)) throw new RangeError('kind must be HANDSHAKE_START, HANDSHAKE_ACK or HANDSHAKE_DONE');
  int(version, -0x80000000, 0x7fffffff, 'version');
  const payload = payloadBytes(extensions);
  const raw = Buffer.alloc(7 + payload.length);
  raw[0] = type;
  raw.writeInt32BE(version, 1);
  raw.writeUInt16BE(payload.length, 5);
  payload.copy(raw, 7);
  return raw;
}

function encodeIdPayload(type, connectionId, value) {
  id(connectionId);
  const payload = payloadBytes(value);
  const raw = Buffer.alloc(7 + payload.length);
  raw[0] = type;
  raw.writeInt32BE(connectionId, 1);
  raw.writeUInt16BE(payload.length, 5);
  payload.copy(raw, 7);
  return raw;
}

export function encodeTcpWrite(connectionId, payload) { return encodeIdPayload(3, connectionId, payload); }
export function encodePullData(connectionId, payload) { return encodeIdPayload(9, connectionId, payload); }

export function encodeTcpClose(connectionId) {
  id(connectionId);
  const raw = Buffer.alloc(5);
  raw[0] = 2;
  raw.writeInt32BE(connectionId, 1);
  return raw;
}

export function encodeActiveNetworkState(networkType, isMetered) {
  int(networkType, -0x80000000, 0x7fffffff, 'networkType');
  if (typeof isMetered !== 'boolean') throw new TypeError('isMetered must be boolean');
  const raw = Buffer.alloc(6);
  raw[0] = 11;
  raw.writeInt32BE(networkType, 1);
  raw[5] = isMetered ? 1 : 0;
  return raw;
}

export function encodePing(token) {
  u32(token, 'token');
  const raw = Buffer.alloc(5);
  raw[0] = 5;
  raw.writeUInt32BE(token, 1);
  return raw;
}
