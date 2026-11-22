/** Source-bound, offline protobuf codec. No Bluetooth, Android API or target code. */
import { decodeProtoFields } from './gms-record.mjs';

const LIMIT = 1024 * 1024;
const MAX_FIELDS = 32768;
const MAX_DEPTH = 32;
const utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
const f = (id, name, type, rule = '') => ({ id, name, type, rule });
const definitions = {
  Connect: [f(1,'nodeId','string','required'),f(2,'nodeName','string','required'),f(3,'androidId','int64'),f(4,'connectionVersion','int32'),f(5,'wireVersion','int32'),f(6,'minWireVersion','int32'),f(7,'networkId','string'),f(8,'companionPackage','string'),f(9,'isMigrating','bool'),f(10,'previousNodeId','string'),f(11,'senderSdkVersion','int32')],
  RpcRequest: [f(1,'requestId','int32','required'),f(2,'packageName','string','required'),f(3,'packageDigest','string','required'),f(4,'targetNodeId','string','required'),f(5,'legacyZero','int32','required'),f(6,'path','string','required'),f(7,'payload','bytes'),f(8,'sourceNodeId','string'),f(9,'channel','ChannelRequest'),f(10,'generation','int32'),f(11,'expectsResponse','bool'),f(12,'responseToken','int32'),f(13,'messageOption','int32')],
  AppKey: [f(1,'packageName','string','required'),f(2,'packageDigest','string','required')],
  AssetPermissions: [f(1,'apps','AppKey','repeated')],
  SetAsset: [f(1,'digest','string','required'),f(2,'inlineData','bytes'),f(3,'permissions','AssetPermissions')],
  AckAsset: [f(1,'digest','string','required')],
  FetchAsset: [f(1,'packageName','string','required'),f(2,'digest','string','required'),f(3,'permissionOnly','bool'),f(4,'packageDigest','string','required')],
  FilePiece: [f(1,'ownerMessageDigest','string','required'),f(2,'finalPiece','bool','required'),f(3,'data','bytes','required'),f(4,'fileDigest','string')],
  SetDataItem: [f(1,'packageName','string','required'),f(2,'uri','string','required'),f(4,'data','bytes'),f(5,'sequenceId','int64'),f(6,'deleted','bool'),f(7,'sourceNodeId','string'),f(8,'assets','AssetEntry','repeated'),f(9,'packageDigest','string','required'),f(10,'lastModified','int64')],
  AssetEntry: [f(1,'name','string','required'),f(2,'value','AssetReference','required'),f(3,'type','int32','required')],
  AssetReference: [f(4,'digest','string')],
  SyncStart: [f(1,'receivedSequenceId','int64'),f(2,'syncTable','SyncEntry','repeated'),f(3,'version','int32'),f(4,'restrictions','SyncRestrictions')],
  SyncEntry: [f(1,'sourceNodeId','string'),f(2,'sequenceId','int64')],
  SyncRestrictions: [f(1,'packages','PackageRestriction','repeated')],
  PackageRestriction: [f(1,'packageName','string'),f(2,'dataFilters','DataFilters'),f(3,'capabilityFilters','CapabilityFilters')],
  DataFilters: [f(1,'filters','DataFilter','repeated')],
  DataFilter: [f(1,'kind','int32'),f(2,'uri','string')],
  CapabilityFilters: [f(1,'filters','CapabilityFilter','repeated')],
  CapabilityFilter: [f(1,'name','string')],
  Heartbeat: [],
  Crypto: [f(1,'handshakeBytes','bytes'),f(2,'resumeAuthenticator','bytes'),f(3,'initialNegotiationMarker','bool'),f(4,'sessionMode','int32')],
  MigrationControl: [f(1,'type','int32'),f(2,'accountMatching','AccountMatching')],
  AccountMatching: [f(1,'operation','int32'),f(2,'accounts','AccountIdentity','repeated'),f(3,'errorMessage','string')],
  AccountIdentity: [f(1,'accountId','string'),f(2,'accountName','string')],
  ChannelRequest: [f(2,'control','ChannelControl'),f(3,'data','ChannelData'),f(4,'ack','ChannelAck'),f(6,'minimumVersion','int32'),f(7,'origin','int32')],
  ChannelControl: [f(1,'type','int32'),f(2,'channelId','fixed64'),f(3,'fromChannelOpener','bool'),f(4,'packageName','string'),f(5,'packageDigest','string'),f(6,'path','string'),f(7,'closeErrorCode','int32'),f(8,'allowOverMetered','bool'),f(9,'opaque','bytes')],
  ChannelHeader: [f(1,'channelId','fixed64'),f(2,'fromChannelOpener','bool'),f(3,'sequence','int64')],
  ChannelData: [f(1,'header','ChannelHeader'),f(2,'data','bytes'),f(3,'final','bool')],
  ChannelAck: [f(1,'header','ChannelHeader'),f(2,'final','bool')],
  DataMap: [f(1,'entries','DataMapEntry','repeated')],
  DataMapEntry: [f(1,'name','string','required'),f(2,'value','DataMapValue','required')],
  DataMapValue: [f(1,'type','int32','required'),f(2,'storage','DataMapStorage')],
  DataMapStorage: [f(1,'byteArray','bytes'),f(2,'stringValue','string'),f(3,'doubleValue','double'),f(4,'floatValue','float'),f(5,'longValue','int64'),f(6,'intValue','int32'),f(7,'byteValue','sint32'),f(8,'boolValue','bool'),f(9,'mapEntries','DataMapEntry','repeated'),f(10,'listValues','DataMapValue','repeated'),f(11,'stringArray','string','repeated'),f(12,'longArray','int64','repeated'),f(13,'assetIndex','int64'),f(14,'floatArray','float','repeated')],
  SetupMessage: [f(1,'type','int32'),f(2,'command','SetupCommand'),f(3,'event','SetupEvent'),f(4,'status','SetupStatus'),f(5,'systemInfo','SetupSystemInfo'),f(6,'companionTime','SetupCompanionTime'),f(8,'connectionInfo','SetupConnectionInfo'),f(9,'enrollmentInfo','SetupEnrollmentInfo')],
  SetupCommand: [f(1,'type','int32'),f(2,'id','int64')],
  SetupEvent: [f(1,'type','int32'),f(2,'optin','SetupOptinStatus'),f(3,'commandId','int64')],
  SetupStatus: [f(1,'state','int32')],
  SetupCompanionTime: [f(2,'millis','int64'),f(5,'timeZoneId','string')],
  SetupSystemInfo: [f(1,'version','int32'),f(2,'capabilities','int32','repeated'),f(3,'edition','int32'),f(4,'platformVersion','int32'),f(5,'maintenanceReleaseVersion','int32'),f(6,'setupCapabilities','int32','packed'),f(7,'setupCapabilityVersion','int32')],
  SetupOptinStatus: [f(1,'type','int32'),f(2,'state','int32'),f(3,'time','int64')],
  SetupCellularInfo: [f(1,'mnc','int32'),f(2,'mcc','int32'),f(3,'carrierName','string')],
  SetupConnectionInfo: [f(1,'cellInfo','SetupCellularInfo')],
  SetupEnrollmentInfo: [f(1,'enrollmentIntent','string')],
  SetTetherConfig: [f(1,'config','int32')],
  WatchRestoreStatus: [f(1,'status','int32')],
  WatchGmsCheckinStatus: [f(1,'status','int32'),f(2,'factoryReset','bool')],
};

