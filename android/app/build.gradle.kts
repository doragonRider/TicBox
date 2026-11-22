import groovy.json.JsonOutput
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ticbox.companion"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    // Use the installed native toolchain to strip dependency debug symbols from packaged libraries.
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.ticbox.companion"
        minSdk = 34
        targetSdk = 37
        versionCode = rootProject.extra["companionVersionCode"] as Int
        versionName = rootProject.extra["companionVersionName"] as String
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("proguard-common.pro"),
                "proguard-rules.pro",
            )
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { task ->
            task.useJUnitPlatform()
            task.maxHeapSize = "1g"
            listOf(
                    "robolectric.dependency.repo.url",
                    "https.proxyHost",
                    "https.proxyPort",
                    "http.proxyHost",
                    "http.proxyPort",
                )
                .forEach { key -> System.getProperty(key)?.let { task.systemProperty(key, it) } }
        }
    }
    buildFeatures { compose = true }
    // Bouncy Castle's three modules each carry their license under the same resource path.
    packaging {
        resources.merges += "META-INF/LICENSE.md"
        dex { useLegacyPackaging = true }
        // Keep all four terminal ABIs; compress the stripped runtime libraries for APK delivery.
        jniLibs { useLegacyPackaging = true }
    }
    lint { lintConfig = rootProject.file("lint-toolbox.xml") }
    // Only English and Chinese are packaged, also for libraries such as Material, so an
    // unsupported system language shows English everywhere instead of a mix of languages.
    androidResources {
        localeFilters += listOf("en", "zh", "zh-rCN", "zh-rTW", "zh-rHK", "b+zh+Hant")
    }
    // Both languages remain available when an unsupported OS locale falls back to English.
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    implementation(project(":core:platform"))
    implementation(project(":ui"))
    implementation(project(":demo"))
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.vintage)
    testRuntimeOnly(libs.junit.launcher)
}

// One payload is both the installable watch app and the shell agent's app_process classpath.
// The edge belongs to the phone application: watch tests depend on :core:platform, never :app.
abstract class PrepareLocalBridgeApk : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceApk: org.gradle.api.file.RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val toolboxResources: org.gradle.api.file.DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: org.gradle.api.file.DirectoryProperty

    @TaskAction
    fun prepare() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val target = outputDirectory.file("watch/local-bridge.apk").get().asFile
        target.parentFile.mkdirs()
        sourceApk.get().asFile.copyTo(target, overwrite = true)
        // scrcpy is read from the embedded watch APK. Keep the licenses accessible on the phone.
        val resources = toolboxResources.get().asFile
        resources
            .walkTopDown()
            .filter { it.isFile && it.extension != "jar" }
            .forEach { source ->
                val destination = output.resolve(source.relativeTo(resources))
                destination.parentFile.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
    }
}

abstract class PrepareHealthModule : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceZip: org.gradle.api.file.RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: org.gradle.api.file.DirectoryProperty

    @TaskAction
    fun prepare() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val watch = output.resolve("watch").apply { mkdirs() }
        val source = sourceZip.get().asFile
        fun hash(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        ZipFile(source).use { zip ->
            val properties =
                Properties().apply {
                    zip.getInputStream(zip.getEntry("companion-build.properties")).use { load(it) }
                }
            val entries =
                zip.entries()
                    .asSequence()
                    .filter { !it.isDirectory }
                    .associate {
                        it.name to hash(zip.getInputStream(it).use { stream -> stream.readBytes() })
                    }
                    .toSortedMap()
            val catalog =
                mapOf(
                    "schema" to 1,
                    "id" to properties.getProperty("id"),
                    "versionName" to properties.getProperty("versionName"),
                    "versionCode" to properties.getProperty("versionCode").toLong(),
                    "buildId" to properties.getProperty("buildId"),
                    "sha256" to hash(source.readBytes()),
                    "bytes" to source.length(),
                    "entries" to entries,
                )
            watch.resolve("health-module.json").writeText(JsonOutput.toJson(catalog))
        }
        source.copyTo(watch.resolve("health-module.zip"))
    }
}

androidComponents.onVariants { variant ->
    val buildType = variant.name
    val taskVariant = buildType.replaceFirstChar { it.uppercase() }
    val module =
        tasks.register<PrepareHealthModule>("prepare${taskVariant}HealthModule") {
            dependsOn(":watch:healthmodule:verifyModuleZip")
            sourceZip.set(
                project(":watch:healthmodule")
                    .layout
                    .buildDirectory
                    .file(
                        "distributions/ticbox-healthmodule-${rootProject.extra["companionVersionName"]}.zip"
                    )
            )
            outputDirectory.set(
                layout.buildDirectory.dir("generated/healthModuleAssets/$buildType")
            )
        }
    variant.sources.assets?.addGeneratedSourceDirectory(
        module,
        PrepareHealthModule::outputDirectory,
    )
    val unsigned =
        buildType == "release" &&
            providers.environmentVariable("TICBOX_SIGNING_STORE_FILE").orNull.isNullOrBlank()
    val apk =
        "outputs/apk/$buildType/healthbridge-$buildType${if (unsigned) "-unsigned" else ""}.apk"
    val prepare =
        tasks.register<PrepareLocalBridgeApk>("prepare${taskVariant}LocalBridgeApk") {
            dependsOn(":watch:healthbridge:assemble$taskVariant")
            sourceApk.set(project(":watch:healthbridge").layout.buildDirectory.file(apk))
            toolboxResources.set(rootProject.layout.projectDirectory.dir("toolbox-resources"))
        }
    variant.sources.assets?.addGeneratedSourceDirectory(
        prepare,
        PrepareLocalBridgeApk::outputDirectory,
    )
}
