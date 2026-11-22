/**
 * Offline inspection of source-confirmed Mobvoi MessageApi
 * payloads. path is separate MessageApi metadata, NOT a prefix in payload.
 * This does not implement GMS framing, pairing, encryption or Bluetooth.
 * Specification: docs/spec/companion.md and docs/spec/health.md.
 */

// TicYcx uses a separate GMS application/capability. Recognizing its payload
// bytes does not imply that the normal Mobvoi .at AppKey routes to that app.
export const WATCH_SERVICE_PATHS = Object.freeze({
  sosSettings: '/companion/ticsos/watch_settings',
  sosNumber: '/companion/ticsos/edit_number',
  sosContacts: '/companion/ticsos/edit_contacts',
  sosButton: '/companion/ticsos/button_trigger',
  sosShareLocation: '/companion/ticsos/share_location',
  sosFallDetection: '/companion/ticsos/fall_detection',
  sosSyncOk: '/companion/ticsos/sync_ok',
  sosStart: '/companion/ticsos/start_sos',
  watchfaceSet: '/tic_watchface_market/set',
  watchfaceImage: '/tic_watchface_market/get_image',
  watchfaceImageInfo: '/tic_watchface_market/get_image_watchface_info',
  watchfaceBackground: '/tic_watchface_market/set_back_image',
  watchfaceCombined: '/tic_watchface_market/set_image_watchface',
  watchfaceSaved: '/tic_watchface_market/set_back_image_confirm',
  watchfaceDownload: '/tic_watchface_market/download_watchface',
  watchfaceFeaturedNoop: '/tic_watchface_market/list_featured',
  mwfCompanionInfo: '/ycxbp/send_companion_info',
  mwfWearInfo: '/ycxbp/sync_wear_info',
  mwfSettings: '/ycxbp/sync_wear_setting',
  mwfCheck: '/ycxbp/check_companion_app',
  mwfCheckSuccess: '/ycxbp/check_companion_app_success',
  mwfCheckFail: '/ycxbp/check_companion_app_fail',
  mwfSubscription: '/ycxbp/subscription_success',
});

const JSON_PATHS = new Set([
  '/companion/send_companion_info',
  '/companion/sync_wear_info',
  '/companion/sync_account_info',
  '/companion/get_phone_battery',
  '/companion/setup_wizard',
  '/companion/battery_full',
  '/companion/launch_intent',
  '/health/cmd_new_wear_push_to_phone',
  '/health/cmd_wear_pull_from_phone',
  WATCH_SERVICE_PATHS.sosSettings,
  WATCH_SERVICE_PATHS.watchfaceSet,
  WATCH_SERVICE_PATHS.watchfaceImage,
  WATCH_SERVICE_PATHS.watchfaceImageInfo,
  WATCH_SERVICE_PATHS.mwfCompanionInfo,
  WATCH_SERVICE_PATHS.mwfWearInfo,
  WATCH_SERVICE_PATHS.mwfSettings,
]);
const S = WATCH_SERVICE_PATHS;
const textDecoder = new TextDecoder('utf-8',{fatal:true,ignoreBOM:true});

function jsonObject(payload) {
  const data = parseJsonLossless(textDecoder.decode(payload));
  if (data === null || Array.isArray(data) || typeof data !== 'object') throw new TypeError('Expected JSON object for this observed path');
  return data;
}

// Exact spelling of the official path constants. No universal /re suffix is assumed.
export const MESSAGE_PATHS = Object.freeze({
  companionInfo: '/companion/send_companion_info',
  wearInfo: '/companion/sync_wear_info',
  accountInfo: '/companion/sync_account_info',
  phoneBattery: '/companion/get_phone_battery',
  wearBattery: '/companion/sync_wear_battery',
  recorder: '/health/cmd_new_wear_push_to_phone',
  requestWearPush: '/health/cmd_wear_push_to_phone',
  updateStepGoal: '/health/cmd_wear_pull_from_phone',
  login: '/companion/login',
  setupWizard: '/companion/setup_wizard',
  batteryFull: '/companion/battery_full',
  launchIntent: '/companion/launch_intent',
  wechatQrTicket: '/health/open_wechat_qr_ticket',
  heartHealthSwitch: '/health/cmd_change_heart_health_switch',
  loadPhotos: '/screen_shot/load_photo',
  photoCount: '/screen_shot/photo_nums',
  startPhotos: '/screen_shot/start_sync',
  photoData: '/screen_shot/photo_data',
  photoReceived: '/screen_shot/photo_received',
});

