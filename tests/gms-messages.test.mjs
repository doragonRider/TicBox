import test from 'node:test';
import assert from 'node:assert/strict';
import { decodeGmsMessage, encodeGmsMessage, decodeGmsStruct, encodeGmsStruct, decodeDataMap, encodeDataMap } from '../src/gms-messages.mjs';
const h = str => Buffer.from(str.replaceAll(' ',''),'hex');

test('Channel control IDs are fixed64 LE while data sequence is an int64 varint',()=>{
  // Independent field-by-field vector, no encoder used to construct it:
  // req2 -> control1 OPEN, 2 fixed64 ID, 3 opener, 6 "/setup/channel".
  const bytes=h('121d08011108070605040302011801320e2f73657475702f6368616e6e656c30003800');
  const request=decodeGmsStruct('ChannelRequest',bytes);
  assert.equal(request.control.channelId,0x0102030405060708n);
  assert.equal(request.control.type,1);
  assert.equal(request.control.path,'/setup/channel');
  assert.equal(request.minimumVersion,0);
  assert.deepEqual(encodeGmsStruct('ChannelRequest',request),bytes);
  const header=h('090807060504030201100118ac02');
  assert.equal(decodeGmsStruct('ChannelHeader',header).sequence,300n);
  assert.deepEqual(encodeGmsStruct('ChannelHeader',{channelId:0x0102030405060708n,fromChannelOpener:true,sequence:300n}),header);
  assert.throws(()=>decodeGmsStruct('ChannelHeader',h('0801')),/wire type/);
});

test('Data item fields include certificate at 9, source sequence and external asset reference',()=>{
  const bytes=h('0a0170120a776561723a2f2f6e2f782201ff28818080808080801030003a016e420a0a0130120322016418044a01635001');
  const item=decodeGmsStruct('SetDataItem',bytes);
  assert.equal(item.packageName,'p');
  assert.equal(item.uri,'wear://n/x');
  assert.equal(item.sequenceId,9007199254740993n);
  assert.equal(item.deleted,false);
  assert.equal(item.assets[0].name,'0');
  assert.equal(item.assets[0].value.digest,'d');
  assert.equal(item.assets[0].type,4);
  assert.equal(item.packageDigest,'c');
  assert.deepEqual(encodeGmsStruct('SetDataItem',item),bytes);
});

test('SyncStart version 2 carries each origin and negative initial cursor independently',()=>{
  const bytes=h('08ffffffffffffffffff0112050a016e10031802');
  const message=decodeGmsStruct('SyncStart',bytes);
  assert.equal(message.receivedSequenceId,-1n);
  assert.equal(message.version,2);
  assert.deepEqual(message.syncTable.map(entry=>[entry.sourceNodeId,entry.sequenceId]),[['n',3n]]);
  assert.deepEqual(encodeGmsStruct('SyncStart',message),bytes);
});

test('setup capability encoder uses packed field6 while hardware capabilities remain unpacked',()=>{
  const binary=encodeGmsStruct('SetupSystemInfo',{version:4,capabilities:[1,6],edition:1,platformVersion:33,maintenanceReleaseVersion:0,setupCapabilities:[2,5,6],setupCapabilityVersion:1});
  assert.deepEqual(binary,h('08041001100618012021280032030205063801'));
  assert.deepEqual(decodeGmsStruct('SetupSystemInfo',binary).setupCapabilities,[2,5,6]);
});

test('RPC service correlation token and expectsResponse have distinct wire fields',()=>{
  const request=h('08011201701a016322016e280032022f783a02aabb42017350025801607f6801');
  const message=decodeGmsMessage(Buffer.concat([h('9201'),Buffer.from([request.length]),request,h('6801 f80107')]));
  const rpc=message.messages[0];
  assert.equal(rpc.field,18);
  assert.equal(rpc.kind,'RPC_SERVICE_REQUEST');
  assert.equal(rpc.requestId,1);
  assert.equal(rpc.generation,2);
  assert.equal(rpc.expectsResponse,true);
  assert.equal(rpc.responseToken,127);
  assert.equal(rpc.messageOption,1);
  assert.equal(message.hasFile,true);
  assert.equal(message.unknownFields[0].field,31);
  const again=encodeGmsMessage({rpcServiceRequest:decodeGmsStruct('RpcRequest',request),hasFile:true,unknownFields:message.unknownFields});
  assert.equal(decodeGmsMessage(again).messages[0].responseToken,127);
});

