import java.util.Properties
import java.util.zip.ZipFile
import java.security.MessageDigest
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

plugins { java }

// Robolectric's AndroidX test helpers are AARs. A plain Java module must extract their JVM
// classes explicitly; this transform is requested only by testRuntimeClasspath, never D8.
abstract class TestAarClasses : TransformAction<TransformParameters.None> {
    @get:InputArtifact @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputArtifact: Provider<FileSystemLocation>
    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        ZipFile(input).use { archive ->
            archive.entries().asSequence().filter { it.name == "classes.jar" || it.name.startsWith("libs/") && it.name.endsWith(".jar") }
                .forEach { entry -> outputs.file(input.nameWithoutExtension + "-" + entry.name.substringAfterLast('/'))
                    .writeBytes(archive.getInputStream(entry).use { it.readBytes() }) }
        }
    }
}
dependencies.registerTransform(TestAarClasses::class) {
    from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
    to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
}
configurations.testRuntimeClasspath { attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
val sdkRoot = providers.environmentVariable("ANDROID_HOME").orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(providers.provider {
        val properties = Properties()
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { properties.load(it) }
        requireNotNull(properties.getProperty("sdk.dir")) { "Set ANDROID_HOME or sdk.dir for the health module build" }
    })
val ndkVersion = "27.2.12479018"
val androidJar = sdkRoot.map { file("$it/platforms/android-37.2/android.jar") }
dependencies {
    compileOnly(files(androidJar))
    testImplementation(files(androidJar))
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    testRuntimeOnly(libs.junit.launcher)
}
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // Robolectric's SharedMemory reaches into FileDescriptor.
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    listOf("robolectric.dependency.repo.url", "https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
// This identity is compiled into the injected DEX as well as included in the ZIP.
val moduleBuildDirectory = layout.buildDirectory.dir("generated/moduleBuild")
val generateModuleBuild = tasks.register("generateModuleBuild") {
    inputs.dir("src/main"); inputs.dir("module"); inputs.file("build.gradle.kts"); inputs.file(rootProject.file("version.properties"))
    outputs.dir(moduleBuildDirectory)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        val sources = file("src/main").walkTopDown().filter { it.isFile }.toList() + file("module").walkTopDown().filter { it.isFile }.toList() + file("build.gradle.kts")
        sources.sortedBy { it.relativeTo(projectDir).path }.forEach { source ->
            digest.update(source.relativeTo(projectDir).invariantSeparatorsPath.toByteArray()); digest.update(0.toByte()); digest.update(source.readBytes())
        }
        digest.update(rootProject.file("version.properties").readBytes())
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        val output = moduleBuildDirectory.get().asFile
        val java = output.resolve("java/dev/ticbox/companion/healthmodule/ModuleBuildInfo.java"); java.parentFile.mkdirs()
        java.writeText("package dev.ticbox.companion.healthmodule; final class ModuleBuildInfo { static final String ID = \"$id\"; private ModuleBuildInfo() {} }\n")
        val properties = output.resolve("metadata/companion-build.properties"); properties.parentFile.mkdirs()
        properties.writeText("id=ticwatch_health_settings\nbuildId=$id\nversionName=${rootProject.extra["companionVersionName"]}\nversionCode=${rootProject.extra["companionVersionCode"]}\n")
    }
}
sourceSets.main { java.srcDir(moduleBuildDirectory.map { it.dir("java") }) }
tasks.compileJava { dependsOn(generateModuleBuild) }
val dexDirectory = layout.buildDirectory.dir("dex")
val dexModule = tasks.register<Exec>("dexModule") {
    dependsOn(tasks.jar)
    inputs.file(tasks.jar.flatMap { it.archiveFile }); inputs.file(androidJar)
    outputs.file(dexDirectory.map { it.file("classes.dex") })
    doFirst {
        dexDirectory.get().asFile.mkdirs()
        commandLine("${sdkRoot.get()}/build-tools/37.0.0/d8", "--release", "--min-api", "30", "--lib", androidJar.get(),
            "--output", dexDirectory.get().asFile, tasks.jar.get().archiveFile.get().asFile)
    }
}
val nativeDirectory = layout.buildDirectory.dir("native")
val buildNative = tasks.register<Exec>("buildNative") {
    inputs.dir("src/main/cpp")
    outputs.files(nativeDirectory.map { it.file("libs/armeabi-v7a/libticbox_healthmodule.so") },
        nativeDirectory.map { it.file("libs/arm64-v8a/libticbox_healthmodule.so") })
    doFirst {
        val headerDigest = MessageDigest.getInstance("SHA-256").digest(file("src/main/cpp/zygisk.hpp").readBytes())
            .joinToString("") { "%02x".format(it) }
        require(headerDigest == "fc6523882c0f8659b4fcd1e04ffb07d3a948090e5a8c08817af6e2b506a078d0") { "Zygisk API header differs from the pinned upstream source" }
        val ndk = file("${sdkRoot.get()}/ndk/$ndkVersion/ndk-build")
        require(ndk.isFile) { "Install fixed NDK with sdkmanager 'ndk;$ndkVersion'" }
        commandLine(ndk, "-C", file("src/main/cpp"), "NDK_PROJECT_PATH=.", "APP_BUILD_SCRIPT=Android.mk", "NDK_APPLICATION_MK=Application.mk",
            "NDK_OUT=${nativeDirectory.get().asFile}/obj", "NDK_LIBS_OUT=${nativeDirectory.get().asFile}/libs", "-j2")
    }
}
val nativePolicyTest = tasks.register<Exec>("nativePolicyTest") {
    inputs.files("src/main/cpp/bootstrap_policy.h", "src/test/native/bootstrap_policy_test.cpp", "tools/test-native.sh")
    outputs.file(layout.buildDirectory.file("tests/bootstrap-policy-test"))
    doFirst {
        val binary = layout.buildDirectory.file("tests/bootstrap-policy-test").get().asFile
        binary.parentFile.mkdirs()
        commandLine("sh", file("tools/test-native.sh"), binary)
    }
}
val moduleZip = tasks.register<Zip>("moduleZip") {
    dependsOn(dexModule, buildNative, generateModuleBuild)
    group = "build"
    description = "Builds the standalone Magisk/Zygisk health settings module; never installs it."
    archiveFileName = "ticbox-healthmodule-${rootProject.extra["companionVersionName"]}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false; isReproducibleFileOrder = true
    filePermissions { unix("0644") }
    from(moduleBuildDirectory.map { it.dir("metadata") })
    from("module") { filesMatching("module.prop") {
        expand(mapOf("versionName" to rootProject.extra["companionVersionName"], "versionCode" to rootProject.extra["companionVersionCode"]))
    } }
    from(dexDirectory) { include("classes.dex"); rename("classes.dex", "module.dex") }
    from(nativeDirectory.map { it.dir("libs/armeabi-v7a") }) { include("libticbox_healthmodule.so"); into("zygisk"); rename("libticbox_healthmodule.so", "armeabi-v7a.so") }
    from(nativeDirectory.map { it.dir("libs/arm64-v8a") }) { include("libticbox_healthmodule.so"); into("zygisk"); rename("libticbox_healthmodule.so", "arm64-v8a.so") }
    from(rootProject.file("../LICENSE"))
    from("src/main/cpp/zygisk.hpp") { into("licenses") }
    from("SDK_PROVENANCE.md") { into("licenses") }
}
val verifyModuleZip = tasks.register("verifyModuleZip") {
    dependsOn(moduleZip, nativePolicyTest)
    inputs.file(moduleZip.flatMap { it.archiveFile })
    doLast {
        ZipFile(moduleZip.get().archiveFile.get().asFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            require(names.none { it.startsWith("/") || ".." in it.split('/') || it in setOf("sepolicy.rule", "system.prop", "service.sh", "post-fs-data.sh") })
            require(names.toSet().containsAll(listOf("module.prop", "customize.sh", "module.dex", "zygisk/armeabi-v7a.so", "zygisk/arm64-v8a.so", "META-INF/com/google/android/update-binary", "META-INF/com/google/android/updater-script")))
            require(names.count { it.endsWith(".so") } == 2)
            for ((path, machine) in mapOf("zygisk/armeabi-v7a.so" to 40, "zygisk/arm64-v8a.so" to 183)) {
                val elf = zip.getInputStream(zip.getEntry(path)).readBytes()
                require(elf.size >= 64 && elf[0] == 0x7f.toByte() && elf[1] == 'E'.code.toByte() && elf[5] == 1.toByte())
                require((elf[18].toInt() and 255 or ((elf[19].toInt() and 255) shl 8)) == machine)
                require(elf.toString(Charsets.ISO_8859_1).contains("zygisk_module_entry"))
            }
            val dex = zip.getInputStream(zip.getEntry("module.dex")).readBytes()
            require(dex.size in 112..2 * 1024 * 1024 && dex.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "dex\n")
            require(dex.toString(Charsets.ISO_8859_1).contains("Ldev/ticbox/companion/healthmodule/HealthModule;"))
            require(dex.toString(Charsets.ISO_8859_1).contains("Ldev/ticbox/companion/healthmodule/NotificationAlertsModule;"))
            // The notification control is on until turned off on the watch; the package never carries either marker.
            require("notification-alerts.disabled" !in names && "notification-alerts.enabled" !in names)
            require(!zip.getInputStream(zip.getEntry("module.prop")).readBytes().toString(Charsets.UTF_8).contains("\${version"))
        }
    }
}
tasks.assemble { dependsOn(moduleZip) }
tasks.check { dependsOn(nativePolicyTest, verifyModuleZip) }