const topLevel = [
  [4,'setAsset','SET_ASSET','SetAsset'],[5,'ackAsset','ACK_ASSET','AckAsset'],[6,'fetchAsset','FETCH_ASSET','FetchAsset'],[7,'connect','CONNECT','Connect'],[8,'syncStart','SYNC_START','SyncStart'],[9,'setDataItem','SET_DATA_ITEM','SetDataItem'],[10,'rpcRequest','RPC_REQUEST','RpcRequest'],[11,'heartbeat','HEARTBEAT','Heartbeat'],[12,'filePiece','FILE_PIECE','FilePiece'],[16,'channelRpcRequest','CHANNEL_RPC_REQUEST','RpcRequest'],[17,'crypto','CRYPTO','Crypto'],[18,'rpcServiceRequest','RPC_SERVICE_REQUEST','RpcRequest'],[20,'control','CONTROL','MigrationControl'],
];
definitions.WearMessage = [...topLevel.map(([id,name,,type])=>f(id,name,type)),f(13,'hasFile','bool')].sort((a,b)=>a.id-b.id);
export const GMS_STRUCT_NAMES = Object.freeze(Object.keys(definitions));
export const GMS_FIELD_SCHEMA = Object.freeze(Object.fromEntries(Object.entries(definitions).map(([name,fields])=>[name,Object.freeze(fields.map(item=>Object.freeze({...item})))])));

