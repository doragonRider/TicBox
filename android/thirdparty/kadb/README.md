# Kadb Android transport binding

This module contains the Android and shared sources of
[Kadb 2.1.5](https://github.com/flyfishxu/Kadb/tree/3d1c13e8df261cc4a2bb57b03aa8c392cb8386c3),
commit `3d1c13e8df261cc4a2bb57b03aa8c392cb8386c3`. It replaces the Maven AAR in
`:core:platform`; there is one Kadb implementation in the phone APK. Runtime
transitive dependencies retain the versions used by that release. The phone
minimum API is 34; this module is not part of the watch runtime.

`upstream-index.json` records each original upstream path and SHA-256 before local
edits. Kotlin common/Android `expect`/`actual` declarations are flattened to an
Android-only binding. Desktop implementations are omitted. Upstream copyright
headers and [Apache-2.0 license](LICENSE) are preserved. Modified source files
carry a notice. The application's third-party NOTICE also identifies this fork.
SPAKE2-Java remains a GPL dependency under the existing project distribution.

The runtime changes are the TLS upgrade and delayed ACK. For the TLS upgrade:

- `AdbConnection.kt`: after the existing STLS exchange, wrap the same connected
  TCP socket using the existing SSLContext and client key manager.
- `PlainBlockingChannel.kt`: upgrade through `SSLSocket`, with TLS 1.3 as the only
  enabled protocol, and let the platform manage records and post-handshake work.
  Closing the raw socket first interrupts blocked I/O or handshakes before
  releasing the TLS socket. One stream's CLSE still leaves the TLS transport open.
- The hand-driven `TlsNioChannel.kt` is omitted. `SslUtils.kt` changes only lint
  annotations/comments explaining the existing ADB self-signed trust model;
  the key choice, certificate policy and SPAKE2 logic are retained.

This addresses a suspected client TLS transport problem (BAD_RECORD_MAC on some
devices). It does **not** establish which old engine branch caused it; the
published AAR did not reproduce the failure under a Linux host fixture. No
additional REQUEST replay, certificate bypass, new key generation, TLS downgrade
or APK native TLS payload is introduced.

For delayed ACK, which upstream only exposes as a mode:

- `KadbOptions.delayedAckWindowBytes` sets the receive window sent in OPEN. Upstream
  always asks for 32 MiB, which is how far a stream's reader may fall behind.
- `AdbStream` sends one OKAY per message read, not one per 8 KiB buffered read, and
  collects the OKAYs that arrive for its writes. `AdbMessageQueue.poll` makes that
  possible without waiting, and lets a write notice the peer closed the stream.

`AndroidTlsTransportTest` uses real loopback sockets, synthetic RSA identities and
two independent TLS providers: a Conscrypt client (test dependency only) and a
SunJSSE peer. The peer asserts TLS 1.3 and the expected client public key. Tests
cover STLS, 200 sequential / 400 parallel ADB stream lifecycles, large binary I/O,
peer KeyUpdate, an unpaired key, a TLS 1.2-only peer, timeout and cancellation.
The upstream queue, stream, USB framing and connection lifecycle tests are kept.
The host test cannot exercise vendor TLS providers or a real adbd.

The existing application certificate store and `AdbKeyPair` API are unchanged;
users can cover-install the phone APK and retain the existing ADB pairing.
Production uses Android's built-in provider. Conscrypt's host-test native library
is excluded from Android runtime dependencies.

`backendCheck` and `backendDebugCheck` include this module's tests and lint. Lint
retains upstream SDK guards even though they are redundant for the phone's API
34 minimum; ADB's existing self-signed trust manager and pinned dependencies have
scoped lint explanations. Other modules retain their existing checks.
