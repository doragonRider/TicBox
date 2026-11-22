#!/usr/bin/env node
import { readFile, stat } from 'node:fs/promises';
import { Flow15StreamDecoder } from '../src/sysproxy.mjs';
import { LegacyBleDecoder } from '../src/legacy-ble.mjs';
import { decodeMobvoiMessage } from '../src/mobvoi-message.mjs';
import { GmsRecordStreamDecoder, decodeMessagePiece } from '../src/gms-record.mjs';
import { decodeGmsMessage, decodeGmsStruct, decodeDataMap } from '../src/gms-messages.mjs';
import { SetupChannelStreamDecoder, decodeSetupMessage } from '../src/setup-channel.mjs';
import { decodeCdmSecureRecord, decodeCdmMessage } from '../src/cdm-transport.mjs';

const USAGE = `Offline inspection only; no Bluetooth or network access.
Usage: node tools/inspect-message.mjs LAYER (--hex HEX | --file FILE) [--path PATH]
Layers: sysproxy | legacy-ble | mobvoi | gms-record | gms-piece | gms-message | gms-channel | datamap | setup-channel | setup-message | cdm-record | cdm-message
--path is required for mobvoi and is separate MessageApi metadata.
gms-record preserves opaque record payloads; it does not assume cleartext.
gms-piece / gms-message explicitly parse caller-selected cleartext protobuf.
gms-channel parses a ChannelRequest, not the inner application stream.
datamap explicitly parses DataMap bytes, not arbitrary DataApi data.
setup-channel is the inner /setup/channel byte stream; setup-message is its unframed protobuf.
cdm-record parses exactly one SecureChannel record and keeps ciphertext opaque.
cdm-message requires one explicitly selected decrypted CDM message; it does not decrypt a record.
Input limit: 1 MiB. Java long values are printed as decimal strings.`;
const INPUT_LIMIT = 1024 * 1024;

function printable(value) {
  if (typeof value === 'bigint') return value.toString();
  if (typeof value === 'number' && (!Number.isFinite(value) || Object.is(value, -0))) return Object.is(value, -0) ? '-0' : String(value);
  if (value instanceof Uint8Array) return { hex: Buffer.from(value).toString('hex') };
  if (Array.isArray(value)) return value.map(printable);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, printable(item)]));
  }
  return value;
}

async function main(args) {
  if (args.length === 1 && args[0] === '--help') {
    process.stdout.write(`${USAGE}\n`);
    return;
  }
  const [layer, ...flags] = args;
  if (!['sysproxy', 'legacy-ble', 'mobvoi', 'gms-record', 'gms-piece', 'gms-message', 'gms-channel', 'datamap', 'setup-channel', 'setup-message', 'cdm-record', 'cdm-message'].includes(layer)) throw new Error(USAGE);
  const options = {};
  for (let i = 0; i < flags.length; i += 2) {
    const flag = flags[i];
    if (!['--hex', '--file', '--path'].includes(flag) || i + 1 >= flags.length || Object.hasOwn(options, flag)) {
      throw new Error(`Invalid or duplicate argument: ${flag}\n${USAGE}`);
    }
    options[flag] = flags[i + 1];
  }
  if (Object.hasOwn(options, '--hex') === Object.hasOwn(options, '--file')) {
    throw new Error('Provide exactly one --hex or --file');
  }
  if (layer === 'mobvoi' ? !options['--path'] : Object.hasOwn(options, '--path')) {
    throw new Error('--path is required only for mobvoi payload inspection');
  }
  let input;
  if (Object.hasOwn(options, '--hex')) {
    const source = options['--hex'];
    if (source.length > INPUT_LIMIT * 3) throw new RangeError('Hex input limit exceeded');
    const hex = source.replace(/\s/g, '');
    if (!/^[\da-f]*$/i.test(hex) || hex.length % 2) throw new Error('Hex must contain complete byte pairs');
    input = Buffer.from(hex, 'hex');
  } else {
    const file = options['--file'];
    const metadata = await stat(file);
    if (!metadata.isFile() || metadata.size > INPUT_LIMIT) throw new RangeError('Expected regular file of at most 1 MiB');
    input = await readFile(file);
  }
  if (input.length > INPUT_LIMIT) throw new RangeError('Input limit exceeded');

  let output;
  if (layer === 'sysproxy') {
    const decoder = new Flow15StreamDecoder();
    const frames = decoder.push(input);
    output = { layer, frames, ...decoder.finish() };
  } else if (layer === 'legacy-ble') {
    const decoder = new LegacyBleDecoder();
    const payloads = decoder.feed(input);
    let status = 'complete';
    let reason = null;
    try { decoder.finish(); } catch (error) { status = 'incomplete'; reason = error.message; }
    output = { layer, status, reason, payloads, discardedBytes: decoder.discardedBytes };
  } else if (layer === 'gms-record') {
    const decoder = new GmsRecordStreamDecoder();
    output = { layer, records: decoder.push(input), ...decoder.finish() };
  } else if (layer === 'gms-piece') {
    output = { layer, ...decodeMessagePiece(input) };
  } else if (layer === 'gms-message') {
    output = { layer, ...decodeGmsMessage(input) };
  } else if (layer === 'gms-channel') {
    output = { layer, ...decodeGmsStruct('ChannelRequest', input) };
  } else if (layer === 'datamap') {
    output = { layer, ...decodeDataMap(input) };
  } else if (layer === 'setup-channel') {
    const decoder = new SetupChannelStreamDecoder();
    output = { layer, messages: decoder.push(input).map(payload => ({ payload, ...decodeSetupMessage(payload) })), ...decoder.finish() };
  } else if (layer === 'setup-message') {
    output = { layer, ...decodeSetupMessage(input) };
  } else if (layer === 'cdm-record') {
    output = { layer, ...decodeCdmSecureRecord(input) };
  } else if (layer === 'cdm-message') {
    output = { layer, ...decodeCdmMessage(input) };
  } else {
    output = { layer, ...decodeMobvoiMessage(options['--path'], input) };
  }
  process.stdout.write(`${JSON.stringify(printable(output), null, 2)}\n`);
  if (output.status === 'incomplete' || output.status === 'unknown') process.exitCode = 2;
}

main(process.argv.slice(2)).catch(error => {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
});
