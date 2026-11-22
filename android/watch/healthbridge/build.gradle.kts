plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.ticbox.companion.healthbridge"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    // Use the installed native toolchain to strip dependency debug symbols from packaged libraries.
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.ticbox.companion" // Same identity/key as the phone; different device.
        minSdk = 30
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
    buildFeatures { compose = true }
    sourceSets["main"].assets.srcDir(rootProject.file("toolbox-resources"))
    // Match the phone's supported languages; dependency translations need not fill the watch APK.
    androidResources { localeFilters += listOf("en", "zh", "zh-rCN", "zh-rTW", "zh-rHK", "b+zh+Hant") }
    packaging { dex { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { task ->
            task.useJUnitPlatform(); task.maxHeapSize = "1g"
            // Robolectric's SharedMemory reaches into FileDescriptor.
            task.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            listOf("robolectric.dependency.repo.url", "https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort").forEach { key -> System.getProperty(key)?.let { task.systemProperty(key, it) } }
            if (task.name == "testReleaseUnitTest") {
                val fixture = layout.buildDirectory.file("journal-fixtures/health.db")
                task.systemProperty("ticbox.journal.fixture", fixture.get().asFile.absolutePath)
                task.outputs.file(fixture)
            }
        }
    }
}
tasks.register<Exec>("journalCrashCheck") {
    group = "verification"
    description = "Kills owned Node SQLite writers against the production-trigger test fixture and verifies crash recovery."
    dependsOn("testReleaseUnitTest")
    val fixture = layout.buildDirectory.file("journal-fixtures/health.db")
    inputs.file(rootProject.file("../tools/check-health-journal-crash.mjs")); inputs.file(fixture)
    commandLine("node", rootProject.file("../tools/check-health-journal-crash.mjs"), fixture.get().asFile)
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

// The release check runs the shared tests in release. It only needs the debug variant for the
// debug-only sampling UI's tests in src/testDebug, so it runs just those classes there.
val debugOnlySources = fileTree("src/testDebug") { include("**/*Test.kt") }
tasks.register("debugOnlyTests") {
    group = "verification"
    description = "Runs only the watch tests that need the debug variant."
    dependsOn("testDebugUnitTest")
}
gradle.taskGraph.whenReady {
    if (hasTask(":watch:healthbridge:debugOnlyTests")) {
        tasks.named<Test>("testDebugUnitTest") {
            filter { debugOnlySources.forEach { includeTestsMatching("*.${it.nameWithoutExtension}") } }
        }
    }
}

dependencies {
    implementation(project(":watch:toolboxagent"))
    implementation(project(":core:protocol"))
    implementation(libs.play.services.wearable)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    testImplementation(platform(libs.junit.bom))
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.robolectric)
    // A JVM integration fixture composes the production watch reader with the phone engine/store.
    // None of the phone modules are packaged into the watch APK.
    testImplementation(project(":core:engine"))
    testImplementation(project(":core:platform"))
    testImplementation(libs.coroutines.core)
    testImplementation(libs.junit4)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.vintage)
    testRuntimeOnly(libs.junit.launcher)
}
