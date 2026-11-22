# Toolbox PTY native boundary

This small C/JNI implementation is part of the repository's GPL-3.0-only code. It uses the system PTY and process APIs; it does not bundle a shell, terminal renderer, C++ runtime, or package manager.

- NDK 27.2.12479018, Android API 30, ARM32/ARM64/x86/x86_64. Every ELF load segment is aligned for 16 KiB pages.
- Libraries are assets, so embedding the watch APK does not impose the phone's ABI on a remote ADB target. The standalone agent chooses its own process ABI, copies only its APK's fixed asset into a private temporary directory, loads it explicitly, and removes the temporary file. UID 0 creates that directory directly under the validated root-owned `/data/local`; only shell UID 2000 uses `/data/local/tmp`. Fixed ancestors are checked with `lstat`, and unsafe or missing parents fail closed without fallback or permission repair.
- `start` creates a controlling terminal and invokes the selected system shell with `-i`. A close-on-exec error pipe confirms exec before the protocol can report ready. The child resets inherited signals and closes inherited descriptors before exec.
- Opaque IDs refer to owned native records. References and duplicate nonblocking master descriptors keep concurrent I/O from using a recycled file descriptor. A cancellation pipe wakes blocked reads/writes; closing the session also wakes `awaitExit`.
- Closing the last master descriptor uses the kernel's terminal hangup behavior. It never turns an ordinary disconnect into SIGKILL. A separate `waitpid` owner reaps the shell; a child that ignores hangup is allowed to survive. The terminal session does not claim that every background process exited.
- The raw byte stream includes remote echo, ANSI escapes, and merged stdout/stderr. Ordinary command and file-transfer pipes remain separate.

The standalone host JNI test runs the production C code against real Linux PTYs. It checks controlling TTYs, dimensions, merged output, foreground Ctrl-C, canonical Ctrl-D, actual exit status, exec failure, concurrent close, and a child that ignores hangup. The Android ELF payload verifier checks all four actual libraries. Device SELinux and Android shell behavior still require an ADB and root-bridge device test.

Platform references: [Linux PTY overview](https://man7.org/linux/man-pages/man7/pty.7.html), [terminal ioctls](https://man7.org/linux/man-pages/man2/ioctl_tty.2.html), [fork restrictions](https://man7.org/linux/man-pages/man2/fork.2.html), and [AOSP shell service](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/shell_service.cpp).
