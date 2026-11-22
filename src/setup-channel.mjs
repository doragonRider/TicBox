/**
 * Offline /setup/channel payload codec (see docs/spec/pairing-setup.md). These frames live inside
 * a Wear ChannelClient stream. They are not raw RFCOMM MessagePiece records.
 * Implements no Bluetooth, channel creation, activation or network operations.
 */
import { decodeProtoFields } from './gms-record.mjs';
import { decodeGmsStruct } from './gms-messages.mjs';

export const SETUP_CHANNEL_PATH = '/setup/channel';
const LIMIT = 1024 * 1024; // Tool admission bound, not a discovered watch MTU.
const TYPES = ['TYPE_UNKNOWN', 'COMMAND', 'EVENT', 'STATUS', 'SYSTEM_INFO', 'TIME', 'TIME_ZONE', 'CONNECTION_INFO', 'ENROLLMENT_INFO'];
const COMMANDS = ['TYPE_UNKNOWN', 'GET_STATUS', 'GET_SYSTEM_INFO', 'STOP', 'SYNC_TIME', 'GET_CONNECTION_INFO'];
const STATES = ['STATE_UNKNOWN', 'READY', 'COMPLETE_PENDING', 'COMPLETE', 'UPDATING', 'OOBE_DONE'];
const EVENTS = ['TYPE_UNKNOWN', 'STARTED', 'STOPPED', 'OPTIN_UPDATED', 'UNKNOWN_COMMAND', 'UNSUPPORTED_COMMAND'];
const VERSIONS = ['VERSION_UNKNOWN','EMERALD_AND_BELOW','FELDSPAR','IOLITE','KYANITE'];
const CAPABILITIES = ['CAPABILITY_UNKNOWN','WIFI','SPEAKER','CELLULAR','ACCOUNTS','LEGACY_CALLING','SETUP_PROTOCOMM_CHANNEL'];
const EDITIONS = ['EDITION_UNKNOWN','GLOBAL','LOCAL'];
const SETUP_CAPABILITIES = ['SETUP_CAPABILITY_UNKNOWN','RESTORE_SUPPORTED','GMS_CHECKIN','DAY0_OTA','FACTORY_RESET_PROTECTION','TETHER_CONFIG','SIGNALS_OOBE_DONE','TALKBACK_MISTRIGGER','PRELOADED_ASSISTANT','STANDALONE_CONNECTIVITY','RESTRICTED_CONNECTIVITY'];
const OPTIN_TYPES = ['TYPE_UNKNOWN','ACCOUNTS','CLOUDSYNC','COMMUNICATION','CALENDAR','NOTIFICATIONS','LOGGING','LOCATION'];
const OPTIN_STATES = ['STATE_UNKNOWN','ACCEPTED','DECLINED','SKIPPED'];
const utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });

function bytes(value) {
  if (!(value instanceof Uint8Array)) throw new TypeError('Expected Buffer or Uint8Array');
  return Buffer.from(value);
}

// Refuse ambiguous singular input rather than invent a peer's merge policy.
function get(fields, number, wireType) {
  const matches = fields.filter(field => field.field === number);
  if (matches.length > 1) throw new Error(`Duplicate singular setup field ${number}`);
  if (!matches.length) return undefined;
  if (matches[0].wireType !== wireType) throw new Error(`Wrong wire type for setup field ${number}`);
  return matches[0].value;
}
function enumValue(value, names) {
  const number = Number(BigInt.asIntN(32, value ?? 0n));
  return { value: number, name: names[number] ?? null, explicit: value !== undefined };
}
const unknown = (fields, numbers) => fields.filter(field => !numbers.includes(field.field));

