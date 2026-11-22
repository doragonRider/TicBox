package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxApk
import dev.ticbox.companion.api.ToolboxBundle
import dev.ticbox.companion.api.ToolboxBundleData
import dev.ticbox.companion.api.ToolboxBundleResult
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxFileAction
import dev.ticbox.companion.api.ToolboxFileRequest
import dev.ticbox.companion.api.ToolboxInstallOptions
import dev.ticbox.companion.api.ToolboxProgress
import dev.ticbox.companion.api.ToolboxWriteOptions
import dev.ticbox.companion.api.WatchToolboxBackend
import dev.ticbox.companion.protocol.text
import java.io.EOFException
import java.io.IOException
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Every package format is an archive of APKs with a metadata file that differs by store, so the
 * format is recognised by what's inside, and each APK by its own manifest: real archives have
 * parts called main.apk and obb.apk, and a phone's own backup keeps whatever names it had there.
 */
internal suspend fun readPackageFile(file: ToolboxApk, watch: suspend () -> WatchProfile): ToolboxBundle? {
    val directory = withContext(Dispatchers.IO) { ZipDirectory.read(file.size, file.open) }
    if (directory == null) {
        return when {
            file.name.endsWith(".apk", ignoreCase = true) -> null
            // APKMirror encrypted its files with its own scheme before 2021.
            file.name.endsWith(".apkm", ignoreCase = true) -> throw ToolboxException("package_encrypted")
            else -> throw ToolboxException("package_not_recognised")
        }
    }
    if (directory.item(APK_MANIFEST) != null) return null
    val apks = directory.items.filter { it.size > 0 && !it.name.startsWith(DATA_ROOT) && it.name.endsWith(".apk", ignoreCase = true) }
    if (apks.isEmpty()) throw ToolboxException("package_not_recognised")
    val profile = watch()
    return withContext(Dispatchers.IO) {
        try {
            assemble(directory, apks, profile)
        } catch (_: ZipException) {
            throw ToolboxException("package_unreadable")
        } catch (_: EOFException) {
            throw ToolboxException("package_unreadable")
        }
    }
}

private suspend fun assemble(directory: ZipDirectory, apks: List<ZipItem>, profile: WatchProfile): ToolboxBundle {
    val listed = directory.item(TABLE_OF_CONTENTS)?.let { toc ->
        installTimePaths(directory.bytesOf(toc, MAX_TOC_BYTES), profile)
    }
    val candidates = apks.filter { listed == null || it.name in listed }
    if (candidates.isEmpty()) throw ToolboxException("package_unreadable")
    val manifests = candidates.associateWith { apk ->
        currentCoroutineContext().ensureActive()
        directory.manifestOf(apk)
    }
    val base = manifests.values.filter { it.split == null }
    if (base.size > 1 || manifests.values.distinctBy { it.packageName }.size > 1) throw ToolboxException("package_many_apps")
    val app = base.singleOrNull() ?: throw ToolboxException("package_unreadable")
    val picked = pickForWatch(candidates, profile) { manifests.getValue(it).split }.sortedBy { manifests.getValue(it).split != null }
    val metadata = directory.metadata()
    return ToolboxBundle(
        app.packageName,
        LABEL_KEYS.firstNotNullOfOrNull { metadata?.text(it) }?.takeIf { it.isNotBlank() },
        VERSION_KEYS.firstNotNullOfOrNull { metadata?.text(it) }?.takeIf { it.isNotBlank() } ?: app.versionName,
        picked.map { apk -> ToolboxApk(apk.name.substringAfterLast('/'), apk.size) { directory.open(apk) } },
        apks.size - picked.size,
        dataFiles(directory, app.packageName, metadata),
    )
}

private fun ZipDirectory.manifestOf(apk: ZipItem): ApkManifest {
    val inner = ZipDirectory.read(apk.size) { open(apk, verified = false) } ?: throw ToolboxException("package_unreadable")
    val manifest = inner.item(APK_MANIFEST) ?: throw ToolboxException("package_unreadable")
    return readApkManifest(inner.bytesOf(manifest, MAX_MANIFEST_BYTES)) ?: throw ToolboxException("package_unreadable")
}

private fun ZipDirectory.bytesOf(item: ZipItem, limit: Long): ByteArray {
    if (item.size !in 0..limit) throw ToolboxException("package_unreadable")
    return open(item, verified = false).use { it.readExactly(item.size.toInt()) }
}