function asBytes(value) {
  if (!(value instanceof Uint8Array)) throw new TypeError('Expected Buffer or Uint8Array');
  return Buffer.from(value);
}
function integer(value, bits, signed) {
  if (typeof value !== 'bigint' && !(typeof value === 'number' && Number.isSafeInteger(value))) throw new TypeError(`Expected exact ${bits}-bit integer`);
  const n = BigInt(value);
  const low = signed ? -(1n << BigInt(bits-1)) : 0n;
  const high = (1n << BigInt(signed ? bits-1 : bits)) - 1n;
  if (n < low || n > high) throw new RangeError(`Integer outside ${signed?'int':'uint'}${bits}`);
  return n;
}
function vi(value) {
  let n = BigInt.asUintN(64, value);
  const out = [];
  do { const b = Number(n & 127n); n >>= 7n; out.push(b | (n ? 128 : 0)); } while (n);
  return Buffer.from(out);
}
function wire(type) {
  if (['int32','int64','sint32','bool'].includes(type)) return 0;
  if (type === 'fixed64' || type === 'double') return 1;
  return type === 'float' ? 5 : 2;
}
function scalarDecode(type, value) {
  if (type === 'string') return utf8.decode(value);
  if (type === 'bytes') return Buffer.from(value);
  if (type === 'int32') return Number(BigInt.asIntN(32,value));
  if (type === 'int64') return BigInt.asIntN(64,value);
  if (type === 'sint32') { const n = BigInt.asUintN(32,value); return Number((n>>1n)^-(n&1n)); }
  if (type === 'bool') return value !== 0n;
  if (type === 'fixed64') return value.readBigUInt64LE();
  if (type === 'double') return value.readDoubleLE();
  if (type === 'float') return value.readFloatLE();
  throw new Error(`Unsupported scalar ${type}`);
}
function packedFields(field, type) {
  const w = wire(type), data = field.value;
  if (![0,1,5].includes(w)) throw new Error('Non-numeric packed field');
  // Reuse the same validated protobuf walker for each packed scalar, retaining
  // its exact value bytes. A synthetic field 1 tag is only a local parse adapter.
  const result = [];
  for (let i=0; i<data.length;) {
    let end = i;
    if (w===0) {
      while (end<data.length && (data[end++]&128)) {}
      if (end===i || (data[end-1]&128)) throw new RangeError('Truncated packed varint');
    } else end += w===1 ? 8 : 4;
    if (end>data.length) throw new RangeError('Truncated packed fixed field');
    result.push(decodeProtoFields(Buffer.concat([Buffer.from([8|w]),data.subarray(i,end)]))[0]);
    i=end;
  }
  return result;
}
function decode(name, data, state, depth) {
  if (depth>MAX_DEPTH) throw new RangeError('Offline protobuf nesting limit');
  const schema = definitions[name];
  if (!schema) throw new Error(`Unknown GMS structure ${name}`);
  const fields = decodeProtoFields(data);
  state.fields += fields.length;
  if (state.fields>MAX_FIELDS) throw new RangeError('Offline protobuf field limit');
  const result = { fields, unknownFields: [] };
  for (const field of fields) {
    const spec = schema.find(item=>item.id===field.field);
    if (!spec) { result.unknownFields.push(field); continue; }
    const expected = wire(spec.type);
    let items = [field];
    if (field.wireType!==expected) {
      if (['repeated','packed'].includes(spec.rule) && field.wireType===2 && [0,1,5].includes(expected)) {
        items=packedFields(field,spec.type);
        state.fields += items.length;
        if (state.fields>MAX_FIELDS) throw new RangeError('Offline protobuf field limit');
      } else throw new Error(`Wrong wire type for ${name}.${spec.name}`);
    }
    if (!['repeated','packed'].includes(spec.rule) && Object.hasOwn(result,spec.name)) throw new Error(`Duplicate singular ${name}.${spec.name}`);
    for (const item of items) {
      const value = definitions[spec.type] ? decode(spec.type,item.value,state,depth+1) : scalarDecode(spec.type,item.value);
      if (['repeated','packed'].includes(spec.rule)) (result[spec.name]??=[]).push(value);
      else result[spec.name]=value;
    }
  }
  for (const spec of schema) {
    if (spec.rule==='required' && !Object.hasOwn(result,spec.name)) throw new Error(`Missing required ${name}.${spec.name}`);
  }
  return result;
}
/** Presence is preserved. Absent fields are not silently filled with defaults.
 * Unknown enums stay numeric; watch-only field 19 stays raw in this Android codec.
 */