test('DataMap handles signed zigzag byte, int64, IEEE float, null and external asset index',()=>{
  // Entry n has value type=14 (null) and no storage. x is type=7 with
  // byte_value field7 sint32=-128, encoded zigzag255 = ff01.
  const bytes=h('0a070a016e1202080e0a0c0a017812070807120338ff01');
  const entries=decodeDataMap(bytes).entries;
  assert.deepEqual(entries,[{name:'n',type:14,typeName:'null',value:null},{name:'x',type:7,typeName:'byte',value:-128}]);
  // Reader-side Java byte narrowing differs from generic sint32 storage.
  // This hand-written field7 zigzag256 stores +128 but exposes byte -128.
  const wideByte=h('0a0c0a0178120708071203388002');
  const narrowed=decodeDataMap(wideByte);
  assert.equal(narrowed.entries[0].value,-128);
  assert.equal(narrowed.raw.entries[0].value.storage.byteValue,128);
  assert.deepEqual(encodeGmsStruct('DataMap',narrowed.raw),wideByte);
  const storage=h('19000000000000f43f250000c03f28ffffffffffffffffff016803');
  const values=decodeGmsStruct('DataMapStorage',storage);
  assert.equal(values.doubleValue,1.25);
  assert.equal(values.floatValue,1.5);
  assert.equal(values.longValue,-1n);
  assert.equal(values.assetIndex,3n);
  assert.deepEqual(encodeGmsStruct('DataMapStorage',values),storage);
});

test('DataMap numeric arrays accept observed unpacked and protobuf-compatible packed forms',()=>{
  const unpacked=h('600160ffffffffffffffffff01750000803f75000000c0');
  const packed=h('620b01ffffffffffffffffff0172080000803f000000c0');
  for(const bytes of [unpacked,packed]) {
    const parsed=decodeGmsStruct('DataMapStorage',bytes);
    assert.deepEqual(parsed.longArray,[1n,-1n]);
    assert.deepEqual(parsed.floatArray,[1,-2]);
    assert.deepEqual(encodeGmsStruct('DataMapStorage',parsed),unpacked);
  }
  assert.throws(()=>decodeGmsStruct('DataMapStorage',h('7203000000')),/Truncated/);
});

test('Typed DataMap encoder matches independent null/zigzag golden bytes and Java key ordering',()=>{
  const entries=[{name:'x',type:7,value:-128},{name:'n',type:14,value:null}];
  assert.deepEqual(encodeDataMap(entries),h('0a070a016e1202080e0a0c0a017812070807120338ff01'));
  const binary=encodeDataMap([
    {name:'__proto__',type:13,value:2n},
    {name:'nested',type:9,value:[{name:'z',type:5,value:9223372036854775807n},{name:'a',type:8,value:false}]},
    {name:'list',type:10,value:[{type:14,value:null},{type:6,value:0},{type:6,value:-1}]},
  ]);
  const decoded=decodeDataMap(binary).entries;
  assert.equal(decoded[0].name,'__proto__');
  assert.equal(decoded[0].value,2n);
  assert.deepEqual(decoded[2].value.map(item=>item.name),['a','z']);
  assert.equal(decoded[2].value[1].value,9223372036854775807n);
  assert.throws(()=>encodeDataMap([{name:'x',type:7,value:128}]),/Java byte/);
  assert.throws(()=>encodeDataMap([{name:'x',type:13,value:-1n}]),/Negative/);
  assert.throws(()=>encodeDataMap([{name:'x',type:10,value:[{type:2,value:'a'},{type:6,value:1}]}]),/ArrayList/);
  assert.throws(()=>encodeDataMap([{name:'x',type:14,value:null},{name:'x',type:14,value:null}]),/Duplicate/);
});

