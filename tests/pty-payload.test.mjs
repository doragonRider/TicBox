import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { PTY_ABIS, PTY_ASSETS, PTY_JNI_EXPORTS, inspectPtyLibrary, verifyPtyAssetEntries } from '../tools/pty-payload.mjs';

/** A small ELF image with an executable mapping and actual dynamic symbol declarations. */
function elf(abi) {
  const profile = PTY_ABIS[abi];
  const wide = profile.elfClass === 2;
  const bytes = Buffer.alloc(0x3000);
  const word = (offset, value) => wide ? bytes.writeBigUInt64LE(BigInt(value), offset) : bytes.writeUInt32LE(value, offset);
  bytes.set([0x7f, 0x45, 0x4c, 0x46, profile.elfClass, 1, 1]);
  bytes.writeUInt16LE(3, 16);
  bytes.writeUInt16LE(profile.machine, 18);
  bytes.writeUInt32LE(1, 20);
  word(wide ? 32 : 28, 64);
  word(wide ? 40 : 32, 0x1000);
  bytes.writeUInt16LE(wide ? 64 : 52, wide ? 52 : 40);
  bytes.writeUInt16LE(wide ? 56 : 32, wide ? 54 : 42);
  bytes.writeUInt16LE(1, wide ? 56 : 44);
  bytes.writeUInt16LE(wide ? 64 : 40, wide ? 58 : 46);
  bytes.writeUInt16LE(4, wide ? 60 : 48);
  bytes.writeUInt32LE(1, 64);
  word(64 + (wide ? 8 : 4), 0);
  word(64 + (wide ? 16 : 8), 0x4000);
  word(64 + (wide ? 32 : 16), bytes.length);
  word(64 + (wide ? 40 : 20), bytes.length);
  bytes.writeUInt32LE(5, 64 + (wide ? 4 : 24));
  word(64 + (wide ? 48 : 28), 16384);
  const symbolSize = wide ? 24 : 16;
  const names = Buffer.from('\0' + PTY_JNI_EXPORTS.join('\0') + '\0');
  names.copy(bytes, 0x800);
  const section = (index, type, offset, size, link = 0, entrySize = 0) => {
    const at = 0x1000 + index * (wide ? 64 : 40);
    bytes.writeUInt32LE(type, at + 4);
    word(at + (wide ? 24 : 16), offset);
    word(at + (wide ? 32 : 20), size);
    bytes.writeUInt32LE(link, at + (wide ? 40 : 24));
    word(at + (wide ? 56 : 36), entrySize);
  };
  section(1, 11, 0x400, symbolSize * (PTY_JNI_EXPORTS.length + 1), 2, symbolSize);
  section(2, 3, 0x800, names.length);
  section(3, 1, 0x2000, 0x100);
  let nameOffset = 1;
  PTY_JNI_EXPORTS.forEach((name, i) => {
    const at = 0x400 + symbolSize * (i + 1);
    bytes.writeUInt32LE(nameOffset, at);
    word(at + (wide ? 8 : 4), 0x6000 + i * 16);
    word(at + (wide ? 16 : 8), 16);
    bytes[at + (wide ? 4 : 12)] = 0x12;
    bytes.writeUInt16LE(3, at + (wide ? 6 : 14));
    nameOffset += Buffer.byteLength(name) + 1;
  });
  return bytes;
}

for (const abi of Object.keys(PTY_ABIS)) {
  test(`PTY ${abi}: records supported ABI, 16KB mappings and defined JNI exports`, () => {
    const bytes = elf(abi);
    const result = inspectPtyLibrary(bytes, abi);
    assert.equal(result.abi, abi);
    assert.equal(result.bytes, bytes.length);
    assert.equal(result.loadAlignment, 16384);
    assert.equal(result.sha256, createHash('sha256').update(bytes).digest('hex'));
    assert.deepEqual(result.exports, PTY_JNI_EXPORTS);
  });
}

