# Zygisk SDK provenance

- Source: <https://github.com/topjohnwu/zygisk-module-sample/blob/7bb941ac8edfcffd1d23761e401c45ca95409dc1/module/jni/zygisk.hpp>
- Exact commit: `7bb941ac8edfcffd1d23761e401c45ca95409dc1`; public Zygisk API **4**, minimum Magisk **26000**.
- `src/main/cpp/zygisk.hpp` is copied unchanged; SHA-256 `fc6523882c0f8659b4fcd1e04ffb07d3a948090e5a8c08817af6e2b506a078d0`.
- The header is 0BSD; its original copyright and permission notice are preserved. The rest of this repository remains under its root license.
- Module installer bootstrap is adapted from <https://github.com/topjohnwu/Magisk/blob/v26.0/scripts/module_installer.sh> (GPL-3.0), loading the installed Magisk utility functions without downloading code at installation time.
- Build NDK: **r27c / 27.2.12479018**, Android SDK Build Tools **37.0.0**, compile platform **android-37.2**, Java **17**, D8 min API **30**.

The loader links no bundled C++ STL, does not register any global hooks, never touches system_server, and honors DenyList. By default it loads only `com.mobvoi.wear.health.at` with its matching private app directory. A separate root-owned `notification-alerts.enabled` marker opts the primary-user `com.mobvoi.wear.refsysui` main process into its own exact-APK-verified entry. That entry wraps two existing Java pattern-loader interfaces; it uses neither an ART method-patching library nor copied OEM implementation. The ZIP contains no marker. See [notification alerts](../../../docs/spec/notification-alerts.md) and [watch components](../../../docs/dev/watch.md) for the fixed profile, control and disable path.

A bounded worker waits for the real Application and invokes the verified Java entry; the Java side posts startup to the app main Looper after Application.onCreate. Existing running apps require a later process start/reboot before injection. Failure leaves the extension unavailable, without fabricating another package's Context.

Build without installing: `cd android && ./gradlew :watch:healthmodule:check :watch:healthmodule:moduleZip`. The independent ZIP is under `android/watch/healthmodule/build/distributions/`. `moduleZip` is timestamp-normalized and has stable entry ordering. `verifyModuleZip` checks both ELF ABIs, the DEX entry, and the absence of global policy/property/startup scripts; native policy tests cover wrong process, wrong app directory, UID, DenyList and malformed DEX refusal. Java controller tests run under Robolectric; no target execution is part of the build.