test('Transport asset ACK differs from empty heartbeat and watch-only tag19 stays raw',()=>{
  const parsed=decodeGmsMessage(h('2a030a01645a009a01020801'));
  assert.deepEqual(parsed.messages.map(entry=>entry.kind),['ACK_ASSET','HEARTBEAT']);
  assert.equal(parsed.messages[0].digest,'d');
  assert.equal(parsed.unknownFields[0].field,19);
  const file=h('0a016d10011a03616263220164');
  const decoded=decodeGmsStruct('FilePiece',file);
  assert.equal(decoded.finalPiece,true);
  assert.equal(decoded.ownerMessageDigest,'m');
  assert.equal(decoded.data.toString(),'abc');
});

test('complete setup envelopes preserve independent fields, sparse time numbers and opt-in payloads',()=>{
  const status=h('080322020803');
  assert.deepEqual(encodeGmsStruct('SetupMessage',{type:3,status:{state:3}}),status);
  assert.equal(decodeGmsStruct('SetupMessage',status).status.state,3);
  const time=h('0805321010ffffffffffffffffff012a03555443');
  assert.deepEqual(encodeGmsStruct('SetupMessage',{type:5,companionTime:{millis:-1n,timeZoneId:'UTC'}}),time);
  assert.equal(decodeGmsStruct('SetupMessage',time).companionTime.millis,-1n);
  const optin=h('08021a0b080312070805100118ac02');
  assert.deepEqual(encodeGmsStruct('SetupMessage',{type:2,event:{type:3,optin:{type:5,state:1,time:300n}}}),optin);
  // The generated envelope has independent optional fields, not a oneof.
  const both=h('12040801100122020801');
  assert.deepEqual(encodeGmsStruct('SetupMessage',{command:{type:1,id:1n},status:{state:1}}),both);
  const unknown=decodeGmsStruct('SetupMessage',h('3a01ff'));
  assert.equal(unknown.unknownFields[0].field,7);
  assert.deepEqual(encodeGmsStruct('SetupMessage',unknown),h('3a01ff'));
});

test('Strict offline schema validation rejects ambiguous, missing, unsafe and unbounded inputs',()=>{
  assert.throws(()=>decodeGmsStruct('SetDataItem',h('0a017012016e')),/required.*packageDigest/);
  assert.throws(()=>decodeGmsStruct('SyncEntry',h('10011002')),/Duplicate singular/);
  assert.throws(()=>decodeGmsStruct('SyncEntry',h('0a01ff')),/encoded data/);
  assert.throws(()=>encodeGmsStruct('ChannelHeader',{channelId:-1n}),/uint64/);
  assert.throws(()=>encodeGmsStruct('SyncEntry',{sequenceId:9007199254740992}),/exact/);
  assert.throws(()=>encodeGmsStruct('SyncEntry',{sequenceId:1n<<63n}),/int64/);
  assert.throws(()=>encodeGmsStruct('Heartbeat',{typo:1}),/Unknown encoder field/);
  assert.throws(()=>encodeGmsStruct('SyncEntry',{unknownFields:[{raw:h('1001')}]}),/collides/);
  const nested=()=>({type:9,storage:{mapEntries:[{name:'x',value:{type:14}}]}});
  const deep=nested(); let cursor=deep;
  for(let i=0;i<40;i++){const next=nested();cursor.storage.mapEntries[0].value=next;cursor=next;}
  assert.throws(()=>encodeGmsStruct('DataMapValue',deep),/nesting/);
  assert.throws(()=>decodeGmsStruct('Heartbeat',Buffer.alloc(1048577)),/size limit/);
});