/** It only names and labels the app, so a store's malformed file is no reason to refuse it. */
private fun ZipDirectory.metadata(): JsonObject? = METADATA_FILES.firstNotNullOfOrNull { name ->
    item(name)?.takeIf { it.size in 1..MAX_METADATA_BYTES }?.let { file ->
        try {
            Json.parseToJsonElement(bytesOf(file, MAX_METADATA_BYTES).decodeToString()) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Where these go comes from an untrusted file and root may be doing the writing, so nothing may
 * leave the app's own folders, and the whole package is refused rather than installed without
 * data it asked for.
 */
private fun dataFiles(directory: ZipDirectory, packageName: String, metadata: JsonObject?): List<ToolboxBundleData> {
    if (!PACKAGE_NAME.matches(packageName)) throw ToolboxException("package_bad_path")
    val placed = LinkedHashMap<ZipItem, String>()
    (metadata?.get("expansions") as? JsonArray).orEmpty().forEach { value ->
        val expansion = value as? JsonObject ?: throw ToolboxException("package_unreadable")
        val file = expansion.text("file") ?: throw ToolboxException("package_unreadable")
        val item = directory.item(file) ?: throw ToolboxException("package_unreadable")
        placed[item] = ownDataPath(expansion.text("install_path") ?: file, packageName)
    }
    directory.items.filter { !it.isDirectory && it !in placed }.forEach { item ->
        val fileName = item.name.substringAfterLast('/')
        when {
            item.name.startsWith(DATA_ROOT + "obb/") || item.name.startsWith(DATA_ROOT + "data/") ->
                placed[item] = ownDataPath(item.name, packageName)
            item.name.endsWith(".obb", ignoreCase = true) -> {
                if (!isObbOf(fileName, packageName)) throw ToolboxException("package_bad_path")
                placed[item] = "${DATA_ROOT}obb/$packageName/$fileName"
            }
        }
    }
    return placed.map { (item, path) -> ToolboxBundleData(path, item.size) { directory.open(item) } }
}

private fun ownDataPath(path: String, packageName: String): String {
    val segments = path.split('/')
    val plain = segments.all { it.isNotEmpty() && it != "." && it != ".." && '\u0000' !in it && '\\' !in it }
    val own = path.startsWith("${DATA_ROOT}obb/$packageName/") || path.startsWith("${DATA_ROOT}data/$packageName/")
    if (!plain || !own) throw ToolboxException("package_bad_path")
    return path
}

private fun isObbOf(fileName: String, packageName: String): Boolean {
    val parts = fileName.removeSuffix(".obb").split('.', limit = 3)
    return parts.size == 3 && parts[0] in OBB_KINDS && parts[1].all(Char::isDigit) && parts[2] == packageName
}

/**
 * The APKs first, since a failed install leaves no reason to send the data. Each data file only
 * takes its real name once all of it has arrived, so an app can't open half a file. Data runs to
 * gigabytes on a small disk, so a lack of room is told before anything is sent.
 */
internal suspend fun WatchToolboxBackend.installPackage(
    bundle: ToolboxBundle,
    options: ToolboxInstallOptions,
    progress: (ToolboxProgress) -> Unit,
): ToolboxBundleResult {
    val total = bundle.apkBytes + bundle.dataBytes
    val storage = if (options.userId == 0) "/sdcard" else "/storage/emulated/${options.userId}"
    if (bundle.data.isNotEmpty() && readFreeBytes(storage)?.let { it < total } == true) {
        throw ToolboxException("package_no_space")
    }
    val installed = install(bundle.apks, options) { progress(ToolboxProgress(it.completedBytes, total, it.item)) }
    if (!installed.success || bundle.data.isEmpty()) return ToolboxBundleResult(installed)
    return try {
        var done = bundle.apkBytes
        bundle.data.forEachIndexed { index, file ->
            val path = "$storage/${file.path}"
            val item = bundle.apks.size + index
            val before = done
            progress(ToolboxProgress(before, total, item))
            file(ToolboxFileRequest(ToolboxFileAction.MAKE_DIRECTORY, path.substringBeforeLast('/'), recursive = true))
            val input = toolboxIo { file.open() }
            push(path, input, file.size, ToolboxWriteOptions(overwrite = true, atomic = true)) {
                progress(ToolboxProgress(before + it.completedBytes, total, item))
            }
            if (stat(path).size != file.size) throw ToolboxException("file_size_mismatch")
            done += file.size
        }
        ToolboxBundleResult(installed)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (problem: ToolboxException) {
        ToolboxBundleResult(installed, problem.code)
    } catch (_: IOException) {
        ToolboxBundleResult(installed, "connection_closed")
    }
}

private const val APK_MANIFEST = "AndroidManifest.xml"
private const val TABLE_OF_CONTENTS = "toc.pb"
private const val DATA_ROOT = "Android/"
private const val MAX_TOC_BYTES = 16L * 1024 * 1024
private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
private const val MAX_METADATA_BYTES = 4L * 1024 * 1024
private val METADATA_FILES = listOf("manifest.json", "info.json", "apk+.json", "meta.sai_v2.json", "meta.sai_v1.json")
private val LABEL_KEYS = listOf("name", "app_name", "label")
private val VERSION_KEYS = listOf("version_name", "release_version")
private val PACKAGE_NAME = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*""")
private val OBB_KINDS = setOf("main", "patch")
