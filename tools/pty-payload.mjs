/** Static checks for the tiny PTY payload loaded by the standalone shell/root agent. */
import { createHash } from 'node:crypto';

export const PTY_ABIS = Object.freeze({
  'armeabi-v7a': { elfClass: 1, machine: 40 },
  'arm64-v8a': { elfClass: 2, machine: 183 },
  x86: { elfClass: 1, machine: 3 },
  x86_64: { elfClass: 2, machine: 62 },
});
export const PTY_ASSETS = Object.freeze(Object.keys(PTY_ABIS).map(abi => `assets/toolbox/pty/${abi}/libticbox_pty.so`));
export const PTY_JNI_CLASS = 'Ldev/ticbox/companion/toolboxagent/PtyNative;';
export const SCREEN_JNI_CLASS = 'Ldev/ticbox/companion/toolboxagent/ScreenNative;';
export const PTY_JNI_EXPORTS = Object.freeze([
  ...['start', 'read', 'write', 'resize', 'awaitExit', 'close'].map(method => `Java_dev_ticbox_companion_toolboxagent_PtyNative_${method}`),
  ...['open', 'sendBatch', 'close', 'fecMode', 'encodeParity', 'threadCpuNanos'].map(method => `Java_dev_ticbox_companion_toolboxagent_ScreenNative_${method}`),
]);

export function verifyPtyAssetEntries(entries) {
  const files = entries.filter(name => name && !name.endsWith('/'));
  if (PTY_ASSETS.some(path => files.filter(name => name === path).length !== 1)
    || files.some(name => name.startsWith('assets/toolbox/pty/') && !PTY_ASSETS.includes(name))) {
    throw new Error('watch: expected exactly the four supported PTY native assets');
  }
  // The watch APK is also a portable ADB agent. Native assets must not restrict APK installation
  // to the host phone/watch ABI or be copied into a second native library location.
  if (files.some(name => /^lib\/[^/]+\/libticbox_pty\.so$/.test(name))) {
    throw new Error('watch: PTY libraries must be assets, not ABI-filtering APK native libraries');
  }
}

