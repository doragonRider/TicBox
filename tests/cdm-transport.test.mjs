import test from 'node:test';
import assert from 'node:assert/strict';
import {CDM_SECURE_TYPES as S,CDM_MESSAGE_TYPES as M,decodeCdmSecureRecord,encodeCdmSecureRecord,decodeCdmMessage,encodeCdmMessage} from '../src/cdm-transport.mjs';
const h=hex=>Buffer.from(hex,'hex');

test('CDM HI and HF keep native handshake bytes opaque with distinct headers',()=>{
  const hi=h('0000000148490000000400010203'),hf=h('000000014846000000020405');
  const init=decodeCdmSecureRecord(hi);
  assert.equal(init.type,S.HANDSHAKE_INIT);
  assert.equal(init.encrypted,false);
  assert.equal(init.associatedData,null);
  assert.deepEqual(init.payload,h('00010203')); // role0 + native bytes
  assert.deepEqual(encodeCdmSecureRecord({type:S.HANDSHAKE_INIT,payload:init.payload}),hi);
  const finish=decodeCdmSecureRecord(hf);
  assert.equal(finish.encrypted,false);
  assert.deepEqual(finish.payload,h('0405')); // no role prefix
  assert.deepEqual(encodeCdmSecureRecord({type:S.HANDSHAKE_FINISH,payload:finish.payload}),hf);
});

test('CDM SM/AT expose ciphertext and the exact six associated bytes only',()=>{
  for(const [type,hex] of [[S.SECURE_MESSAGE,'00000001534d00000004abcdef01'],[S.ATTESTATION,'00000001415400000004abcdef01']]) {
    const frame=decodeCdmSecureRecord(h(hex));
    assert.equal(frame.encrypted,true);
    assert.deepEqual(frame.associatedData,h(hex.slice(0,12)));
    assert.deepEqual(frame.payload,h('abcdef01'));
    assert.deepEqual(encodeCdmSecureRecord({type,payload:frame.payload}),h(hex));
    assert.throws(()=>decodeCdmMessage(frame.payload),/Truncated/);
  }
});

test('CDM decrypted ping and restore vectors retain sequence and opaque backup',()=>{
  for(const [hex,type,sequence,body] of [
    ['6380737800000001000000030102ff',M.REQUEST_PING,1,'0102ff'],
    ['3383856700000001000000030102ff',M.RESPONSE_SUCCESS,1,'0102ff'],
    ['638269830000000200000004deadbeef',M.REQUEST_PERMISSION_RESTORE,2,'deadbeef'],
    ['338385670000000200000000',M.RESPONSE_SUCCESS,2,''],
  ]) {
    const message=decodeCdmMessage(h(hex));
    assert.equal(message.type,type); assert.equal(message.sequence,sequence);
    assert.deepEqual(message.payload,h(body));
    assert.deepEqual(encodeCdmMessage({type,sequence,payload:h(body)}),h(hex));
  }
});

test('CDM rejects truncation, concatenation, invalid lengths and layer confusion',()=>{
  const outer=h('00000001534d00000004abcdef01'),inner=h('338385670000000200000000');
  for(let n=0;n<outer.length;n++)assert.throws(()=>decodeCdmSecureRecord(outer.subarray(0,n)),/Truncated|mismatch/);
  for(let n=0;n<inner.length;n++)assert.throws(()=>decodeCdmMessage(inner.subarray(0,n)),/Truncated|mismatch/);
  assert.throws(()=>decodeCdmSecureRecord(Buffer.concat([outer,outer])),/exactly one/);
  assert.throws(()=>decodeCdmSecureRecord(h('000000014849ffffffff')),/length/);
  assert.throws(()=>decodeCdmSecureRecord(h('00000002484900000000')),/version/);
  assert.throws(()=>decodeCdmMessage(outer),/length|mismatch/);
  assert.throws(()=>encodeCdmMessage({type:M.REQUEST_PING,sequence:0x80000000,payload:h('')}),/sequence/);
  assert.throws(()=>encodeCdmSecureRecord({type:1,payload:h('')}),/type/);
  const unknown=decodeCdmSecureRecord(h('000000017f7f00000000'));
  assert.equal(unknown.typeName,'UNKNOWN'); assert.equal(unknown.encrypted,null);
});
