import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
}

// Formatting for the Compose UI code (phone ui, app, demo, watch screens): ktfmt kotlinlang style,
// 100 columns.
spotless {
    val ktfmtVersion = libs.versions.ktfmt.get()
    kotlin {
        target("ui/src/**/*.kt", "app/src/**/*.kt", "demo/src/**/*.kt", "watch/healthbridge/src/*/kotlin/**/ui/*.kt")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
    kotlinGradle {
        target("ui/*.gradle.kts", "app/*.gradle.kts", "demo/*.gradle.kts")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
}

val companionVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
extra["companionVersionName"] = companionVersion.getProperty("versionName")
extra["companionVersionCode"] = companionVersion.getProperty("versionCode").toInt()

// Both installations share one injected key. Explicit debug builds can use Gradle's debug key.
val signingVariableNames = listOf("TICBOX_SIGNING_STORE_FILE", "TICBOX_SIGNING_STORE_PASSWORD", "TICBOX_SIGNING_KEY_ALIAS", "TICBOX_SIGNING_KEY_PASSWORD")
val injectedSigning = signingVariableNames.associateWith { providers.environmentVariable(it).orNull }
if (injectedSigning.values.any { !it.isNullOrEmpty() }) {
    val missing = injectedSigning.filterValues { it.isNullOrBlank() }.keys
    require(missing.isEmpty()) { "Incomplete signing configuration; missing ${missing.joinToString()}" }
    val selectedStore = rootProject.file(injectedSigning.getValue("TICBOX_SIGNING_STORE_FILE")!!)
    require(selectedStore.isFile) { "Injected signing keystore is not a regular file" }
    subprojects {
        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationExtension> {
                val injected = signingConfigs.create("injected") {
                    storeFile = selectedStore
                    storePassword = injectedSigning.getValue("TICBOX_SIGNING_STORE_PASSWORD")
                    keyAlias = injectedSigning.getValue("TICBOX_SIGNING_KEY_ALIAS")
                    keyPassword = injectedSigning.getValue("TICBOX_SIGNING_KEY_PASSWORD")
                }
                buildTypes.configureEach { signingConfig = injected }
            }
        }
    }
}

val verifyScrcpyServer by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rebuilds the pinned scrcpy source/patch offline and checks the shipped DEX plus sync-frame lifecycle tests."
    workingDir(rootProject.projectDir.parentFile)
    commandLine("node", "tools/build-scrcpy-server.mjs", "--check")
}

fun registerBackendCheck(name: String, variant: String) {
    tasks.register(name) {
        group = "verification"
        description = "Runs tests/lint and builds the matching $variant phone and watch APKs."
        dependsOn(":core:api:test", ":core:protocol:test", ":core:engine:test", ":watch:healthmodule:check", "spotlessCheck", verifyScrcpyServer)
        listOf(":core:platform", ":ui", ":app", ":demo", ":watch:healthbridge", ":watch:toolboxagent", ":thirdparty:kadb").forEach { module ->
            dependsOn("$module:test${variant}UnitTest", "$module:lint$variant")
        }
        dependsOn(":app:assemble$variant", ":watch:healthbridge:assemble$variant")
        // The sampling UI is deliberately debug-only; exercise its tests without delivering a debug APK.
        if (variant == "Release") dependsOn(":watch:healthbridge:debugOnlyTests", ":watch:healthbridge:journalCrashCheck")
    }
}
registerBackendCheck("backendCheck", "Release")
registerBackendCheck("backendDebugCheck", "Debug")
