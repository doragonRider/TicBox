// Native Compose frontend; business dependencies stay behind core:api.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.ticbox.companion.ui"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 34 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Plain JVM tests may reach android.util.Log through production code.
        unitTests.isReturnDefaultValues = true
        unitTests.all { task ->
            task.useJUnitPlatform()
            task.maxHeapSize = "2g"
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    api(project(":core:api"))
    implementation(libs.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.navigationevent.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    // Its POM lists these, but nothing in the library uses them and this app has no View themes.
    implementation(libs.termlib) {
        exclude(group = "androidx.appcompat")
        exclude(group = "com.google.android.material")
    }
    debugImplementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(project(":demo"))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage)
    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
