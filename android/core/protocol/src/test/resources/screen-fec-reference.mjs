// Independent GF(256) reference-vector generator for the documented Cauchy RS profile.
// It uses logarithm/exponent tables; production uses polynomial multiplication tables.
import {createHash} from 'node:crypto';
const exponent = new Uint8Array(510), logarithm = new Uint8Array(256);
let value = 1;
for (let i=0;i<255;i++) { exponent[i]=value; logarithm[value]=i; value<<=1; if(value&256)value^=0x11d; }
for(let i=255;i<510;i++)exponent[i]=exponent[i-255];
const mul=(a,b)=>a&&b?exponent[logarithm[a]+logarithm[b]]:0;
const inv=a=>exponent[255-logarithm[a]];
const data=Array.from({length:16},(_,r)=>Uint8Array.from({length:32},(_,c)=>(r*17+c*31)&255));
const parity=Array.from({length:4},(_,r)=>Uint8Array.from({length:32},(_,c)=>data.reduce((sum,row,k)=>sum^mul(inv(r^(k+4)),row[c]),0)));
console.log(parity.map(row=>Buffer.from(row).toString('hex')).join('\n'));
console.log(createHash('sha256').update(Buffer.concat(parity.map(row=>Buffer.from(row)))).digest('hex'));
