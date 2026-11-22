/** Offline codec for Android CDM SecureChannel envelopes. No crypto, keys or I/O.
 * SecureChannel records contain ciphertext except HI/HF. CDM logical messages
 * are parsed only when the caller explicitly supplies decrypted plaintext.
 * Specification: docs/spec/identity.md (6.3). The size ceiling is a tool policy.
 */
export const CDM_SECURE_TYPES = Object.freeze({
  HANDSHAKE_INIT:0x4849, HANDSHAKE_FINISH:0x4846,
  PRE_SHARED_KEY:0x504b, ATTESTATION:0x4154,
  AVF_RESULT:0x5652, SECURE_MESSAGE:0x534d,
});
export const CDM_MESSAGE_TYPES = Object.freeze({
  REQUEST_CONTEXT_SYNC:0x63678883, REQUEST_PING:0x63807378,
  REQUEST_PERMISSION_RESTORE:0x63826983,
  RESPONSE_FAILURE:0x33706573, RESPONSE_SUCCESS:0x33838567,
});
const names = values => new Map(Object.entries(values).map(([name,value])=>[value,name]));
const secureNames=names(CDM_SECURE_TYPES), messageNames=names(CDM_MESSAGE_TYPES);
const MAX_PAYLOAD=1024*1024;
function bytes(value) {
  if(!(value instanceof Uint8Array))throw new TypeError('Expected Buffer or Uint8Array');
  if(value.byteLength>MAX_PAYLOAD+12)throw new RangeError('Offline CDM size limit');
  return Buffer.from(value);
}
function int32(value,name) {
  if(!Number.isInteger(value) || value< -0x80000000 || value>0x7fffffff)throw new RangeError(`Invalid int32 ${name}`);
  return value;
}
function payloadOf(input,headerSize) {
  const inputBytes=bytes(input);
  if(inputBytes.length<headerSize)throw new RangeError('Truncated CDM header');
  const length=inputBytes.readInt32BE(headerSize-4);
  if(length<0 || length>MAX_PAYLOAD)throw new RangeError('Invalid CDM payload length or offline size limit');
  if(inputBytes.length!==headerSize+length)throw new RangeError('CDM payload length mismatch; supply exactly one frame');
  return {data:inputBytes,payload:Buffer.from(inputBytes.subarray(headerSize))};
}
export function decodeCdmSecureRecord(input) {
  const {data,payload}=payloadOf(input,10);
  const version=data.readInt32BE(0),type=data.readInt16BE(4);
  if(version!==1)throw new RangeError('Unsupported SecureChannel version');
  const typeName=secureNames.get(type)??'UNKNOWN';
  const encrypted=typeName==='UNKNOWN'?null:!['HANDSHAKE_INIT','HANDSHAKE_FINISH'].includes(typeName);
  return {version,type,typeName,encrypted,payload,
    associatedData:encrypted===true?Buffer.from(data.subarray(0,6)):null};
}
export function encodeCdmSecureRecord({type,payload}) {
  if(!secureNames.has(type))throw new RangeError('Unknown SecureChannel type');
  const content=bytes(payload);
  if(content.length>MAX_PAYLOAD)throw new RangeError('Offline CDM size limit');
  const header=Buffer.alloc(10);
  header.writeInt32BE(1,0); header.writeInt16BE(type,4); header.writeInt32BE(content.length,6);
  return Buffer.concat([header,content]);
}
export function decodeCdmMessage(input) {
  const {data,payload}=payloadOf(input,12);
  const type=data.readInt32BE(0),sequence=data.readInt32BE(4);
  return {type,typeName:messageNames.get(type)??'UNKNOWN',sequence,payload};
}
export function encodeCdmMessage({type,sequence,payload}) {
  int32(type,'type'); int32(sequence,'sequence');
  const content=bytes(payload);
  if(content.length>MAX_PAYLOAD)throw new RangeError('Offline CDM size limit');
  const header=Buffer.alloc(12);
  header.writeInt32BE(type,0); header.writeInt32BE(sequence,4); header.writeInt32BE(content.length,8);
  return Buffer.concat([header,content]);
}