/** Watch-baseline schema; newer phone-only enum values retain numeric identity. */
export function decodeSetupMessage(input) {
  const fields = decodeProtoFields(input);
  const result = { type: enumValue(get(fields, 1, 0), TYPES), fields };
  const command = get(fields, 2, 2);
  if (command !== undefined) {
    const nested = decodeProtoFields(command);
    const id = get(nested, 2, 0);
    result.command = {
      type: enumValue(get(nested, 1, 0), COMMANDS),
      id: BigInt.asIntN(64, id ?? 0n), idExplicit: id !== undefined,
      unknownFields: unknown(nested, [1, 2]), fields: nested,
    };
  }
  const event = get(fields, 3, 2);
  if (event !== undefined) {
    const nested = decodeProtoFields(event);
    const commandId = get(nested, 3, 0);
    result.event = {
      type: enumValue(get(nested, 1, 0), EVENTS), optin: get(nested, 2, 2),
      commandId: BigInt.asIntN(64, commandId ?? 0n), commandIdExplicit: commandId !== undefined,
      unknownFields: unknown(nested, [1, 2, 3]), fields: nested,
    };
    if(result.event.optin!==undefined) {
      const optin=decodeGmsStruct('SetupOptinStatus',result.event.optin);
      result.event.optinStatus={...optin,type:enumValue(optin.type===undefined?undefined:BigInt(optin.type),OPTIN_TYPES),state:enumValue(optin.state===undefined?undefined:BigInt(optin.state),OPTIN_STATES)};
    }
  }
  const status = get(fields, 4, 2);
  if (status !== undefined) {
    const nested = decodeProtoFields(status);
    result.setupStatus = { state: enumValue(get(nested, 1, 0), STATES), unknownFields: unknown(nested, [1]), fields: nested };
  }
  const time = get(fields, 6, 2);
  if (time !== undefined) {
    const nested = decodeProtoFields(time);
    const millis = get(nested, 2, 0);
    const zone = get(nested, 5, 2);
    result.companionTime = {
      millis: BigInt.asIntN(64, millis ?? 0n), millisExplicit: millis !== undefined,
      timeZoneId: zone === undefined ? undefined : utf8.decode(zone),
      unknownFields: unknown(nested, [2, 5]), fields: nested,
    };
  }
  result.rawMessages = [];
  for (const [number, kind, name, schema] of [[5,'SYSTEM_INFO','systemInfo','SetupSystemInfo'],[8,'CONNECTION_INFO','connectionInfo','SetupConnectionInfo'],[9,'ENROLLMENT_INFO','enrollmentInfo','SetupEnrollmentInfo']]) {
    const payload = get(fields, number, 2);
    if (payload !== undefined) {
      result.rawMessages.push({field:number,kind,payload});
      result[name]=decodeGmsStruct(schema,payload);
      if(number===5) {
        const info=result.systemInfo;
        for(const [key,names] of [['version',VERSIONS],['edition',EDITIONS],['setupCapabilityVersion',['UNKNOWN','V1']]])info[key]=enumValue(info[key]===undefined?undefined:BigInt(info[key]),names);
        info.capabilities=(info.capabilities??[]).map(value=>enumValue(BigInt(value),CAPABILITIES));
        info.setupCapabilities=(info.setupCapabilities??[]).map(value=>enumValue(BigInt(value),SETUP_CAPABILITIES));
      }
    }
  }
  result.unknownFields = unknown(fields, [1, 2, 3, 4, 5, 6, 8, 9]);
  return result;
}

function varint(value) {
  let remaining = BigInt.asUintN(64, value);
  const output = [];
  do {
    const next = Number(remaining & 127n);
    remaining >>= 7n;
    output.push(next | (remaining ? 128 : 0));
  } while (remaining);
  return Buffer.from(output);
}
function signedLong(value) {
  if (typeof value !== 'bigint' && !(typeof value === 'number' && Number.isSafeInteger(value))) {
    throw new TypeError('commandId must be bigint or a safe integer');
  }
  const integer = BigInt(value);
  if (integer < -9223372036854775808n || integer > 9223372036854775807n) throw new RangeError('commandId exceeds signed int64');
  return integer;
}

/** Construct an offline query payload. Does not send it or create a channel. */
export function encodeSetupQuery(command, { commandId } = {}) {
  const number = typeof command === 'string' ? COMMANDS.indexOf(command) : command;
  if (![1, 2, 5].includes(number)) throw new RangeError('Expected GET_STATUS, GET_SYSTEM_INFO or GET_CONNECTION_INFO');
  const parts = [Buffer.from([8, number])];
  if (commandId !== undefined) parts.push(Buffer.from([16]), varint(signedLong(commandId)));
  const nested = Buffer.concat(parts);
  return Buffer.concat([Buffer.from([8, 1, 18, nested.length]), nested]);
}

export function encodeSetupFrame(input) {
  const payload = bytes(input);
  if (payload.length > LIMIT) throw new RangeError('Offline setup frame length exceeds 1 MiB');
  const header = Buffer.alloc(4);
  header.writeInt32BE(payload.length);
  return Buffer.concat([header, payload]);
}

/** Framing only. Accepts the zero-length protobuf permitted by the watch reader. */
export class SetupChannelStreamDecoder {
  #pending = Buffer.alloc(0);
  push(input) {
    const chunk = bytes(input);
    const frames = [];
    let offset = 0;
    while (offset < chunk.length) {
      if (this.#pending.length < 4) {
        const take = Math.min(4 - this.#pending.length, chunk.length - offset);
        this.#pending = Buffer.concat([this.#pending, chunk.subarray(offset, offset + take)]);
        offset += take;
        if (this.#pending.length < 4) break;
      }
      const size = this.#pending.readInt32BE(0);
      if (size < 0 || size > LIMIT) throw new RangeError('Invalid or excessive setup frame length');
      const take = Math.min(size + 4 - this.#pending.length, chunk.length - offset);
      this.#pending = Buffer.concat([this.#pending, chunk.subarray(offset, offset + take)]);
      offset += take;
      if (this.#pending.length === size + 4) {
        frames.push(Buffer.from(this.#pending.subarray(4)));
        this.#pending = Buffer.alloc(0);
      }
    }
    return frames;
  }
  finish() { return { status: this.#pending.length ? 'incomplete' : 'complete', remainder: Buffer.from(this.#pending) }; }
}