export function decodeGmsStruct(name, input) {
  return decode(name,asBytes(input),{fields:0},0);
}

function scalarEncode(type, value) {
  if (type==='bytes') return asBytes(value);
  if (type==='string') { if(typeof value!=='string')throw new TypeError('Expected string'); return Buffer.from(value,'utf8'); }
  if (type==='bool') { if(typeof value!=='boolean')throw new TypeError('Expected boolean'); return vi(value?1n:0n); }
  if (type==='int32' || type==='int64') return vi(integer(value,type==='int32'?32:64,true));
  if (type==='sint32') { const n=integer(value,32,true); return vi((n<<1n)^(n>>31n)); }
  const output = Buffer.alloc(type==='float'?4:8);
  if (type==='fixed64') output.writeBigUInt64LE(integer(value,64,false));
  else {
    if(typeof value!=='number')throw new TypeError('Expected floating-point number');
    if(type==='double')output.writeDoubleLE(value); else if(type==='float')output.writeFloatLE(value); else throw new Error('Unsupported scalar');
  }
  return output;
}
function encode(name, object, state, depth) {
  if(depth>MAX_DEPTH)throw new RangeError('Offline protobuf nesting limit');
  const schema=definitions[name];
  if(!schema)throw new Error(`Unknown GMS structure ${name}`);
  if(!object || typeof object!=='object' || Array.isArray(object))throw new TypeError('Expected message object');
  const valid=new Set(schema.map(item=>item.name));
  for(const key of Object.keys(object))if(!valid.has(key) && !['fields','unknownFields'].includes(key))throw new Error(`Unknown encoder field ${name}.${key}`);
  const chunks=[];
  let total=0;
  const append=part=>{total+=part.length;if(total>LIMIT)throw new RangeError('Offline protobuf size limit');chunks.push(part);};
  for(const spec of schema) {
    if(!Object.hasOwn(object,spec.name) || object[spec.name]===undefined) {
      if(spec.rule==='required')throw new Error(`Missing required ${name}.${spec.name}`);
      continue;
    }
    const values=['repeated','packed'].includes(spec.rule)?object[spec.name]:[object[spec.name]];
    if(!Array.isArray(values))throw new TypeError(`Expected repeated ${spec.name}`);
    if(spec.rule==='packed') {
      if(state.fields+values.length>MAX_FIELDS)throw new RangeError('Offline protobuf field limit');
      state.fields+=values.length;
      if(values.length) {
        const data=Buffer.concat(values.map(value=>scalarEncode(spec.type,value)));
        append(vi(BigInt(spec.id*8+2)));append(vi(BigInt(data.length)));append(data);
      }
      continue;
    }
    for(const value of values) {
      if(++state.fields>MAX_FIELDS)throw new RangeError('Offline protobuf field limit');
      const data=definitions[spec.type]?encode(spec.type,value,state,depth+1):scalarEncode(spec.type,value);
      const w=wire(spec.type);
      append(vi(BigInt(spec.id*8+w)));
      if(w===2)append(vi(BigInt(data.length)));
      append(data);
    }
  }
  for(const unknown of object.unknownFields??[]) {
    const raw=asBytes(unknown.raw);
    const fields=decodeProtoFields(raw);
    if(fields.length!==1 || schema.some(item=>item.id===fields[0].field))throw new Error('Unknown raw field collides with schema');
    if(++state.fields>MAX_FIELDS)throw new RangeError('Offline protobuf field limit');
    append(raw);
  }
  return Buffer.concat(chunks,total);
}
export function encodeGmsStruct(name, object) { return encode(name,object,{fields:0},0); }

/** Full recovered Android container, including transport data/asset messages.
 * Multiple optional submessages are preserved; this container is not a oneof.
 */
export function decodeGmsMessage(input) {
  const decoded=decodeGmsStruct('WearMessage',input);
  const messages=[];
  for(const [field,name,kind] of topLevel)if(decoded[name])messages.push({field,kind,...decoded[name],raw:decoded.fields.find(item=>item.field===field).value});
  return {messages,...(Object.hasOwn(decoded,'hasFile')?{hasFile:decoded.hasFile}:{}),fields:decoded.fields,unknownFields:decoded.unknownFields};
}
export function encodeGmsMessage(object) { return encodeGmsStruct('WearMessage',object); }

