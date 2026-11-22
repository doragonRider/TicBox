// Demo watch: an in-memory fake of the core:api clients with sample data. No Bluetooth, storage,
// network or service access. The app swaps it in for the real backend; the UI cannot tell apart.
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "dev.ticbox.companion.demo"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 34 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { task ->
            task.useJUnitPlatform()
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    api(project(":core:api"))
    implementation(libs.coroutines.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.vintage)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
