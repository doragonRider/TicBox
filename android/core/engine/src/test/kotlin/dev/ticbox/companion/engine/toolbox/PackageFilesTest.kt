package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import java.io.InputStream
import kotlin.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class PackageFilesTest {
    private val pkg = "dev.example.game"
    private val watch = WatchProfile(listOf("armeabi_v7a", "armeabi"), density = 320, languages = setOf("zh"), sdk = 33)

    private fun file(name: String, bytes: ByteArray) = ToolboxApk(name, bytes.size.toLong()) { bytes.inputStream() }

    private fun read(name: String, bytes: ByteArray, profile: WatchProfile = watch): ToolboxBundle? =
        runBlocking { readPackageFile(file(name, bytes)) { profile } }

    private fun readBundle(name: String, entries: List<ArchiveEntry>, profile: WatchProfile = watch): ToolboxBundle =
        assertNotNull(read(name, archiveOf(entries), profile))

    private fun apk(name: String, split: String? = null, deflated: Boolean = false) = ArchiveEntry(name, apkOf(pkg, split), deflated)

    private fun refusal(name: String, entries: List<ArchiveEntry>, profile: WatchProfile = watch): String =
        assertFailsWith<ToolboxException> { read(name, archiveOf(entries), profile) }.code

    private fun ToolboxBundle.names() = apks.map { it.name }

    /** As a real .xapk from APKPure has it, down to the parts named like something else. */
    private fun xapkEntries() = listOf(
        apk("config.ko.apk", "config.ko"),
        apk("$pkg.apk"),
        apk("main.apk", "main"),
        apk("obb.apk", "obb"),
        apk("config.arm64_v8a.apk", "config.arm64_v8a"),
        apk("config.armeabi_v7a.apk", "config.armeabi_v7a"),
        apk("config.xhdpi.apk", "config.xhdpi"),
        apk("config.xxhdpi.apk", "config.xxhdpi"),
        apk("config.zh.apk", "config.zh"),
        apk("config.en.apk", "config.en"),
        ArchiveEntry("icon.png", ByteArray(10)),
        ArchiveEntry("manifest.json", """{"xapk_version":2,"name":"A Game","version_name":"2.0","package_name":"$pkg"}""".toByteArray()),
    )

    @Test fun anXapkKeepsWhatThisWatchNeedsGoingByEachApksOwnManifest() {
        val bundle = readBundle("game.xapk", xapkEntries())
        assertEquals(pkg, bundle.packageName)
        assertEquals("A Game", bundle.label)
        assertEquals("2.0", bundle.versionName)
        assertEquals("$pkg.apk", bundle.names().first())
        assertEquals(
            setOf("$pkg.apk", "main.apk", "obb.apk", "config.armeabi_v7a.apk", "config.xhdpi.apk", "config.zh.apk", "config.en.apk"),
            bundle.names().toSet(),
        )
        assertEquals(3, bundle.skippedApks)
        assertTrue(bundle.data.isEmpty())
    }

    @Test fun anApkmWithModulesIsRead() {
        val bundle = readBundle(
            "app.apkm",
            listOf(
                apk("base.apk", deflated = true),
                apk("split_config.armeabi_v7a.apk", "config.armeabi_v7a", deflated = true),
                apk("split_config.x86.apk", "config.x86", deflated = true),
                apk("split_loader.apk", "loader", deflated = true),
                apk("split_loader.config.armeabi_v7a.apk", "loader.config.armeabi_v7a", deflated = true),
                apk("split_loader.config.x86.apk", "loader.config.x86", deflated = true),
                ArchiveEntry("APKM_installer.url", ByteArray(5)),
                ArchiveEntry("info.json", """{"apkm_version":5,"pname":"$pkg","app_name":"Mirror","release_version":"3.1"}""".toByteArray(), deflated = true),
            ),
        )
        assertEquals("Mirror", bundle.label)
        assertEquals("3.1", bundle.versionName)
        assertEquals(setOf("base.apk", "split_config.armeabi_v7a.apk", "split_loader.apk", "split_loader.config.armeabi_v7a.apk"), bundle.names().toSet())
    }

    @Test fun anApkPlusBackupIsRead() {
        val bundle = readBundle(
            "game.apk+",
            listOf(
                ArchiveEntry("apk+.json", """{"app_name":"Backed Up","package_name":"$pkg","version_code":7,"version_name":"7.0","min_sdk_version":30}""".toByteArray()),
                ArchiveEntry("icon.png", ByteArray(10)),
                apk("base.apk"),
                apk("split_config.armeabi_v7a.apk", "config.armeabi_v7a"),
            ),
        )
        assertEquals("Backed Up", bundle.label)
        assertEquals(listOf("base.apk", "split_config.armeabi_v7a.apk"), bundle.names())
    }

    @Test fun aPhonesBackupMadeForAnotherProcessorIsSaidToBeSo() {
        val entries = listOf(apk("base.apk"), apk("split_config.arm64_v8a.apk", "config.arm64_v8a"))
        assertEquals("package_wrong_abi", refusal("phone.apk+", entries))
    }

    private fun variant(minSdk: Int, number: Int, vararg sets: Proto): Proto {
        val sdk = Proto().message(1, Proto().message(1, Proto().number(1, minSdk.toLong())))
        return Proto().message(1, Proto().message(1, sdk)).number(3, number.toLong()).also { variant -> sets.forEach { variant.message(2, it) } }
    }

    private fun apkSet(module: String, delivery: Long, vararg paths: String, kind: Int = 3): Proto {
        val set = Proto().message(1, Proto().text(1, module).number(6, delivery))
        paths.forEach { set.message(2, Proto().text(2, it).message(kind, Proto())) }
        return set
    }

    @Test fun aBundletoolSetFollowsItsTableOfContents() {
        val toc = Proto()
            .message(1, variant(1, 0, apkSet("base", 1, "standalones/standalone-armeabi_v7a_xhdpi.apk", kind = 4)))
            .message(
                1,
                variant(
                    21,
                    1,
                    apkSet("base", 1, "splits/base-master.apk", "splits/base-armeabi_v7a.apk", "splits/base-arm64_v8a.apk", "splits/base-zh.apk"),
                    apkSet("later", 2, "splits/later-master.apk"),
                    apkSet("first", 1, "splits/first-master.apk"),
                ),
            )
            .message(1, variant(40, 2, apkSet("base", 1, "splits/base-master_2.apk")))
            .message(3, Proto().message(1, Proto().text(1, "pack").number(4, 1)).message(2, Proto().text(2, "asset-slices/pack-master.apk").message(7, Proto())))
            .message(3, Proto().message(1, Proto().text(1, "big").number(4, 2)).message(2, Proto().text(2, "asset-slices/big-master.apk").message(7, Proto())))
            .bytes()
        val bundle = readBundle(
            "app.apks",
            listOf(
                ArchiveEntry("toc.pb", toc),
                apk("standalones/standalone-armeabi_v7a_xhdpi.apk"),
                apk("splits/base-master.apk"),
                apk("splits/base-master_2.apk"),
                apk("splits/base-armeabi_v7a.apk", "config.armeabi_v7a"),
                apk("splits/base-arm64_v8a.apk", "config.arm64_v8a"),
                apk("splits/base-zh.apk", "config.zh"),
                apk("splits/later-master.apk", "later"),
                apk("splits/first-master.apk", "first"),
                apk("asset-slices/pack-master.apk", "pack"),
                apk("asset-slices/big-master.apk", "big"),
            ),
        )
        assertEquals(setOf("base-master.apk", "base-armeabi_v7a.apk", "base-zh.apk", "first-master.apk", "pack-master.apk"), bundle.names().toSet())
        assertEquals(5, bundle.skippedApks)
    }

    @Test fun aSetWithNothingForThisAndroidVersionIsRefused() {
        val toc = Proto().message(1, variant(40, 0, apkSet("base", 1, "splits/base-master.apk"))).bytes()
        assertEquals("package_incompatible", refusal("app.apks", listOf(ArchiveEntry("toc.pb", toc), apk("splits/base-master.apk"))))
    }

    @Test fun obbFilesKeepTheirPlaceAndContent() {
        val obb = ByteArray(9_000) { (it % 7).toByte() }
        val path = "Android/obb/$pkg/main.12.$pkg.obb"
        val bundle = readBundle(
            "game.xapk",
            listOf(
                apk("$pkg.apk"),
                ArchiveEntry(path, obb),
                ArchiveEntry("Android/obb/$pkg/patch.12.$pkg.obb", ByteArray(5)),
                ArchiveEntry("manifest.json", """{"expansions":[{"file":"$path","install_location":"EXTERNAL_STORAGE","install_path":"$path"}]}""".toByteArray()),
            ),
        )
        assertEquals(listOf(path, "Android/obb/$pkg/patch.12.$pkg.obb"), bundle.data.map { it.path })
        assertContentEquals(obb, bundle.data.first().open().use(InputStream::readBytes))
        assertEquals(1, bundle.apks.size)
        assertEquals(9_005, bundle.dataBytes)
    }

    @Test fun anObbOutsideItsFolderGoesWhereItsOwnNameSays() {
        val bundle = readBundle("game.xapk", listOf(apk("base.apk"), ArchiveEntry("main.3.$pkg.obb", ByteArray(4))))
        assertEquals(listOf("Android/obb/$pkg/main.3.$pkg.obb"), bundle.data.map { it.path })
    }

    @Test fun nothingMayBeWrittenOutsideTheAppsOwnFolders() {
        fun expansion(installPath: String) = listOf(
            apk("base.apk"),
            ArchiveEntry("data.bin", ByteArray(4)),
            ArchiveEntry("manifest.json", """{"expansions":[{"file":"data.bin","install_path":"$installPath"}]}""".toByteArray()),
        )
        listOf(
            "/data/adb/service.d/x.sh",
            "../../../data/adb/x",
            "Android/obb/$pkg/../../../x",
            "Android/obb/$pkg/../other/x",
            "Android/obb/com.other.app/x.obb",
            "Android/obb/$pkg",
            "Pictures/x.jpg",
        ).forEach { assertEquals("package_bad_path", refusal("game.xapk", expansion(it)), it) }
        listOf(
            "Android/obb/$pkg/../../x.obb",
            "Android/obb/com.other.app/main.1.com.other.app.obb",
            "Android/data/com.other.app/x",
            "main.1.com.other.app.obb",
            "stray.obb",
        ).forEach { assertEquals("package_bad_path", refusal("game.xapk", listOf(apk("base.apk"), ArchiveEntry(it, ByteArray(4)))), it) }
    }

    @Test fun anExpansionThatIsntThereIsDamage() {
        val entries = listOf(apk("base.apk"), ArchiveEntry("manifest.json", """{"expansions":[{"file":"gone.obb"}]}""".toByteArray()))
        assertEquals("package_unreadable", refusal("game.xapk", entries))
    }

    @Test fun aPlainApkIsLeftForTheNormalInstallWithoutAskingTheWatch() = runBlocking<Unit> {
        var asked = false
        val apk = file("app.apk", apkOf(pkg))
        assertNull(readPackageFile(apk) { asked = true; watch })
        assertFalse(asked)
        // Named oddly, or not even an archive: the package manager gets the last word on an .apk.
        assertNull(read("renamed.apks", apkOf(pkg)))
        assertNull(read("odd.apk", ByteArray(100)))
    }

    @Test fun otherFilesAreToldApart() {
        assertEquals("package_encrypted", assertFailsWith<ToolboxException> { read("old.apkm", ByteArray(100) { 9 }) }.code)
        assertEquals("package_not_recognised", assertFailsWith<ToolboxException> { read("photo.jpg", ByteArray(100) { 9 }) }.code)
        assertEquals("package_not_recognised", refusal("music.zip", listOf(ArchiveEntry("a.mp3", ByteArray(9)))))
    }

    @Test fun onePackageFileIsOneApp() {
        assertEquals("package_many_apps", refusal("two.zip", listOf(apk("a.apk"), ArchiveEntry("b.apk", apkOf("dev.other")))))
        assertEquals("package_many_apps", refusal("two.zip", listOf(apk("a.apk"), apk("b.apk"))))
        assertEquals("package_unreadable", refusal("none.zip", listOf(apk("config.en.apk", "config.en"))))
        assertEquals("package_unreadable", refusal("bad.zip", listOf(ArchiveEntry("a.apk", ByteArray(50)))))
    }

    private fun splits(vararg names: String) = names.toList()
    private fun pick(profile: WatchProfile, vararg names: String) = pickForWatch(splits(*names), profile) { it }

    @Test fun theSharpestNeededDensityIsKept() {
        val all = arrayOf("config.hdpi", "config.xxhdpi")
        assertEquals(listOf("config.xxhdpi"), pick(watch.copy(density = 320), *all))
        assertEquals(listOf("config.hdpi"), pick(watch.copy(density = 240), *all))
        assertEquals(listOf("config.xxhdpi"), pick(watch.copy(density = 640), *all))
    }

    @Test fun languagesMatchWhateverWayTheyAreWritten() {
        val profile = watch.copy(languages = setOf(languageOf("in-ID"), languageOf("zh-CN"), languageOf("sr")))
        assertEquals(
            listOf("config.id", "config.in", "config.zh_TW", "config.b+sr+Latn", "config.en"),
            pick(profile, "config.id", "config.in", "config.zh_TW", "config.b+sr+Latn", "config.en", "config.ko"),
        )
    }

    @Test fun unknownConfigsAndModulesStay() {
        val names = arrayOf("config.astc", "config.atc", "config.tier_1", "feature", "feature.config.zh")
        assertEquals(names.toList(), pick(watch, *names))
    }

    @Test fun theWatchDescribesItself() {
        val profile = parseWatchProfile(
            "abi=arm64-v8a,armeabi-v7a,armeabi\nsdk=30\ndensity=Override density: 280\nlcd=320\n" +
                "locales=zh-CN,en-US\nlocale=\nproduct_locale=null\n",
            knownSdk = null,
        )
        assertEquals(WatchProfile(listOf("arm64_v8a", "armeabi_v7a", "armeabi"), 280, setOf("zh", "en"), 30), profile)
        assertEquals(320, parseWatchProfile("abi=x86\nsdk=30\ndensity=\nlcd=320\n", null).density)
        assertEquals(33, parseWatchProfile("abi=x86\nsdk=\n", knownSdk = 33).sdk)
        assertEquals("package_watch_unknown", assertFailsWith<ToolboxException> { parseWatchProfile("abi=\nsdk=30\n", null) }.code)
    }

    private class Backend(
        val installed: Boolean = true,
        val refusePush: Boolean = false,
        val lostOnPush: Boolean = false,
        val reportedSize: Long? = null,
        val freeSpace: String? = null,
    ) : WatchToolboxBackend by WatchToolboxClient.UNAVAILABLE.localBridge {
        val calls = mutableListOf<String>()
        val written = mutableMapOf<String, ByteArray>()
        val spaceChecks = mutableListOf<String>()
        override val state = MutableStateFlow(ToolboxBackendState(ToolboxBackendKind.ADB))
        override suspend fun install(apks: List<ToolboxApk>, options: ToolboxInstallOptions, progress: (ToolboxProgress) -> Unit): ToolboxInstallResult {
            calls += "install:${apks.size}"
            progress(ToolboxProgress(apks.sumOf { it.size }, apks.sumOf { it.size }, apks.size - 1))
            return ToolboxInstallResult(installed, if (installed) "Success" else "Failure [X]")
        }
        override suspend fun execute(command: String, options: ToolboxCommandOptions): ToolboxCommandResult {
            spaceChecks += command
            return ToolboxCommandResult(0, (freeSpace ?: throw ToolboxException("not_found")).toByteArray(), byteArrayOf())
        }
        override suspend fun file(request: ToolboxFileRequest) {
            assertEquals(ToolboxFileAction.MAKE_DIRECTORY, request.action)
            assertTrue(request.recursive)
            calls += "mkdir:${request.path}"
        }
        override suspend fun push(path: String, input: InputStream, size: Long, options: ToolboxWriteOptions, progress: (ToolboxProgress) -> Unit) {
            calls += "push:$path"
            assertTrue(options.overwrite && options.atomic)
            if (refusePush) throw ToolboxException("permission_denied")
            if (lostOnPush) throw java.io.IOException("gone")
            written[path] = input.use { it.readBytes() }
            progress(ToolboxProgress(size, size))
        }
        override suspend fun stat(path: String): ToolboxFile {
            val size = reportedSize ?: written.getValue(path).size.toLong()
            return ToolboxFile(path, path, ToolboxFileKind.FILE, size, 0, 0, 0, 0)
        }
    }

    private fun bundleWithData() = ToolboxBundle(
        pkg, null, null,
        listOf(ToolboxApk("base.apk", 10) { ByteArray(10).inputStream() }),
        skippedApks = 0,
        data = listOf(
            ToolboxBundleData("Android/obb/$pkg/main.1.$pkg.obb", 20) { ByteArray(20).inputStream() },
            ToolboxBundleData("Android/data/$pkg/files/x", 30) { ByteArray(30).inputStream() },
        ),
    )

    @Test fun theAppGoesFirstAndItsDataAfter() = runBlocking {
        val backend = Backend()
        val seen = mutableListOf<ToolboxProgress>()
        val result = backend.installPackage(bundleWithData(), ToolboxInstallOptions()) { seen += it }
        assertTrue(result.install.success)
        assertNull(result.dataFailure)
        assertEquals(
            listOf(
                "install:1",
                "mkdir:/sdcard/Android/obb/$pkg", "push:/sdcard/Android/obb/$pkg/main.1.$pkg.obb",
                "mkdir:/sdcard/Android/data/$pkg/files", "push:/sdcard/Android/data/$pkg/files/x",
            ),
            backend.calls,
        )
        assertEquals(60, seen.last().completedBytes)
        assertTrue(seen.all { it.totalBytes == 60L })
        assertEquals(seen.map { it.completedBytes }, seen.map { it.completedBytes }.sorted())
        assertEquals(listOf(0, 1, 2), seen.map { it.item }.distinct())
    }

    @Test fun aWatchWithoutRoomIsToldBeforeAnythingIsSent() = runBlocking {
        val full = Backend(freeSpace = "/dev/fuse 2000 1990 0 99% /storage/emulated\n")
        val problem = assertFailsWith<ToolboxException> { full.installPackage(bundleWithData(), ToolboxInstallOptions()) {} }
        assertEquals("package_no_space", problem.code)
        assertTrue(full.calls.isEmpty())
        val enough = Backend(freeSpace = "/dev/fuse 2000 1000 1000 50% /storage/emulated\n")
        assertTrue(enough.installPackage(bundleWithData(), ToolboxInstallOptions()) {}.install.success)
        val odd = Backend(freeSpace = "df: /sdcard/: Permission denied\n")
        assertTrue(odd.installPackage(bundleWithData(), ToolboxInstallOptions()) {}.install.success)
    }

    @Test fun anotherUsersDataGoesToTheirStorage() = runBlocking {
        val backend = Backend()
        backend.installPackage(bundleWithData(), ToolboxInstallOptions(userId = 10)) {}
        assertContains(backend.calls, "mkdir:/storage/emulated/10/Android/obb/$pkg")
        assertEquals(listOf("df -k /storage/emulated/10/ | tail -n 1"), backend.spaceChecks)
    }

    @Test fun aRefusedInstallSendsNoData() = runBlocking {
        val backend = Backend(installed = false)
        val result = backend.installPackage(bundleWithData(), ToolboxInstallOptions()) {}
        assertFalse(result.install.success)
        assertEquals("Failure [X]", result.install.output)
        assertNull(result.dataFailure)
        assertEquals(listOf("install:1"), backend.calls)
    }

    @Test fun dataThatDidntArriveIsReportedWithTheAppStillInstalled() = runBlocking {
        val refused = Backend(refusePush = true).installPackage(bundleWithData(), ToolboxInstallOptions()) {}
        assertTrue(refused.install.success)
        assertEquals("permission_denied", refused.dataFailure)
        val lost = Backend(lostOnPush = true).installPackage(bundleWithData(), ToolboxInstallOptions()) {}
        assertEquals("connection_closed", lost.dataFailure)
        val short = Backend(reportedSize = 3).installPackage(bundleWithData(), ToolboxInstallOptions()) {}
        assertEquals("file_size_mismatch", short.dataFailure)
    }
}