export const DATAMAP_TYPES=Object.freeze({1:'bytes',2:'string',3:'double',4:'float',5:'long',6:'int',7:'byte',8:'boolean',9:'map',10:'list',11:'string[]',12:'long[]',13:'asset',14:'null',15:'float[]'});
/** Typed entries avoid lossy integer conversion and prototype-key assignment.
 * Asset indices refer to external assets, never invented inline file bytes.
 */
export function decodeDataMap(input) {
  const map=decodeGmsStruct('DataMap',input);
  const convertEntry=entry=>({name:entry.name,...convertValue(entry.value)});
  const convertValue=value=>{
    const storage=value.storage??{};
    const type=value.type;
    const scalar={1:['byteArray',Buffer.alloc(0)],2:['stringValue',''],3:['doubleValue',0],4:['floatValue',0],5:['longValue',0n],6:['intValue',0],7:['byteValue',0],8:['boolValue',false],11:['stringArray',[]],12:['longArray',[]],13:['assetIndex',0n],15:['floatArray',[]]};
    let decoded;
    if(type===14)decoded=null;
    else if(type===9)decoded=(storage.mapEntries??[]).map(convertEntry);
    else if(type===10)decoded=(storage.listValues??[]).map(convertValue);
    else if(scalar[type])decoded=storage[scalar[type][0]]??scalar[type][1];
    else return {type,typeName:'unknown',value:undefined,raw:value};
    // Both the OEM SDK and the watch's DataMap converter cast the decoded
    // sint32 storage to Java byte; generic raw storage remains unchanged.
    if(type===7)decoded=Number(BigInt.asIntN(8,BigInt(decoded)));
    return {type,typeName:DATAMAP_TYPES[type],value:decoded};
  };
  return {entries:(map.entries??[]).map(convertEntry),raw:map};
}

/** Encode explicit Java types. JS numbers are never guessed to be int/long/float.
 * Names follow Java TreeSet's UTF-16 ordering; asset values are external indices.
 */
export function encodeDataMap(entries) {
  let count=0;
  const mapEntries=(items,depth)=>{
    if(depth>MAX_DEPTH)throw new RangeError('Offline DataMap nesting limit');
    if(!Array.isArray(items))throw new TypeError('Expected typed DataMap entries');
    const names=new Set();
    return [...items].sort((a,b)=>a.name<b.name?-1:a.name>b.name?1:0).map(entry=>{
      if(!entry || typeof entry.name!=='string')throw new TypeError('Expected DataMap entry name');
      if(names.has(entry.name))throw new Error('Duplicate DataMap entry name');
      names.add(entry.name);
      return {name:entry.name,value:mapValue(entry,depth+1)};
    });
  };
  const mapValue=(entry,depth)=>{
    if(depth>MAX_DEPTH || ++count>MAX_FIELDS)throw new RangeError('Offline DataMap nesting/entry limit');
    const {type,value}=entry;
    if(!Object.hasOwn(DATAMAP_TYPES,type))throw new Error('Unknown DataMap value type');
    if(type===14){if(value!==null)throw new TypeError('Null type requires null');return {type};}
    let storage;
    if(type===9)storage={mapEntries:mapEntries(value,depth+1)};
    else if(type===10) {
      if(!Array.isArray(value))throw new TypeError('Expected typed ArrayList values');
      const nonNull=new Set(value.filter(item=>item.type!==14).map(item=>item.type));
      if(value.some(item=>![2,6,9,14].includes(item.type)) || nonNull.size>1)throw new TypeError('DataMap ArrayList needs one non-null Java type: string, int or map');
      storage={listValues:value.map(item=>mapValue(item,depth+1))};
    } else {
      const names={1:'byteArray',2:'stringValue',3:'doubleValue',4:'floatValue',5:'longValue',6:'intValue',7:'byteValue',8:'boolValue',11:'stringArray',12:'longArray',13:'assetIndex',15:'floatArray'};
      if(type===7 && (!Number.isInteger(value)||value< -128||value>127))throw new RangeError('Java byte outside -128..127');
      if(type===13 && integer(value,64,true)<0n)throw new RangeError('Negative external asset index');
      if([11,12,15].includes(type)&&!Array.isArray(value))throw new TypeError('Expected Java array');
      storage={[names[type]]:value};
    }
    return {type,storage};
  };
  return encodeGmsStruct('DataMap',{entries:mapEntries(entries,0)});
}
