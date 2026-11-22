import test from 'node:test';
import assert from 'node:assert/strict';
import { encodeGmsMessage, decodeGmsMessage } from '../src/gms-messages.mjs';
import { encodeMessagePieces, reassembleMessagePieces, encodeGmsRecord, GmsRecordStreamDecoder } from '../src/gms-record.mjs';
import { encodeSetupQuery, encodeSetupFrame, SetupChannelStreamDecoder, decodeSetupMessage } from '../src/setup-channel.mjs';

test('synthetic first-setup data crosses Channel chunks, MessagePieces and RFCOMM records without mixing layers',()=>{
  const appKey={packageName:'com.mobvoi.companion.at',packageDigest:'5cc9866db03e530a21f842a421c7eb42bac58961'};
  const rpc={requestId:1,...appKey,targetNodeId:'watch',legacyZero:0,path:'',sourceNodeId:'phone',generation:0};
  const channelId=0x0102030405060708n;
  const open=decodeGmsMessage(encodeGmsMessage({channelRpcRequest:{...rpc,channel:{control:{type:1,channelId,fromChannelOpener:true,...appKey,path:'/setup/channel'},minimumVersion:0,origin:0}}}));
  assert.equal(open.messages[0].path,'');
  assert.equal(open.messages[0].channel.control.path,'/setup/channel');
  // Fixed independently written setup record; changing the outer encoding
  // cannot make an incorrect setup payload pass this assertion.
  const setup=encodeSetupFrame(encodeSetupQuery('GET_STATUS',{commandId:1n}));
  assert.equal(setup.toString('hex'),'000000080801120408011001');
  const chunks=[setup.subarray(0,3),setup.subarray(3,9),setup.subarray(9),Buffer.alloc(0)];
  const receiver=new SetupChannelStreamDecoder();
  const messages=[];
  for(let sequence=0;sequence<chunks.length;sequence++) {
    const final=sequence===chunks.length-1;
    const bytes=encodeGmsMessage({channelRpcRequest:{...rpc,requestId:sequence+2,channel:{data:{header:{channelId,fromChannelOpener:true,sequence:BigInt(sequence)},data:chunks[sequence],final},minimumVersion:0,origin:0}}});
    const pieces=encodeMessagePieces(bytes,{fragmentBytes:7,queueId:sequence+100});
    const records=Buffer.concat(pieces.map(encodeGmsRecord));
    const recordReader=new GmsRecordStreamDecoder();
    const received=[...records].flatMap(byte=>recordReader.push(Buffer.from([byte])));
    assert.equal(recordReader.finish().status,'complete');
    const packet=decodeGmsMessage(reassembleMessagePieces(received)).messages[0];
    assert.equal(packet.field,16);
    assert.equal(packet.channel.data.header.channelId,channelId);
    assert.equal(packet.channel.data.header.sequence,BigInt(sequence));
    assert.equal(packet.channel.data.final,final);
    messages.push(...receiver.push(packet.channel.data.data));
    // Explicitly model consumption before constructing an ACK. The actual
    // Android pipe, retry timers and peer-side authorization are not simulated.
    const ack=decodeGmsMessage(encodeGmsMessage({channelRpcRequest:{...rpc,targetNodeId:'phone',sourceNodeId:'watch',channel:{ack:{header:{channelId,fromChannelOpener:false,sequence:BigInt(sequence)},final}}}})).messages[0].channel.ack;
    assert.equal(ack.header.fromChannelOpener,false);
    assert.equal(ack.final,final);
  }
  assert.equal(receiver.finish().status,'complete');
  assert.equal(messages.length,1);
  assert.equal(decodeSetupMessage(messages[0]).command.type.name,'GET_STATUS');
});
