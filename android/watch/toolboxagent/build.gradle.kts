import java.util.Properties

plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.ticbox.companion.toolboxagent"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 30; consumerProguardFiles("consumer-rules.pro") }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/pty-assets").get().asFile)
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { task ->
            task.useJUnitPlatform(); task.maxHeapSize = "1g"
            listOf("robolectric.dependency.repo.url", "https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort").forEach { key -> System.getProperty(key)?.let { task.systemProperty(key, it) } }
        }
    }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    api(project(":core:protocol"))
    compileOnly(libs.androidx.annotation)
    testImplementation(project(":core:engine"))
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.vintage)
    testRuntimeOnly(libs.junit.launcher)
}

// Keep the four tiny PTY libraries in assets: the embedded watch APK must remain installable
// independently of the phone/target ABI, including when used as an ADB agent carrier.
val ptySdkRoot = providers.environmentVariable("ANDROID_HOME").orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(providers.provider {
        val properties = Properties()
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { properties.load(it) }
        requireNotNull(properties.getProperty("sdk.dir")) { "Set ANDROID_HOME or sdk.dir for PTY native build" }
    })
val ptyAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val ptyNativeDirectory = layout.buildDirectory.dir("pty-native")
val buildPtyNative = tasks.register<Exec>("buildPtyNative") {
    inputs.dir("src/main/cpp")
    outputs.files(ptyAbis.map { abi -> ptyNativeDirectory.map { it.file("libs/$abi/libticbox_pty.so") } })
    doFirst {
        val ndk = file("${ptySdkRoot.get()}/ndk/27.2.12479018/ndk-build")
        require(ndk.isFile) { "Install NDK 27.2.12479018 for PTY native build" }
        commandLine(ndk, "-C", file("src/main/cpp"), "NDK_PROJECT_PATH=.", "APP_BUILD_SCRIPT=Android.mk", "NDK_APPLICATION_MK=Application.mk",
            "NDK_OUT=${ptyNativeDirectory.get().asFile}/obj", "NDK_LIBS_OUT=${ptyNativeDirectory.get().asFile}/libs", "-j2")
    }
}
val packagePtyAssets = tasks.register<Sync>("packagePtyAssets") {
    dependsOn(buildPtyNative)
    from(ptyNativeDirectory.map { it.dir("libs") }) { include("*/libticbox_pty.so") }
    into(layout.buildDirectory.dir("generated/pty-assets/toolbox/pty"))
}
tasks.named("preBuild") { dependsOn(packagePtyAssets) }
val buildHostPty = tasks.register<Exec>("buildHostPty") {
    inputs.dir("src/main/cpp")
    inputs.file("tools/build-host-pty.sh")
    val library = layout.buildDirectory.file("pty-host/libticbox_pty.so")
    outputs.file(library)
    doFirst { commandLine("sh", file("tools/build-host-pty.sh"), library.get().asFile, System.getProperty("java.home")) }
}
tasks.withType<Test>().configureEach {
    dependsOn(buildHostPty)
    systemProperty("ticbox.pty.library", layout.buildDirectory.file("pty-host/libticbox_pty.so").get().asFile.absolutePath)
}
val nativePtyTest = tasks.register<Exec>("nativePtyTest") {
    dependsOn(buildHostPty)
    inputs.files("src/main/java/dev/ticbox/companion/toolboxagent/PtyNative.java", "src/test/native/PtyNativeTest.java", "tools/test-host-pty.sh")
    inputs.file(layout.buildDirectory.file("pty-host/libticbox_pty.so"))
    outputs.file(layout.buildDirectory.file("pty-host/tests/passed.txt"))
    doFirst { commandLine("sh", file("tools/test-host-pty.sh"), layout.buildDirectory.file("pty-host/libticbox_pty.so").get().asFile,
        System.getProperty("java.home"), layout.buildDirectory.dir("pty-host/tests").get().asFile) }
}
tasks.withType<Test>().configureEach { dependsOn(nativePtyTest) }
val nativeScreenTest = tasks.register<Exec>("nativeScreenTest") {
    dependsOn(buildHostPty)
    inputs.dir("src/main/cpp")
    inputs.files("src/main/java/dev/ticbox/companion/toolboxagent/ScreenNative.java", "src/test/native/ScreenNativeTest.java",
        "src/test/native/screen_native_test.c", "tools/test-host-screen.sh")
    inputs.file(layout.buildDirectory.file("pty-host/libticbox_pty.so"))
    outputs.file(layout.buildDirectory.file("screen-host/passed.txt"))
    doFirst { commandLine("sh", file("tools/test-host-screen.sh"), layout.buildDirectory.file("pty-host/libticbox_pty.so").get().asFile,
        System.getProperty("java.home"), layout.buildDirectory.dir("screen-host").get().asFile) }
}
tasks.withType<Test>().configureEach { dependsOn(nativeScreenTest) }
