plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.ticbox.companion.platform"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    lint { lintConfig = rootProject.file("lint-toolbox.xml") }
    defaultConfig { minSdk = 34 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { task ->
            task.useJUnitPlatform()
            task.maxHeapSize = "1g"
            listOf("robolectric.dependency.repo.url", "https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort").forEach { key -> System.getProperty(key)?.let { task.systemProperty(key, it) } }
        }
    }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    api(project(":core:api"))
    implementation(project(":core:engine"))
    implementation(project(":core:protocol"))
    implementation(libs.coroutines.android)
    implementation(project(":thirdparty:kadb"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.vintage)
    testRuntimeOnly(libs.junit.launcher)
}
