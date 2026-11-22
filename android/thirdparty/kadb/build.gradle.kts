plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.flyfishxu.kadb"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 34 } // This Android binding is used only by the phone application.
    lint {
        lintConfig = rootProject.file("lint-toolbox.xml")
        // Preserve upstream guards; this binding is deliberately phone-only (API 34+).
        disable += "ObsoleteSdkInt"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions.unitTests.all { it.useJUnitPlatform(); it.maxHeapSize = "1g" }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    api(libs.kadb.okio)
    implementation(libs.coroutines.core)
    implementation(libs.kadb.bcprov)
    implementation(libs.kadb.bcpkix)
    implementation(libs.kadb.spake2)
    implementation(libs.kadb.documentfile)
    implementation(libs.kadb.hiddenapi)
    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kadb.conscrypt.fixture)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