function asBytes(payload) {
  if (!(payload instanceof Uint8Array)) throw new TypeError('payload must be Buffer or Uint8Array');
  return Buffer.from(payload);
}

/** Keep decimal long tokens exact; JS number alone cannot hold Java long IDs. */
export function parseJsonLossless(text) {
  return JSON.parse(text, (_key, value, context) => {
    if (typeof value !== 'number') return value;
    if (!Number.isFinite(value)) throw new RangeError('JSON number exceeds finite numeric range');
    if (Number.isInteger(value) && !Number.isSafeInteger(value)) {
      if (!/^-?(?:0|[1-9]\d*)$/.test(context.source)) {
        throw new RangeError('Unsafe integer with non-decimal spelling is unsupported');
      }
      return BigInt(context.source);
    }
    return value;
  });
}

/** Payload inspection only. No Intent, image parser, ACK or send is executed. */
export function decodeMobvoiMessage(path, input, { maxPayloadBytes = 1024 * 1024 } = {}) {
  if (typeof path !== 'string' || !path.startsWith('/')) throw new TypeError('path must start with /');
  if (!Number.isSafeInteger(maxPayloadBytes) || maxPayloadBytes < 1) throw new RangeError('Invalid maxPayloadBytes');
  const payload = asBytes(input);
  if (payload.length > maxPayloadBytes) throw new RangeError('Offline payload limit exceeded');
  const result = { path, byteLength: payload.length, rawHex: payload.toString('hex') };
  if (path===S.watchfaceCombined) {
    if(payload.length<4)throw new RangeError('Truncated watchface JSON length');
    const jsonBytes=payload.readInt32BE(0);
    if(jsonBytes<0 || jsonBytes>payload.length-4)throw new RangeError('Invalid watchface JSON length');
    return {...result,format:'watchface-json-image',jsonBytes,data:jsonObject(payload.subarray(4,4+jsonBytes)),imageBytes:payload.length-4-jsonBytes,imageHex:payload.subarray(4+jsonBytes).toString('hex')};
  }
  if(path===S.watchfaceBackground) return {...result,format:'image-bytes',interpretation:'raw BitmapFactory input; file format and image integrity not inspected'};
  if([S.mwfCheck,S.mwfCheckSuccess,S.mwfCheckFail].includes(path)) return {...result,format:'opaque',interpretation:'Magic Watch Face check request or echoed request bytes; separate application identity'};
  if(path===S.watchfaceFeaturedNoop || path===S.mwfSubscription) return {...result,format:'ignored-payload',interpretation:path===S.watchfaceFeaturedNoop?'recognized branch has no business action':'triggers local subscription notification; payload ignored'};
  if(!payload.length && [S.sosSyncOk,S.watchfaceSaved,S.watchfaceDownload].includes(path)) return {...result,format:'empty',interpretation:'observed zero-byte watch notification; no request ID or result code'};
  if(!payload.length && [S.sosSettings,S.watchfaceImage,S.watchfaceImageInfo,S.mwfWearInfo].includes(path)) return {...result,format:'ignored-request',interpretation:'watch handler ignores query payload; this empty candidate is not a captured phone request'};
  if([S.sosNumber,S.sosContacts,S.sosButton,S.sosShareLocation,S.sosStart].includes(path)) {
    const text=textDecoder.decode(payload);
    return {...result,format:'text',text,...([S.sosButton,S.sosShareLocation].includes(path)?{enabled:/^true$/i.test(text)}:{}),...(path===S.sosStart?{phoneSideEffect:'emergency-call-request'}:{})};
  }
  if (path === MESSAGE_PATHS.photoData) {
    return { ...result, format: 'png-bytes', signatureMatches: payload.length >= 8 && payload.subarray(0,8).equals(Buffer.from('89504e470d0a1a0a','hex')), interpretation: 'raw screenshot bytes; image integrity and decoding not checked' };
  }
  const emptyCommands = new Map([
    [MESSAGE_PATHS.login,'watch requests phone login; actual zero-byte sender'],
    [MESSAGE_PATHS.loadPhotos,'phone requests screenshot count; actual zero-byte sender'],
    [MESSAGE_PATHS.startPhotos,'phone requests first screenshot; actual zero-byte sender'],
    [MESSAGE_PATHS.photoReceived,'phone confirms screenshot; watch deletes the current sorted first file then sends the next'],
  ]);
  if (!payload.length && emptyCommands.has(path)) {
    return { ...result, format: 'empty', interpretation: emptyCommands.get(path), ...(path === MESSAGE_PATHS.photoReceived ? { watchSideEffect: 'delete-first-screenshot' } : {}) };
  }
  if ([MESSAGE_PATHS.heartHealthSwitch,MESSAGE_PATHS.wechatQrTicket].includes(path)) {
    const text = textDecoder.decode(payload);
    return { ...result,format:'text',text,charset:'UTF-8 inspection of sender-default-charset bytes',...(path === MESSAGE_PATHS.heartHealthSwitch ? { enabled: text === '1' } : {}) };
  }
  // The official phone app sends empty push and wear-info requests.
  // Empty GET_PHONE_BATTERY is
  // accepted here as a request form because the watch ignores its incoming payload;
  // the watch-side sender's actual bytes have not been recovered.
  if (!payload.length && [MESSAGE_PATHS.requestWearPush, MESSAGE_PATHS.wearInfo, MESSAGE_PATHS.phoneBattery].includes(path)) {
    return {
      ...result,
      format: 'empty',
      interpretation: path === MESSAGE_PATHS.phoneBattery
        ? 'request-candidate: phone handler ignores input; watch sender not observed'
        : path === MESSAGE_PATHS.wearInfo
          ? 'empty request observed in MainSettingFragment'
          : 'empty request observed in vo/f.b',
    };
  }
  if (!JSON_PATHS.has(path) && ![MESSAGE_PATHS.wearBattery,MESSAGE_PATHS.photoCount,S.sosFallDetection].includes(path)) {
    return { ...result, format: 'unknown' };
  }
  if (JSON_PATHS.has(path)) {
    return { ...result, format: 'json', data: jsonObject(payload) };
  }
  const text = textDecoder.decode(payload);
  // Mirrors the decimal-int boundary, while reporting the raw bytes separately.
  if (!/^[+-]?\d+$/.test(text)) throw new TypeError('Payload must be a decimal Java int');
  const value = Number(text);
  if (!Number.isInteger(value) || value < -2147483648 || value > 2147483647) {
    throw new RangeError('Payload exceeds Java int range');
  }
  return { ...result, format: 'decimal-int', value };
}

/**
 * Offline example of vo/f.a's ACK: same path, echo messageId, status=0.
 * JSON property order is not a wire requirement; accepts a long decimal string,
 * bigint or a safe integer. No send operation is performed.
 */
export function encodeRecorderAck(messageId) {
  let id;
  if (typeof messageId === 'bigint') id = messageId;
  else if (typeof messageId === 'number' && Number.isSafeInteger(messageId)) id = BigInt(messageId);
  else if (typeof messageId === 'string' && /^-?(?:0|[1-9]\d*)$/.test(messageId)) id = BigInt(messageId);
  else throw new TypeError('messageId must be an exact decimal Java long');
  if (id < -0x8000000000000000n || id > 0x7fffffffffffffffn) throw new RangeError('messageId exceeds Java long range');
  return Buffer.from(`{"messageId":${id},"status":0}`, 'utf8');
}
