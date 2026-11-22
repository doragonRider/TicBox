import test from 'node:test';
import assert from 'node:assert/strict';
import { MESSAGE_PATHS, WATCH_SERVICE_PATHS as S, decodeMobvoiMessage, encodeRecorderAck, parseJsonLossless } from '../src/mobvoi-message.mjs';

test('source-confirmed Recorder JSON and same-path ACK preserve Java long IDs', () => {
  const payload = Buffer.from('{"current_step":3210,"messageId":9223372036854775807}');
  const decoded = decodeMobvoiMessage(MESSAGE_PATHS.recorder, payload);
  assert.equal(decoded.data.messageId, 9223372036854775807n);
  assert.equal(decoded.data.current_step, 3210);
  assert.equal(encodeRecorderAck(decoded.data.messageId).toString(), '{"messageId":9223372036854775807,"status":0}');
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.recorder, encodeRecorderAck(decoded.data.messageId)).data.status, 0);
});

test('companion account and phone battery JSON remain ordinary payload objects', () => {
  const account = { b: 946684800, u: 'metric', w: 70.5, h: 175, g: 1 };
  assert.deepEqual(decodeMobvoiMessage(MESSAGE_PATHS.accountInfo, Buffer.from(JSON.stringify(account))).data, account);
  const response = Buffer.from('{"level":85,"scale":100}');
  assert.deepEqual(decodeMobvoiMessage(MESSAGE_PATHS.phoneBattery, response).data, { level: 85, scale: 100 });
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.phoneBattery, Buffer.alloc(0)).format, 'empty');
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.wearBattery, Buffer.from('85')).value, 85);
});

test('empty push request does not imply a general message frame or suffix', () => {
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.requestWearPush, Buffer.alloc(0)).format, 'empty');
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.wearInfo, Buffer.alloc(0)).format, 'empty');
  const opaque = Buffer.from([0xff, 0x01, 0x02]);
  assert.equal(decodeMobvoiMessage('/unknown/new_path', opaque).rawHex, 'ff0102');
  assert.equal(decodeMobvoiMessage(`${MESSAGE_PATHS.recorder}/re`, opaque).format, 'unknown');
});

test('watch 5.5 current-step payload stays a two-field object without invented message IDs', () => {
  const decoded = decodeMobvoiMessage(MESSAGE_PATHS.recorder, Buffer.from('{"current_step":4321,"goal":10000}'));
  assert.deepEqual(decoded.data, { current_step: 4321, goal: 10000 });
  assert.equal(Object.hasOwn(decoded.data, 'messageId'), false);
  // The old phone receiver defaults its own long to zero; no automatic ACK is generated here.
  assert.equal(encodeRecorderAck(0).toString(), '{"messageId":0,"status":0}');
});

test('new watch business paths distinguish JSON events, text switches and destructive screenshot ACK',()=>{
  assert.deepEqual(decodeMobvoiMessage(MESSAGE_PATHS.setupWizard,Buffer.from('{"time":1700000000000}')).data,{time:1700000000000});
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.login,Buffer.alloc(0)).format,'empty');
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.heartHealthSwitch,Buffer.from('1')).enabled,true);
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.heartHealthSwitch,Buffer.from('true')).enabled,false);
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.heartHealthSwitch,Buffer.alloc(0)).enabled,false);
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.photoCount,Buffer.from('12')).value,12);
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.photoReceived,Buffer.alloc(0)).watchSideEffect,'delete-first-screenshot');
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.photoData,Buffer.from('89504e470d0a1a0a','hex')).signatureMatches,true);
  assert.equal(decodeMobvoiMessage(MESSAGE_PATHS.photoData,Buffer.from('{}')).signatureMatches,false);
  // A launch_intent is decoded as data and never run.
  assert.deepEqual(decodeMobvoiMessage(MESSAGE_PATHS.launchIntent,Buffer.from('{"type":2,"intent":"#Intent;end"}')).data,{type:2,intent:'#Intent;end'});
});