test('PTY assets: requires exactly four portable payloads and rejects duplicate/ABI-filtering copies', () => {
  verifyPtyAssetEntries([...PTY_ASSETS, 'assets/toolbox/pty/', 'classes.dex']);
  assert.throws(() => verifyPtyAssetEntries(PTY_ASSETS.slice(1)), /four supported/);
  assert.throws(() => verifyPtyAssetEntries([...PTY_ASSETS, PTY_ASSETS[0]]), /four supported/);
  assert.throws(() => verifyPtyAssetEntries([...PTY_ASSETS, 'assets/toolbox/pty/riscv64/libticbox_pty.so']), /four supported/);
  assert.throws(() => verifyPtyAssetEntries([...PTY_ASSETS, 'lib/arm64-v8a/libticbox_pty.so']), /ABI-filtering/);
});

test('PTY ELF refuses a renamed wrong-ABI binary, executable file or big-endian image', () => {
  assert.throws(() => inspectPtyLibrary(elf('x86_64'), 'arm64-v8a'), /machine/);
  assert.throws(() => inspectPtyLibrary(elf('x86'), 'x86_64'), /identity/);
  const executable = elf('arm64-v8a');
  executable.writeUInt16LE(2, 16);
  assert.throws(() => inspectPtyLibrary(executable, 'arm64-v8a'), /machine\/type/);
  const swapped = elf('arm64-v8a');
  swapped[5] = 2;
  assert.throws(() => inspectPtyLibrary(swapped, 'arm64-v8a'), /identity/);
});

test('PTY ELF refuses 4KB-only or incongruent load segments and missing executable mappings', () => {
  const small = elf('arm64-v8a');
  small.writeBigUInt64LE(4096n, 64 + 48);
  assert.throws(() => inspectPtyLibrary(small, 'arm64-v8a'), /16KB/);
  const incongruent = elf('x86');
  incongruent.writeUInt32LE(0x4001, 64 + 8);
  assert.throws(() => inspectPtyLibrary(incongruent, 'x86'), /16KB/);
  const noCode = elf('x86_64');
  noCode.writeUInt32LE(4, 64 + 4);
  assert.throws(() => inspectPtyLibrary(noCode, 'x86_64'), /executable/);
});

test('PTY ELF requires defined, externally visible function exports, not just matching strings', () => {
  const stringsOnly = elf('arm64-v8a');
  stringsOnly.writeUInt32LE(1, 0x1000 + 64 + 4);
  assert.throws(() => inspectPtyLibrary(stringsOnly, 'arm64-v8a'), /missing.*JNI/);
  const undefinedSymbol = elf('x86');
  undefinedSymbol.writeUInt16LE(0, 0x400 + 16 + 14);
  assert.throws(() => inspectPtyLibrary(undefinedSymbol, 'x86'), /JNI export/);
  const hiddenSymbol = elf('x86_64');
  hiddenSymbol[0x400 + 24 + 5] = 2;
  assert.throws(() => inspectPtyLibrary(hiddenSymbol, 'x86_64'), /JNI export/);
  const dataSymbol = elf('arm64-v8a');
  dataSymbol[0x400 + 24 + 4] = 0x11;
  assert.throws(() => inspectPtyLibrary(dataSymbol, 'arm64-v8a'), /JNI export/);
});

test('PTY ELF refuses truncated tables and impossible 64-bit offsets before accessing them', () => {
  assert.throws(() => inspectPtyLibrary(elf('arm64-v8a').subarray(0, 100), 'arm64-v8a'), /out-of-bounds/);
  const overflow = elf('arm64-v8a');
  overflow.writeBigUInt64LE(2n ** 63n, 40);
  assert.throws(() => inspectPtyLibrary(overflow, 'arm64-v8a'), /unrepresentable/);
  const badStringTable = elf('x86');
  badStringTable.writeUInt32LE(99, 0x1000 + 40 + 24);
  assert.throws(() => inspectPtyLibrary(badStringTable, 'x86'), /strings/);
  const outsideCode = elf('x86_64');
  outsideCode.writeBigUInt64LE(0xffffn, 0x400 + 24 + 8);
  assert.throws(() => inspectPtyLibrary(outsideCode, 'x86_64'), /JNI export/);
});
