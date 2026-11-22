/**
 * Offline codec for the legacy Mobvoi BLE message frame.
 * This codec does not select or open a Bluetooth device. Applicability to
 * current TicWatch models has NOT been established.
 *
 * Specification: docs/spec/flow15-legacy.md
 */

export const LEGACY_BLE_MAGIC_HEX = '97918d89';
export const LEGACY_BLE_UUIDS = Object.freeze({
  service: '735dc4fa-348e-11e7-a919-92ebcb67fe33',
  receive: 'c5b1d8c4-3496-11e7-b687-92ebcb67fe33',
  transmit: 'c5b1dc5c-3496-11e7-b687-92ebcb67fe33',
  cccd: '00002902-0000-1000-8000-00805f9b34fb',
});

const MAGIC = Buffer.from(LEGACY_BLE_MAGIC_HEX, 'hex');
const HEADER_BYTES = 8;
const DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024;

function bytes(value) {
  if (!(value instanceof Uint8Array)) {
    throw new TypeError('Expected a Buffer or Uint8Array');
  }
  return Buffer.from(value);
}

function checkLimit(value) {
  if (!Number.isInteger(value) || value < 1 || value > 0x7fffffff) {
    throw new RangeError('maxPayloadBytes must be an integer from 1 to 2147483647');
  }
}

/** Encode exactly one nonempty frame, before characteristic-value splitting. */
export function encodeLegacyBleFrame(payload, { maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES } = {}) {
  checkLimit(maxPayloadBytes);
  const data = bytes(payload);
  if (data.length < 1 || data.length > maxPayloadBytes) {
    throw new RangeError(`Payload length must be from 1 to ${maxPayloadBytes}`);
  }
  const header = Buffer.alloc(HEADER_BYTES);
  MAGIC.copy(header);
  header.writeInt32LE(data.length, 4);
  return Buffer.concat([header, data]);
}

/**
 * valueBytes is the usable characteristic value length, NOT ATT_MTU.
 * The central helper uses ATT_MTU - 3, initially 20.
 */
export function packetizeLegacyBle(payload, { valueBytes = 20, maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES } = {}) {
  if (!Number.isInteger(valueBytes) || valueBytes < HEADER_BYTES) {
    throw new RangeError('valueBytes must be an integer of at least 8');
  }
  const frame = encodeLegacyBleFrame(payload, { maxPayloadBytes });
  const packets = [];
  for (let offset = 0; offset < frame.length; offset += valueBytes) {
    packets.push(Buffer.from(frame.subarray(offset, offset + valueBytes)));
  }
  return packets;
}

/**
 * Incremental offline decoder. Valid-frame layout follows the APK, while
 * malformed lengths throw and the search handles overlapping magic prefixes.
 * The configurable 1 MiB cap is our resource bound, not a protocol constant.
 */
export class LegacyBleDecoder {
  #buffer = Buffer.alloc(0);
  #maxPayloadBytes;
  #discardedBytes = 0;

  constructor({ maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES } = {}) {
    checkLimit(maxPayloadBytes);
    this.#maxPayloadBytes = maxPayloadBytes;
  }

  get bufferedBytes() {
    return this.#buffer.length;
  }

  get discardedBytes() {
    return this.#discardedBytes;
  }

  reset() {
    this.#buffer = Buffer.alloc(0);
  }

  feed(input) {
    const data = bytes(input);
    this.#buffer = Buffer.concat([this.#buffer, data]);
    const messages = [];
    while (this.#buffer.length >= MAGIC.length) {
      const at = this.#buffer.indexOf(MAGIC);
      if (at < 0) {
        // Retain enough bytes for a magic value crossing a chunk boundary.
        const discarded = this.#buffer.length - (MAGIC.length - 1);
        this.#discardedBytes += discarded;
        this.#buffer = Buffer.from(this.#buffer.subarray(discarded));
        break;
      }
      if (at > 0) {
        this.#discardedBytes += at;
        this.#buffer = Buffer.from(this.#buffer.subarray(at));
      }
      if (this.#buffer.length < HEADER_BYTES) break;
      const length = this.#buffer.readInt32LE(4);
      if (length < 1 || length > this.#maxPayloadBytes) {
        this.reset();
        throw new RangeError(`Invalid legacy BLE payload length: ${length}`);
      }
      if (this.#buffer.length < HEADER_BYTES + length) break;
      messages.push(Buffer.from(this.#buffer.subarray(HEADER_BYTES, HEADER_BYTES + length)));
      this.#buffer = Buffer.from(this.#buffer.subarray(HEADER_BYTES + length));
    }
    return messages;
  }

  /** Assert that an offline recording ends at an exact frame boundary. */
  finish() {
    if (this.#buffer.length !== 0) {
      throw new Error(`Incomplete legacy BLE stream: ${this.#buffer.length} bytes remain`);
    }
  }
}