test('watchface combined image uses a signed BE JSON length and retains uninterpreted image bytes',()=>{
  const packet=Buffer.from('000000147b2263617465676f7279223a312c2278223a327dffd8ff','hex');
  const decoded=decodeMobvoiMessage(S.watchfaceCombined,packet);
  assert.equal(decoded.jsonBytes,20);
  assert.deepEqual(decoded.data,{category:1,x:2});
  assert.equal(decoded.imageHex,'ffd8ff');
  assert.equal(decoded.imageBytes,3);
  assert.throws(()=>decodeMobvoiMessage(S.watchfaceCombined,Buffer.from('ffffff','hex')),/Truncated/);
  assert.throws(()=>decodeMobvoiMessage(S.watchfaceCombined,Buffer.from('ffffffff','hex')),/Invalid/);
  assert.throws(()=>decodeMobvoiMessage(S.watchfaceCombined,Buffer.from('000100007b7d','hex')),/Invalid/);
  assert.equal(decodeMobvoiMessage(S.watchfaceBackground,Buffer.from('ffd8ff','hex')).format,'image-bytes');
  const response=decodeMobvoiMessage(S.watchfaceImage,Buffer.from('{"image":[-1,-40,-1],"class_name":"watch"}'));
  assert.deepEqual(response.data.image,[-1,-40,-1]);
});

test('SOS boolean text, shared ACK and separate Magic Watch Face payloads retain their actual formats',()=>{
  assert.equal(decodeMobvoiMessage(S.sosButton,Buffer.from('TRUE')).enabled,true);
  assert.equal(decodeMobvoiMessage(S.sosButton,Buffer.from('1')).enabled,false);
  assert.equal(decodeMobvoiMessage(S.sosShareLocation,Buffer.from('true ')).enabled,false);
  assert.equal(decodeMobvoiMessage(S.sosFallDetection,Buffer.from('2')).value,2);
  assert.equal(decodeMobvoiMessage(S.sosSyncOk,Buffer.alloc(0)).format,'empty');
  assert.equal(decodeMobvoiMessage(S.sosSettings,Buffer.alloc(0)).format,'ignored-request');
  assert.equal(decodeMobvoiMessage(S.sosContacts,Buffer.from('[{"x":1}]')).text,'[{"x":1}]');
  assert.equal(decodeMobvoiMessage(S.sosStart,Buffer.from('123')).phoneSideEffect,'emergency-call-request');
  assert.equal(decodeMobvoiMessage(S.mwfCheck,Buffer.from('ff00','hex')).rawHex,'ff00');
  assert.equal(decodeMobvoiMessage(S.mwfCompanionInfo,Buffer.from('{"userId":"u","token":"t"}')).data.userId,'u');
  assert.equal(decodeMobvoiMessage(S.watchfaceFeaturedNoop,Buffer.from([255])).format,'ignored-payload');
});

test('malformed JSON, invalid UTF-8, shape errors and excessive inputs fail explicitly', () => {
  assert.throws(() => decodeMobvoiMessage(MESSAGE_PATHS.recorder, Buffer.from('{')), SyntaxError);
  assert.throws(() => decodeMobvoiMessage(MESSAGE_PATHS.recorder, Buffer.from([0xff])), TypeError);
  assert.throws(() => decodeMobvoiMessage(MESSAGE_PATHS.recorder, Buffer.from('[]')), TypeError);
  assert.throws(() => decodeMobvoiMessage(MESSAGE_PATHS.wearBattery, Buffer.from('2147483648')), RangeError);
  assert.throws(() => decodeMobvoiMessage(MESSAGE_PATHS.wearBattery, Buffer.from('85%')), TypeError);
  assert.throws(() => decodeMobvoiMessage(MESSAGE_PATHS.recorder, Buffer.from('{}'), { maxPayloadBytes: 1 }), RangeError);
});

test('ACK IDs reject rounded JS numbers and keep decimal-long bounds', () => {
  assert.equal(encodeRecorderAck('-9223372036854775808').toString(), '{"messageId":-9223372036854775808,"status":0}');
  assert.throws(() => encodeRecorderAck(9007199254740993), TypeError);
  assert.throws(() => encodeRecorderAck('9223372036854775808'), RangeError);
  assert.throws(() => encodeRecorderAck('01'), TypeError);
  assert.throws(() => parseJsonLossless('{"id":9.223372036854776e18}'), RangeError);
});