/** Validates original ELF declarations; this neither loads the library nor proves JNI execution. */
export function inspectPtyLibrary(bytes, abi) {
  const profile = PTY_ABIS[abi];
  const fail = reason => { throw new Error(`watch: invalid PTY library ${abi}: ${reason}`); };
  const check = (condition, reason) => { if (!condition) fail(reason); };
  check(profile && Buffer.isBuffer(bytes) && bytes.length >= 64 && bytes.length <= 4 * 1024 * 1024, 'size or ABI');
  const range = (offset, length) => {
    check(Number.isSafeInteger(offset) && Number.isSafeInteger(length) && offset >= 0 && length >= 0 && offset <= bytes.length - length, 'out-of-bounds ELF range');
  };
  const u16 = offset => { range(offset, 2); return bytes.readUInt16LE(offset); };
  const u32 = offset => { range(offset, 4); return bytes.readUInt32LE(offset); };
  const u64 = offset => {
    range(offset, 8);
    const value = bytes.readBigUInt64LE(offset);
    check(value <= BigInt(Number.MAX_SAFE_INTEGER), 'unrepresentable ELF integer');
    return Number(value);
  };
  check(bytes.subarray(0, 4).equals(Buffer.from([0x7f, 0x45, 0x4c, 0x46])) && bytes[4] === profile.elfClass && bytes[5] === 1 && bytes[6] === 1, 'ELF identity');
  check(u16(16) === 3 && u16(18) === profile.machine && u32(20) === 1, 'shared-object machine/type');
  const wide = profile.elfClass === 2;
  check(u16(wide ? 52 : 40) === (wide ? 64 : 52), 'header size');
  const programOffset = wide ? u64(32) : u32(28);
  const programSize = u16(wide ? 54 : 42);
  const programCount = u16(wide ? 56 : 44);
  check(programSize === (wide ? 56 : 32) && programCount > 0 && programCount < 4096, 'program table');
  range(programOffset, programSize * programCount);
  const loads = [];
  for (let i = 0; i < programCount; i++) {
    const at = programOffset + i * programSize;
    if (u32(at) !== 1) continue;
    const offset = wide ? u64(at + 8) : u32(at + 4);
    const address = wide ? u64(at + 16) : u32(at + 8);
    const fileSize = wide ? u64(at + 32) : u32(at + 16);
    const memorySize = wide ? u64(at + 40) : u32(at + 20);
    const flags = u32(at + (wide ? 4 : 24));
    const alignment = wide ? u64(at + 48) : u32(at + 28);
    range(offset, fileSize);
    check(memorySize >= fileSize, 'segment sizes');
    check(alignment >= 16384 && (BigInt(alignment) & (BigInt(alignment) - 1n)) === 0n && offset % alignment === address % alignment, '16KB PT_LOAD alignment');
    loads.push({ address, fileSize, flags, alignment });
  }
  check(loads.some(segment => (segment.flags & 1) !== 0 && segment.fileSize > 0), 'executable load segment');
  const sectionOffset = wide ? u64(40) : u32(32);
  const sectionSize = u16(wide ? 58 : 46);
  const sectionCount = u16(wide ? 60 : 48);
  check(sectionSize === (wide ? 64 : 40) && sectionCount > 0 && sectionCount < 4096, 'section table');
  range(sectionOffset, sectionSize * sectionCount);
  const sections = [];
  for (let i = 0; i < sectionCount; i++) {
    const at = sectionOffset + i * sectionSize;
    sections.push({
      type: u32(at + 4),
      offset: wide ? u64(at + 24) : u32(at + 16),
      size: wide ? u64(at + 32) : u32(at + 20),
      link: u32(at + (wide ? 40 : 24)),
      entrySize: wide ? u64(at + 56) : u32(at + 36),
    });
  }
  const exports = new Map();
  for (const section of sections.filter(value => value.type === 11)) {
    range(section.offset, section.size);
    check(section.entrySize === (wide ? 24 : 16) && section.size % section.entrySize === 0, 'dynamic symbol table');
    const strings = sections[section.link];
    check(strings?.type === 3, 'dynamic symbol strings');
    range(strings.offset, strings.size);
    for (let offset = section.offset; offset < section.offset + section.size; offset += section.entrySize) {
      const nameOffset = u32(offset);
      check(nameOffset < strings.size, 'symbol name offset');
      const end = bytes.indexOf(0, strings.offset + nameOffset);
      check(end >= 0 && end < strings.offset + strings.size, 'unterminated symbol');
      const name = bytes.toString('utf8', strings.offset + nameOffset, end);
      if (!PTY_JNI_EXPORTS.includes(name)) continue;
      const info = bytes[offset + (wide ? 4 : 12)];
      const visibility = bytes[offset + (wide ? 5 : 13)] & 3;
      const sectionIndex = u16(offset + (wide ? 6 : 14));
      const address = wide ? u64(offset + 8) : u32(offset + 4);
      const size = wide ? u64(offset + 16) : u32(offset + 8);
      check((info >>> 4) === 1 && (info & 15) === 2 && [0, 3].includes(visibility)
        && sectionIndex > 0 && sectionIndex < sectionCount && size > 0
        && loads.some(segment => (segment.flags & 1) !== 0 && address >= segment.address && address - segment.address < segment.fileSize), `JNI export ${name}`);
      exports.set(name, (exports.get(name) ?? 0) + 1);
    }
  }
  check(PTY_JNI_EXPORTS.every(name => exports.get(name) === 1), 'missing or duplicate JNI exports');
  return { abi, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'),
    loadAlignment: Math.min(...loads.map(segment => segment.alignment)), exports: [...PTY_JNI_EXPORTS] };
}
